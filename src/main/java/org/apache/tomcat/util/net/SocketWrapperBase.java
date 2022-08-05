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
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.buf.ByteBufferUtils;
import org.apache.tomcat.util.res.StringManager;

public abstract class SocketWrapperBase<E> extends AbstractChannel<E> {

	private static final Log log = LogFactory.getLog(SocketWrapperBase.class);

	protected static final StringManager sm = StringManager.getManager(SocketWrapperBase.class);

	private E socket;

	private final SocketWrapperBaseEndpoint<E, ?> endpoint;

	/**
	 * The buffers used for communicating with the socket.
	 */
	private volatile SocketBufferHandler socketBufferHandler = null;

	/**
	 * The read buffer.
	 */
	private ByteBufferWrapper appReadBuffer = new ByteBufferWrapper(this, ByteBuffer.allocate(0));

	/**
	 * The max size of the individual buffered write buffers
	 */
	protected int bufferedWriteSize = 64 * 1024; // 64k default write buffer

	/**
	 * Additional buffer used for non-blocking writes. Non-blocking writes need to
	 * return immediately even if the data cannot be written immediately but the
	 * socket buffer may not be big enough to hold all of the unwritten data. This
	 * structure provides an additional buffer to hold the data until it can be
	 * written. Not that while the Servlet API only allows one non-blocking write at
	 * a time, due to buffering and the possible need to write HTTP headers, this
	 * layer may see multiple writes.
	 */
	protected final WriteBuffer nonBlockingWriteBuffer = new WriteBuffer(bufferedWriteSize);

	public SocketWrapperBase(E socket, SocketWrapperBaseEndpoint<E, ?> endpoint) {
		super(socket, endpoint);
		this.socket = socket;
		this.endpoint = endpoint;
	}

	protected SocketWrapperBaseEndpoint<E, ?> getEndpoint() {
		return endpoint;
	}

	@Override
	public void initAppReadBuffer(int headerBufferSize) {

		if (getSocketBufferHandler() != null && getSocketBufferHandler().getReadBuffer() != null) {
			int bufLength = headerBufferSize + getSocketBufferHandler().getReadBuffer().capacity();
			ByteBuffer buffer = appReadBuffer.getByteBuffer();
			if (buffer == null || buffer.capacity() < bufLength) {
				buffer = ByteBuffer.allocate(bufLength);
				buffer.position(0).limit(0);
				appReadBuffer.setByteBuffer(buffer);
			}

			setAppReadBufHandler(appReadBuffer);
		}

	}

	/**
	 * Attempts to read some data into the input buffer.
	 *
	 * @return <code>true</code> if more data was added to the input buffer
	 *         otherwise <code>false</code>
	 * @throws IOException
	 */
	@Override
	public boolean fillAppReadBuffer(boolean block) throws IOException {
		int nRead = this.read(block, appReadBuffer);
		if (nRead > 0) {
			return true;
		} else if (nRead == -1) {
			throw new EOFException(sm.getString("iib.eof.error"));
		} else {
			return false;
		}
	}

	@Override
	public BufWrapper getAppReadBuffer() {
		return appReadBuffer;
	}

	// public SocketBufferHandler getSocketBufferHandler();

	@Override
	public BufWrapper allocate(int size) {
		ByteBuffer byteBuffer = ByteBuffer.allocate(size);
		ByteBufferWrapper byteBufferWrapper = new ByteBufferWrapper(this, byteBuffer);
		return byteBufferWrapper;
	}

	public void expandSocketBuffer(int size) {
		if (socketBufferHandler != null) {
			socketBufferHandler.expand(size);
		}
	}

	// @Override
	protected SocketBufferHandler getSocketBufferHandler() {
		return socketBufferHandler;
	}

	protected void setSocketBufferHandler(SocketBufferHandler socketBufferHandler) {
		this.socketBufferHandler = socketBufferHandler;
	}

	@Override
	public boolean hasDataToRead() {
		// Return true because it is always safe to make a read attempt
		return true;
	}

	@Override
	public boolean hasDataToWrite() {
		return !socketBufferHandler.isWriteBufferEmpty() || !nonBlockingWriteBuffer.isEmpty();
	}

	/**
	 * Checks to see if there are any writes pending and if there are calls
	 * {@link #registerWriteInterest()} to trigger a callback once the pending
	 * writes have completed.
	 * <p>
	 * Note: Once this method has returned <code>false</code> it <b>MUST NOT</b> be
	 * called again until the pending write has completed and the callback has been
	 * fired. TODO: Modify {@link #registerWriteInterest()} so the above restriction
	 * is enforced there rather than relying on the caller.
	 *
	 * @return <code>true</code> if no writes are pending and data can be written
	 *         otherwise <code>false</code>
	 */
	@Override
	public boolean isReadyForWrite() {
		boolean result = canWrite();
		if (!result) {
			registerWriteInterest();
		}
		return result;
	}

	@Override
	public boolean canWrite() {
		if (socketBufferHandler == null) {
			throw new IllegalStateException(sm.getString("socket.closed"));
		}
		return socketBufferHandler.isWriteBufferWritable() && nonBlockingWriteBuffer.isEmpty();
	}

	// public abstract int read(boolean block, byte[] b, int off, int len) throws
	// IOException;

	// public abstract int read(boolean block, ByteBuffer to) throws IOException;

	// public abstract boolean isReadyForRead() throws IOException;

	// public abstract void setAppReadBufHandler(ApplicationBufferHandler handler);

	protected int populateReadBuffer(byte[] b, int off, int len) {
		socketBufferHandler.configureReadBufferForRead();
		ByteBuffer readBuffer = socketBufferHandler.getReadBuffer();
		int remaining = readBuffer.remaining();

		// Is there enough data in the read buffer to satisfy this request?
		// Copy what data there is in the read buffer to the byte array
		if (remaining > 0) {
			remaining = Math.min(remaining, len);
			readBuffer.get(b, off, remaining);

			if (log.isDebugEnabled()) {
				log.debug("Socket: [" + this + "], Read from buffer: [" + remaining + "]");
			}
		}
		return remaining;
	}

	protected int populateReadBuffer(ByteBuffer to) {
		// Is there enough data in the read buffer to satisfy this request?
		// Copy what data there is in the read buffer to the byte array
		socketBufferHandler.configureReadBufferForRead();
		int nRead = transfer(socketBufferHandler.getReadBuffer(), to);

		if (log.isDebugEnabled()) {
			log.debug("Socket: [" + this + "], Read from buffer: [" + nRead + "]");
		}
		return nRead;
	}

	@Override
	public int read(boolean block, BufWrapper to) throws IOException {
		if (to instanceof ByteBufferWrapper) {
			ByteBufferWrapper byteBufferWrapper = (ByteBufferWrapper) to;

			ByteBuffer delegate = byteBufferWrapper.getByteBuffer();

			if (byteBufferWrapper.parsingHeader) {
				if (delegate.limit() >= byteBufferWrapper.headerBufferSize) {
					// if (parsingRequestLine) {
					// Avoid unknown protocol triggering an additional error
					// request.protocol().setString(Constants.HTTP_11);
					// }
					throw new IllegalArgumentException(sm.getString("iib.requestheadertoolarge.error"));
				}
			} else {
				delegate.limit(byteBufferWrapper.headerPos).position(byteBufferWrapper.headerPos);
			}

			delegate.mark();
			if (delegate.position() < delegate.limit()) {
				delegate.position(delegate.limit());
			}
			delegate.limit(delegate.capacity());
			int nRead = read(block, delegate);
			delegate.limit(delegate.position()).reset();
			if (nRead > 0) {
				return nRead;
			} else if (nRead == -1) {
				throw new EOFException(sm.getString("iib.eof.error"));
			} else {
				return nRead;
			}
		} else {
			throw new RuntimeException();
		}
	}

	/**
	 * Return input that has been read to the input buffer for re-reading by the
	 * correct component. There are times when a component may read more data than
	 * it needs before it passes control to another component. One example of this
	 * is during HTTP upgrade. If an (arguably misbehaving client) sends data
	 * associated with the upgraded protocol before the HTTP upgrade completes, the
	 * HTTP handler may read it. This method provides a way for that data to be
	 * returned so it can be processed by the correct component.
	 *
	 * @param returnedInput The input to return to the input buffer.
	 */
	@Override
	public void unRead(ByteBuffer returnedInput) {
		if (returnedInput != null) {
			socketBufferHandler.unReadReadBuffer(returnedInput);
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
	protected void writeBlocking(byte[] buf, int off, int len) throws IOException {
		if (len > 0) {
			socketBufferHandler.configureWriteBufferForWrite();
			int thisTime = transfer(buf, off, len, socketBufferHandler.getWriteBuffer());
			len -= thisTime;
			while (len > 0) {
				off += thisTime;
				doWrite(true);
				socketBufferHandler.configureWriteBufferForWrite();
				thisTime = transfer(buf, off, len, socketBufferHandler.getWriteBuffer());
				len -= thisTime;
			}
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
	 * @param from The ByteBuffer containing the data to be written
	 *
	 * @throws IOException If an IO error occurs during the write
	 */
	protected void writeBlocking(ByteBuffer from) throws IOException {
		if (from.hasRemaining()) {
			socketBufferHandler.configureWriteBufferForWrite();
			transfer(from, socketBufferHandler.getWriteBuffer());
			while (from.hasRemaining()) {
				doWrite(true);
				socketBufferHandler.configureWriteBufferForWrite();
				transfer(from, socketBufferHandler.getWriteBuffer());
			}
		}
	}

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
	protected void writeNonBlocking(byte[] buf, int off, int len) throws IOException {
		if (len > 0 && nonBlockingWriteBuffer.isEmpty() && socketBufferHandler.isWriteBufferWritable()) {
			socketBufferHandler.configureWriteBufferForWrite();
			int thisTime = transfer(buf, off, len, socketBufferHandler.getWriteBuffer());
			len -= thisTime;
			while (len > 0) {
				off = off + thisTime;
				doWrite(false);
				if (len > 0 && socketBufferHandler.isWriteBufferWritable()) {
					socketBufferHandler.configureWriteBufferForWrite();
					thisTime = transfer(buf, off, len, socketBufferHandler.getWriteBuffer());
				} else {
					// Didn't write any data in the last non-blocking write.
					// Therefore the write buffer will still be full. Nothing
					// else to do here. Exit the loop.
					break;
				}
				len -= thisTime;
			}
		}

		if (len > 0) {
			// Remaining data must be buffered
			nonBlockingWriteBuffer.add(buf, off, len);
		}
	}

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
	protected void writeNonBlocking(ByteBuffer from) throws IOException {

		if (from.hasRemaining() && nonBlockingWriteBuffer.isEmpty() && socketBufferHandler.isWriteBufferWritable()) {
			writeNonBlockingInternal(from);
		}

		if (from.hasRemaining()) {
			// Remaining data must be buffered
			nonBlockingWriteBuffer.add(from);
		}
	}

	/**
	 * Separate method so it can be re-used by the socket write buffer to write data
	 * to the network
	 *
	 * @param from The ByteBuffer containing the data to be written
	 *
	 * @throws IOException If an IO error occurs during the write
	 */
	protected void writeNonBlockingInternal(ByteBuffer from) throws IOException {
		socketBufferHandler.configureWriteBufferForWrite();
		transfer(from, socketBufferHandler.getWriteBuffer());
		while (from.hasRemaining()) {
			doWrite(false);
			if (socketBufferHandler.isWriteBufferWritable()) {
				socketBufferHandler.configureWriteBufferForWrite();
				transfer(from, socketBufferHandler.getWriteBuffer());
			} else {
				break;
			}
		}
	}

	@Override
	protected void writeBlocking(BufWrapper from) throws IOException {
		if (from instanceof ByteBufferWrapper) {
			ByteBufferWrapper byteBufferWrapper = (ByteBufferWrapper) from;
			writeBlocking(byteBufferWrapper.delegate);
		}
	}

	@Override
	protected void writeNonBlocking(BufWrapper from) throws IOException {
		if (from instanceof ByteBufferWrapper) {
			ByteBufferWrapper byteBufferWrapper = (ByteBufferWrapper) from;
			writeNonBlocking(byteBufferWrapper.delegate);
		}
	}

	protected void flushBlocking() throws IOException {
		doWrite(true);

		if (!nonBlockingWriteBuffer.isEmpty()) {
			nonBlockingWriteBuffer.write(this, true);

			if (!socketBufferHandler.isWriteBufferEmpty()) {
				doWrite(true);
			}
		}

	}

	protected boolean flushNonBlocking() throws IOException {
		boolean dataLeft = !socketBufferHandler.isWriteBufferEmpty();

		// Write to the socket, if there is anything to write
		if (dataLeft) {
			doWrite(false);
			dataLeft = !socketBufferHandler.isWriteBufferEmpty();
		}

		if (!dataLeft && !nonBlockingWriteBuffer.isEmpty()) {
			dataLeft = nonBlockingWriteBuffer.write(this, false);

			if (!dataLeft && !socketBufferHandler.isWriteBufferEmpty()) {
				doWrite(false);
				dataLeft = !socketBufferHandler.isWriteBufferEmpty();
			}
		}

		return dataLeft;
	}

	/**
	 * Write the contents of the socketWriteBuffer to the socket. For blocking
	 * writes either then entire contents of the buffer will be written or an
	 * IOException will be thrown. Partial blocking writes will not occur.
	 *
	 * @param block Should the write be blocking or not?
	 *
	 * @throws IOException If an I/O error such as a timeout occurs during the write
	 */
	protected void doWrite(boolean block) throws IOException {
		socketBufferHandler.configureWriteBufferForRead();
		doWrite(block, socketBufferHandler.getWriteBuffer());
	}

	/**
	 * Write the contents of the ByteBuffer to the socket. For blocking writes
	 * either then entire contents of the buffer will be written or an IOException
	 * will be thrown. Partial blocking writes will not occur.
	 *
	 * @param block Should the write be blocking or not?
	 * @param from  the ByteBuffer containing the data to be written
	 *
	 * @throws IOException If an I/O error such as a timeout occurs during the write
	 */
	protected abstract void doWrite(boolean block, ByteBuffer from) throws IOException;

	@Override
	public void processSocket(SocketEvent socketStatus, boolean dispatch) {
		((SocketWrapperBaseEndpoint) endpoint).processSocket(this, socketStatus, dispatch);
	}

	// public abstract void registerReadInterest();

	// public abstract void registerWriteInterest();

	// public abstract SendfileDataBase createSendfileData(String filename, long
	// pos, long length);

	/**
	 * Starts the sendfile process. It is expected that if the sendfile process does
	 * not complete during this call and does not report an error, that the caller
	 * <b>will not</b> add the socket to the poller (or equivalent). That is the
	 * responsibility of this method.
	 *
	 * @param sendfileData Data representing the file to send
	 *
	 * @return The state of the sendfile process after the first write.
	 */
	// public abstract SendfileState processSendfile(SendfileDataBase sendfileData);

	/**
	 * Require the client to perform CLIENT-CERT authentication if it hasn't already
	 * done so.
	 *
	 * @param sslSupport The SSL/TLS support instance currently being used by the
	 *                   connection that may need updating after the client
	 *                   authentication
	 *
	 * @throws IOException If authentication is required then there will be I/O with
	 *                     the client and this exception will be thrown if that goes
	 *                     wrong
	 */
	// public abstract void doClientAuth(SSLSupport sslSupport) throws IOException;

	// public abstract SSLSupport getSslSupport(String clientCertProvider);

	public abstract void setAppReadBufHandler(ApplicationBufferHandler handler);

	public static class ByteBufferWrapper implements BufWrapper, ApplicationBufferHandler {

		private SocketWrapperBase<?> channel;

		private ByteBuffer delegate;

		private boolean parsingHeader;

		private int headerBufferSize;

		private boolean parsingRequestLine;

		private int headerPos;

		private boolean trace;

		private boolean readMode = true;

		public ByteBufferWrapper(SocketWrapperBase<?> channel, ByteBuffer delegate) {
			super();
			this.channel = channel;
			this.delegate = delegate;
		}

		@Override
		public ByteBuffer getByteBuffer() {
			return delegate;
		}

		@Override
		public void setByteBuffer(ByteBuffer buffer) {
			this.delegate = buffer;
		}

		@Override
		public void switchToWriteMode() {
			if (readMode) {
				delegate.position(delegate.limit());
				delegate.limit(delegate.capacity());
				readMode = false;
			}
		}

		@Override
		public void switchToReadMode() {
			if (!readMode) {
				delegate.limit(delegate.position());
				delegate.position(0);
				readMode = true;
			}
		}

		@Override
		public boolean reuseable() {
			return true;
		}

		@Override
		public int getLimit() {
			return delegate.limit();
		}

		@Override
		public void setLimit(int limit) {
			delegate.limit(limit);
		}

		@Override
		public byte getByte() {
			return delegate.get();
		}

		@Override
		public void getByte(byte[] b, int off, int len) {
			delegate.get(b, off, len);
		}

		@Override
		public byte getByte(int index) {
			return delegate.get(index);
		}

		@Override
		public int getPosition() {
			return delegate.position();
		}

		@Override
		public void setPosition(int position) {
			delegate.position(position);
		}

		@Override
		public boolean hasArray() {
			return delegate.hasArray();
		}

		@Override
		public byte[] getArray() {
			return delegate.array();
		}

		@Override
		public int getRemaining() {
			return delegate.remaining();
		}

		// @Override
		// public int arrayOffset() {
		// return delegate.arrayOffset();
		// }

		@Override
		public boolean hasRemaining() {
			return delegate.remaining() > 0;
		}

		@Override
		public boolean hasNoRemaining() {
			return delegate.remaining() <= 0;
		}

		@Override
		public int getCapacity() {
			return delegate.capacity();
		}

		@Override
		public void setByte(int index, byte b) {
			delegate.put(index, b);
		}

		@Override
		public void putByte(byte b) {
			delegate.put(b);
		}

		@Override
		public void putBytes(byte[] b) {
			delegate.put(b);
		}

		@Override
		public void putBytes(byte[] b, int off, int len) {
			delegate.put(b, off, len);
		}

		/**
		 * Attempts to read some data into the input buffer.
		 *
		 * @return <code>true</code> if more data was added to the input buffer
		 *         otherwise <code>false</code>
		 */
//		@Override
//		public boolean fill(boolean block) throws IOException {
//			if (channel != null) {
//				int nRead = channel.read(block, this);
//				if (nRead > 0) {
//					return true;
//				} else if (nRead == -1) {
//					throw new EOFException(sm.getString("iib.eof.error"));
//				} else {
//					return false;
//				}
//			} else {
//				throw new CloseNowException(sm.getString("iib.eof.error"));
//			}
//		}

//		@Override
//		public int doRead(PreInputBuffer handler) throws IOException {
//
//			if (hasNoRemaining()) {
//				// The application is reading the HTTP request body which is
//				// always a blocking operation.
//				if (!fill(true))
//					return -1;
//			}
//
//			int length = delegate.remaining();
//			handler.setBufWrapper(duplicate());
//			delegate.position(delegate.limit());
//
//			return length;
//		}

		@Override
		public void startParsingHeader(int headerBufferSize) {
			parsingHeader = true;
			this.headerBufferSize = headerBufferSize;
		}

		@Override
		public void startParsingRequestLine() {
			parsingRequestLine = true;
		}

		@Override
		public void finishParsingRequestLine() {
			parsingRequestLine = false;
		}

		@Override
		public void finishParsingHeader(boolean keepHeadPos) {
			parsingHeader = false;
			if (keepHeadPos) {
				headerPos = delegate.position();
			}
		}

		@Override
		public void expand(int size) {
			if (delegate.capacity() >= size) {
				delegate.limit(size);
			}
			ByteBuffer temp = ByteBuffer.allocate(size);
			temp.put(delegate);
			delegate = temp;
			delegate.mark();
			temp = null;
		}

		@Override
		public void nextRequest() {
			if (delegate.position() > 0) {
				if (delegate.remaining() > 0) {
					// Copy leftover bytes to the beginning of the buffer
					delegate.compact();
					delegate.flip();
				} else {
					// Reset position and limit to 0
					delegate.position(0).limit(0);
				}
			}
		}

		@Override
		public void reset() {
			delegate.limit(0).position(0);
		}

		@Override
		public ByteBuffer nioBuffer() {
			return delegate.duplicate();
		}

		// @Override
		public BufWrapper duplicate() {
			return new ByteBufferWrapper(channel, delegate.duplicate());
		}

		public static BufWrapper wrapper(ByteBuffer buffer) {
			return new ByteBufferWrapper(null, buffer);
		}

		@Override
		public void startTrace() {
			trace = true;
		}

		@Override
		public boolean released() {
			return false;
		}

		@Override
		public void release() {

		}

	}

	protected static class SocketBufferHandler {

		static SocketBufferHandler EMPTY = new SocketBufferHandler(0, 0, false) {
			@Override
			public void expand(int newSize) {
			}
		};

		private volatile boolean readBufferConfiguredForWrite = true;
		private volatile ByteBuffer readBuffer;

		private volatile boolean writeBufferConfiguredForWrite = true;
		private volatile ByteBuffer writeBuffer;

		private final boolean direct;

		public SocketBufferHandler(int readBufferSize, int writeBufferSize, boolean direct) {
			this.direct = direct;
			if (direct) {
				readBuffer = ByteBuffer.allocateDirect(readBufferSize);
				writeBuffer = ByteBuffer.allocateDirect(writeBufferSize);
			} else {
				readBuffer = ByteBuffer.allocate(readBufferSize);
				writeBuffer = ByteBuffer.allocate(writeBufferSize);
			}
		}

		public void configureReadBufferForWrite() {
			setReadBufferConfiguredForWrite(true);
		}

		public void configureReadBufferForRead() {
			setReadBufferConfiguredForWrite(false);
		}

		private void setReadBufferConfiguredForWrite(boolean readBufferConFiguredForWrite) {
			// NO-OP if buffer is already in correct state
			if (this.readBufferConfiguredForWrite != readBufferConFiguredForWrite) {
				if (readBufferConFiguredForWrite) {
					// Switching to write
					int remaining = readBuffer.remaining();
					if (remaining == 0) {
						readBuffer.clear();
					} else {
						readBuffer.compact();
					}
				} else {
					// Switching to read
					readBuffer.flip();
				}
				this.readBufferConfiguredForWrite = readBufferConFiguredForWrite;
			}
		}

		public ByteBuffer getReadBuffer() {
			return readBuffer;
		}

		public boolean isReadBufferEmpty() {
			if (readBufferConfiguredForWrite) {
				return readBuffer.position() == 0;
			} else {
				return readBuffer.remaining() == 0;
			}
		}

		public void unReadReadBuffer(ByteBuffer returnedData) {
			if (isReadBufferEmpty()) {
				configureReadBufferForWrite();
				readBuffer.put(returnedData);
			} else {
				int bytesReturned = returnedData.remaining();
				if (readBufferConfiguredForWrite) {
					// Writes always start at position zero
					if ((readBuffer.position() + bytesReturned) > readBuffer.capacity()) {
						throw new BufferOverflowException();
					} else {
						// Move the bytes up to make space for the returned data
						for (int i = 0; i < readBuffer.position(); i++) {
							readBuffer.put(i + bytesReturned, readBuffer.get(i));
						}
						// Insert the bytes returned
						for (int i = 0; i < bytesReturned; i++) {
							readBuffer.put(i, returnedData.get());
						}
						// Update the position
						readBuffer.position(readBuffer.position() + bytesReturned);
					}
				} else {
					// Reads will start at zero but may have progressed
					int shiftRequired = bytesReturned - readBuffer.position();
					if (shiftRequired > 0) {
						if ((readBuffer.capacity() - readBuffer.limit()) < shiftRequired) {
							throw new BufferOverflowException();
						}
						// Move the bytes up to make space for the returned data
						int oldLimit = readBuffer.limit();
						readBuffer.limit(oldLimit + shiftRequired);
						for (int i = readBuffer.position(); i < oldLimit; i++) {
							readBuffer.put(i + shiftRequired, readBuffer.get(i));
						}
					} else {
						shiftRequired = 0;
					}
					// Insert the returned bytes
					int insertOffset = readBuffer.position() + shiftRequired - bytesReturned;
					for (int i = insertOffset; i < bytesReturned + insertOffset; i++) {
						readBuffer.put(i, returnedData.get());
					}
					readBuffer.position(insertOffset);
				}
			}
		}

		public void configureWriteBufferForWrite() {
			setWriteBufferConfiguredForWrite(true);
		}

		public void configureWriteBufferForRead() {
			setWriteBufferConfiguredForWrite(false);
		}

		private void setWriteBufferConfiguredForWrite(boolean writeBufferConfiguredForWrite) {
			// NO-OP if buffer is already in correct state
			if (this.writeBufferConfiguredForWrite != writeBufferConfiguredForWrite) {
				if (writeBufferConfiguredForWrite) {
					// Switching to write
					int remaining = writeBuffer.remaining();
					if (remaining == 0) {
						writeBuffer.clear();
					} else {
						writeBuffer.compact();
						writeBuffer.position(remaining);
						writeBuffer.limit(writeBuffer.capacity());
					}
				} else {
					// Switching to read
					writeBuffer.flip();
				}
				this.writeBufferConfiguredForWrite = writeBufferConfiguredForWrite;
			}
		}

		public boolean isWriteBufferWritable() {
			if (writeBufferConfiguredForWrite) {
				return writeBuffer.hasRemaining();
			} else {
				return writeBuffer.remaining() == 0;
			}
		}

		public ByteBuffer getWriteBuffer() {
			return writeBuffer;
		}

		public boolean isWriteBufferEmpty() {
			if (writeBufferConfiguredForWrite) {
				return writeBuffer.position() == 0;
			} else {
				return writeBuffer.remaining() == 0;
			}
		}

		public void reset() {
			readBuffer.clear();
			readBufferConfiguredForWrite = true;
			writeBuffer.clear();
			writeBufferConfiguredForWrite = true;
		}

		public void expand(int newSize) {
			configureReadBufferForWrite();
			readBuffer = ByteBufferUtils.expand(readBuffer, newSize);
			configureWriteBufferForWrite();
			writeBuffer = ByteBufferUtils.expand(writeBuffer, newSize);
		}

		public void free() {
			if (direct) {
				ByteBufferUtils.cleanDirectBuffer(readBuffer);
				ByteBufferUtils.cleanDirectBuffer(writeBuffer);
			}
		}

	}

}
