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
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Collections;
import java.util.List;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLEngineResult.Status;
import javax.net.ssl.SSLException;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.buf.ByteBufferUtils;
import org.apache.tomcat.util.compat.JreCompat;
import org.apache.tomcat.util.net.NioEndpoint.NioSocketWrapper;
import org.apache.tomcat.util.net.TLSClientHelloExtractor.ExtractorResult;
import org.apache.tomcat.util.net.openssl.ciphers.Cipher;
import org.apache.tomcat.util.res.StringManager;

/**
 * Implementation of a secure socket channel
 */
public class SecureNioChannel extends NioChannel {

	private static final Log log = LogFactory.getLog(SecureNioChannel.class);
	private static final StringManager sm = StringManager.getManager(SecureNioChannel.class);

	// Value determined by observation of what the SSL Engine requested in
	// various scenarios
	private static final int DEFAULT_NET_BUFFER_SIZE = 16921;

	private final NioEndpoint endpoint;

	protected ByteBuffer netInBuffer;
	protected ByteBuffer netOutBuffer;

	protected SSLEngine sslEngine;

	protected boolean sniComplete = false;

	protected boolean handshakeComplete = false;
	protected HandshakeStatus handshakeStatus; // gets set by handshake

	protected boolean closed = false;
	protected boolean closing = false;

	protected NioSelectorPool pool;

	public SecureNioChannel(SocketProperties socketProperties, NioSelectorPool pool, NioEndpoint endpoint) {
		super(socketProperties.getAppReadBufSize(), socketProperties.getAppWriteBufSize(),
				socketProperties.getDirectBuffer());

		// Create the network buffers (these hold the encrypted data).
		if (endpoint.getSocketProperties().getDirectSslBuffer()) {
			netInBuffer = ByteBuffer.allocateDirect(DEFAULT_NET_BUFFER_SIZE);
			netOutBuffer = ByteBuffer.allocateDirect(DEFAULT_NET_BUFFER_SIZE);
		} else {
			netInBuffer = ByteBuffer.allocate(DEFAULT_NET_BUFFER_SIZE);
			netOutBuffer = ByteBuffer.allocate(DEFAULT_NET_BUFFER_SIZE);
		}

		// selector pool for blocking operations
		this.pool = pool;
		this.endpoint = endpoint;
	}

	@Override
	public void reset(SocketChannel channel, NioSocketWrapper socketWrapper) throws IOException {
		super.reset(channel, socketWrapper);
		sslEngine = null;
		sniComplete = false;
		handshakeComplete = false;
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

//===========================================================================================
//                  NIO SSL METHODS
//===========================================================================================

	/**
	 * Flush the channel.
	 *
	 * @param block   Should a blocking write be used?
	 * @param s       The selector to use for blocking, if null then a busy write
	 *                will be initiated
	 * @param timeout The timeout for this write operation in milliseconds, -1 means
	 *                no timeout
	 * @return <code>true</code> if the network buffer has been flushed out and is
	 *         empty else <code>false</code>
	 * @throws IOException If an I/O error occurs during the operation
	 */
	@Override
	public boolean flush(boolean block, Selector s, long timeout) throws IOException {
		if (!block) {
			flush(netOutBuffer);
		} else {
			pool.write(netOutBuffer, socketWrapper, s, timeout);
		}
		return !netOutBuffer.hasRemaining();
	}

	/**
	 * Flushes the buffer to the network, non blocking
	 * 
	 * @param buf ByteBuffer
	 * @return boolean true if the buffer has been emptied out, false otherwise
	 * @throws IOException An IO error occurred writing data
	 */
	protected boolean flush(ByteBuffer buf) throws IOException {
		int remaining = buf.remaining();
		if (remaining > 0) {
			boolean success = false;
			int writed = socketChannel.write(buf);
			success = (writed >= remaining);
			// System.out.println(socketChannel.socket().getPort() + " flush success:" +
			// success);
			return success;
		} else {
			return true;
		}
	}

	/**
	 * Performs SSL handshake, non blocking, but performs NEED_TASK on the same
	 * thread. Hence, you should never call this method using your Acceptor thread,
	 * as you would slow down your system significantly. If the return value from
	 * this method is positive, the selection key should be registered interestOps
	 * given by the return value.
	 *
	 * @param read  boolean - true if the underlying channel is readable
	 * @param write boolean - true if the underlying channel is writable
	 *
	 * @return 0 if hand shake is complete, -1 if an error (other than an
	 *         IOException) occurred, otherwise it returns a SelectionKey
	 *         interestOps value
	 *
	 * @throws IOException If an I/O error occurs during the handshake or if the
	 *                     handshake fails during wrapping or unwrapping
	 */
	@Override
	public int handshake(boolean read, boolean write) throws IOException {
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

		if (!flush(netOutBuffer)) {
			return HandShakeable.HANDSHAKE_NEEDWRITE; // we still have data to write
		}

		SSLEngineResult handshake = null;

		while (!handshakeComplete) {
			switch (handshakeStatus) {
			case NOT_HANDSHAKING:
				// should never happen
				throw new IOException(sm.getString("channel.nio.ssl.notHandshaking"));
			case FINISHED:
				System.out.println(socketChannel.socket().getPort() + " finished");
				if (endpoint.hasNegotiableProtocols()) {
					if (sslEngine instanceof SSLUtil.ProtocolInfo) {
						socketWrapper.setNegotiatedProtocol(((SSLUtil.ProtocolInfo) sslEngine).getNegotiatedProtocol());
					} else if (JreCompat.isAlpnSupported()) {
						socketWrapper.setNegotiatedProtocol(JreCompat.getInstance().getApplicationProtocol(sslEngine));
					}
				}
				// we are complete if we have delivered the last package
				handshakeComplete = !netOutBuffer.hasRemaining();
				// return 0 if we are complete, otherwise we still have data to write
				return handshakeComplete ? HandShakeable.HANDSHAKE_COMPLETE : HandShakeable.HANDSHAKE_NEEDWRITE;
			case NEED_WRAP:
				System.out.println(socketChannel.socket().getPort() + " need wrap");
				// perform the wrap function
				try {
					handshake = handshakeWrap(write);
				} catch (SSLException e) {
					if (log.isDebugEnabled()) {
						log.debug(sm.getString("channel.nio.ssl.wrapException"), e);
					}
					handshake = handshakeWrap(write);
				}
				if (handshake.getStatus() == Status.OK) {
					if (handshakeStatus == HandshakeStatus.NEED_TASK) {
						HandshakeStatus oldHandshakeStatus = handshakeStatus;
						handshakeStatus = tasks();
						if (oldHandshakeStatus != handshakeStatus) {
							System.out.println(socketChannel.socket().getPort() + " change from " + oldHandshakeStatus
									+ " to " + handshakeStatus);
						}
					}
				} else if (handshake.getStatus() == Status.CLOSED) {
					flush(netOutBuffer);
					return HandShakeable.HANDSHAKE_FAIL;
				} else {
					// wrap should always work with our buffers
					throw new IOException(
							sm.getString("channel.nio.ssl.unexpectedStatusDuringWrap", handshake.getStatus()));
				}
				if (handshakeStatus != HandshakeStatus.NEED_UNWRAP || (!flush(netOutBuffer))) {
					// should actually return OP_READ if we have NEED_UNWRAP
					if (handshakeStatus == HandshakeStatus.FINISHED) {
						if (flush(netOutBuffer)) {
							break;
						}
					}
					return HandShakeable.HANDSHAKE_NEEDWRITE;
				}
				// fall down to NEED_UNWRAP on the same call, will result in a
				// BUFFER_UNDERFLOW if it needs data
				// $FALL-THROUGH$
			case NEED_UNWRAP:
				System.out.println(socketChannel.socket().getPort() + " need unwrap");
				// perform the unwrap function
				handshake = handshakeUnwrap(read);
				if (handshake.getStatus() == Status.OK) {
					if (handshakeStatus == HandshakeStatus.NEED_TASK) {
						HandshakeStatus oldHandshakeStatus = handshakeStatus;
						handshakeStatus = tasks();
						if (oldHandshakeStatus != handshakeStatus) {
							System.out.println(socketChannel.socket().getPort() + " change from " + oldHandshakeStatus
									+ " to " + handshakeStatus);
						}
					}
				} else if (handshake.getStatus() == Status.BUFFER_UNDERFLOW) {
					System.out.println(socketChannel.socket().getPort() + " need more data");
					// read more data, reregister for OP_READ
					return HandShakeable.HANDSHAKE_NEEDREAD;
				} else {
					throw new IOException(
							sm.getString("channel.nio.ssl.unexpectedStatusDuringWrap", handshake.getStatus()));
				}
				break;
			case NEED_TASK:
				System.out.println(socketChannel.socket().getPort() + " tasks");
				HandshakeStatus oldHandshakeStatus = handshakeStatus;
				handshakeStatus = tasks();
				if (oldHandshakeStatus != handshakeStatus) {
					System.out.println(socketChannel.socket().getPort() + " change from " + oldHandshakeStatus + " to "
							+ handshakeStatus);
				}
				break;
			default:
				throw new IllegalStateException(sm.getString("channel.nio.ssl.invalidStatus", handshakeStatus));
			}
		}
		// Handshake is complete if this point is reached
		return HandShakeable.HANDSHAKE_COMPLETE;
	}

	/*
	 * Peeks at the initial network bytes to determine if the SNI extension is
	 * present and, if it is, what host name has been requested. Based on the
	 * provided host name, configure the SSLEngine for this connection.
	 *
	 * @return 0 if SNI processing is complete, -1 if an error (other than an
	 * IOException) occurred, otherwise it returns a SelectionKey interestOps value
	 *
	 * @throws IOException If an I/O error occurs during the SNI processing
	 */
	private int processSNI() throws IOException {
		System.out.println(socketChannel.socket().getPort() + " processSNI start ");
		// Read some data into the network input buffer so we can peek at it.
		int pos = netInBuffer.position();
		int bytesRead = socketChannel.read(netInBuffer);
		printInBuffer(pos);
		if (bytesRead == -1) {
			// Reached end of stream before SNI could be processed.
			return HandShakeable.HANDSHAKE_FAIL;
		}
		TLSClientHelloExtractor extractor = new TLSClientHelloExtractor(netInBuffer);
		// byte[] b = netInBuffer.array();

		while (extractor.getResult() == ExtractorResult.UNDERFLOW
				&& netInBuffer.capacity() < endpoint.getSniParseLimit()) {
			// extractor needed more data to process but netInBuffer was full so
			// expand the buffer and read some more data.
			int newLimit = Math.min(netInBuffer.capacity() * 2, endpoint.getSniParseLimit());
			log.info(sm.getString("channel.nio.ssl.expandNetInBuffer", Integer.toString(newLimit)));

			netInBuffer = ByteBufferUtils.expand(netInBuffer, newLimit);
			pos = netInBuffer.position();
			socketChannel.read(netInBuffer);
			printInBuffer(pos);
			extractor = new TLSClientHelloExtractor(netInBuffer);
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
			return HandShakeable.HANDSHAKE_NEEDREAD;
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
			flushOutbound();
			throw new IOException(sm.getString("channel.nio.ssl.foundHttp"));
		}

		if (log.isDebugEnabled()) {
			log.debug(sm.getString("channel.nio.ssl.sniHostName", socketChannel, hostName));
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
		System.out.println(socketChannel.socket().getPort() + " processSNI end ");
		return HandShakeable.HANDSHAKE_COMPLETE;
	}

	/**
	 * Force a blocking handshake to take place for this key. This requires that
	 * both network and application buffers have been emptied out prior to this call
	 * taking place, or a IOException will be thrown.
	 * 
	 * @param timeout - timeout in milliseconds for each socket operation
	 * @throws IOException            - if an IO exception occurs or if application
	 *                                or network buffers contain data
	 * @throws SocketTimeoutException - if a socket operation timed out
	 */
	@SuppressWarnings("null") // key cannot be null
	public void rehandshake(long timeout) throws IOException {
		// validate the network buffers are empty
		if (netInBuffer.position() > 0 && netInBuffer.position() < netInBuffer.limit()) {
			throw new IOException(sm.getString("channel.nio.ssl.netInputNotEmpty"));
		}
		if (netOutBuffer.position() > 0 && netOutBuffer.position() < netOutBuffer.limit()) {
			throw new IOException(sm.getString("channel.nio.ssl.netOutputNotEmpty"));
		}
		if (!isReadBufferEmpty()) {
			throw new IOException(sm.getString("channel.nio.ssl.appInputNotEmpty"));
		}
		if (!isWriteBufferEmpty()) {
			throw new IOException(sm.getString("channel.nio.ssl.appOutputNotEmpty"));
		}
		handshakeComplete = false;
		boolean isReadable = false;
		boolean isWriteable = false;
		boolean handshaking = true;
		Selector selector = null;
		SelectionKey key = null;
		try {
			sslEngine.beginHandshake();
			handshakeStatus = sslEngine.getHandshakeStatus();
			while (handshaking) {
				int hsStatus = this.handshake(isReadable, isWriteable);
				switch (hsStatus) {
				case -1:
					throw new EOFException(sm.getString("channel.nio.ssl.eofDuringHandshake"));
				case 0:
					handshaking = false;
					break;
				default:
					long now = System.currentTimeMillis();
					if (selector == null) {
						selector = Selector.open();
						key = getIOChannel().register(selector, hsStatus);
					} else {
						key.interestOps(hsStatus); // null warning suppressed
					}
					int keyCount = selector.select(timeout);
					if (keyCount == 0 && ((System.currentTimeMillis() - now) >= timeout)) {
						throw new SocketTimeoutException(sm.getString("channel.nio.ssl.timeoutDuringHandshake"));
					}
					isReadable = key.isReadable();
					isWriteable = key.isWritable();
				}
			}
		} catch (IOException x) {
			closeSilently();
			throw x;
		} catch (Exception cx) {
			closeSilently();
			IOException x = new IOException(cx);
			throw x;
		} finally {
			if (key != null) {
				try {
					key.cancel();
				} catch (Exception ignore) {
				}
			}
			if (selector != null) {
				try {
					selector.close();
				} catch (Exception ignore) {
				}
			}
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
	 * @param doWrite boolean
	 * @return the result
	 * @throws IOException An IO error occurred
	 */
	protected SSLEngineResult handshakeWrap(boolean doWrite) throws IOException {
		boolean cout = false;
		SSLEngineResult result = null;
		do {
			// this should never be called with a network buffer that contains data
			// so we can clear it here.
			netOutBuffer.clear();
			// perform the wrap
			// configureWriteBufferForRead();
			int pos = netOutBuffer.position();
			result = sslEngine.wrap(getEmptyBuf(), netOutBuffer);// getWriteBuffer()
			printOutBuffer(pos);
			// prepare the results to be written
			netOutBuffer.flip();
			// set the status
			HandshakeStatus oldHandshakeStatus = handshakeStatus;
			handshakeStatus = result.getHandshakeStatus();
			if (oldHandshakeStatus != handshakeStatus) {
				System.out.println(socketChannel.socket().getPort() + " change from " + oldHandshakeStatus + " to "
						+ handshakeStatus);
			}
			// optimization, if we do have a writable channel, write it now
			// if (doWrite) {
			if (flush(netOutBuffer)) {
				if (result.getStatus() == SSLEngineResult.Status.OK && handshakeStatus == HandshakeStatus.NEED_TASK) {
					// execute tasks if we need to
					oldHandshakeStatus = handshakeStatus;
					handshakeStatus = tasks();
					if (oldHandshakeStatus != handshakeStatus) {
						System.out.println(socketChannel.socket().getPort() + " change from " + oldHandshakeStatus
								+ " to " + handshakeStatus);
					}
				}
				cout = result.getStatus() == SSLEngineResult.Status.OK && handshakeStatus == HandshakeStatus.NEED_WRAP;
			} else {
				cout = false;
			}
			// }
		} while (cout);
		return result;
	}

	/**
	 * Perform handshake unwrap
	 * 
	 * @param doread boolean
	 * @return the result
	 * @throws IOException An IO error occurred
	 */
	protected SSLEngineResult handshakeUnwrap(boolean doread) throws IOException {

		if (netInBuffer.position() == netInBuffer.limit()) {
			// clear the buffer if we have emptied it out on data
			netInBuffer.clear();
		}
		if (netInBuffer.position() == 0) {
			// if (doread) {
			// if we have data to read, read it
			int pos = netInBuffer.position();
			int read = -1;
			read = socketChannel.read(netInBuffer);
			printInBuffer(pos);
			if (read == -1) {
				throw new IOException(sm.getString("channel.nio.ssl.eofDuringHandshake"));
			}
			// }
		}
		SSLEngineResult result;
		boolean cont = false;
		// loop while we can perform pure SSLEngine data
		int count = 0;
		netInBuffer.flip();
		ByteBuffer buffer = ByteBuffer.allocate(netInBuffer.capacity());

		do {

			// prepare the buffer with the incoming data
			// call unwrap
			// configureReadBufferForWrite();

			result = sslEngine.unwrap(netInBuffer, buffer);

			// compact the buffer, this is an optional method, wonder what would happen if
			// we didn't
			// netInBuffer.compact();
			// read in the status
			HandshakeStatus oldHandshakeStatus = handshakeStatus;
			handshakeStatus = result.getHandshakeStatus();
			if (oldHandshakeStatus != handshakeStatus) {
				System.out.println(socketChannel.socket().getPort() + " change from " + oldHandshakeStatus + " to "
						+ handshakeStatus);
			}
			if (result.getStatus() == SSLEngineResult.Status.OK && handshakeStatus == HandshakeStatus.NEED_TASK) {
				// execute tasks if we need to
				oldHandshakeStatus = handshakeStatus;
				handshakeStatus = tasks();
				if (oldHandshakeStatus != handshakeStatus) {
					System.out.println(socketChannel.socket().getPort() + " change from " + oldHandshakeStatus + " to "
							+ handshakeStatus);
				}
			}
			// perform another unwrap?
			cont = result.getStatus() == SSLEngineResult.Status.OK && handshakeStatus == HandshakeStatus.NEED_UNWRAP;
			count++;
		} while (cont);
		netInBuffer.compact();
		if (buffer.position() != 0) {
			throw new RuntimeException();
		}
		return result;
	}

	/**
	 * Sends an SSL close message, will not physically close the connection here.
	 * <br>
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
		if (closing) {
			return;
		}
		closing = true;
		sslEngine.closeOutbound();

		if (!flush(netOutBuffer)) {
			throw new IOException(sm.getString("channel.nio.ssl.remainingDataDuringClose"));
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
		flush(netOutBuffer);

		// is the channel closed?
		closed = (!netOutBuffer.hasRemaining() && (handshake.getHandshakeStatus() != HandshakeStatus.NEED_WRAP));
		System.out.println(socketChannel.socket().getPort() + " close");
	}

	@Override
	public void close(boolean force) throws IOException {
		try {
			close();
		} finally {
			if (force || closed) {
				closed = true;
				socketChannel.close();
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

	/**
	 * Reads a sequence of bytes from this channel into the given buffer.
	 *
	 * @param dst The buffer into which bytes are to be transferred
	 * @return The number of bytes read, possibly zero, or <code>-1</code> if the
	 *         channel has reached end-of-stream
	 * @throws IOException              If some other I/O error occurs
	 * @throws IllegalArgumentException if the destination buffer is different than
	 *                                  getReadBuffer()
	 */
	@Override
	public int read(ByteBuffer dst) throws IOException {
		if (!handshakeComplete) {
			System.out.println(socketChannel.socket().getPort() + " read dst ");
		}
		// are we in the middle of closing or closed?
		if (closing || closed) {
			return -1;
		}
		// did we finish our handshake?
		if (!handshakeComplete) {
			throw new IllegalStateException(sm.getString("channel.nio.ssl.incompleteHandshake"));
		}

		// read from the network
		int pos = netInBuffer.position();
		int netread = -1;
		try {
			netread = socketChannel.read(netInBuffer);
			printInBuffer(pos);
		} catch (IOException e) {
			System.out.println(socketChannel.socket().getPort() + " error when read");
			throw e;
		}
		// did we reach EOF? if so send EOF up one layer.
		if (netread == -1) {
			return -1;
		}

		// the data read
		int read = 0;
		// the SSL engine result
		SSLEngineResult unwrap;
		do {
			// prepare the buffer
			netInBuffer.flip();
			// unwrap the data
			unwrap = sslEngine.unwrap(netInBuffer, dst);
			// if (netread > 0) {
			// System.out.println(this + "read内容:" + new String(dst.array(), 0,
			// dst.limit()));
			// }
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
					break;
				}
			} else if (unwrap.getStatus() == Status.BUFFER_OVERFLOW) {
				if (read > 0) {
					// Buffer overflow can happen if we have read data. Return
					// so the destination buffer can be emptied before another
					// read is attempted
					break;
				} else {
					// The SSL session has increased the required buffer size
					// since the buffer was created.
					if (dst == getReadBuffer()) {
						// This is the normal case for this code
						expand(sslEngine.getSession().getApplicationBufferSize());
						dst = getReadBuffer();
					} else if (dst == this.getAppReadBuffer()) {
						this.expandAppReadBuffer(sslEngine.getSession().getApplicationBufferSize());
						dst = this.getAppReadBuffer();
					} else {
						// Can't expand the buffer as there is no way to signal
						// to the caller that the buffer has been replaced.
						throw new IOException(sm.getString("channel.nio.ssl.unwrapFailResize", unwrap.getStatus()));
					}
				}
			} else {
				// Something else went wrong
				throw new IOException(sm.getString("channel.nio.ssl.unwrapFail", unwrap.getStatus()));
			}
		} while (netInBuffer.position() != 0); // continue to unwrapping as long as the input buffer has stuff
		return read;
	}

	@Override
	public long read(ByteBuffer[] dsts, int offset, int length) throws IOException {
		if (!handshakeComplete) {
			System.out.println(socketChannel.socket().getPort() + " read dsts");
		}
		// are we in the middle of closing or closed?
		if (closing || closed) {
			return -1;
		}
		// did we finish our handshake?
		if (!handshakeComplete) {
			throw new IllegalStateException(sm.getString("channel.nio.ssl.incompleteHandshake"));
		}

		// read from the network
		int pos = netInBuffer.position();
		int netread = -1;
		try {
			netread = socketChannel.read(netInBuffer);
			printInBuffer(pos);
		} catch (IOException e) {
			System.out.println(socketChannel.socket().getPort() + " error when read");
			throw e;
		}
		// did we reach EOF? if so send EOF up one layer.
		if (netread == -1) {
			return -1;
		}

		// the data read
		int read = 0;
		// the SSL engine result
		SSLEngineResult unwrap;
		OverflowState overflowState = OverflowState.NONE;
		do {
			if (overflowState == OverflowState.PROCESSING) {
				overflowState = OverflowState.DONE;
			}
			// prepare the buffer
			netInBuffer.flip();
			// unwrap the data
			unwrap = sslEngine.unwrap(netInBuffer, dsts, offset, length);
			// compact the buffer
			netInBuffer.compact();

			if (unwrap.getStatus() == Status.OK || unwrap.getStatus() == Status.BUFFER_UNDERFLOW) {
				// we did receive some data, add it to our total
				read += unwrap.bytesProduced();
				if (overflowState == OverflowState.DONE) {
					// Remove the data read into the overflow buffer
					read -= getReadBuffer().position();
				}
				// perform any tasks if needed
				if (unwrap.getHandshakeStatus() == HandshakeStatus.NEED_TASK) {
					tasks();
				}
				// if we need more network data, then bail out for now.
				if (unwrap.getStatus() == Status.BUFFER_UNDERFLOW) {
					break;
				}
			} else if (unwrap.getStatus() == Status.BUFFER_OVERFLOW) {
				if (read > 0) {
					// Buffer overflow can happen if we have read data. Return
					// so the destination buffer can be emptied before another
					// read is attempted
					break;
				} else {
					ByteBuffer readBuffer = getReadBuffer();
					boolean found = false;
					boolean resized = true;
					for (int i = 0; i < length; i++) {
						// The SSL session has increased the required buffer size
						// since the buffer was created.
						if (dsts[offset + i] == getReadBuffer()) {
							expand(sslEngine.getSession().getApplicationBufferSize());
							if (dsts[offset + i] == getReadBuffer()) {
								resized = false;
							}
							dsts[offset + i] = getReadBuffer();
							found = true;
						} else if (dsts[offset + i] == this.getAppReadBuffer()) {
							this.expandAppReadBuffer(sslEngine.getSession().getApplicationBufferSize());
							if (dsts[offset + i] == this.getAppReadBuffer()) {
								resized = false;
							}
							dsts[offset + i] = this.getAppReadBuffer();
							found = true;
						}
					}
					if (found) {
						if (!resized) {
							throw new IOException(sm.getString("channel.nio.ssl.unwrapFail", unwrap.getStatus()));
						}
					} else {
						// Add the main read buffer in the destinations and try again
						ByteBuffer[] dsts2 = new ByteBuffer[dsts.length + 1];
						int dstOffset = 0;
						for (int i = 0; i < dsts.length + 1; i++) {
							if (i == offset + length) {
								dsts2[i] = readBuffer;
								dstOffset = -1;
							} else {
								dsts2[i] = dsts[i + dstOffset];
							}
						}
						dsts = dsts2;
						length++;
						configureReadBufferForWrite();
						overflowState = OverflowState.PROCESSING;
					}
				}
			} else {
				// Something else went wrong
				throw new IOException(sm.getString("channel.nio.ssl.unwrapFail", unwrap.getStatus()));
			}
		} while ((netInBuffer.position() != 0 || overflowState == OverflowState.PROCESSING)
				&& overflowState != OverflowState.DONE);
		return read;
	}

	/**
	 * Writes a sequence of bytes to this channel from the given buffer.
	 *
	 * @param src The buffer from which bytes are to be retrieved
	 * @return The number of bytes written, possibly zero
	 * @throws IOException If some other I/O error occurs
	 */
	@Override
	public int write(ByteBuffer src) throws IOException {
		if (!handshakeComplete) {
			System.out.println(socketChannel.socket().getPort() + " write src " + src.remaining());
		}
		checkInterruptStatus();
		if (src == this.netOutBuffer) {
			// we can get here through a recursive call
			// by using the NioBlockingSelector
			int written = socketChannel.write(src);
			return written;
		} else {
			// Are we closing or closed?
			if (closing || closed) {
				throw new IOException(sm.getString("channel.nio.ssl.closing"));
			}

			if (!flush(netOutBuffer)) {
				// We haven't emptied out the buffer yet
				return 0;
			}

			// The data buffer is empty, we can reuse the entire buffer.
			netOutBuffer.clear();

			SSLEngineResult result = sslEngine.wrap(src, netOutBuffer);
			// The number of bytes written
			int written = result.bytesConsumed();
			netOutBuffer.flip();

			if (result.getStatus() == Status.OK) {
				if (result.getHandshakeStatus() == HandshakeStatus.NEED_TASK) {
					tasks();
				}
			} else {
				throw new IOException(sm.getString("channel.nio.ssl.wrapFail", result.getStatus()));
			}

			// Force a flush
			flush(netOutBuffer);

			return written;
		}
	}

	@Override
	public long write(ByteBuffer[] srcs, int offset, int length) throws IOException {
		if (!handshakeComplete) {
			System.out.println(socketChannel.socket().getPort() + " write srcs");
		}
		checkInterruptStatus();
		// Are we closing or closed?
		if (closing || closed) {
			throw new IOException(sm.getString("channel.nio.ssl.closing"));
		}

		if (!flush(netOutBuffer)) {
			// We haven't emptied out the buffer yet
			return 0;
		}

		// The data buffer is empty, we can reuse the entire buffer.
		netOutBuffer.clear();

		SSLEngineResult result = sslEngine.wrap(srcs, offset, length, netOutBuffer);
		// The number of bytes written
		int written = result.bytesConsumed();
		netOutBuffer.flip();

		if (result.getStatus() == Status.OK) {
			if (result.getHandshakeStatus() == HandshakeStatus.NEED_TASK)
				tasks();
		} else {
			throw new IOException(sm.getString("channel.nio.ssl.wrapFail", result.getStatus()));
		}

		// Force a flush
		flush(netOutBuffer);

		return written;
	}

	@Override
	public int getOutboundRemaining() {
		return netOutBuffer.remaining();
	}

	@Override
	public boolean flushOutbound() throws IOException {
		int remaining = netOutBuffer.remaining();
		flush(netOutBuffer);
		int remaining2 = netOutBuffer.remaining();
		return remaining2 < remaining;
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

	private void printInBuffer(int pos) {
		if ("1".equals("1"))
			return;
		synchronized (endpoint) {
			System.out.print(socketChannel.socket().getPort() + " read:[");
			for (int i = pos; i < netInBuffer.position(); i++) {
				if (i > pos)
					System.out.print(",");
				System.out.print(
						netInBuffer.get(i) >= 0 ? netInBuffer.get(i) : Integer.toHexString(netInBuffer.get(i) & 0xFF));
			}
			System.out.println("]");
		}
	}

	private void printOutBuffer(int pos) {
		if ("1".equals("1"))
			return;
		synchronized (endpoint) {
			System.out.print(socketChannel.socket().getPort() + " write:[");
			for (int i = pos; i < netOutBuffer.position(); i++) {
				if (i > pos)
					System.out.print(",");
				System.out.print(netOutBuffer.get(i) >= 0 ? netOutBuffer.get(i)
						: Integer.toHexString(netOutBuffer.get(i) & 0xFF));
			}
			System.out.println("]");
		}
	}
}
