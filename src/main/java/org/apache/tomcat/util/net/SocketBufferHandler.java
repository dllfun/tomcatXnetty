package org.apache.tomcat.util.net;

import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;

import org.apache.tomcat.util.buf.ByteBufferUtils;
import org.apache.tomcat.util.net.SocketWrapperBase.ByteBufferWrapper;

public class SocketBufferHandler {

	static SocketBufferHandler EMPTY = new SocketBufferHandler(0, 0, false) {
		@Override
		public void expand(int newSize) {
		}
	};

//	private volatile boolean readBufferConfiguredForWrite = true;
	private volatile ByteBufferWrapper readBuffer;

//	private volatile boolean writeBufferConfiguredForWrite = true;
	private volatile ByteBufferWrapper writeBuffer;

	private final boolean direct;

	/**
	 * The application read buffer.
	 */
	private ByteBufferWrapper appReadBuffer = ByteBufferWrapper.wrapper(ByteBuffer.allocate(0), false);

	public SocketBufferHandler(int readBufferSize, int writeBufferSize, boolean direct) {
		this.direct = direct;
		if (direct) {
			readBuffer = ByteBufferWrapper.wrapper(ByteBuffer.allocateDirect(readBufferSize), false);
			writeBuffer = ByteBufferWrapper.wrapper(ByteBuffer.allocateDirect(writeBufferSize), false);
		} else {
			readBuffer = ByteBufferWrapper.wrapper(ByteBuffer.allocate(readBufferSize), false);
			writeBuffer = ByteBufferWrapper.wrapper(ByteBuffer.allocate(writeBufferSize), false);
		}
	}

	public void configureReadBufferForWrite() {
//		setReadBufferConfiguredForWrite(true);
		readBuffer.switchToWriteMode();
	}

	public void configureReadBufferForRead() {
//		setReadBufferConfiguredForWrite(false);
		readBuffer.switchToReadMode();
	}

//	private void setReadBufferConfiguredForWrite(boolean readBufferConFiguredForWrite) {
//		// NO-OP if buffer is already in correct state
//		if (this.readBufferConfiguredForWrite != readBufferConFiguredForWrite) {
//			if (readBufferConFiguredForWrite) {
//				// Switching to write
//				int remaining = readBuffer.remaining();
//				if (remaining == 0) {
//					readBuffer.clear();
//				} else {
//					readBuffer.compact();
//				}
//			} else {
//				// Switching to read
//				readBuffer.flip();
//			}
//			this.readBufferConfiguredForWrite = readBufferConFiguredForWrite;
//		}
//	}

	public ByteBufferWrapper getReadBuffer() {
		return readBuffer;
	}

	public boolean isReadBufferEmpty() {
//		if (readBufferConfiguredForWrite) {
//			return readBuffer.position() == 0;
//		} else {
//			return readBuffer.remaining() == 0;
//		}
		return readBuffer.isEmpty();
	}

	public void unReadReadBuffer(ByteBufferWrapper returnedData) {
		if (isReadBufferEmpty()) {
			configureReadBufferForWrite();
			System.err.println("unReadReadBuffer returnedData");
//			readBuffer.getByteBuffer().put(returnedData.getByteBuffer());
			returnedData.transferTo(readBuffer);
		} else {
			if ("1".equals("1"))
				throw new RuntimeException("has bug here");
			int bytesReturned = returnedData.getRemaining();
			if (readBuffer.isWriteMode()) {
				// Writes always start at position zero
				if ((readBuffer.getPosition() + bytesReturned) > readBuffer.getCapacity()) {
					throw new BufferOverflowException();
				} else {
					// Move the bytes up to make space for the returned data
					// has bug
					for (int i = 0; i < readBuffer.getPosition(); i++) {
						readBuffer.setByte(i + bytesReturned, readBuffer.getByte(i));
					}
					// Insert the bytes returned
					for (int i = 0; i < bytesReturned; i++) {
						readBuffer.setByte(i, returnedData.getByte());
					}
					// Update the position
					readBuffer.setPosition(readBuffer.getPosition() + bytesReturned);
				}
			} else {
				// Reads will start at zero but may have progressed
				int shiftRequired = bytesReturned - readBuffer.getPosition();
				if (shiftRequired > 0) {
					if ((readBuffer.getCapacity() - readBuffer.getLimit()) < shiftRequired) {
						throw new BufferOverflowException();
					}
					// Move the bytes up to make space for the returned data
					int oldLimit = readBuffer.getLimit();
					readBuffer.setLimit(oldLimit + shiftRequired);
					for (int i = readBuffer.getPosition(); i < oldLimit; i++) {
						readBuffer.setByte(i + shiftRequired, readBuffer.getByte(i));
					}
				} else {
					shiftRequired = 0;
				}
				// Insert the returned bytes
				int insertOffset = readBuffer.getPosition() + shiftRequired - bytesReturned;
				for (int i = insertOffset; i < bytesReturned + insertOffset; i++) {
					readBuffer.setByte(i, returnedData.getByte());
				}
				readBuffer.setPosition(insertOffset);
			}
		}
	}

	public void configureWriteBufferForWrite() {
//		setWriteBufferConfiguredForWrite(true);
		writeBuffer.switchToWriteMode();
	}

	public void configureWriteBufferForRead() {
//		setWriteBufferConfiguredForWrite(false);
		writeBuffer.switchToReadMode();
	}

//	private void setWriteBufferConfiguredForWrite(boolean writeBufferConfiguredForWrite) {
//		// NO-OP if buffer is already in correct state
//		if (this.writeBufferConfiguredForWrite != writeBufferConfiguredForWrite) {
//			if (writeBufferConfiguredForWrite) {
//				// Switching to write
//				int remaining = writeBuffer.remaining();
//				if (remaining == 0) {
//					writeBuffer.clear();
//				} else {
//					writeBuffer.compact();
//					writeBuffer.position(remaining);
//					writeBuffer.limit(writeBuffer.capacity());
//				}
//			} else {
//				// Switching to read
//				writeBuffer.flip();
//			}
//			this.writeBufferConfiguredForWrite = writeBufferConfiguredForWrite;
//		}
//	}

	public boolean isWriteBufferWritable() {
//		if (writeBufferConfiguredForWrite) {
//			return writeBuffer.hasRemaining();
//		} else {
//			return writeBuffer.remaining() == 0;
//		}
		if (writeBuffer.isWriteMode()) {
			return writeBuffer.hasRemaining();
		} else {
			if ("1".equals("1"))
				throw new RuntimeException("has bug here");
			return writeBuffer.getPosition() > 0 || writeBuffer.getLimit() < writeBuffer.getCapacity();
		}
	}

	public ByteBufferWrapper getWriteBuffer() {
		return writeBuffer;
	}

	public boolean isWriteBufferEmpty() {
//		if (writeBufferConfiguredForWrite) {
//			return writeBuffer.position() == 0;
//		} else {
//			return writeBuffer.remaining() == 0;
//		}
		return writeBuffer.isEmpty();
	}

	public void reset() {
//		readBuffer.clear();
//		readBufferConfiguredForWrite = true;
//		writeBuffer.clear();
//		writeBufferConfiguredForWrite = true;
		readBuffer.switchToWriteMode();
		readBuffer.setPosition(0);
		readBuffer.setLimit(readBuffer.getCapacity());
		writeBuffer.switchToWriteMode();
		writeBuffer.setPosition(0);
		writeBuffer.setLimit(writeBuffer.getCapacity());
		appReadBuffer.switchToWriteMode();
		appReadBuffer.setPosition(0);
		appReadBuffer.setLimit(appReadBuffer.getCapacity());
	}

	public void expand(int newSize) {
		configureReadBufferForWrite();
//		readBuffer = ByteBufferUtils.expand(readBuffer, newSize);
		readBuffer.expand(newSize);
		configureWriteBufferForWrite();
//		writeBuffer = ByteBufferUtils.expand(writeBuffer, newSize);
		writeBuffer.expand(newSize);
	}

	public void free() {
		if (direct) {
			ByteBufferUtils.cleanDirectBuffer(readBuffer.getByteBuffer());
			ByteBufferUtils.cleanDirectBuffer(writeBuffer.getByteBuffer());
		}
	}

	public void initAppReadBuffer(int headerBufferSize) {
		if (this.readBuffer != null) {
			int bufLength = headerBufferSize + this.readBuffer.getCapacity();
			if (this.appReadBuffer.getCapacity() < bufLength) {
				this.appReadBuffer.expand(bufLength);
			}
		}
	}

	public ByteBufferWrapper getAppReadBuffer() {
		return this.appReadBuffer;
	}

	public void expandAppReadBuffer(int size) {
		if (this.appReadBuffer.getCapacity() >= size) {
			this.appReadBuffer.setLimit(size);
		} else {
			this.appReadBuffer.expand(size);
		}
//		ByteBuffer temp = ByteBuffer.allocate(size);
//		temp.put(this.appReadBuffer.getByteBuffer());
//		this.appReadBuffer = ByteBufferWrapper.wrapper(temp, this.appReadBuffer.isReadMode());
//		this.appReadBuffer.mark();
//		temp = null;
	}

}
