package org.apache.coyote.http2;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.apache.coyote.AbstractProcessor;
import org.apache.coyote.CloseNowException;
import org.apache.coyote.ExchangeData;
import org.apache.coyote.http2.HpackDecoder.HeaderEmitter;
import org.apache.tomcat.util.buf.ByteChunk;
import org.apache.tomcat.util.http.MimeHeaders;
import org.apache.tomcat.util.http.parser.Host;
import org.apache.tomcat.util.net.SocketChannel.BufWrapper;
import org.apache.tomcat.util.net.SocketWrapperBase.ByteBufferWrapper;

import io.netty.buffer.ByteBuf;

public class StreamChannel extends Stream {

	private static final int HEADER_STATE_START = 0;
	private static final int HEADER_STATE_PSEUDO = 1;
	private static final int HEADER_STATE_REGULAR = 2;
	private static final int HEADER_STATE_TRAILER = 3;

	// State machine would be too much overhead
	private int headerState = HEADER_STATE_START;
	private StreamException headerException = null;
	private StringBuilder cookieHeader = null;

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
	private final Deque<ByteBuffer> deque = new ArrayDeque<>();
	private volatile boolean readInterest;
	private volatile boolean resetReceived = false;

	private volatile boolean inputClosed = false;
	private volatile boolean outputClosed = false;
	protected volatile StreamException resetException = null;
//	private volatile boolean endOfStreamSent = false;

	public StreamChannel(Integer identifier, Http2UpgradeHandler handler) {
		super(identifier, handler);
	}

	public StreamChannel(Integer identifier, Http2UpgradeHandler handler, ExchangeData exchangeData) {
		super(identifier, handler, exchangeData);
	}

	private final HeaderEmitter headerEmitter = new HeaderEmitter() {

		@Override
		public final void emitHeader(String name, String value) throws HpackException {
			if (log.isDebugEnabled()) {
				log.debug(sm.getString("stream.header.debug", getConnectionId(), getIdentifier(), name, value));
			}

			// Header names must be lower case
			if (!name.toLowerCase(Locale.US).equals(name)) {
				throw new HpackException(sm.getString("stream.header.case", getConnectionId(), getIdentifier(), name));
			}

			if ("connection".equals(name)) {
				throw new HpackException(sm.getString("stream.header.connection", getConnectionId(), getIdentifier()));
			}

			if ("te".equals(name)) {
				if (!"trailers".equals(value)) {
					throw new HpackException(
							sm.getString("stream.header.te", getConnectionId(), getIdentifier(), value));
				}
			}

			if (headerException != null) {
				// Don't bother processing the header since the stream is going to
				// be reset anyway
				return;
			}

			if (name.length() == 0) {
				throw new HpackException(sm.getString("stream.header.empty", getConnectionId(), getIdentifier()));
			}

			boolean pseudoHeader = name.charAt(0) == ':';

			if (pseudoHeader && headerState != HEADER_STATE_PSEUDO) {
				headerException = new StreamException(
						sm.getString("stream.header.unexpectedPseudoHeader", getConnectionId(), getIdentifier(), name),
						Http2Error.PROTOCOL_ERROR, getIdAsInt());
				// No need for further processing. The stream will be reset.
				return;
			}

			if (headerState == HEADER_STATE_PSEUDO && !pseudoHeader) {
				headerState = HEADER_STATE_REGULAR;
			}

			switch (name) {
			case ":method": {
				if (exchangeData.getMethod().isNull()) {
					exchangeData.getMethod().setString(value);
				} else {
					throw new HpackException(
							sm.getString("stream.header.duplicate", getConnectionId(), getIdentifier(), ":method"));
				}
				break;
			}
			case ":scheme": {
				if (exchangeData.getScheme().isNull()) {
					exchangeData.getScheme().setString(value);
				} else {
					throw new HpackException(
							sm.getString("stream.header.duplicate", getConnectionId(), getIdentifier(), ":scheme"));
				}
				break;
			}
			case ":path": {
				if (!exchangeData.getRequestURI().isNull()) {
					throw new HpackException(
							sm.getString("stream.header.duplicate", getConnectionId(), getIdentifier(), ":path"));
				}
				if (value.length() == 0) {
					throw new HpackException(sm.getString("stream.header.noPath", getConnectionId(), getIdentifier()));
				}
				int queryStart = value.indexOf('?');
				String uri;
				if (queryStart == -1) {
					uri = value;
				} else {
					uri = value.substring(0, queryStart);
					String query = value.substring(queryStart + 1);
					exchangeData.getQueryString().setString(query);
				}
				// Bug 61120. Set the URI as bytes rather than String so:
				// - any path parameters are correctly processed
				// - the normalization security checks are performed that prevent
				// directory traversal attacks
				byte[] uriBytes = uri.getBytes(StandardCharsets.ISO_8859_1);
				exchangeData.getRequestURI().setBytes(uriBytes, 0, uriBytes.length);
				break;
			}
			case ":authority": {
				if (exchangeData.getServerName().isNull()) {
					int i;
					try {
						i = Host.parse(value);
					} catch (IllegalArgumentException iae) {
						// Host value invalid
						throw new HpackException(sm.getString("stream.header.invalid", getConnectionId(),
								getIdentifier(), ":authority", value));
					}
					if (i > -1) {
						exchangeData.getServerName().setString(value.substring(0, i));
						exchangeData.setServerPort(Integer.parseInt(value.substring(i + 1)));
					} else {
						exchangeData.getServerName().setString(value);
					}
				} else {
					throw new HpackException(
							sm.getString("stream.header.duplicate", getConnectionId(), getIdentifier(), ":authority"));
				}
				break;
			}
			case "cookie": {
				// Cookie headers need to be concatenated into a single header
				// See RFC 7540 8.1.2.5
				if (cookieHeader == null) {
					cookieHeader = new StringBuilder();
				} else {
					cookieHeader.append("; ");
				}
				cookieHeader.append(value);
				break;
			}
			default: {
				if (headerState == HEADER_STATE_TRAILER && !handler.getProtocol().isTrailerHeaderAllowed(name)) {
					break;
				}
				if ("expect".equals(name) && "100-continue".equals(value)) {
					exchangeData.setExpectation(true);
				}
				if (pseudoHeader) {
					headerException = new StreamException(
							sm.getString("stream.header.unknownPseudoHeader", getConnectionId(), getIdentifier(), name),
							Http2Error.PROTOCOL_ERROR, getIdAsInt());
				}

				if (headerState == HEADER_STATE_TRAILER) {
					// HTTP/2 headers are already always lower case
					exchangeData.getTrailerFields().put(name, value);
				} else {
					exchangeData.getRequestHeaders().addValue(name).setString(value);
				}
			}
			}
		}

		@Override
		public void setHeaderException(StreamException streamException) {
			if (headerException == null) {
				headerException = streamException;
			}
		}

		@Override
		public void validateHeaders() throws StreamException {
			if (headerException == null) {
				return;
			}

			throw headerException;
		}
	};

	public HeaderEmitter getHeaderEmitter() {
		return headerEmitter;
	}

	@Override
	protected void receivedStartOfHeadersLocal(boolean headersEndStream) throws ConnectionException {
		if (headerState == HEADER_STATE_START) {
			headerState = HEADER_STATE_PSEUDO;
			handler.getHpackDecoder().setMaxHeaderCount(handler.getProtocol().getMaxHeaderCount());
			handler.getHpackDecoder().setMaxHeaderSize(handler.getProtocol().getMaxHeaderSize());
		} else if (headerState == HEADER_STATE_PSEUDO || headerState == HEADER_STATE_REGULAR) {
			// Trailer headers MUST include the end of stream flag
			if (headersEndStream) {
				headerState = HEADER_STATE_TRAILER;
				handler.getHpackDecoder().setMaxHeaderCount(handler.getProtocol().getMaxTrailerCount());
				handler.getHpackDecoder().setMaxHeaderSize(handler.getProtocol().getMaxTrailerSize());
			} else {
				throw new ConnectionException(
						sm.getString("stream.trailerHeader.noEndOfStream", getConnectionId(), getIdentifier()),
						Http2Error.PROTOCOL_ERROR);
			}
		}
	}

	@Override
	protected boolean receivedEndOfHeadersLocal() throws ConnectionException {
		if (exchangeData.getMethod().isNull() || exchangeData.getScheme().isNull()
				|| exchangeData.getRequestURI().isNull()) {
			throw new ConnectionException(sm.getString("stream.header.required", getConnectionId(), getIdentifier()),
					Http2Error.PROTOCOL_ERROR);
		}
		// Cookie headers need to be concatenated into a single header
		// See RFC 7540 8.1.2.5
		// Can only do this once the headers are fully received
		if (cookieHeader != null) {
			exchangeData.getRequestHeaders().addValue("cookie").setString(cookieHeader.toString());
		}
		return headerState == HEADER_STATE_REGULAR || headerState == HEADER_STATE_PSEUDO;
	}

	@Override
	protected void closeInternal() throws IOException {
		swallowUnread();
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
			for (ByteBuffer buf : deque) {
				available += buf.remaining();
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
		ByteBuffer buffer = null;
		// Ensure that only one thread accesses inBuffer at a time
		try {
			readLock.lock();
			if (inputClosed) {
				return null;
			}
			int available = 0;
			for (ByteBuffer buf : deque) {
				available += buf.remaining();
			}
			if (available > 0) {
				buffer = ByteBuffer.allocate(available);
				ByteBuffer buf = null;
				while ((buf = deque.poll()) != null) {
					buffer.put(buf);
				}
				buffer.flip();
			}
			boolean canRead = false;
			while ((buffer == null || buffer.remaining() == 0)
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
						exchangeData.setError();
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
		int written = buffer.remaining();
		if (log.isDebugEnabled()) {
			log.debug(sm.getString("stream.inputBuffer.copy", Integer.toString(written)));
		}
		handler.getWriter().writeWindowUpdate(this, written, true);
		ByteBufferWrapper toreturn = ByteBufferWrapper.wrapper(buffer);
		System.out.println("stream" + getIdAsString() + " take:" + toreturn.getRemaining());
		return toreturn;
	}

	@Override
	public final void insertReplayedBody(ByteChunk body) {
		try {
			readLock.lock();
			// bufferIndex = 0;
			// buffers[bufferIndex] = ByteBuffer.wrap(body.getBytes(), body.getOffset(),
			// body.getLength());
			deque.offer(ByteBuffer.wrap(body.getBytes(), body.getOffset(), body.getLength()));
		} finally {
			readLock.unlock();
		}
	}

	protected final void receiveReset() {
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
			for (ByteBuffer buffer : deque) {
				size -= buffer.remaining();
			}
//			System.out.println("availableWindowSize:" + size);
			return size;
		} finally {
			readLock.unlock();
		}
	}

	public boolean offer(ByteBuffer buffer) throws IOException {
		System.out.println("stream" + getIdAsString() + " offer " + buffer.remaining());
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
		System.out.println("swallowUnread");
		int unreadByteCount = 0;
		try {
			readLock.lock();
			inputClosed = true;
			if (deque.size() > 0) {
				for (ByteBuffer buffer : deque) {
					unreadByteCount += buffer.remaining();
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
			handler.getWriter().writeWindowUpdate(this, unreadByteCount, false);
		}
	}

	@Override
	protected void cancelStream(StreamException se) {
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
