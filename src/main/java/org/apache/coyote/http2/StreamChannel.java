package org.apache.coyote.http2;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.apache.coyote.AbstractProcessor;
import org.apache.coyote.CloseNowException;
import org.apache.coyote.ExchangeData;
import org.apache.coyote.http2.HpackDecoder.HeaderEmitter;
import org.apache.tomcat.util.buf.ByteChunk;
import org.apache.tomcat.util.http.MimeHeaders;
import org.apache.tomcat.util.net.BufWrapper;
import org.apache.tomcat.util.net.SocketWrapperBase.ByteBufferWrapper;

public class StreamChannel extends Stream {

	private static final Integer HTTP_UPGRADE_STREAM = Integer.valueOf(1);

	// State machine would be too much overhead

	// TODO: null these when finished to reduce memory used by closed stream
	// protected final ExchangeData exchangeData;

	// private volatile AbstractProcessor processor;
	// private final SocketChannel channel;
	/*
	 * Two buffers are required to avoid various multi-threading issues. These
	 * issues arise from the fact that the Stream (or the Request/Response) used by
	 * the application is processed in one thread but the connection is processed in
	 * another. Therefore it is possible that a request body frame could be received
	 * before the application is ready to read it. If it isn't buffered, processing
	 * of the connection (and hence all streams) would block until the application
	 * read the data. Hence the incoming data has to be buffered. If only one buffer
	 * was used then it could become corrupted if the connection thread is trying to
	 * add to it at the same time as the application is read it. While it should be
	 * possible to avoid this corruption by careful use of the buffer it would still
	 * require the same copies as using two buffers and the behaviour would be less
	 * clear.
	 *
	 * The buffers are created lazily because they quickly add up to a lot of memory
	 * and most requests do not have bodies.
	 */
	// This buffer is used to populate the ByteChunk passed in to the read
	// method
	// private byte[] outBuffer;
	// This buffer is the destination for incoming data. It is normally is
	// 'write mode'.
	private final ReentrantLock readLock = new ReentrantLock();
	private final Condition notEmpty = readLock.newCondition();
	private final ReentrantLock writeLock = new ReentrantLock();
	// private volatile int bufferIndex = -1;
	// private ByteBuffer[] buffers = new ByteBuffer[2];
	private final Deque<ByteBufferWrapper> deque = new ArrayDeque<>();
	private volatile boolean readInterest;
	private volatile boolean resetReceived = false;

	private volatile boolean inputClosed = false;
	private volatile boolean outputClosed = false;
	protected volatile StreamException resetException = null;

//	private volatile boolean endOfStreamSent = false;

	public StreamChannel(Integer identifier, Http2UpgradeHandler handler) {
		this(identifier, handler, null);
	}

	public StreamChannel(Integer identifier, Http2UpgradeHandler handler, ExchangeData exchangeData) {
		super(identifier, handler);
		if (exchangeData == null) {
			// HTTP/2 new request
			// this.exchangeData = new ExchangeData();
//			this.streamInputBuffer = new StreamInputBuffer();
			// this.coyoteRequest.setInputBuffer(inputBuffer);
		} else {
			// HTTP/2 Push or HTTP/1.1 upgrade
			setCurrentProcessor(new StreamProcessor(handler, this, handler.getAdapter(), exchangeData));
//			this.exchangeData = exchangeData;
//			this.streamInputBuffer = null;
			// Headers have been read by this point
			state.receivedStartOfHeaders();
			if (HTTP_UPGRADE_STREAM.equals(identifier)) {
				// Populate coyoteRequest from headers (HTTP/1.1 only)
				try {
					((StreamProcessor) getCurrentProcessor()).prepareRequest();
				} catch (IllegalArgumentException iae) {
					// Something in the headers is invalid
					// Set correct return status
					exchangeData.setStatus(400);
					// Set error flag. This triggers error processing rather than
					// the normal mapping
					exchangeData.setError();
				}
			}
			// TODO Assuming the body has been read at this point is not valid
			state.receivedEndOfStream();
		}

	}

//	final ExchangeData getExchangeData() {
//		return exchangeData;
//	}

	public HeaderEmitter getHeaderEmitter() {
		return (StreamProcessor) getCurrentProcessor();
	}

	@Override
	protected void receivedStartOfHeadersInternal(boolean headersEndStream) throws ConnectionException {
		StreamProcessor processor = ((StreamProcessor) getCurrentProcessor());
		if (processor != null) {
			System.err.println("stream" + getIdentifier() + " processor init too early");
		} else {
			processor = new StreamProcessor(handler, this, handler.getAdapter());
			setCurrentProcessor(processor);
		}
		processor.receivedStartOfHeadersInternal(headersEndStream);
	}

	@Override
	protected boolean receivedEndOfHeadersInternal() throws ConnectionException {
		StreamProcessor processor = ((StreamProcessor) getCurrentProcessor());
		if (processor != null) {
			return processor.receivedEndOfHeadersInternal();
		} else {
			System.err.println("stream" + getIdentifier() + " processor cleared too early");
			return false;
		}
	}

	@Override
	protected void receivedDataInternal(int payloadSize) throws ConnectionException {
		StreamProcessor processor = ((StreamProcessor) getCurrentProcessor());
		if (processor != null) {
			processor.receivedDataInternal(payloadSize);
		} else {
			System.err.println("stream" + getIdentifier() + " processor cleared too early");
		}
	}

	@Override
	protected void receivedEndOfStreamInternal() throws ConnectionException {
		StreamProcessor processor = ((StreamProcessor) getCurrentProcessor());
		if (processor != null) {
			processor.receivedEndOfStreamInternal();
		} else {
			System.err.println("stream" + getIdentifier() + " processor cleared too early");
		}
	}

	@Override
	protected void closeInternal() throws IOException {
		swallowUnread();
		clearCurrentProcessor();
	}

//	@Override
//	public void startWrite() {
//		readLock.lock();
//	}

//	@Override
//	public ByteBuffer getByteBuffer() {
//		ensureBuffersExist();
//		return buffers[bufferIndex];
//	}

//	@Override
//	public void finishWrite() {
//		readLock.unlock();
//	}

//	private final void ensureBuffersExist() {
//		if (bufferIndex == -1) {
//			// The client must obey Tomcat's window size when sending so
//			// this is the initial window size set by Tomcat that the client
//			// uses (i.e. the local setting is required here).
//			int size = handler.getLocalSettings().getInitialWindowSize();
//			try {
//				readLock.lock();
//				if (bufferIndex == -1) {
//					bufferIndex = 0;
//					if (!inputClosed) {
//						buffers[0] = ByteBuffer.allocate(size);
//						buffers[1] = ByteBuffer.allocate(size);
//					} else {
//						buffers[0] = ByteBuffer.allocate(0);
//						buffers[1] = ByteBuffer.allocate(0);
//					}
//				}
//			} finally {
//				readLock.unlock();
//			}
//		}
//	}

	@Override
	public final int available() {
		try {
			readLock.lock();
			if (deque.size() == 0) {
				return 0;
			}
			int available = 0;
			for (ByteBufferWrapper buf : deque) {
				available += buf.getRemaining();
			}
			return available;
		} finally {
			readLock.unlock();
		}
	}

	@Override
	public final boolean isReadyForRead() {
//		ensureBuffersExist();

		try {
			readLock.lock();
			if (available() > 0) {
				return true;
			}

			if (!isRequestBodyFullyRead()) {
				readInterest = true;
			}

			return false;
		} finally {
			readLock.unlock();
		}
	}

	@Override
	public final boolean isRequestBodyFullyRead() {
		try {
			readLock.lock();
			boolean finished = deque.size() == 0 && isInputFinished();
			if (finished) {
				System.out.println(
						"conn(" + getConnectionId() + ") " + "stream(" + getIdentifier() + ")" + " bodyFullyReaded");
			}
			return finished;
		} finally {
			readLock.unlock();
		}
	}

	@Override
	public final BufWrapper doRead() throws IOException {

//		ensureBuffersExist();
		ByteBufferWrapper buffer = null;
		// Ensure that only one thread accesses inBuffer at a time
		try {
			readLock.lock();
			if (inputClosed) {
				return null;
			}
			int available = 0;
			for (ByteBufferWrapper buf : deque) {
				available += buf.getRemaining();
			}
			if (available > 0) {
				buffer = ByteBufferWrapper.wrapper(ByteBuffer.allocate(available), false);
				ByteBufferWrapper buf = null;
				while ((buf = deque.poll()) != null) {
//					buffer.put(buf);
					buf.transferTo(buffer);
				}
//				buffer.flip();
				buffer.switchToReadMode();
			}
			boolean canRead = false;
			while ((buffer == null || buffer.getRemaining() == 0)
					&& (canRead = this.isActive() && !this.isInputFinished())) {
				// Need to block until some data is written
				try {
					if (log.isDebugEnabled()) {
						log.debug(sm.getString("stream.inputBuffer.empty"));
					}

					long readTimeout = handler.getProtocol().getStreamReadTimeout();
					if (readTimeout < 0) {
						notEmpty.await();
//						buffer = deque.poll();
					} else {
						notEmpty.await(readTimeout, TimeUnit.MILLISECONDS);
//						buffer = deque.poll(readTimeout, TimeUnit.MILLISECONDS);
					}

					if (resetReceived) {
						throw new IOException(sm.getString("stream.inputBuffer.reset"));
					}

					if (inputClosed) {
						return null;
					}

					buffer = deque.poll();

					if (buffer == null && this.isActive() && !this.isInputFinished()) {
						String msg = sm.getString("stream.inputBuffer.readTimeout");
						StreamException se = new StreamException(msg, Http2Error.ENHANCE_YOUR_CALM, this.getIdAsInt());
						// Trigger a reset once control returns to Tomcat
						((StreamProcessor) getCurrentProcessor()).getExchangeData().setError();
						resetException = se;
						throw new CloseNowException(msg, se);
					}
				} catch (InterruptedException e) {
					// Possible shutdown / rst or similar. Use an
					// IOException to signal to the client that further I/O
					// isn't possible for this Stream.
					throw new IOException(e);
				}
			}

			if (buffer != null) {
				// Data is available in the readingBuffer. Copy it to the
				// outBuffer.
//				bufferIndex = (bufferIndex + 1) % 2;
//				buffers[bufferIndex].clear();
				// buffers[bufferIndex.intValue()].get(outBuffer, 0, written);
				// buffers[bufferIndex.intValue()].clear();
				// applicationBufferHandler.setBufWrapper();

			} else if (!canRead) {
				return null;
			} else {
				// Should never happen
				throw new IllegalStateException();
			}
		} finally {
			readLock.unlock();
		}

		// Increment client-side flow control windows by the number of bytes
		// read
//		int freeIndex = (bufferIndex + 1) % 2;
//		buffers[freeIndex].flip();
//		buffer.flip();
		int written = buffer.getRemaining();
		if (log.isDebugEnabled()) {
			log.debug(sm.getString("stream.inputBuffer.copy", Integer.toString(written)));
		}
		handler.getWriter().writeWindowUpdate(this, written, true);
//		System.out.println("stream" + getIdAsString() + " take:" + toreturn.getRemaining());
		return buffer;
	}

	@Override
	public final void insertReplayedBody(ByteChunk body) {
		try {
			readLock.lock();
			// bufferIndex = 0;
			// buffers[bufferIndex] = ByteBuffer.wrap(body.getBytes(), body.getOffset(),
			// body.getLength());
			deque.offer(ByteBufferWrapper.wrapper(ByteBuffer.wrap(body.getBytes(), body.getOffset(), body.getLength()),
					true));
		} finally {
			readLock.unlock();
		}
	}

	@Override
	protected final void receiveResetInternal(long errorCode) {
		String uri = (getCurrentProcessor() == null ? ""
				: " uri:" + ((StreamProcessor) getCurrentProcessor()).getExchangeData().getRequestURI().toString());
		System.out.println("conn(" + getConnectionId() + ") " + "stream(" + getIdentifier() + ")"
				+ " receiveReset errorCode: " + errorCode + uri);
//		if (bufferIndex != -1) {
		try {// synchronized (buffers[bufferIndex.intValue()])
			readLock.lock();
			resetReceived = true;
			notEmpty.signalAll();// buffers[bufferIndex.intValue()]
		} finally {
			readLock.unlock();
		}
//		}
	}

	protected final void notifyEof() {
//		if (bufferIndex != -1) {
		try {// synchronized (buffers[bufferIndex.intValue()])
			readLock.lock();
			notEmpty.signalAll();// buffers[bufferIndex.intValue()]
		} finally {
			readLock.unlock();
		}
//		}
	}

	/*
	 * Called after placing some data in the readingBuffer.
	 */
	final boolean onDataAvailable() {
		try {// synchronized (buffers[bufferIndex.intValue()])
			readLock.lock();
			if (readInterest) {
				if (log.isDebugEnabled()) {
					log.debug(sm.getString("stream.inputBuffer.dispatch"));
				}
				readInterest = false;
				((AbstractProcessor) getCurrentProcessor()).dispatchRead();
				// Always need to dispatch since this thread is processing
				// the incoming connection and streams are processed on their
				// own.
				((AbstractProcessor) getCurrentProcessor()).dispatchExecute();
				return true;
			} else {
				if (log.isDebugEnabled()) {
					log.debug(sm.getString("stream.inputBuffer.signal"));
				}

				notEmpty.signalAll();
				return false;
			}
		} finally {
			readLock.unlock();
		}
	}

	public int availableWindowSize() {
		int size = handler.getLocalSettings().getInitialWindowSize();
		try {
			readLock.lock();
			for (ByteBufferWrapper buffer : deque) {
				size -= buffer.getRemaining();
			}
//			System.out.println("availableWindowSize:" + size);
			return size;
		} finally {
			readLock.unlock();
		}
	}

	public boolean offer(ByteBufferWrapper buffer) throws IOException {
//		System.out.println("stream" + getIdAsString() + " offer " + buffer.remaining());
		try {
			readLock.lock();
			deque.offer(buffer);
			if (inputClosed) {
				swallowUnread();
			}
			return true;
		} finally {
			readLock.unlock();
		}
	}

	private final void swallowUnread() throws IOException {
		int unreadByteCount = 0;
		try {
			readLock.lock();
			inputClosed = true;
			if (deque.size() > 0) {
				for (ByteBufferWrapper buffer : deque) {
					unreadByteCount += buffer.getRemaining();
				}
//				unreadByteCount = buffers[bufferIndex].position();
				if (log.isDebugEnabled()) {
					log.debug(sm.getString("stream.inputBuffer.swallowUnread", Integer.valueOf(unreadByteCount)));
				}
//				if (unreadByteCount > 0) {
//					buffers[bufferIndex].position(0);
//					buffers[bufferIndex].limit(buffers[bufferIndex].limit() - unreadByteCount);
//				}
//				buffers[0] = ByteBuffer.allocate(0);
//				buffers[1] = ByteBuffer.allocate(0);
				deque.clear();
				notEmpty.signalAll();
			}
		} finally {
			readLock.unlock();
		}
		// Do this outside of the sync because:
		// - it doesn't need to be inside the sync
		// - if inside the sync it can trigger a deadlock
		// https://markmail.org/message/vbglzkvj6wxlhh3p
		if (unreadByteCount > 0) {
			System.out.println("stream" + getIdentifier() + " swallowUnread: " + unreadByteCount);
			handler.getWriter().writeWindowUpdate(this, unreadByteCount, false);
		}
	}

	@Override
	protected void cancelStreamInternal(StreamException se) {
		// Prevent Tomcat's error handling trying to write
		((StreamProcessor) getCurrentProcessor()).getExchangeData().setError();
		((StreamProcessor) getCurrentProcessor()).getExchangeData().setErrorReported();
		outputClosed = true;
		resetException = se;
	}

	@Override
	protected void endOfStreamSent() {
		outputClosed = true;
	}

	@Override
	public final boolean isReadyForWrite() {
		return isReady();
	}

	private synchronized boolean isReady() {
		// Bug 63682
		// Only want to return false if the window size is zero AND we are
		// already waiting for an allocation.
		if (getWindowSize() > 0 && allocationManager.isWaitingForStream()
				|| handler.getZero().getWindowSize() > 0 && allocationManager.isWaitingForConnection()) {
			return false;
		} else {
			return true;
		}
	}

	@Override
	public final void doWriteHeader(MimeHeaders headers, boolean finished) throws IOException {
		try {
			getWriteLock().lock();
			if (isClosed() || isOutputClosed()) {
				throw new IllegalStateException(sm.getString("stream.closed", getConnectionId(), getIdentifier()));
			}
			getHandler().getWriter().writeHeaders(this, 0, headers, finished,
					org.apache.coyote.http2.Constants.DEFAULT_HEADERS_FRAME_SIZE);
		} catch (Exception e) {
			throw e;
		} finally {
			getWriteLock().unlock();
		}
	}

	/*
	 * The write methods are synchronized to ensure that only one thread at a time
	 * is able to access the buffer. Without this protection, a client that
	 * performed concurrent writes could corrupt the buffer.
	 */

	@Override
	public final int doWriteBody(ByteBuffer chunk, boolean finished) throws IOException {
		try {
			getWriteLock().lock();
			if (isClosed() || isOutputClosed()) {
				throw new IllegalStateException(sm.getString("stream.closed", getConnectionId(), getIdentifier()));
			}
			// chunk is always fully written
			int result = chunk.remaining();
			System.out.println("stream(" + getIdentifier() + ") write: " + chunk.remaining());
			getHandler().getWriter().writeBody(this, chunk, result, finished);
			return result;
		} finally {
			getWriteLock().unlock();
		}
	}

//	public final boolean flush(boolean block) throws IOException {
//		return streamOutputBuffer.flush(block);
//	}

//	final StreamInputBuffer getInputBuffer() {
//		return streamInputBuffer;
//	}

//	final StreamOutputBuffer getOutputBuffer() {
//		return streamOutputBuffer;
//	}

//	@Override
//	public final long getBytesWritten() {
//		return written;
//	}

	@Override
	public boolean isOutputClosed() {
		return outputClosed;
	}

//	@Override
//	public void setOutputClosed(boolean outputClosed) {
//		this.outputClosed = outputClosed;
//	}

//	public boolean isEndOfStreamSent() {
//		return endOfStreamSent;
//	}

	public StreamException getResetException() {
		return resetException;
	}

	private ReentrantLock getReadLock() {
		return readLock;
	}

	private ReentrantLock getWriteLock() {
		return writeLock;
	}

}
