/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.tomcat.util.net;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.nio.channels.WritePendingException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLEngineResult.Status;
import javax.net.ssl.SSLException;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.buf.ByteBufferUtils;
import org.apache.tomcat.util.compat.JreCompat;
import org.apache.tomcat.util.net.SocketWrapperBase.ByteBufferWrapper;
import org.apache.tomcat.util.net.TLSClientHelloExtractor.ExtractorResult;
import org.apache.tomcat.util.net.openssl.ciphers.Cipher;
import org.apache.tomcat.util.res.StringManager;

/**
 * Implementation of a secure socket channel for NIO2.
 */
public class SecureNio2Channel extends Nio2Channel {

	private static final Log log = LogFactory.getLog(SecureNio2Channel.class);
	private static final StringManager sm = StringManager.getManager(SecureNio2Channel.class);

	// Value determined by observation of what the SSL Engine requested in
	// various scenarios
	private static final int DEFAULT_NET_BUFFER_SIZE = 16921;

	protected final Nio2Endpoint endpoint;

	protected ByteBuffer netInBuffer;
	protected ByteBuffer netOutBuffer;

	protected SSLEngine sslEngine;

	protected boolean sniComplete = false;

	private volatile boolean handshakeComplete = false;
	private volatile HandshakeStatus handshakeStatus; // gets set by handshake

	protected boolean closed;
	protected boolean closing;

	private volatile boolean unwrapBeforeRead;
	private final CompletionHandler<Integer, SocketWrapperBase<Nio2Channel>> handshakeReadCompletionHandler;
	private final CompletionHandler<Integer, SocketWrapperBase<Nio2Channel>> handshakeWriteCompletionHandler;

	public SecureNio2Channel(SocketProperties socketProperties, Nio2Endpoint endpoint) {
		super(socketProperties.getAppReadBufSize(), socketProperties.getAppWriteBufSize(),
				socketProperties.getDirectBuffer());
		this.endpoint = endpoint;
		if (endpoint.getSocketProperties().getDirectSslBuffer()) {
			netInBuffer = ByteBuffer.allocateDirect(DEFAULT_NET_BUFFER_SIZE);
			netOutBuffer = ByteBuffer.allocateDirect(DEFAULT_NET_BUFFER_SIZE);
		} else {
			netInBuffer = ByteBuffer.allocate(DEFAULT_NET_BUFFER_SIZE);
			netOutBuffer = ByteBuffer.allocate(DEFAULT_NET_BUFFER_SIZE);
		}
		handshakeReadCompletionHandler = new HandshakeReadCompletionHandler();
		handshakeWriteCompletionHandler = new HandshakeWriteCompletionHandler();
	}

	private class HandshakeReadCompletionHandler implements CompletionHandler<Integer, SocketWrapperBase<Nio2Channel>> {
		@Override
		public void completed(Integer result, SocketWrapperBase<Nio2Channel> attachment) {
			if (result.intValue() < 0) {
				failed(new EOFException(), attachment);
			} else {
				endpoint.getHandler().processSocket(attachment, SocketEvent.OPEN_READ, false);
			}
		}

		@Override
		public void failed(Throwable exc, SocketWrapperBase<Nio2Channel> attachment) {
			endpoint.getHandler().processSocket(attachment, SocketEvent.ERROR, false);
		}
	}

	private class HandshakeWriteCompletionHandler
			implements CompletionHandler<Integer, SocketWrapperBase<Nio2Channel>> {
		@Override
		public void completed(Integer result, SocketWrapperBase<Nio2Channel> attachment) {
			if (result.intValue() < 0) {
				failed(new EOFException(), attachment);
			} else {
				endpoint.getHandler().processSocket(attachment, SocketEvent.OPEN_WRITE, false);
			}
		}

		@Override
		public void failed(Throwable exc, SocketWrapperBase<Nio2Channel> attachment) {
			endpoint.getHandler().processSocket(attachment, SocketEvent.ERROR, false);
		}
	}

	@Override
	public void reset(AsynchronousSocketChannel channel, SocketWrapperBase<Nio2Channel> socket) throws IOException {
		super.reset(channel, socket);
		sslEngine = null;
		sniComplete = false;
		handshakeComplete = false;
		unwrapBeforeRead = true;
		closed = false;
		closing = false;
		netInBuffer.clear();
	}

	@Override
	public void free() {
		super.free();
		if (endpoint.getSocketProperties().getDirectSslBuffer()) {
			ByteBufferUtils.cleanDirectBuffer(netInBuffer);
			ByteBufferUtils.cleanDirectBuffer(netOutBuffer);
		}
	}

	private class FutureFlush implements Future<Boolean> {
		private Future<Integer> integer;
		private Exception e = null;

		protected FutureFlush() {
			try {
				integer = getSocketChannel().write(netOutBuffer);
			} catch (IllegalStateException e) {
				this.e = e;
			}
		}

		@Override
		public boolean cancel(boolean mayInterruptIfRunning) {
			return (e != null) ? true : integer.cancel(mayInterruptIfRunning);
		}

		@Override
		public boolean isCancelled() {
			return (e != null) ? true : integer.isCancelled();
		}

		@Override
		public boolean isDone() {
			return (e != null) ? true : integer.isDone();
		}

		@Override
		public Boolean get() throws InterruptedException, ExecutionException {
			if (e != null) {
				throw new ExecutionException(e);
			}
			return Boolean.valueOf(integer.get().intValue() >= 0);
		}

		@Override
		public Boolean get(long timeout, TimeUnit unit)
				throws InterruptedException, ExecutionException, TimeoutException {
			if (e != null) {
				throw new ExecutionException(e);
			}
			return Boolean.valueOf(integer.get(timeout, unit).intValue() >= 0);
		}
	}

	/**
	 * Flush the channel.
	 *
	 * @return <code>true</code> if the network buffer has been flushed out and is
	 *         empty else <code>false</code> (as a future)
	 */
	@Override
	public Future<Boolean> flush() {
		return new FutureFlush();
	}

	/**
	 * Performs SSL handshake, non blocking, but performs NEED_TASK on the same
	 * thread. Hence, you should never call this method using your Acceptor thread,
	 * as you would slow down your system significantly.
	 * <p>
	 * The return for this operation is 0 if the handshake is complete and a
	 * positive value if it is not complete. In the event of a positive value coming
	 * back, the appropriate read/write will already have been called with an
	 * appropriate CompletionHandler.
	 *
	 * @return 0 if hand shake is complete, negative if the socket needs to close
	 *         and positive if the handshake is incomplete
	 *
	 * @throws IOException if an error occurs during the handshake
	 */
	@Override
	public int handshake() throws IOException {
		return handshakeInternal(true);
	}

	protected int handshakeInternal(boolean async) throws IOException {
		if (handshakeComplete) {
			return HandShakeable.HANDSHAKE_COMPLETE; // we have done our initial handshake
		}

		if (!sniComplete) {
			int sniResult = processSNI();
			if (sniResult == HandShakeable.HANDSHAKE_COMPLETE) {
				sniComplete = true;
			} else {
				return sniResult;
			}
		}

		SSLEngineResult handshake = null;
		long timeout = endpoint.getConnectionTimeout();

		while (!handshakeComplete) {
			switch (handshakeStatus) {
			case NOT_HANDSHAKING: {
				// should never happen
				throw new IOException(sm.getString("channel.nio.ssl.notHandshaking"));
			}
			case FINISHED: {
				if (endpoint.hasNegotiableProtocols()) {
					if (sslEngine instanceof SSLUtil.ProtocolInfo) {
						getSocketWrapper()
								.setNegotiatedProtocol(((SSLUtil.ProtocolInfo) sslEngine).getNegotiatedProtocol());
					} else if (JreCompat.isAlpnSupported()) {
						getSocketWrapper()
								.setNegotiatedProtocol(JreCompat.getInstance().getApplicationProtocol(sslEngine));
					}
				}
				// we are complete if we have delivered the last package
				handshakeComplete = !netOutBuffer.hasRemaining();
				// return 0 if we are complete, otherwise we still have data to write
				if (handshakeComplete) {
					return HandShakeable.HANDSHAKE_COMPLETE;
				} else {
					if (async) {
						getSocketChannel().write(netOutBuffer, Endpoint.toTimeout(timeout), TimeUnit.MILLISECONDS,
								getSocketWrapper(), handshakeWriteCompletionHandler);
					} else {
						try {
							if (timeout > 0) {
								getSocketChannel().write(netOutBuffer).get(timeout, TimeUnit.MILLISECONDS);
							} else {
								getSocketChannel().write(netOutBuffer).get();
							}
						} catch (InterruptedException | ExecutionException | TimeoutException e) {
							throw new IOException(sm.getString("channel.nio.ssl.handshakeError"));
						}
					}
					return HandShakeable.HANDSHAKE_IGNORE;
				}
			}
			case NEED_WRAP: {
				// perform the wrap function
				try {
					handshake = handshakeWrap();
				} catch (SSLException e) {
					if (log.isDebugEnabled()) {
						log.debug(sm.getString("channel.nio.ssl.wrapException"), e);
					}
					handshake = handshakeWrap();
				}
				if (handshake.getStatus() == Status.OK) {
					if (handshakeStatus == HandshakeStatus.NEED_TASK)
						handshakeStatus = tasks();
				} else if (handshake.getStatus() == Status.CLOSED) {
					return HandShakeable.HANDSHAKE_FAIL;
				} else {
					// wrap should always work with our buffers
					throw new IOException(
							sm.getString("channel.nio.ssl.unexpectedStatusDuringWrap", handshake.getStatus()));
				}
				if (handshakeStatus != HandshakeStatus.NEED_UNWRAP || netOutBuffer.remaining() > 0) {
					// should actually return OP_READ if we have NEED_UNWRAP
					if (async) {
						getSocketChannel().write(netOutBuffer, Endpoint.toTimeout(timeout), TimeUnit.MILLISECONDS,
								getSocketWrapper(), handshakeWriteCompletionHandler);
					} else {
						try {
							if (timeout > 0) {
								getSocketChannel().write(netOutBuffer).get(timeout, TimeUnit.MILLISECONDS);
							} else {
								getSocketChannel().write(netOutBuffer).get();
							}
						} catch (InterruptedException | ExecutionException | TimeoutException e) {
							throw new IOException(sm.getString("channel.nio.ssl.handshakeError"));
						}
					}
					return HandShakeable.HANDSHAKE_IGNORE;
				}
				// fall down to NEED_UNWRAP on the same call, will result in a
				// BUFFER_UNDERFLOW if it needs data
			}
			//$FALL-THROUGH$
			case NEED_UNWRAP: {
				// perform the unwrap function
				handshake = handshakeUnwrap();
				if (handshake.getStatus() == Status.OK) {
					if (handshakeStatus == HandshakeStatus.NEED_TASK)
						handshakeStatus = tasks();
				} else if (handshake.getStatus() == Status.BUFFER_UNDERFLOW) {
					if (netInBuffer.position() == netInBuffer.limit()) {
						// clear the buffer if we have emptied it out on data
						netInBuffer.clear();
					}
					// read more data
					if (async) {
						getSocketChannel().read(netInBuffer, Endpoint.toTimeout(timeout), TimeUnit.MILLISECONDS,
								getSocketWrapper(), handshakeReadCompletionHandler);
					} else {
						try {
							int read;
							if (timeout > 0) {
								read = getSocketChannel().read(netInBuffer).get(timeout, TimeUnit.MILLISECONDS)
										.intValue();
							} else {
								read = getSocketChannel().read(netInBuffer).get().intValue();
							}
							if (read == -1) {
								throw new EOFException();
							}
						} catch (InterruptedException | ExecutionException | TimeoutException e) {
							throw new IOException(sm.getString("channel.nio.ssl.handshakeError"));
						}
					}
					return HandShakeable.HANDSHAKE_IGNORE;
				} else {
					throw new IOException(
							sm.getString("channel.nio.ssl.unexpectedStatusDuringUnwrap", handshake.getStatus()));
				}
				break;
			}
			case NEED_TASK: {
				handshakeStatus = tasks();
				break;
			}
			default:
				throw new IllegalStateException(sm.getString("channel.nio.ssl.invalidStatus", handshakeStatus));
			}
		}
		// return 0 if we are complete, otherwise recurse to process the task
		return handshakeComplete ? HandShakeable.HANDSHAKE_COMPLETE : handshakeInternal(async);
	}

	/*
	 * Peeks at the initial network bytes to determine if the SNI extension is
	 * present and, if it is, what host name has been requested. Based on the
	 * provided host name, configure the SSLEngine for this connection.
	 */
	private int processSNI() throws IOException {
		// If there is no data to process, trigger a read immediately. This is
		// an optimisation for the typical case so we don't create an
		// SNIExtractor only to discover there is no data to process
		if (netInBuffer.position() == 0) {
			getSocketChannel().read(netInBuffer, Endpoint.toTimeout(endpoint.getConnectionTimeout()),
					TimeUnit.MILLISECONDS, getSocketWrapper(), handshakeReadCompletionHandler);
			return HandShakeable.HANDSHAKE_IGNORE;
		}

		TLSClientHelloExtractor extractor = new TLSClientHelloExtractor(netInBuffer);

		if (extractor.getResult() == ExtractorResult.UNDERFLOW
				&& netInBuffer.capacity() < endpoint.getSniParseLimit()) {
			// extractor needed more data to process but netInBuffer was full so
			// expand the buffer and read some more data.
			int newLimit = Math.min(netInBuffer.capacity() * 2, endpoint.getSniParseLimit());
			log.info(sm.getString("channel.nio.ssl.expandNetInBuffer", Integer.toString(newLimit)));

			netInBuffer = ByteBufferUtils.expand(netInBuffer, newLimit);
			getSocketChannel().read(netInBuffer, Endpoint.toTimeout(endpoint.getConnectionTimeout()),
					TimeUnit.MILLISECONDS, getSocketWrapper(), handshakeReadCompletionHandler);
			return HandShakeable.HANDSHAKE_IGNORE;
		}

		String hostName = null;
		List<Cipher> clientRequestedCiphers = null;
		List<String> clientRequestedApplicationProtocols = null;
		switch (extractor.getResult()) {
		case COMPLETE:
			hostName = extractor.getSNIValue();
			clientRequestedApplicationProtocols = extractor.getClientRequestedApplicationProtocols();
			//$FALL-THROUGH$ to set the client requested ciphers
		case NOT_PRESENT:
			clientRequestedCiphers = extractor.getClientRequestedCiphers();
			break;
		case NEED_READ:
			getSocketChannel().read(netInBuffer, Endpoint.toTimeout(endpoint.getConnectionTimeout()),
					TimeUnit.MILLISECONDS, getSocketWrapper(), handshakeReadCompletionHandler);
			return HandShakeable.HANDSHAKE_IGNORE;
		case UNDERFLOW:
			// Unable to buffer enough data to read SNI extension data
			if (log.isDebugEnabled()) {
				log.debug(sm.getString("channel.nio.ssl.sniDefault"));
			}
			hostName = endpoint.getDefaultSSLHostConfigName();
			clientRequestedCiphers = Collections.emptyList();
			break;
		case NON_SECURE:
			netOutBuffer.clear();
			netOutBuffer.put(TLSClientHelloExtractor.USE_TLS_RESPONSE);
			netOutBuffer.flip();
			flush();
			throw new IOException(sm.getString("channel.nio.ssl.foundHttp"));
		}

		if (log.isDebugEnabled()) {
			log.debug(sm.getString("channel.nio.ssl.sniHostName", getSocketChannel(), hostName));
		}

		sslEngine = endpoint.createSSLEngine(hostName, clientRequestedCiphers, clientRequestedApplicationProtocols);

		// Ensure the application buffers (which have to be created earlier) are
		// big enough.
		expand(sslEngine.getSession().getApplicationBufferSize());
		if (netOutBuffer.capacity() < sslEngine.getSession().getApplicationBufferSize()) {
			// Info for now as we may need to increase DEFAULT_NET_BUFFER_SIZE
			log.info(sm.getString("channel.nio.ssl.expandNetOutBuffer",
					Integer.toString(sslEngine.getSession().getApplicationBufferSize())));
		}
		netInBuffer = ByteBufferUtils.expand(netInBuffer, sslEngine.getSession().getPacketBufferSize());
		netOutBuffer = ByteBufferUtils.expand(netOutBuffer, sslEngine.getSession().getPacketBufferSize());

		// Set limit and position to expected values
		netOutBuffer.position(0);
		netOutBuffer.limit(0);

		// Initiate handshake
		sslEngine.beginHandshake();
		handshakeStatus = sslEngine.getHandshakeStatus();

		return HandShakeable.HANDSHAKE_COMPLETE;
	}

	/**
	 * Force a blocking handshake to take place for this key. This requires that
	 * both network and application buffers have been emptied out prior to this call
	 * taking place, or a IOException will be thrown.
	 * 
	 * @throws IOException                     - if an IO exception occurs or if
	 *                                         application or network buffers
	 *                                         contain data
	 * @throws java.net.SocketTimeoutException - if a socket operation timed out
	 */
	public void rehandshake() throws IOException {
		// validate the network buffers are empty
		if (netInBuffer.position() > 0 && netInBuffer.position() < netInBuffer.limit())
			throw new IOException(sm.getString("channel.nio.ssl.netInputNotEmpty"));
		if (netOutBuffer.position() > 0 && netOutBuffer.position() < netOutBuffer.limit())
			throw new IOException(sm.getString("channel.nio.ssl.netOutputNotEmpty"));
		if (!isReadBufferEmpty())
			throw new IOException(sm.getString("channel.nio.ssl.appInputNotEmpty"));
		if (!isWriteBufferEmpty())
			throw new IOException(sm.getString("channel.nio.ssl.appOutputNotEmpty"));

		netOutBuffer.position(0);
		netOutBuffer.limit(0);
		netInBuffer.position(0);
		netInBuffer.limit(0);
		reset();

		handshakeComplete = false;
		// initiate handshake
		sslEngine.beginHandshake();
		handshakeStatus = sslEngine.getHandshakeStatus();

		boolean handshaking = true;
		try {
			while (handshaking) {
				int hsStatus = handshakeInternal(false);
				switch (hsStatus) {
				case -1:
					throw new EOFException(sm.getString("channel.nio.ssl.eofDuringHandshake"));
				case 0:
					handshaking = false;
					break;
				default: // Some blocking IO occurred, so iterate
				}
			}
		} catch (IOException x) {
			closeSilently();
			throw x;
		} catch (Exception cx) {
			closeSilently();
			IOException x = new IOException(cx);
			throw x;
		}
	}

	/**
	 * Executes all the tasks needed on the same thread.
	 * 
	 * @return the status
	 */
	protected SSLEngineResult.HandshakeStatus tasks() {
		Runnable r = null;
		while ((r = sslEngine.getDelegatedTask()) != null) {
			r.run();
		}
		return sslEngine.getHandshakeStatus();
	}

	/**
	 * Performs the WRAP function
	 * 
	 * @return the result
	 * @throws IOException An IO error occurred
	 */
	protected SSLEngineResult handshakeWrap() throws IOException {
		// this should never be called with a network buffer that contains data
		// so we can clear it here.
		netOutBuffer.clear();
		// perform the wrap
		configureWriteBufferForRead();
		SSLEngineResult result = sslEngine.wrap(getWriteBuffer().getByteBuffer(), netOutBuffer);
		// prepare the results to be written
		netOutBuffer.flip();
		// set the status
		handshakeStatus = result.getHandshakeStatus();
		return result;
	}

	/**
	 * Perform handshake unwrap
	 * 
	 * @return the result
	 * @throws IOException An IO error occurred
	 */
	protected SSLEngineResult handshakeUnwrap() throws IOException {
		SSLEngineResult result;
		boolean cont = false;
		ByteBuffer buffer = ByteBuffer.allocate(netInBuffer.capacity());
		// loop while we can perform pure SSLEngine data
		do {
			// prepare the buffer with the incoming data
			netInBuffer.flip();
			// call unwrap
			configureReadBufferForWrite();
			result = sslEngine.unwrap(netInBuffer, buffer);
			// compact the buffer, this is an optional method, wonder what would happen if
			// we didn't
			netInBuffer.compact();
			// read in the status
			handshakeStatus = result.getHandshakeStatus();
			if (result.getStatus() == SSLEngineResult.Status.OK
					&& result.getHandshakeStatus() == HandshakeStatus.NEED_TASK) {
				// execute tasks if we need to
				handshakeStatus = tasks();
			}
			// perform another unwrap?
			cont = result.getStatus() == SSLEngineResult.Status.OK && handshakeStatus == HandshakeStatus.NEED_UNWRAP;
		} while (cont);
		if (buffer.position() != 0) {
			throw new RuntimeException();
		}
		return result;
	}

	/**
	 * Sends an SSL close message, will not physically close the connection
	 * here.<br>
	 * To close the connection, you could do something like
	 * 
	 * <pre>
	 * <code>
	 *   close();
	 *   while (isOpen() &amp;&amp; !myTimeoutFunction()) Thread.sleep(25);
	 *   if ( isOpen() ) close(true); //forces a close if you timed out
	 * </code>
	 * </pre>
	 * 
	 * @throws IOException if an I/O error occurs
	 * @throws IOException if there is data on the outgoing network buffer and we
	 *                     are unable to flush it
	 */
	@Override
	public void close() throws IOException {
		if (closing)
			return;
		closing = true;
		sslEngine.closeOutbound();
		long timeout = endpoint.getConnectionTimeout();

		try {
			if (timeout > 0) {
				if (!flush().get(timeout, TimeUnit.MILLISECONDS).booleanValue()) {
					closeSilently();
					throw new IOException(sm.getString("channel.nio.ssl.remainingDataDuringClose"));
				}
			} else {
				if (!flush().get().booleanValue()) {
					closeSilently();
					throw new IOException(sm.getString("channel.nio.ssl.remainingDataDuringClose"));
				}
			}
		} catch (InterruptedException | ExecutionException | TimeoutException e) {
			closeSilently();
			throw new IOException(sm.getString("channel.nio.ssl.remainingDataDuringClose"), e);
		} catch (WritePendingException e) {
			closeSilently();
			throw new IOException(sm.getString("channel.nio.ssl.pendingWriteDuringClose"), e);
		}
		// prep the buffer for the close message
		netOutBuffer.clear();
		// perform the close, since we called sslEngine.closeOutbound
		SSLEngineResult handshake = sslEngine.wrap(getEmptyBuf(), netOutBuffer);
		// we should be in a close state
		if (handshake.getStatus() != SSLEngineResult.Status.CLOSED) {
			throw new IOException(sm.getString("channel.nio.ssl.invalidCloseState"));
		}
		// prepare the buffer for writing
		netOutBuffer.flip();
		// if there is data to be written
		try {
			if (timeout > 0) {
				if (!flush().get(timeout, TimeUnit.MILLISECONDS).booleanValue()) {
					closeSilently();
					throw new IOException(sm.getString("channel.nio.ssl.remainingDataDuringClose"));
				}
			} else {
				if (!flush().get().booleanValue()) {
					closeSilently();
					throw new IOException(sm.getString("channel.nio.ssl.remainingDataDuringClose"));
				}
			}
		} catch (InterruptedException | ExecutionException | TimeoutException e) {
			closeSilently();
			throw new IOException(sm.getString("channel.nio.ssl.remainingDataDuringClose"), e);
		} catch (WritePendingException e) {
			closeSilently();
			throw new IOException(sm.getString("channel.nio.ssl.pendingWriteDuringClose"), e);
		}

		// is the channel closed?
		closed = (!netOutBuffer.hasRemaining() && (handshake.getHandshakeStatus() != HandshakeStatus.NEED_WRAP));
	}

	@Override
	public void close(boolean force) throws IOException {
		try {
			close();
		} finally {
			if (force || closed) {
				closed = true;
				getSocketChannel().close();
			}
		}
	}

	private void closeSilently() {
		try {
			close(true);
		} catch (IOException ioe) {
			// This is expected - swallowing the exception is the reason this
			// method exists. Log at debug in case someone is interested.
			log.debug(sm.getString("channel.nio.ssl.closeSilentError"), ioe);
		}
	}

	private class FutureRead implements Future<Integer> {
		private ByteBufferWrapper dst;
		private Future<Integer> integer;

		private FutureRead(ByteBufferWrapper dst) {
			this.dst = dst;
			if (unwrapBeforeRead || netInBuffer.position() > 0) {
				this.integer = null;
			} else {
				this.integer = getSocketChannel().read(netInBuffer);
			}
		}

		@Override
		public boolean cancel(boolean mayInterruptIfRunning) {
			return (integer == null) ? false : integer.cancel(mayInterruptIfRunning);
		}

		@Override
		public boolean isCancelled() {
			return (integer == null) ? false : integer.isCancelled();
		}

		@Override
		public boolean isDone() {
			return (integer == null) ? true : integer.isDone();
		}

		@Override
		public Integer get() throws InterruptedException, ExecutionException {
			try {
				return (integer == null) ? unwrap(netInBuffer.position(), -1, TimeUnit.MILLISECONDS)
						: unwrap(integer.get().intValue(), -1, TimeUnit.MILLISECONDS);
			} catch (TimeoutException e) {
				// Cannot happen: no timeout
				throw new ExecutionException(e);
			}
		}

		@Override
		public Integer get(long timeout, TimeUnit unit)
				throws InterruptedException, ExecutionException, TimeoutException {
			System.out.println("readfuture timeout " + timeout);
			return (integer == null) ? unwrap(netInBuffer.position(), timeout, unit)
					: unwrap(integer.get(timeout, unit).intValue(), timeout, unit);
		}

		private Integer unwrap(int nRead, long timeout, TimeUnit unit)
				throws ExecutionException, TimeoutException, InterruptedException {
			// are we in the middle of closing or closed?
			if (closing || closed)
				return Integer.valueOf(-1);
			// did we reach EOF? if so send EOF up one layer.
			if (nRead < 0)
				return Integer.valueOf(-1);
			// the data read
			int read = 0;
			// the SSL engine result
			SSLEngineResult unwrap;
			do {
				// prepare the buffer
				netInBuffer.flip();
				// unwrap the data
				try {
					if (!dst.isWriteMode()) {
						throw new RuntimeException();
					}
					unwrap = sslEngine.unwrap(netInBuffer, dst.getByteBuffer());
				} catch (SSLException e) {
					throw new ExecutionException(e);
				}
				// compact the buffer
				netInBuffer.compact();
				if (unwrap.getStatus() == Status.OK || unwrap.getStatus() == Status.BUFFER_UNDERFLOW) {
					// we did receive some data, add it to our total
					read += unwrap.bytesProduced();
					// perform any tasks if needed
					if (unwrap.getHandshakeStatus() == HandshakeStatus.NEED_TASK) {
						tasks();
					}
					// if we need more network data, then bail out for now.
					if (unwrap.getStatus() == Status.BUFFER_UNDERFLOW) {
						if (read == 0) {
							integer = getSocketChannel().read(netInBuffer);
							if (timeout > 0) {
								return unwrap(integer.get(timeout, unit).intValue(), timeout, unit);
							} else {
								return unwrap(integer.get().intValue(), -1, TimeUnit.MILLISECONDS);
							}
						} else {
							break;
						}
					}
				} else if (unwrap.getStatus() == Status.BUFFER_OVERFLOW) {
					if (read > 0) {
						// Buffer overflow can happen if we have read data. Return
						// so the destination buffer can be emptied before another
						// read is attempted
						break;
					} else {
						dst.expand(sslEngine.getSession().getApplicationBufferSize());
						// The SSL session has increased the required buffer size
						// since the buffer was created.
//						if (dst == getReadBuffer()) {
//							// This is the normal case for this code
//							expand(sslEngine.getSession().getApplicationBufferSize());
//							dst = getReadBuffer();
//						} else if (dst == SecureNio2Channel.this.getAppReadBuffer()) {
//							SecureNio2Channel.this
//									.expandAppReadBuffer(sslEngine.getSession().getApplicationBufferSize());
//							dst = SecureNio2Channel.this.getAppReadBuffer();
//						} else {
//							// Can't expand the buffer as there is no way to signal
//							// to the caller that the buffer has been replaced.
//							throw new ExecutionException(new IOException(
//									sm.getString("channel.nio.ssl.unwrapFailResize", unwrap.getStatus())));
//						}
					}
				} else {
					// Something else went wrong
					throw new ExecutionException(
							new IOException(sm.getString("channel.nio.ssl.unwrapFail", unwrap.getStatus())));
				}
			} while (netInBuffer.position() != 0); // continue to unwrapping as long as the input buffer has stuff
			if (!dst.hasRemaining()) {
				unwrapBeforeRead = true;
			} else {
				unwrapBeforeRead = false;
			}
			return Integer.valueOf(read);
		}
	}

	/**
	 * Reads a sequence of bytes from this channel into the given buffer.
	 *
	 * @param dst The buffer into which bytes are to be transferred
	 * @return The number of bytes read, possibly zero, or <code>-1</code> if the
	 *         channel has reached end-of-stream
	 * @throws IllegalStateException if the handshake was not completed
	 */
	@Override
	public Future<Integer> read(ByteBufferWrapper dst) {
		if (!handshakeComplete) {
			throw new IllegalStateException(sm.getString("channel.nio.ssl.incompleteHandshake"));
		}
		return new FutureRead(dst);
	}

	private class FutureWrite implements Future<Integer> {
		private final ByteBufferWrapper src;
		private Future<Integer> integer = null;
		private int written = 0;
		private Throwable t = null;

		private FutureWrite(ByteBufferWrapper src) {
			this.src = src;
			// are we closing or closed?
			if (closing || closed) {
				t = new IOException(sm.getString("channel.nio.ssl.closing"));
			} else {
				wrap();
			}
		}

		@Override
		public boolean cancel(boolean mayInterruptIfRunning) {
			return integer.cancel(mayInterruptIfRunning);
		}

		@Override
		public boolean isCancelled() {
			return integer.isCancelled();
		}

		@Override
		public boolean isDone() {
			return integer.isDone();
		}

		@Override
		public Integer get() throws InterruptedException, ExecutionException {
			if (t != null) {
				throw new ExecutionException(t);
			}
			if (integer.get().intValue() > 0 && written == 0) {
				wrap();
				return get();
			} else if (netOutBuffer.hasRemaining()) {
				integer = getSocketChannel().write(netOutBuffer);
				return get();
			} else {
				return Integer.valueOf(written);
			}
		}

		@Override
		public Integer get(long timeout, TimeUnit unit)
				throws InterruptedException, ExecutionException, TimeoutException {
			System.out.println("writefuture timeout " + timeout);
			if (t != null) {
				throw new ExecutionException(t);
			}
			if (integer.get(timeout, unit).intValue() > 0 && written == 0) {
				wrap();
				return get(timeout, unit);
			} else if (netOutBuffer.hasRemaining()) {
				integer = getSocketChannel().write(netOutBuffer);
				return get(timeout, unit);
			} else {
				return Integer.valueOf(written);
			}
		}

		protected void wrap() {
			try {
				if (!netOutBuffer.hasRemaining()) {
					netOutBuffer.clear();
					if (!src.isReadMode()) {
						throw new RuntimeException();
					}
					SSLEngineResult result = sslEngine.wrap(src.getByteBuffer(), netOutBuffer);
					written = result.bytesConsumed();
					netOutBuffer.flip();
					if (result.getStatus() == Status.OK) {
						if (result.getHandshakeStatus() == HandshakeStatus.NEED_TASK)
							tasks();
					} else {
						t = new IOException(sm.getString("channel.nio.ssl.wrapFail", result.getStatus()));
					}
				}
				integer = getSocketChannel().write(netOutBuffer);
			} catch (SSLException e) {
				t = e;
			}
		}
	}

	/**
	 * Writes a sequence of bytes to this channel from the given buffer.
	 *
	 * @param src The buffer from which bytes are to be retrieved
	 * @return The number of bytes written, possibly zero
	 */
	@Override
	public Future<Integer> write(ByteBufferWrapper src) {
		return new FutureWrite(src);
	}

	@Override
	public <A> void read(final ByteBufferWrapper dst, final long timeout, final TimeUnit unit, final A attachment,
			final CompletionHandler<Integer, ? super A> handler) {
		System.out.println("read timeout " + timeout);
		// Check state
		if (closing || closed) {
			handler.completed(Integer.valueOf(-1), attachment);
			return;
		}
		if (!handshakeComplete) {
			throw new IllegalStateException(sm.getString("channel.nio.ssl.incompleteHandshake"));
		}
		CompletionHandler<Integer, A> readCompletionHandler = new CompletionHandler<Integer, A>() {
			@Override
			public void completed(Integer nBytes, A attach) {
				if (nBytes.intValue() < 0) {
					failed(new EOFException(), attach);
				} else {
					try {
						ByteBufferWrapper dst2 = dst;
						// the data read
						int read = 0;
						// the SSL engine result
						SSLEngineResult unwrap;
						do {
							// prepare the buffer
							netInBuffer.flip();
							// unwrap the data
							if (!dst2.isWriteMode()) {
								throw new RuntimeException();
							}
							unwrap = sslEngine.unwrap(netInBuffer, dst2.getByteBuffer());
							// compact the buffer
							netInBuffer.compact();
							if (unwrap.getStatus() == Status.OK || unwrap.getStatus() == Status.BUFFER_UNDERFLOW) {
								// we did receive some data, add it to our total
								read += unwrap.bytesProduced();
								// perform any tasks if needed
								if (unwrap.getHandshakeStatus() == HandshakeStatus.NEED_TASK)
									tasks();
								// if we need more network data, then bail out for now.
								if (unwrap.getStatus() == Status.BUFFER_UNDERFLOW) {
									if (read == 0) {
										getSocketChannel().read(netInBuffer, timeout, unit, attachment, this);
										return;
									} else {
										break;
									}
								}
							} else if (unwrap.getStatus() == Status.BUFFER_OVERFLOW) {
								if (read > 0) {
									// Buffer overflow can happen if we have read data. Return
									// so the destination buffer can be emptied before another
									// read is attempted
									break;
								} else {
									dst2.expand(sslEngine.getSession().getApplicationBufferSize());
									// The SSL session has increased the required buffer size
									// since the buffer was created.
//									if (dst2 == getReadBuffer()) {
//										// This is the normal case for this code
//										expand(sslEngine.getSession().getApplicationBufferSize());
//										dst2 = getReadBuffer();
//									} else if (dst2 == SecureNio2Channel.this.getAppReadBuffer()) {
//										SecureNio2Channel.this
//												.expandAppReadBuffer(sslEngine.getSession().getApplicationBufferSize());
//										dst2 = SecureNio2Channel.this.getAppReadBuffer();
//									} else {
//										// Can't expand the buffer as there is no way to signal
//										// to the caller that the buffer has been replaced.
//										throw new IOException(
//												sm.getString("channel.nio.ssl.unwrapFailResize", unwrap.getStatus()));
//									}
								}
							} else {
								// Something else went wrong
								throw new IOException(sm.getString("channel.nio.ssl.unwrapFail", unwrap.getStatus()));
							}
							// continue to unwrap as long as the input buffer has stuff
						} while (netInBuffer.position() != 0);
						if (!dst2.hasRemaining()) {
							unwrapBeforeRead = true;
						} else {
							unwrapBeforeRead = false;
						}
						// If everything is OK, so complete
						handler.completed(Integer.valueOf(read), attach);
					} catch (Exception e) {
						failed(e, attach);
					}
				}
			}

			@Override
			public void failed(Throwable exc, A attach) {
				handler.failed(exc, attach);
			}
		};
		if (unwrapBeforeRead || netInBuffer.position() > 0) {
			readCompletionHandler.completed(Integer.valueOf(netInBuffer.position()), attachment);
		} else {
			getSocketChannel().read(netInBuffer, timeout, unit, attachment, readCompletionHandler);
		}
	}

	@Override
	public <A> void read(final ByteBufferWrapper[] dsts, final int offset, final int length, final long timeout,
			final TimeUnit unit, final A attachment, final CompletionHandler<Long, ? super A> handler) {
		System.out.println("read timeout " + timeout);
		if (offset < 0 || dsts == null || (offset + length) > dsts.length) {
			throw new IllegalArgumentException();
		}
		if (closing || closed) {
			handler.completed(Long.valueOf(-1), attachment);
			return;
		}
		if (!handshakeComplete) {
			throw new IllegalStateException(sm.getString("channel.nio.ssl.incompleteHandshake"));
		}
		CompletionHandler<Integer, A> readCompletionHandler = new CompletionHandler<Integer, A>() {
			@Override
			public void completed(Integer nBytes, A attach) {
				if (nBytes.intValue() < 0) {
					failed(new EOFException(), attach);
				} else {
					try {
						// the data read
						long read = 0;
						// the SSL engine result
						SSLEngineResult unwrap;
						ByteBufferWrapper[] dsts2 = dsts;
						int length2 = length;
//						OverflowState overflowState = OverflowState.NONE;
						do {
//							if (overflowState == OverflowState.PROCESSING) {
//								overflowState = OverflowState.DONE;
//							}
							// prepare the buffer
							netInBuffer.flip();
							// unwrap the data
							ByteBuffer[] buffers = new ByteBuffer[dsts2.length];
							for (int i = 0; i < dsts2.length; i++) {
								if (!dsts2[i].isWriteMode()) {
									throw new RuntimeException();
								}
								buffers[i] = dsts2[i].getByteBuffer();
							}
							unwrap = sslEngine.unwrap(netInBuffer, buffers, offset, length2);
							// compact the buffer
							netInBuffer.compact();
							if (unwrap.getStatus() == Status.OK || unwrap.getStatus() == Status.BUFFER_UNDERFLOW) {
								// we did receive some data, add it to our total
								read += unwrap.bytesProduced();
//								if (overflowState == OverflowState.DONE) {
								// Remove the data read into the overflow buffer
//									read -= getReadBuffer().getPosition();
//								}
								// perform any tasks if needed
								if (unwrap.getHandshakeStatus() == HandshakeStatus.NEED_TASK)
									tasks();
								// if we need more network data, then bail out for now.
								if (unwrap.getStatus() == Status.BUFFER_UNDERFLOW) {
									if (read == 0) {
										getSocketChannel().read(netInBuffer, timeout, unit, attachment, this);
										return;
									} else {
										break;
									}
								}
							} else if (unwrap.getStatus() == Status.BUFFER_OVERFLOW && read > 0) {
								// buffer overflow can happen, if we have read data, then
								// empty out the dst buffer before we do another read
								break;
							} else if (unwrap.getStatus() == Status.BUFFER_OVERFLOW) {
								// here we should trap BUFFER_OVERFLOW and call expand on the buffer
								// for now, throw an exception, as we initialized the buffers
								// in the constructor
//								ByteBufferWrapper readBuffer = getReadBuffer();
//								boolean found = false;
//								boolean resized = true;
								for (int i = 0; i < length2; i++) {
									dsts[offset + i].expand(sslEngine.getSession().getApplicationBufferSize());
									// The SSL session has increased the required buffer size
									// since the buffer was created.
//									if (dsts[offset + i] == getReadBuffer()) {
//										expand(sslEngine.getSession().getApplicationBufferSize());
//										if (dsts[offset + i] == getReadBuffer()) {
//											resized = false;
//										}
//										dsts[offset + i] = getReadBuffer();
//										found = true;
//									} else if (dsts[offset + i] == SecureNio2Channel.this.getAppReadBuffer()) {
//										SecureNio2Channel.this
//												.expandAppReadBuffer(sslEngine.getSession().getApplicationBufferSize());
//										if (dsts[offset + i] == SecureNio2Channel.this.getAppReadBuffer()) {
//											resized = false;
//										}
//										dsts[offset + i] = SecureNio2Channel.this.getAppReadBuffer();
//										found = true;
//									}
								}
//								if (found) {
//									if (!resized) {
//										throw new IOException(
//												sm.getString("channel.nio.ssl.unwrapFail", unwrap.getStatus()));
//									}
//								} else {
//									// Add the main read buffer in the destinations and try again
//									dsts2 = new ByteBufferWrapper[dsts.length + 1];
//									int dstOffset = 0;
//									for (int i = 0; i < dsts.length + 1; i++) {
//										if (i == offset + length) {
//											dsts2[i] = readBuffer;
//											dstOffset = -1;
//										} else {
//											dsts2[i] = dsts[i + dstOffset];
//										}
//									}
//									length2 = length + 1;
//									configureReadBufferForWrite();
//									overflowState = OverflowState.PROCESSING;
//								}
							} else if (unwrap.getStatus() == Status.CLOSED) {
								break;
							} else {
								throw new IOException(sm.getString("channel.nio.ssl.unwrapFail", unwrap.getStatus()));
							}
						} while (netInBuffer.position() != 0);// (netInBuffer.position() != 0 || overflowState ==
																// OverflowState.PROCESSING)&& overflowState !=
																// OverflowState.DONE
						int capacity = 0;
						final int endOffset = offset + length;
						for (int i = offset; i < endOffset; i++) {
							capacity += dsts[i].getRemaining();
						}
						if (capacity == 0) {
							unwrapBeforeRead = true;
						} else {
							unwrapBeforeRead = false;
						}
						// If everything is OK, so complete
						handler.completed(Long.valueOf(read), attach);
					} catch (Exception e) {
						failed(e, attach);
					}
				}
			}

			@Override
			public void failed(Throwable exc, A attach) {
				handler.failed(exc, attach);
			}
		};
		if (unwrapBeforeRead || netInBuffer.position() > 0) {
			readCompletionHandler.completed(Integer.valueOf(netInBuffer.position()), attachment);
		} else {
			getSocketChannel().read(netInBuffer, timeout, unit, attachment, readCompletionHandler);
		}
	}

	@Override
	public <A> void write(final ByteBufferWrapper src, final long timeout, final TimeUnit unit, final A attachment,
			final CompletionHandler<Integer, ? super A> handler) {
		System.out.println("write timeout " + timeout + src);
		// Check state
		if (closing || closed) {
			handler.failed(new IOException(sm.getString("channel.nio.ssl.closing")), attachment);
			return;
		}
		try {
			// Prepare the output buffer
			netOutBuffer.clear();
			// Wrap the source data into the internal buffer
			if (!src.isReadMode()) {
				throw new RuntimeException();
			}
			SSLEngineResult result = sslEngine.wrap(src.getByteBuffer(), netOutBuffer);
			final int written = result.bytesConsumed();
			netOutBuffer.flip();
			if (result.getStatus() == Status.OK) {
				if (result.getHandshakeStatus() == HandshakeStatus.NEED_TASK) {
					tasks();
				}
				// Write data to the channel
				getSocketChannel().write(netOutBuffer, timeout, unit, attachment, new CompletionHandler<Integer, A>() {
					@Override
					public void completed(Integer nBytes, A attach) {
						if (nBytes.intValue() < 0) {
							failed(new EOFException(), attach);
						} else if (netOutBuffer.hasRemaining()) {
							getSocketChannel().write(netOutBuffer, timeout, unit, attachment, this);
						} else if (written == 0) {
							// Special case, start over to avoid code duplication
							write(src, timeout, unit, attachment, handler);
						} else {
							// Call the handler completed method with the
							// consumed bytes number
							handler.completed(Integer.valueOf(written), attach);
						}
					}

					@Override
					public void failed(Throwable exc, A attach) {
						System.out.println("write failed " + timeout + src);
						handler.failed(exc, attach);
					}
				});
			} else {
				throw new IOException(sm.getString("channel.nio.ssl.wrapFail", result.getStatus()));
			}
		} catch (Exception e) {
			handler.failed(e, attachment);
		}
	}

	@Override
	public <A> void write(final ByteBufferWrapper[] srcs, final int offset, final int length, final long timeout,
			final TimeUnit unit, final A attachment, final CompletionHandler<Long, ? super A> handler) {
		System.out.println("write timeout " + timeout + srcs);
		if ((offset < 0) || (length < 0) || (offset > srcs.length - length)) {
			throw new IndexOutOfBoundsException();
		}
		// Check state
		if (closing || closed) {
			handler.failed(new IOException(sm.getString("channel.nio.ssl.closing")), attachment);
			return;
		}
		try {
			// Prepare the output buffer
			netOutBuffer.clear();
			// Wrap the source data into the internal buffer
			ByteBuffer[] buffers = new ByteBuffer[srcs.length];
			for (int i = 0; i < srcs.length; i++) {
				if (!srcs[i].isReadMode()) {
					throw new RuntimeException();
				}
				buffers[i] = srcs[i].getByteBuffer();
			}
			SSLEngineResult result = sslEngine.wrap(buffers, offset, length, netOutBuffer);
			final int written = result.bytesConsumed();
			netOutBuffer.flip();
			if (result.getStatus() == Status.OK) {
				if (result.getHandshakeStatus() == HandshakeStatus.NEED_TASK) {
					tasks();
				}
				// Write data to the channel
				getSocketChannel().write(netOutBuffer, timeout, unit, attachment, new CompletionHandler<Integer, A>() {
					@Override
					public void completed(Integer nBytes, A attach) {
						if (nBytes.intValue() < 0) {
							failed(new EOFException(), attach);
						} else if (netOutBuffer.hasRemaining()) {
							getSocketChannel().write(netOutBuffer, timeout, unit, attachment, this);
						} else if (written == 0) {
							// Special case, start over to avoid code duplication
							write(srcs, offset, length, timeout, unit, attachment, handler);
						} else {
							// Call the handler completed method with the
							// consumed bytes number
							handler.completed(Long.valueOf(written), attach);
						}
					}

					@Override
					public void failed(Throwable exc, A attach) {
						System.out.println("write failed " + timeout + srcs);
						handler.failed(exc, attach);
					}
				});
			} else {
				throw new IOException(sm.getString("channel.nio.ssl.wrapFail", result.getStatus()));
			}
		} catch (Exception e) {
			handler.failed(e, attachment);
		}
	}

	@Override
	public boolean isHandshakeComplete() {
		return handshakeComplete;
	}

	@Override
	public boolean isClosing() {
		return closing;
	}

	public SSLEngine getSslEngine() {
		return sslEngine;
	}

	public ByteBuffer getEmptyBuf() {
		return emptyBuf;
	}

	private enum OverflowState {
		NONE, PROCESSING, DONE;
	}
}