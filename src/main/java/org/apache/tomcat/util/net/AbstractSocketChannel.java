/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.tomcat.util.net;

import java.io.EOFException;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.CompletionHandler;
import java.nio.channels.InterruptedByTimeoutException;
import java.nio.channels.ReadPendingException;
import java.nio.channels.WritePendingException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.res.StringManager;

public abstract class AbstractSocketChannel<E> extends AbstractChannel implements SocketChannel {

	private static final Log log = LogFactory.getLog(AbstractSocketChannel.class);

	protected static final StringManager sm = StringManager.getManager(AbstractSocketChannel.class);

	private E socket;

	private final AbstractEndpoint<E, ?> endpoint;

	protected final AtomicBoolean closed = new AtomicBoolean(false);

	// Volatile because I/O and setting the timeout values occurs on a different
	// thread to the thread checking the timeout.
	private volatile long readTimeout = -1;
	private volatile long writeTimeout = -1;

	private volatile int keepAliveLeft = 100;
	private volatile boolean upgraded = false;
	private boolean secure = false;
	private String negotiatedProtocol = null;

	/*
	 * Following cached for speed / reduced GC
	 */
	protected String localAddr = null;
	protected String localName = null;
	protected int localPort = -1;
	protected String remoteAddr = null;
	protected String remoteHost = null;
	protected int remotePort = -1;

	/*
	 * Asynchronous operations.
	 */
	private final Semaphore readPending;
	private volatile OperationState<?> readOperation = null;
	private final Semaphore writePending;
	private volatile OperationState<?> writeOperation = null;

	public AbstractSocketChannel(E socket, AbstractEndpoint<E, ?> endpoint) {
		this.socket = socket;
		this.endpoint = endpoint;
		if (endpoint.getUseAsyncIO() || needSemaphores()) {
			readPending = new Semaphore(1);
			writePending = new Semaphore(1);
		} else {
			readPending = null;
			writePending = null;
		}
	}

	// @Override
	public E getSocket() {
		return socket;
	}

	@Override
	public Object getLock() {
		return this;
	}

	protected void reset(E closedSocket) {
		socket = closedSocket;
	}

	protected AbstractEndpoint<E, ?> getEndpoint() {
		return endpoint;
	}

	public boolean isUpgraded() {
		return upgraded;
	}

	@Override
	public void setUpgraded(boolean upgraded) {
		this.upgraded = upgraded;
	}

	public boolean isSecure() {
		return secure;
	}

	public void setSecure(boolean secure) {
		this.secure = secure;
	}

	@Override
	public String getNegotiatedProtocol() {
		return negotiatedProtocol;
	}

	@Override
	public void setNegotiatedProtocol(String negotiatedProtocol) {
		this.negotiatedProtocol = negotiatedProtocol;
	}

	/**
	 * Set the timeout for reading. Values of zero or less will be changed to -1.
	 *
	 * @param readTimeout The timeout in milliseconds. A value of -1 indicates an
	 *                    infinite timeout.
	 */
	@Override
	public void setReadTimeout(long readTimeout) {
		if (readTimeout > 0) {
			this.readTimeout = readTimeout;
		} else {
			this.readTimeout = -1;
		}
	}

	@Override
	public long getReadTimeout() {
		return this.readTimeout;
	}

	/**
	 * Set the timeout for writing. Values of zero or less will be changed to -1.
	 *
	 * @param writeTimeout The timeout in milliseconds. A value of zero or less
	 *                     indicates an infinite timeout.
	 */
	@Override
	public void setWriteTimeout(long writeTimeout) {
		if (writeTimeout > 0) {
			this.writeTimeout = writeTimeout;
		} else {
			this.writeTimeout = -1;
		}
	}

	public long getWriteTimeout() {
		return this.writeTimeout;
	}

	public void setKeepAliveLeft(int keepAliveLeft) {
		this.keepAliveLeft = keepAliveLeft;
	}

	@Override
	public int decrementKeepAlive() {
		return (--keepAliveLeft);
	}

	@Override
	public String getRemoteHost() {
		if (remoteHost == null) {
			populateRemoteHost();
		}
		return remoteHost;
	}

	protected abstract void populateRemoteHost();

	@Override
	public String getRemoteAddr() {
		if (remoteAddr == null) {
			populateRemoteAddr();
		}
		return remoteAddr;
	}

	protected abstract void populateRemoteAddr();

	@Override
	public int getRemotePort() {
		if (remotePort == -1) {
			populateRemotePort();
		}
		return remotePort;
	}

	protected abstract void populateRemotePort();

	@Override
	public String getLocalName() {
		if (localName == null) {
			populateLocalName();
		}
		return localName;
	}

	protected abstract void populateLocalName();

	@Override
	public String getLocalAddr() {
		if (localAddr == null) {
			populateLocalAddr();
		}
		return localAddr;
	}

	protected abstract void populateLocalAddr();

	@Override
	public int getLocalPort() {
		if (localPort == -1) {
			populateLocalPort();
		}
		return localPort;
	}

	protected abstract void populateLocalPort();

	/**
	 * Overridden for debug purposes. No guarantees are made about the format of
	 * this message which may vary significantly between point releases.
	 * <p>
	 * {@inheritDoc}
	 */
	@Override
	public String toString() {
		return super.toString() + ":" + String.valueOf(socket);
	}

	/**
	 * Close the socket wrapper.
	 */
	@Override
	public final void close() {
		if (closed.compareAndSet(false, true)) {
			try {
				if (getCurrentProcessor() != null) {
					if (getEndpoint().getHandler().getProtocol() != null) {
						getEndpoint().getHandler().getProtocol().release(this);
					}
				}
			} catch (Throwable e) {
				ExceptionUtils.handleThrowable(e);
				if (log.isDebugEnabled()) {
					log.error(sm.getString("endpoint.debug.handlerRelease"), e);
				}
			} finally {
				getEndpoint().countDownConnection();
				doClose();
			}
		}
	}

	@Override
	public void close(Throwable e) {
		this.close();
	}

	/**
	 * Perform the actual close. The closed atomic boolean guarantees this will be
	 * called only once per wrapper.
	 */
	protected abstract void doClose();

	/**
	 * @return true if the wrapper has been closed
	 */
	@Override
	public boolean isClosed() {
		return closed.get();
	}

	/**
	 * Writes the provided data to the socket write buffer. If the socket write
	 * buffer fills during the write, the content of the socket write buffer is
	 * written to the network and this method starts to fill the socket write buffer
	 * again. Depending on the size of the data to write, there may be multiple
	 * writes to the network.
	 * <p>
	 * Non-blocking writes must return immediately and the byte array holding the
	 * data to be written must be immediately available for re-use. It may not be
	 * possible to write sufficient data to the network to allow this to happen. In
	 * this case data that cannot be written to the network and cannot be held by
	 * the socket buffer is stored in the non-blocking write buffer.
	 * <p>
	 * Note: There is an implementation assumption that, before switching from
	 * non-blocking writes to blocking writes, any data remaining in the
	 * non-blocking write buffer will have been written to the network.
	 *
	 * @param block <code>true</code> if a blocking write should be used, otherwise
	 *              a non-blocking write will be used
	 * @param buf   The byte array containing the data to be written
	 * @param off   The offset within the byte array of the data to be written
	 * @param len   The length of the data to be written
	 *
	 * @throws IOException If an IO error occurs during the write
	 */
	@Override
	public final void write(boolean block, byte[] buf, int off, int len) throws IOException {
		if (len == 0 || buf == null) {
			return;
		}

		/*
		 * While the implementations for blocking and non-blocking writes are very
		 * similar they have been split into separate methods: - To allow sub-classes to
		 * override them individually. NIO2, for example, overrides the non-blocking
		 * write but not the blocking write. - To enable a marginally more efficient
		 * implemented for blocking writes which do not require the additional checks
		 * related to the use of the non-blocking write buffer
		 */
		if (block) {
			writeBlocking(buf, off, len);
		} else {
			writeNonBlocking(buf, off, len);
		}
	}

	/**
	 * Writes the provided data to the socket write buffer. If the socket write
	 * buffer fills during the write, the content of the socket write buffer is
	 * written to the network and this method starts to fill the socket write buffer
	 * again. Depending on the size of the data to write, there may be multiple
	 * writes to the network.
	 * <p>
	 * Non-blocking writes must return immediately and the ByteBuffer holding the
	 * data to be written must be immediately available for re-use. It may not be
	 * possible to write sufficient data to the network to allow this to happen. In
	 * this case data that cannot be written to the network and cannot be held by
	 * the socket buffer is stored in the non-blocking write buffer.
	 * <p>
	 * Note: There is an implementation assumption that, before switching from
	 * non-blocking writes to blocking writes, any data remaining in the
	 * non-blocking write buffer will have been written to the network.
	 *
	 * @param block <code>true</code> if a blocking write should be used, otherwise
	 *              a non-blocking write will be used
	 * @param from  The ByteBuffer containing the data to be written
	 *
	 * @throws IOException If an IO error occurs during the write
	 */
	@Override
	public final void write(boolean block, ByteBuffer from) throws IOException {
		if (from == null || from.remaining() == 0) {
			return;
		}

		/*
		 * While the implementations for blocking and non-blocking writes are very
		 * similar they have been split into separate methods: - To allow sub-classes to
		 * override them individually. NIO2, for example, overrides the non-blocking
		 * write but not the blocking write. - To enable a marginally more efficient
		 * implemented for blocking writes which do not require the additional checks
		 * related to the use of the non-blocking write buffer
		 */
		if (block) {
			writeBlocking(from);
		} else {
			writeNonBlocking(from);
		}
	}

	/**
	 * Writes the provided data to the socket write buffer. If the socket write
	 * buffer fills during the write, the content of the socket write buffer is
	 * written to the network using a blocking write. Once that blocking write is
	 * complete, this method starts to fill the socket write buffer again. Depending
	 * on the size of the data to write, there may be multiple writes to the
	 * network. On completion of this method there will always be space remaining in
	 * the socket write buffer.
	 *
	 * @param buf The byte array containing the data to be written
	 * @param off The offset within the byte array of the data to be written
	 * @param len The length of the data to be written
	 *
	 * @throws IOException If an IO error occurs during the write
	 */
	protected abstract void writeBlocking(byte[] buf, int off, int len) throws IOException;

	/**
	 * Writes the provided data to the socket write buffer. If the socket write
	 * buffer fills during the write, the content of the socket write buffer is
	 * written to the network using a blocking write. Once that blocking write is
	 * complete, this method starts to fill the socket write buffer again. Depending
	 * on the size of the data to write, there may be multiple writes to the
	 * network. On completion of this method there will always be space remaining in
	 * the socket write buffer.
	 *
	 * @param from The ByteBuffer containing the data to be written
	 *
	 * @throws IOException If an IO error occurs during the write
	 */
	protected abstract void writeBlocking(ByteBuffer from) throws IOException;

	/**
	 * Transfers the data to the socket write buffer (writing that data to the
	 * socket if the buffer fills up using a non-blocking write) until either all
	 * the data has been transferred and space remains in the socket write buffer or
	 * a non-blocking write leaves data in the socket write buffer. After an
	 * incomplete write, any data remaining to be transferred to the socket write
	 * buffer will be copied to the socket write buffer. If the remaining data is
	 * too big for the socket write buffer, the socket write buffer will be filled
	 * and the additional data written to the non-blocking write buffer.
	 *
	 * @param buf The byte array containing the data to be written
	 * @param off The offset within the byte array of the data to be written
	 * @param len The length of the data to be written
	 *
	 * @throws IOException If an IO error occurs during the write
	 */
	protected abstract void writeNonBlocking(byte[] buf, int off, int len) throws IOException;

	/**
	 * Transfers the data to the socket write buffer (writing that data to the
	 * socket if the buffer fills up using a non-blocking write) until either all
	 * the data has been transferred and space remains in the socket write buffer or
	 * a non-blocking write leaves data in the socket write buffer. After an
	 * incomplete write, any data remaining to be transferred to the socket write
	 * buffer will be copied to the socket write buffer. If the remaining data is
	 * too big for the socket write buffer, the socket write buffer will be filled
	 * and the additional data written to the non-blocking write buffer.
	 *
	 * @param from The ByteBuffer containing the data to be written
	 *
	 * @throws IOException If an IO error occurs during the write
	 */
	protected abstract void writeNonBlocking(ByteBuffer from) throws IOException;

	@Override
	public final void write(boolean block, BufWrapper from) throws IOException {
		if (from == null || from.getRemaining() == 0) {
			return;
		}

		/*
		 * While the implementations for blocking and non-blocking writes are very
		 * similar they have been split into separate methods: - To allow sub-classes to
		 * override them individually. NIO2, for example, overrides the non-blocking
		 * write but not the blocking write. - To enable a marginally more efficient
		 * implemented for blocking writes which do not require the additional checks
		 * related to the use of the non-blocking write buffer
		 */
		if (block) {
			writeBlocking(from);
		} else {
			writeNonBlocking(from);
		}
	}

	protected abstract void writeBlocking(BufWrapper from) throws IOException;

	protected abstract void writeNonBlocking(BufWrapper from) throws IOException;

	/**
	 * Writes as much data as possible from any that remains in the buffers.
	 *
	 * @param block <code>true</code> if a blocking write should be used, otherwise
	 *              a non-blocking write will be used
	 *
	 * @return <code>true</code> if data remains to be flushed after this method
	 *         completes, otherwise <code>false</code>. In blocking mode therefore,
	 *         the return value should always be <code>false</code>
	 *
	 * @throws IOException If an IO error occurs during the write
	 */
	@Override
	public final boolean flush(boolean block) throws IOException {
		boolean result = false;
		if (block) {
			// A blocking flush will always empty the buffer.
			flushBlocking();
		} else {
			result = flushNonBlocking();
		}

		return result;
	}

	protected abstract void flushBlocking() throws IOException;

	protected abstract boolean flushNonBlocking() throws IOException;

	// public abstract void registerReadInterest();

	// public abstract void registerWriteInterest();

	// public abstract SendfileDataBase createSendfileData(String filename, long
	// pos, long length);

	// ------------------------------------------------------- NIO 2 style APIs

	/**
	 * Internal state tracker for vectored operations.
	 */
	protected abstract class OperationState<A> implements Runnable {
		protected final boolean read;
		protected final ByteBuffer[] buffers;
		protected final int offset;
		protected final int length;
		protected final A attachment;
		protected final long timeout;
		protected final TimeUnit unit;
		protected final BlockingMode block;
		protected final CompletionCheck check;
		protected final CompletionHandler<Long, ? super A> handler;
		protected final Semaphore semaphore;
		protected final VectoredIOCompletionHandler<A> completion;
		protected final AtomicBoolean callHandler;

		protected OperationState(boolean read, ByteBuffer[] buffers, int offset, int length, BlockingMode block,
				long timeout, TimeUnit unit, A attachment, CompletionCheck check,
				CompletionHandler<Long, ? super A> handler, Semaphore semaphore,
				VectoredIOCompletionHandler<A> completion) {
			this.read = read;
			this.buffers = buffers;
			this.offset = offset;
			this.length = length;
			this.block = block;
			this.timeout = timeout;
			this.unit = unit;
			this.attachment = attachment;
			this.check = check;
			this.handler = handler;
			this.semaphore = semaphore;
			this.completion = completion;
			callHandler = (handler != null) ? new AtomicBoolean(true) : null;
		}

		protected volatile long nBytes = 0;
		protected volatile CompletionState state = CompletionState.PENDING;
		protected boolean completionDone = true;

		/**
		 * @return true if the operation is still inline, false if the operation is
		 *         running on a thread that is not the original caller
		 */
		protected abstract boolean isInline();

		/**
		 * Process the operation using the connector executor.
		 * 
		 * @return true if the operation was accepted, false if the executor rejected
		 *         execution
		 */
		protected boolean process() {
			try {
				if (getEndpoint().getHandler().getProtocol() != null) {
					getEndpoint().getHandler().getProtocol().getExecutor().execute(this);
					return true;
				}
			} catch (RejectedExecutionException ree) {
				log.warn(sm.getString("endpoint.executor.fail", AbstractSocketChannel.this), ree);
			} catch (Throwable t) {
				ExceptionUtils.handleThrowable(t);
				// This means we got an OOM or similar creating a thread, or that
				// the pool and its queue are full
				log.error(sm.getString("endpoint.process.fail"), t);
			}
			return false;
		}

		/**
		 * Start the operation, this will typically call run.
		 */
		protected void start() {
			run();
		}

		/**
		 * End the operation.
		 */
		protected void end() {
		}

	}

	/**
	 * Completion handler for vectored operations. This will check the completion of
	 * the operation, then either continue or call the user provided completion
	 * handler.
	 */
	protected class VectoredIOCompletionHandler<A> implements CompletionHandler<Long, OperationState<A>> {

		protected VectoredIOCompletionHandler() {

		}

		@Override
		public void completed(Long nBytes, OperationState<A> state) {
			if (nBytes.longValue() < 0) {
				failed(new EOFException(), state);
			} else {
				state.nBytes += nBytes.longValue();
				CompletionState currentState = state.isInline() ? CompletionState.INLINE : CompletionState.DONE;
				boolean complete = true;
				boolean completion = true;
				if (state.check != null) {
					CompletionHandlerCall call = state.check.callHandler(currentState, state.buffers, state.offset,
							state.length);
					if (call == CompletionHandlerCall.CONTINUE) {
						complete = false;
					} else if (call == CompletionHandlerCall.NONE) {
						completion = false;
					}
				}
				if (complete) {
					boolean notify = false;
					state.semaphore.release();
					if (state.read) {
						readOperation = null;
					} else {
						writeOperation = null;
					}
					if (state.block == BlockingMode.BLOCK && currentState != CompletionState.INLINE) {
						notify = true;
					} else {
						state.state = currentState;
					}
					state.end();
					if (completion && state.handler != null && state.callHandler.compareAndSet(true, false)) {
						state.handler.completed(Long.valueOf(state.nBytes), state.attachment);
					}
					synchronized (state) {
						state.completionDone = true;
						if (notify) {
							state.state = currentState;
							state.notify();
						}
					}
				} else {
					synchronized (state) {
						state.completionDone = true;
					}
					state.run();
				}
			}
		}

		@Override
		public void failed(Throwable exc, OperationState<A> state) {
			IOException ioe = null;
			if (exc instanceof InterruptedByTimeoutException) {
				ioe = new SocketTimeoutException();
				exc = ioe;
			} else if (exc instanceof IOException) {
				ioe = (IOException) exc;
			}
			setError(ioe);
			boolean notify = false;
			state.semaphore.release();
			if (state.read) {
				readOperation = null;
			} else {
				writeOperation = null;
			}
			if (state.block == BlockingMode.BLOCK) {
				notify = true;
			} else {
				state.state = state.isInline() ? CompletionState.ERROR : CompletionState.DONE;
			}
			state.end();
			if (state.handler != null && state.callHandler.compareAndSet(true, false)) {
				state.handler.failed(exc, state.attachment);
			}
			synchronized (state) {
				state.completionDone = true;
				if (notify) {
					state.state = state.isInline() ? CompletionState.ERROR : CompletionState.DONE;
					state.notify();
				}
			}
		}
	}

	/**
	 * Allows using NIO2 style read/write.
	 *
	 * @return {@code true} if the connector has the capability enabled
	 */
	@Override
	public boolean hasAsyncIO() {
		// The semaphores are only created if async IO is enabled
		return (readPending != null);
	}

	/**
	 * Allows indicating if the connector needs semaphores.
	 *
	 * @return This default implementation always returns {@code false}
	 */
	public boolean needSemaphores() {
		return false;
	}

	/**
	 * Allows indicating if the connector supports per operation timeout.
	 *
	 * @return This default implementation always returns {@code false}
	 */
	public boolean hasPerOperationTimeout() {
		return false;
	}

	/**
	 * Allows checking if an asynchronous read operation is currently pending.
	 * 
	 * @return <code>true</code> if the endpoint supports asynchronous IO and a read
	 *         operation is being processed asynchronously
	 */
	@Override
	public boolean isReadPending() {
		return false;
	}

	/**
	 * Allows checking if an asynchronous write operation is currently pending.
	 * 
	 * @return <code>true</code> if the endpoint supports asynchronous IO and a
	 *         write operation is being processed asynchronously
	 */
	public boolean isWritePending() {
		return false;
	}

	/**
	 * If an asynchronous read operation is pending, this method will block until
	 * the operation completes, or the specified amount of time has passed.
	 * 
	 * @param timeout The maximum amount of time to wait
	 * @param unit    The unit for the timeout
	 * @return <code>true</code> if the read operation is complete,
	 *         <code>false</code> if the operation is still pending and the
	 *         specified timeout has passed
	 */
	@Deprecated
	public boolean awaitReadComplete(long timeout, TimeUnit unit) {
		return true;
	}

	/**
	 * If an asynchronous write operation is pending, this method will block until
	 * the operation completes, or the specified amount of time has passed.
	 * 
	 * @param timeout The maximum amount of time to wait
	 * @param unit    The unit for the timeout
	 * @return <code>true</code> if the read operation is complete,
	 *         <code>false</code> if the operation is still pending and the
	 *         specified timeout has passed
	 */
	@Deprecated
	public boolean awaitWriteComplete(long timeout, TimeUnit unit) {
		return true;
	}

	/**
	 * Scatter read. The completion handler will be called once some data has been
	 * read or an error occurred. The default NIO2 behavior is used: the completion
	 * handler will be called as soon as some data has been read, even if the read
	 * has completed inline.
	 *
	 * @param timeout    timeout duration for the read
	 * @param unit       units for the timeout duration
	 * @param attachment an object to attach to the I/O operation that will be used
	 *                   when calling the completion handler
	 * @param handler    to call when the IO is complete
	 * @param dsts       buffers
	 * @param <A>        The attachment type
	 * @return the completion state (done, done inline, or still pending)
	 */
	public final <A> CompletionState read(long timeout, TimeUnit unit, A attachment,
			CompletionHandler<Long, ? super A> handler, ByteBuffer... dsts) {
		if (dsts == null) {
			throw new IllegalArgumentException();
		}
		return read(dsts, 0, dsts.length, BlockingMode.CLASSIC, timeout, unit, attachment, null, handler);
	}

	/**
	 * Scatter read. The completion handler will be called once some data has been
	 * read or an error occurred. If a CompletionCheck object has been provided, the
	 * completion handler will only be called if the callHandler method returned
	 * true. If no CompletionCheck object has been provided, the default NIO2
	 * behavior is used: the completion handler will be called as soon as some data
	 * has been read, even if the read has completed inline.
	 *
	 * @param block      is the blocking mode that will be used for this operation
	 * @param timeout    timeout duration for the read
	 * @param unit       units for the timeout duration
	 * @param attachment an object to attach to the I/O operation that will be used
	 *                   when calling the completion handler
	 * @param check      for the IO operation completion
	 * @param handler    to call when the IO is complete
	 * @param dsts       buffers
	 * @param <A>        The attachment type
	 * @return the completion state (done, done inline, or still pending)
	 */
	@Override
	public final <A> CompletionState read(BlockingMode block, long timeout, TimeUnit unit, A attachment,
			CompletionCheck check, CompletionHandler<Long, ? super A> handler, ByteBuffer... dsts) {
		if (dsts == null) {
			throw new IllegalArgumentException();
		}
		return read(dsts, 0, dsts.length, block, timeout, unit, attachment, check, handler);
	}

	/**
	 * Scatter read. The completion handler will be called once some data has been
	 * read or an error occurred. If a CompletionCheck object has been provided, the
	 * completion handler will only be called if the callHandler method returned
	 * true. If no CompletionCheck object has been provided, the default NIO2
	 * behavior is used: the completion handler will be called as soon as some data
	 * has been read, even if the read has completed inline.
	 *
	 * @param dsts       buffers
	 * @param offset     in the buffer array
	 * @param length     in the buffer array
	 * @param block      is the blocking mode that will be used for this operation
	 * @param timeout    timeout duration for the read
	 * @param unit       units for the timeout duration
	 * @param attachment an object to attach to the I/O operation that will be used
	 *                   when calling the completion handler
	 * @param check      for the IO operation completion
	 * @param handler    to call when the IO is complete
	 * @param <A>        The attachment type
	 * @return the completion state (done, done inline, or still pending)
	 */
	public final <A> CompletionState read(ByteBuffer[] dsts, int offset, int length, BlockingMode block, long timeout,
			TimeUnit unit, A attachment, CompletionCheck check, CompletionHandler<Long, ? super A> handler) {
		return vectoredOperation(true, dsts, offset, length, block, timeout, unit, attachment, check, handler);
	}

	/**
	 * Gather write. The completion handler will be called once some data has been
	 * written or an error occurred. The default NIO2 behavior is used: the
	 * completion handler will be called, even if the write is incomplete and data
	 * remains in the buffers, or if the write completed inline.
	 *
	 * @param timeout    timeout duration for the write
	 * @param unit       units for the timeout duration
	 * @param attachment an object to attach to the I/O operation that will be used
	 *                   when calling the completion handler
	 * @param handler    to call when the IO is complete
	 * @param srcs       buffers
	 * @param <A>        The attachment type
	 * @return the completion state (done, done inline, or still pending)
	 */
	public final <A> CompletionState write(long timeout, TimeUnit unit, A attachment,
			CompletionHandler<Long, ? super A> handler, ByteBuffer... srcs) {
		if (srcs == null) {
			throw new IllegalArgumentException();
		}
		return write(srcs, 0, srcs.length, BlockingMode.CLASSIC, timeout, unit, attachment, null, handler);
	}

	/**
	 * Gather write. The completion handler will be called once some data has been
	 * written or an error occurred. If a CompletionCheck object has been provided,
	 * the completion handler will only be called if the callHandler method returned
	 * true. If no CompletionCheck object has been provided, the default NIO2
	 * behavior is used: the completion handler will be called, even if the write is
	 * incomplete and data remains in the buffers, or if the write completed inline.
	 *
	 * @param block      is the blocking mode that will be used for this operation
	 * @param timeout    timeout duration for the write
	 * @param unit       units for the timeout duration
	 * @param attachment an object to attach to the I/O operation that will be used
	 *                   when calling the completion handler
	 * @param check      for the IO operation completion
	 * @param handler    to call when the IO is complete
	 * @param srcs       buffers
	 * @param <A>        The attachment type
	 * @return the completion state (done, done inline, or still pending)
	 */
	@Override
	public final <A> CompletionState write(BlockingMode block, long timeout, TimeUnit unit, A attachment,
			CompletionCheck check, CompletionHandler<Long, ? super A> handler, ByteBuffer... srcs) {
		if (srcs == null) {
			throw new IllegalArgumentException();
		}
		return write(srcs, 0, srcs.length, block, timeout, unit, attachment, check, handler);
	}

	/**
	 * Gather write. The completion handler will be called once some data has been
	 * written or an error occurred. If a CompletionCheck object has been provided,
	 * the completion handler will only be called if the callHandler method returned
	 * true. If no CompletionCheck object has been provided, the default NIO2
	 * behavior is used: the completion handler will be called, even if the write is
	 * incomplete and data remains in the buffers, or if the write completed inline.
	 *
	 * @param srcs       buffers
	 * @param offset     in the buffer array
	 * @param length     in the buffer array
	 * @param block      is the blocking mode that will be used for this operation
	 * @param timeout    timeout duration for the write
	 * @param unit       units for the timeout duration
	 * @param attachment an object to attach to the I/O operation that will be used
	 *                   when calling the completion handler
	 * @param check      for the IO operation completion
	 * @param handler    to call when the IO is complete
	 * @param <A>        The attachment type
	 * @return the completion state (done, done inline, or still pending)
	 */
	public final <A> CompletionState write(ByteBuffer[] srcs, int offset, int length, BlockingMode block, long timeout,
			TimeUnit unit, A attachment, CompletionCheck check, CompletionHandler<Long, ? super A> handler) {
		return vectoredOperation(false, srcs, offset, length, block, timeout, unit, attachment, check, handler);
	}

	/**
	 * Vectored operation. The completion handler will be called once the operation
	 * is complete or an error occurred. If a CompletionCheck object has been
	 * provided, the completion handler will only be called if the callHandler
	 * method returned true. If no CompletionCheck object has been provided, the
	 * default NIO2 behavior is used: the completion handler will be called, even if
	 * the operation is incomplete, or if the operation completed inline.
	 *
	 * @param read       true if the operation is a read, false if it is a write
	 * @param buffers    buffers
	 * @param offset     in the buffer array
	 * @param length     in the buffer array
	 * @param block      is the blocking mode that will be used for this operation
	 * @param timeout    timeout duration for the write
	 * @param unit       units for the timeout duration
	 * @param attachment an object to attach to the I/O operation that will be used
	 *                   when calling the completion handler
	 * @param check      for the IO operation completion
	 * @param handler    to call when the IO is complete
	 * @param <A>        The attachment type
	 * @return the completion state (done, done inline, or still pending)
	 */
	protected final <A> CompletionState vectoredOperation(boolean read, ByteBuffer[] buffers, int offset, int length,
			BlockingMode block, long timeout, TimeUnit unit, A attachment, CompletionCheck check,
			CompletionHandler<Long, ? super A> handler) {
		IOException ioe = getError();
		if (ioe != null) {
			handler.failed(ioe, attachment);
			return CompletionState.ERROR;
		}
		if (timeout == -1) {
			timeout = Endpoint.toTimeout(read ? getReadTimeout() : getWriteTimeout());
			unit = TimeUnit.MILLISECONDS;
		} else if (!hasPerOperationTimeout()
				&& (unit.toMillis(timeout) != (read ? getReadTimeout() : getWriteTimeout()))) {
			if (read) {
				setReadTimeout(unit.toMillis(timeout));
			} else {
				setWriteTimeout(unit.toMillis(timeout));
			}
		}
		if (block == BlockingMode.BLOCK || block == BlockingMode.SEMI_BLOCK) {
			try {
				if (read ? !readPending.tryAcquire(timeout, unit) : !writePending.tryAcquire(timeout, unit)) {
					handler.failed(new SocketTimeoutException(), attachment);
					return CompletionState.ERROR;
				}
			} catch (InterruptedException e) {
				handler.failed(e, attachment);
				return CompletionState.ERROR;
			}
		} else {
			if (read ? !readPending.tryAcquire() : !writePending.tryAcquire()) {
				if (block == BlockingMode.NON_BLOCK) {
					return CompletionState.NOT_DONE;
				} else {
					handler.failed(read ? new ReadPendingException() : new WritePendingException(), attachment);
					return CompletionState.ERROR;
				}
			}
		}
		VectoredIOCompletionHandler<A> completion = new VectoredIOCompletionHandler<>();
		OperationState<A> state = newOperationState(read, buffers, offset, length, block, timeout, unit, attachment,
				check, handler, read ? readPending : writePending, completion);
		if (read) {
			readOperation = state;
		} else {
			writeOperation = state;
		}
		state.start();
		if (block == BlockingMode.BLOCK) {
			synchronized (state) {
				if (state.state == CompletionState.PENDING) {
					try {
						state.wait(unit.toMillis(timeout));
						if (state.state == CompletionState.PENDING) {
							if (handler != null && state.callHandler.compareAndSet(true, false)) {
								handler.failed(new SocketTimeoutException(), attachment);
							}
							return CompletionState.ERROR;
						}
					} catch (InterruptedException e) {
						if (handler != null && state.callHandler.compareAndSet(true, false)) {
							handler.failed(new SocketTimeoutException(), attachment);
						}
						return CompletionState.ERROR;
					}
				}
			}
		}
		return state.state;
	}

	protected abstract <A> OperationState<A> newOperationState(boolean read, ByteBuffer[] buffers, int offset,
			int length, BlockingMode block, long timeout, TimeUnit unit, A attachment, CompletionCheck check,
			CompletionHandler<Long, ? super A> handler, Semaphore semaphore, VectoredIOCompletionHandler<A> completion);

	// --------------------------------------------------------- Utility methods

	protected static int transfer(byte[] from, int offset, int length, ByteBuffer to) {
		int max = Math.min(length, to.remaining());
		if (max > 0) {
			to.put(from, offset, max);
		}
		return max;
	}

	protected static int transfer(ByteBuffer from, ByteBuffer to) {
		int max = Math.min(from.remaining(), to.remaining());
		if (max > 0) {
			int fromLimit = from.limit();
			from.limit(from.position() + max);
			to.put(from);
			from.limit(fromLimit);
		}
		return max;
	}

	protected static boolean buffersArrayHasRemaining(ByteBuffer[] buffers, int offset, int length) {
		for (int pos = offset; pos < offset + length; pos++) {
			if (buffers[pos].hasRemaining()) {
				return true;
			}
		}
		return false;
	}

	protected OperationState<?> getReadOperation() {
		return readOperation;
	}

	protected OperationState<?> getWriteOperation() {
		return writeOperation;
	}

	protected Semaphore getReadPending() {
		return readPending;
	}

	protected Semaphore getWritePending() {
		return writePending;
	}

}
