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
import org.apache.tomcat.util.net.SocketWrapperBase.ByteBufferWrapper;
import org.apache.tomcat.util.res.StringManager;

public abstract class SocketWrapperBase<E> extends AbstractSocketChannel<E> {

	private static final Log log = LogFactory.getLog(SocketWrapperBase.class);

	protected static final StringManager sm = StringManager.getManager(SocketWrapperBase.class);

	private final SocketWrapperBaseEndpoint<E, ?> endpoint;

	/**
	 * The buffers used for communicating with the socket.
	 */
	// private volatile SocketBufferHandler socketBufferHandler = null;

	/**
	 * The read buffer.
	 */
	private ByteBufferWrapper appReadBuffer = new ByteBufferWrapper(this, ByteBuffer.allocate(0), false);

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
		this.endpoint = endpoint;
	}

	protected SocketWrapperBaseEndpoint<E, ?> getEndpoint() {
		return endpoint;
	}

	@Override
	public void initAppReadBuffer(int headerBufferSize) {

		if (getSocketAppReadBuffer() != null && getSocketReadBuffer() != null) {
			appReadBuffer = getSocketAppReadBuffer();
			appReadBuffer.expand(headerBufferSize + getSocketReadBuffer().getCapacity());
			appReadBuffer.switchToWriteMode();
			appReadBuffer.clearWrite();
			appReadBuffer.switchToReadMode();
			// setAppReadBufHandler(appReadBuffer);
			// socket.addAppReadBufferExpandListener();
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
		ByteBufferWrapper byteBufferWrapper = ByteBufferWrapper.wrapper(byteBuffer, false);
		return byteBufferWrapper;
	}

	public void expandSocketBuffer(int size) {
//		if (getSocketBufferHandler() != null) {
//			getSocketBufferHandler().expand(size);
//		}
		if (getSocketReadBuffer() != null) {
			getSocketReadBuffer().expand(size);
		}
		if (getSocketWriteBuffer() != null) {
			getSocketWriteBuffer().expand(size);
		}
	}

	// @Override
//	abstract protected SocketBufferHandler getSocketBufferHandler();

	abstract protected ByteBufferWrapper getSocketAppReadBuffer();

	abstract protected ByteBufferWrapper getSocketReadBuffer();

	abstract protected ByteBufferWrapper getSocketWriteBuffer();

	// protected void setSocketBufferHandler(SocketBufferHandler
	// socketBufferHandler) {
	// this.socketBufferHandler = socketBufferHandler;
	// }

	@Override
	public boolean hasDataToRead() {
		// Return true because it is always safe to make a read attempt
		return true;
	}

	@Override
	public boolean hasDataToWrite() {
		return !getSocketWriteBuffer().isEmpty() || !nonBlockingWriteBuffer.isEmpty();
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
		if (getSocketWriteBuffer() == null) {
			throw new IllegalStateException(sm.getString("socket.closed"));
		}
		return getSocketWriteBuffer().isWritable() && nonBlockingWriteBuffer.isEmpty();
	}

	// public abstract int read(boolean block, byte[] b, int off, int len) throws
	// IOException;

	// public abstract int read(boolean block, ByteBuffer to) throws IOException;

	// public abstract boolean isReadyForRead() throws IOException;

	// public abstract void setAppReadBufHandler(ApplicationBufferHandler handler);

	protected int populateReadBuffer(byte[] b, int off, int len) {
//		getSocketBufferHandler().configureReadBufferForRead();
		ByteBufferWrapper readBuffer = getSocketReadBuffer();
		readBuffer.switchToReadMode();
		int remaining = readBuffer.getRemaining();

		// Is there enough data in the read buffer to satisfy this request?
		// Copy what data there is in the read buffer to the byte array
		if (remaining > 0) {
			remaining = Math.min(remaining, len);
			readBuffer.getBytes(b, off, remaining);

			if (log.isDebugEnabled()) {
				log.debug("Socket: [" + this + "], Read from buffer: [" + remaining + "]");
			}
		}
		return remaining;
	}

	protected int populateReadBuffer(ByteBufferWrapper to) {
		// Is there enough data in the read buffer to satisfy this request?
		// Copy what data there is in the read buffer to the byte array
//		getSocketBufferHandler().configureReadBufferForRead();
		ByteBufferWrapper readBuffer = getSocketReadBuffer();
		readBuffer.switchToReadMode();
		if (readBuffer.hasRemaining()) {
//			int nRead = transfer(getSocketBufferHandler().getReadBuffer(), to);
			int nRead = readBuffer.transferTo(to);

			if (log.isDebugEnabled()) {
				log.debug("Socket: [" + this + "], Read from buffer: [" + nRead + "]");
			}
			return nRead;
		} else {
			return 0;
		}
	}

	@Override
	public int read(boolean block, BufWrapper to) throws IOException {
		if (to instanceof ByteBufferWrapper) {
			ByteBufferWrapper byteBufferWrapper = (ByteBufferWrapper) to;

			// ByteBuffer delegate = byteBufferWrapper.getByteBuffer();

//			delegate.mark();
//			if (delegate.position() < delegate.limit()) {
//				delegate.position(delegate.limit());
//			}
//			delegate.limit(delegate.capacity());
			int nRead = read(block, byteBufferWrapper);
			byteBufferWrapper.switchToReadMode();
			// after read buffer may has changed if buffer is appReadBuffer
//			delegate = byteBufferWrapper.getByteBuffer();
//			delegate.limit(delegate.position()).reset();
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
	public void unRead(ByteBufferWrapper returnedInput) {
		if (returnedInput != null && returnedInput.getByteBuffer() != null) {
			getSocketReadBuffer().unread(returnedInput);
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
			getSocketWriteBuffer().switchToWriteMode();
			int thisTime = transfer(buf, off, len, getSocketWriteBuffer());
			len -= thisTime;
			while (len > 0) {
				off += thisTime;
				doWrite(true);
				getSocketWriteBuffer().switchToWriteMode();
				thisTime = transfer(buf, off, len, getSocketWriteBuffer());
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
	@Override
	protected void writeBlocking(ByteBufferWrapper from) throws IOException {
		if (from.hasRemaining()) {
			getSocketWriteBuffer().switchToWriteMode();
//			transfer(from, getSocketBufferHandler().getWriteBuffer());
			from.transferTo(getSocketWriteBuffer());
			while (from.hasRemaining()) {
				doWrite(true);
				getSocketWriteBuffer().switchToWriteMode();
				from.transferTo(getSocketWriteBuffer());
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
		if (len > 0 && nonBlockingWriteBuffer.isEmpty() && getSocketWriteBuffer().isWritable()) {
			getSocketWriteBuffer().switchToWriteMode();
			int thisTime = transfer(buf, off, len, getSocketWriteBuffer());
			len -= thisTime;
			while (len > 0) {
				off = off + thisTime;
				doWrite(false);
				if (len > 0 && getSocketWriteBuffer().isWritable()) {
					getSocketWriteBuffer().switchToWriteMode();
					thisTime = transfer(buf, off, len, getSocketWriteBuffer());
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
	protected void writeNonBlocking(ByteBufferWrapper from) throws IOException {

		if (from.hasRemaining() && nonBlockingWriteBuffer.isEmpty() && getSocketWriteBuffer().isWritable()) {
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
	protected void writeNonBlockingInternal(ByteBufferWrapper from) throws IOException {
		getSocketWriteBuffer().switchToWriteMode();
//		transfer(from, getSocketBufferHandler().getWriteBuffer());
		from.transferTo(getSocketWriteBuffer());
		while (from.hasRemaining()) {
			doWrite(false);
			if (getSocketWriteBuffer().isWritable()) {
				getSocketWriteBuffer().switchToWriteMode();
				from.transferTo(getSocketWriteBuffer());
			} else {
				break;
			}
		}
	}

	@Override
	protected void writeBlocking(BufWrapper from) throws IOException {
		if (from instanceof ByteBufferWrapper) {
			ByteBufferWrapper byteBufferWrapper = (ByteBufferWrapper) from;
			writeBlocking(byteBufferWrapper);
		}
	}

	@Override
	protected void writeNonBlocking(BufWrapper from) throws IOException {
		if (from instanceof ByteBufferWrapper) {
			ByteBufferWrapper byteBufferWrapper = (ByteBufferWrapper) from;
			writeNonBlocking(byteBufferWrapper);
		}
	}

	protected void flushBlocking() throws IOException {
		doWrite(true);

		if (!nonBlockingWriteBuffer.isEmpty()) {
			nonBlockingWriteBuffer.write(this, true);

			if (!getSocketWriteBuffer().isEmpty()) {
				doWrite(true);
			}
		}

	}

	protected boolean flushNonBlocking() throws IOException {
		boolean dataLeft = !getSocketWriteBuffer().isEmpty();

		// Write to the socket, if there is anything to write
		if (dataLeft) {
			doWrite(false);
			dataLeft = !getSocketWriteBuffer().isEmpty();
		}

		if (!dataLeft && !nonBlockingWriteBuffer.isEmpty()) {
			dataLeft = nonBlockingWriteBuffer.write(this, false);

			if (!dataLeft && !getSocketWriteBuffer().isEmpty()) {
				doWrite(false);
				dataLeft = !getSocketWriteBuffer().isEmpty();
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
		getSocketWriteBuffer().switchToReadMode();
		doWrite(block, getSocketWriteBuffer());
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
	protected abstract void doWrite(boolean block, ByteBufferWrapper from) throws IOException;

	// @Override
	// public void processSocket(SocketEvent socketStatus, boolean dispatch) {
	// ((SocketWrapperBaseEndpoint) endpoint).getHandler().processSocket(this,
	// socketStatus, dispatch);
	// }

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

	// public abstract void setAppReadBufHandler(ApplicationBufferHandler handler);

	public static class ByteBufferWrapper implements BufWrapper {

		private SocketWrapperBase<?> channel;

		private ByteBuffer delegate;

		private boolean trace;

		private boolean readMode = true;

		private int readPosition = 0;

		private int retain = 0;

		public ByteBufferWrapper(SocketWrapperBase<?> channel, ByteBuffer delegate, boolean readMode) {
			super();
			this.channel = channel;
			this.delegate = delegate;
			this.readMode = readMode;
		}

		// @Override
		public ByteBuffer getByteBuffer() {
			return delegate;
		}

		// @Override
		public void setByteBuffer(ByteBuffer delegate) {
			this.delegate = delegate;
		}

		@Override
		public void switchToWriteMode(boolean compact) {
			if (released()) {
				throw new RuntimeException();
			}
			if (readMode) {
				if (compact) {
					if (retain > getPosition()) {
						throw new RuntimeException();
					}
					if (delegate.position() > 0) {
						System.arraycopy(delegate.array(), delegate.position() + delegate.arrayOffset() + retain,
								delegate.array(), delegate.arrayOffset() + retain, delegate.remaining());
					}
					delegate.position(delegate.remaining() + retain);
					delegate.limit(delegate.capacity());
					readPosition = retain;
				} else {
					readPosition = delegate.position();
					delegate.position(delegate.limit());
					delegate.limit(delegate.capacity());
				}
				readMode = false;
			}
		}

		@Override
		public void switchToWriteMode() {
			switchToWriteMode(true);
		}

		@Override
		public void setRetain(int retain) {
			if (readMode) {
				if (retain < getPosition()) {
					throw new RuntimeException();
				}
				if (retain != getPosition()) {
					System.err.println("setRetain");
				}
				this.retain = retain;
			} else {
				throw new RuntimeException();
			}
		}

		@Override
		public void clearRetain() {
			this.retain = 0;
		}

		@Override
		public int getRetain() {
			return this.retain;
		}

		@Override
		public boolean isWriteMode() {
			return !readMode;
		}

		@Override
		public void switchToReadMode() {
			if (released()) {
				throw new RuntimeException();
			}
			if (!readMode) {
				delegate.limit(delegate.position());
				delegate.position(readPosition);
				readMode = true;
			}
		}

		@Override
		public boolean isReadMode() {
			return readMode;
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
			if (!readMode) {
				throw new RuntimeException();
			}
			return delegate.get();
		}

		@Override
		public void getBytes(byte[] b, int off, int len) {
			if (!readMode) {
				throw new RuntimeException();
			}
			delegate.get(b, off, len);
		}

		@Override
		public byte getByte(int index) {
			if (!readMode) {
				throw new RuntimeException();
			}
			return delegate.get(index);
		}

		@Override
		public int getPosition() {
			return delegate.position();
		}

		@Override
		public void setPosition(int position) {
			if (readMode) {

			} else {
				if (position < retain) {
					throw new RuntimeException();
				}
			}
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

		public int getArrayOffset() {
			return delegate.arrayOffset();
		}

		@Override
		public boolean isDirect() {
			return delegate.isDirect();
		}

		@Override
		public boolean isEmpty() {
			if (readMode) {
				return delegate.remaining() == 0;
			} else {
				return delegate.position() == 0;
			}
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
			if (readMode) {
				throw new RuntimeException();
			}
			if (index < retain) {
				throw new RuntimeException();
			}
			delegate.put(index, b);
		}

		@Override
		public void putByte(byte b) {
			if (readMode) {
				throw new RuntimeException();
			}
			if (getPosition() < retain) {
				throw new RuntimeException();
			}
			delegate.put(b);
		}

		@Override
		public void putBytes(byte[] b) {
			if (readMode) {
				throw new RuntimeException();
			}
			if (getPosition() < retain) {
				throw new RuntimeException();
			}
			delegate.put(b);
		}

		@Override
		public void putBytes(byte[] b, int off, int len) {
			if (readMode) {
				throw new RuntimeException();
			}
			if (getPosition() < retain) {
				throw new RuntimeException();
			}
			delegate.put(b, off, len);
		}

		@Override
		public void clearWrite() {
			if (readMode) {
				throw new RuntimeException();
			}
			if (readPosition != 0) {
				throw new RuntimeException();
			}
			if (retain != 0) {
				throw new RuntimeException();
			}
			delegate.clear();
		}

		@Override
		public boolean isWritable() {
			if (isWriteMode()) {
				return hasRemaining();
			} else {
				if ("1".equals("1"))
					throw new RuntimeException("has bug here");
				return getPosition() > 0 || getLimit() < getCapacity();
			}
		}

//		@Override
		public void unread(ByteBufferWrapper returned) {
			if (retain != 0) {
				throw new RuntimeException();
			}
			if (isEmpty()) {
				switchToWriteMode();
				returned.transferTo(this);
			} else {
				if ("1".equals("1"))
					throw new RuntimeException("has bug here");
				int bytesReturned = returned.getRemaining();
				if (isWriteMode()) {
					// Writes always start at position zero
					if ((getPosition() + bytesReturned) > getCapacity()) {
						throw new BufferOverflowException();
					} else {
						// Move the bytes up to make space for the returned data
						// has bug
						for (int i = 0; i < getPosition(); i++) {
							setByte(i + bytesReturned, getByte(i));
						}
						// Insert the bytes returned
						for (int i = 0; i < bytesReturned; i++) {
							setByte(i, returned.getByte());
						}
						// Update the position
						setPosition(getPosition() + bytesReturned);
					}
				} else {
					// Reads will start at zero but may have progressed
					int shiftRequired = bytesReturned - getPosition();
					if (shiftRequired > 0) {
						if ((getCapacity() - getLimit()) < shiftRequired) {
							throw new BufferOverflowException();
						}
						// Move the bytes up to make space for the returned data
						int oldLimit = getLimit();
						setLimit(oldLimit + shiftRequired);
						for (int i = getPosition(); i < oldLimit; i++) {
							setByte(i + shiftRequired, getByte(i));
						}
					} else {
						shiftRequired = 0;
					}
					// Insert the returned bytes
					int insertOffset = getPosition() + shiftRequired - bytesReturned;
					for (int i = insertOffset; i < bytesReturned + insertOffset; i++) {
						setByte(i, returned.getByte());
					}
					setPosition(insertOffset);
				}
			}
		}

		public int transferTo(ByteBufferWrapper to) {
			if (!this.isReadMode() || this.hasNoRemaining()) {
				throw new RuntimeException();
			}
			if (!to.isWriteMode() || to.hasNoRemaining()) {
				throw new RuntimeException();
			}
			int max = Math.min(this.getRemaining(), to.getRemaining());
			if (max > 0) {
				int fromLimit = this.getLimit();
				this.setLimit(this.getPosition() + max);
				to.delegate.put(this.delegate);
				this.setLimit(fromLimit);
			}
			return max;
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

//		@Override
//		public void expand(int size) {
//			if (delegate.capacity() >= size) {
//				delegate.limit(size);
//			}
//			ByteBuffer temp = ByteBuffer.allocate(size);
//			temp.put(delegate);
//			delegate = temp;
//			delegate.mark();
//			temp = null;
//		}

		@Override
		public void reset() {
//			delegate.limit(0).position(0);
			retain = 0;
			switchToWriteMode();
			delegate.clear();
		}

		@Override
		public ByteBuffer nioBuffer() {
			return delegate.duplicate();
		}

		@Override
		public BufWrapper duplicate() {
			ByteBuffer buffer = delegate.duplicate();
			return new ByteBufferWrapper(channel, buffer, true);
		}

		public static ByteBufferWrapper wrapper(ByteBuffer buffer, boolean readMode) {
			return new ByteBufferWrapper(null, buffer, readMode);
		}

		@Override
		public void startTrace() {
			trace = true;
		}

		@Override
		public void retain() {
			// NO-OP
		}

		@Override
		public boolean released() {
			return false;
		}

		@Override
		public void release() {
			// NO-OP
		}

		@Override
		public int refCount() {
			// TODO Auto-generated method stub
			return 0;
		}

		@Override
		public String printInfo() {
			// TODO Auto-generated method stub
			return "";
		}

		@Override
		public void expand(int newSize) {
			// TODO
			delegate = ByteBufferUtils.expand(delegate, newSize);
			// throw new RuntimeException();
			// provider.expand(newSize);
		}

	}

}
