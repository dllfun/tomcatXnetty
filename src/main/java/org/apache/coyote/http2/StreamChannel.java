package org.apache.coyote.http2;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.apache.coyote.AbstractProcessor;
import org.apache.coyote.CloseNowException;
import org.apache.coyote.ExchangeData;
import org.apache.coyote.http2.HpackDecoder.HeaderEmitter;
import org.apache.tomcat.util.buf.ByteChunk;
import org.apache.tomcat.util.http.MimeHeaders;
import org.apache.tomcat.util.net.AbstractSocketChannel;
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
	private final Deque<BufWrapper> deque = new ArrayDeque<>();
	private volatile boolean readInterest;
	private volatile boolean resetReceived = false;

	private volatile boolean inputClosed = false;
	private volatile boolean outputClosed = false;
	protected volatile StreamException resetException = null;

	private boolean trace = false;

	private volatile long updateTime = 0;

	private volatile long updateCount = 0;

	private volatile long waitTime = 0;

	private volatile long lastReadTime = -1;

	private volatile long readTime = 0;

	private volatile long readCount = 0;

	private volatile long mergeTime = 0;

	private volatile long lastWriteTime = -1;

	private volatile long writeTime = 0;

	private volatile long offerSize = 0;

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
		getSocketChannel().resetStatics();
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

	public boolean offer(BufWrapper buffer) throws IOException {
//		System.out.println("stream" + getIdAsString() + " offer " + buffer.remaining());
		try {
			readLock.lock();
			offerSize += buffer.getRemaining();
			if (trace) {
				System.out.println(" offer size:" + offerSize);
			}
			deque.offer(buffer);
			if (inputClosed) {
				swallowUnread();
			} else {
				notEmpty.signalAll();
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
				for (BufWrapper buffer : deque) {
					unreadByteCount += buffer.getRemaining();
					buffer.release();
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
//			System.out.println("stream" + getIdentifier() + " swallowUnread: " + unreadByteCount);
			handler.getWriter().writeWindowUpdate(this, unreadByteCount, false);
		}
	}

	@Override
	public final int available() {
		try {
			readLock.lock();
			if (deque.size() == 0) {
				return 0;
			}
			int available = 0;
			for (BufWrapper buf : deque) {
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
//				System.out.println(
//						"conn(" + getConnectionId() + ") " + "stream(" + getIdentifier() + ")" + " bodyFullyReaded");
			}
			return finished;
		} finally {
			readLock.unlock();
		}
	}

	private BufWrapper getFromDeque(boolean block) throws IOException {

		try {
			readLock.lock();
			if (inputClosed) {
				return null;
			}

			BufWrapper result = null;
			if (block) {

				boolean canRead = false;
				while (deque.isEmpty() && (canRead = this.isActive() && !this.isInputFinished())) {
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

						if (deque.isEmpty() && this.isActive() && !this.isInputFinished()) {
							String msg = sm.getString("stream.inputBuffer.readTimeout");
							StreamException se = new StreamException(msg, Http2Error.ENHANCE_YOUR_CALM,
									this.getIdAsInt());
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

				result = deque.poll();

				if (result != null) {
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
			} else {
				result = deque.poll();
			}
			return result;
		} finally {
			readLock.unlock();
		}
	}

	@Override
	public final BufWrapper doRead() throws IOException {

		if (lastReadTime != -1) {
			long useTime = System.currentTimeMillis() - lastReadTime;
			readTime += useTime;
			readCount++;
			if (trace) {
				System.out.println(getSocketChannel().getRemotePort() + " 处理数据用时：" + useTime + " 总用时：" + readTime
						+ " 处理次数：" + readCount);
			}
		}

//		ensureBuffersExist();
		// Ensure that only one thread accesses inBuffer at a time
		boolean first = true;
		int available = 0;
		int pollCount = 0;
		List<BufWrapper> bufs = new ArrayList<>();

		{
			long startTime = System.currentTimeMillis();
			BufWrapper buf = null;
			do {
				buf = getFromDeque(first);
				first = false;
				if (buf != null) {
					pollCount++;
					available += buf.getRemaining();
					bufs.add(buf);
				}
			} while (buf != null);
			long useTime = System.currentTimeMillis() - startTime;
			waitTime += useTime;
			if (trace) {
				System.out.println(getSocketChannel().getRemotePort() + " 读取数据用时：" + useTime + " 总用时：" + waitTime);
			}
		}

		if (available > 0) {
			long startTime = System.currentTimeMillis();
			handler.getWriter().writeWindowUpdate(this, available, true);
			long useTime = (System.currentTimeMillis() - startTime);
			updateTime += useTime;
			updateCount++;
			// (((AbstractSocketChannel<?>) getSocketChannel()).registeReadTimeStamp)
			if (trace) {
				System.out.println(getSocketChannel().getRemotePort() + " 更新窗口用时：" + useTime + " 总用时：" + updateTime
						+ " 更新次数：" + updateCount + " pollCount:" + pollCount + " 窗口大小：" + available);
			}
			((AbstractSocketChannel<?>) getSocketChannel()).registeReadTimeStamp = System.currentTimeMillis();
		}

		ByteBufferWrapper buffer = null;
		if (available > 0) {
			long startTime = System.currentTimeMillis();
			buffer = ByteBufferWrapper.wrapper(ByteBuffer.allocate(available), false);
			for (int i = 0; i < bufs.size(); i++) {
				BufWrapper buf = bufs.get(i);
				buf.transferTo(buffer);
				buf.release();
			}
//			buffer.flip();
			buffer.switchToReadMode();
			long useTime = (System.currentTimeMillis() - startTime);
			mergeTime += useTime;
			if (trace) {
				System.out.println(getSocketChannel().getRemotePort() + " 合并数据用时：" + useTime + " 总用时：" + mergeTime);
			}
		}

		// Increment client-side flow control windows by the number of bytes
		// read
//		int freeIndex = (bufferIndex + 1) % 2;
//		buffers[freeIndex].flip();
//		buffer.flip();
		if (log.isDebugEnabled()) {
			log.debug(sm.getString("stream.inputBuffer.copy", Integer.toString(available)));
		}
		lastReadTime = System.currentTimeMillis();
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
	final void onDataAvailable() {
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
//				return true;
			}
//			else {
//				if (log.isDebugEnabled()) {
//					log.debug(sm.getString("stream.inputBuffer.signal"));
//				}
//
//				notEmpty.signalAll();
//				return false;
//			}
		} finally {
			readLock.unlock();
		}
	}

	public int availableWindowSize() {
		int size = handler.getLocalSettings().getInitialWindowSize();
		try {
			readLock.lock();
			for (BufWrapper buffer : deque) {
				size -= buffer.getRemaining();
			}
//			System.out.println("availableWindowSize:" + size);
			return size;
		} finally {
			readLock.unlock();
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
			lastWriteTime = System.currentTimeMillis();
			getHandler().getWriter().writeHeaders(this, 0, headers, finished,
					org.apache.coyote.http2.Constants.DEFAULT_HEADERS_FRAME_SIZE);
			long useTime = System.currentTimeMillis() - lastWriteTime;
			writeTime += useTime;
			if (trace) {
				System.out.println(getSocketChannel().getRemotePort() + " 写出数据用时：" + useTime + " 总用时：" + writeTime);
			}
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
	public final int doWriteBody(BufWrapper chunk, boolean finished) throws IOException {
		try {
			getWriteLock().lock();
			if (isClosed() || isOutputClosed()) {
				throw new IllegalStateException(sm.getString("stream.closed", getConnectionId(), getIdentifier()));
			}
			// chunk is always fully written
			int result = chunk.getRemaining();
//			System.out.println("stream(" + getIdentifier() + ") write: " + chunk.getRemaining());
			lastWriteTime = System.currentTimeMillis();
			getHandler().getWriter().writeBody(this, chunk, result, finished);
			long useTime = System.currentTimeMillis() - lastWriteTime;
			writeTime += useTime;
			if (trace) {
				System.out.println(getSocketChannel().getRemotePort() + " 写出数据用时：" + useTime + " 总用时：" + writeTime);
			}
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

	@Override
	protected void closeInternal() throws IOException {
		swallowUnread();
		clearCurrentProcessor();
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
