package org.apache.tomcat.util.net;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.CompletionHandler;
import java.util.concurrent.TimeUnit;

public interface SocketChannel extends Channel {

	public static interface BufWrapper {

		public void switchToWriteMode();

		public void switchToReadMode();

		public boolean reuseable();

		public int getLimit();

		public void setLimit(int limit);

		public byte getByte();

		public void getByte(byte[] b, int off, int len);

		public byte getByte(int index);

		public int getPosition();

		public void setPosition(int position);

		public boolean hasArray();

		public byte[] getArray();

		public int getRemaining();

		public boolean hasRemaining();

		public boolean hasNoRemaining();

		// public int arrayOffset();

		public int getCapacity();

		public void setByte(int index, byte b);

		public void putByte(byte b);

		public void putBytes(byte[] b);

		public void putBytes(byte[] b, int off, int len);

		// public boolean fill(boolean block) throws IOException;

		// public int read(boolean block, byte[] b, int off, int len) throws
		// IOException;

		// public int read(boolean block, ByteBuffer to) throws IOException;

		public void startParsingHeader(int headerBufferSize);

		public void startParsingRequestLine();

		public void finishParsingRequestLine();

		public void finishParsingHeader(boolean keepHeadPos);

		public void nextRequest();

		public void reset();

		public ByteBuffer nioBuffer();

		// public BufWrapper duplicate();

		public void startTrace();

		public boolean released();

		public void release();

	}

	public BufWrapper getAppReadBuffer();

	public void initAppReadBuffer(int headerBufferSize);

	public boolean fillAppReadBuffer(boolean block) throws IOException;

	public BufWrapper allocate(int size);

	public Object getCurrentProcessor();

	public void setCurrentProcessor(Object currentProcessor);

	// public void execute(Runnable runnable);

	public IOException getError();

	public void setUpgraded(boolean upgraded);

	public String getNegotiatedProtocol();

	public void setNegotiatedProtocol(String negotiatedProtocol);

	public void setReadTimeout(long readTimeout);

	public long getReadTimeout();

	public void setWriteTimeout(long writeTimeout);

	public int decrementKeepAlive();

	public String getRemoteHost();

	public String getRemoteAddr();

	public int getRemotePort();

	public String getLocalName();

	public String getLocalAddr();

	public int getLocalPort();

	public boolean hasDataToRead();

	public boolean hasDataToWrite();

	public boolean isReadyForWrite();

	public boolean canWrite();

	public int read(boolean block, byte[] b, int off, int len) throws IOException;

	public int read(boolean block, ByteBuffer to) throws IOException;

	public int read(boolean block, BufWrapper to) throws IOException;

	public boolean isReadyForRead() throws IOException;

	public void unRead(ByteBuffer returnedInput);

	public void write(boolean block, byte[] buf, int off, int len) throws IOException;

	public void write(boolean block, ByteBuffer from) throws IOException;

	public void write(boolean block, BufWrapper from) throws IOException;

	public boolean flush(boolean block) throws IOException;

	// public void processSocket(SocketEvent socketStatus, boolean dispatch);

	public void registerReadInterest();

	public void registerWriteInterest();

	public SendfileDataBase createSendfileData(String filename, long pos, long length);

	public SendfileState processSendfile(SendfileDataBase sendfileData);

	public void doClientAuth(SSLSupport sslSupport) throws IOException;

	public SSLSupport getSslSupport(String clientCertProvider);

	public boolean hasAsyncIO();

	public boolean isReadPending();

	public enum BlockingMode {
		/**
		 * The operation will not block. If there are pending operations, the operation
		 * will throw a pending exception.
		 */
		CLASSIC,
		/**
		 * The operation will not block. If there are pending operations, the operation
		 * will return CompletionState.NOT_DONE.
		 */
		NON_BLOCK,
		/**
		 * The operation will block until pending operations are completed, but will not
		 * block after performing it.
		 */
		SEMI_BLOCK,
		/**
		 * The operation will block until completed.
		 */
		BLOCK
	}

	public enum CompletionHandlerCall {
		/**
		 * Operation should continue, the completion handler shouldn't be called.
		 */
		CONTINUE,
		/**
		 * The operation completed but the completion handler shouldn't be called.
		 */
		NONE,
		/**
		 * The operation is complete, the completion handler should be called.
		 */
		DONE
	}

	public enum CompletionState {
		/**
		 * Operation is still pending.
		 */
		PENDING,
		/**
		 * Operation was pending and non blocking.
		 */
		NOT_DONE,
		/**
		 * The operation completed inline.
		 */
		INLINE,
		/**
		 * The operation completed inline but failed.
		 */
		ERROR,
		/**
		 * The operation completed, but not inline.
		 */
		DONE
	}

	public interface CompletionCheck {
		/**
		 * Determine what call, if any, should be made to the completion handler.
		 *
		 * @param state   of the operation (done or done in-line since the IO call is
		 *                done)
		 * @param buffers ByteBuffer[] that has been passed to the original IO call
		 * @param offset  that has been passed to the original IO call
		 * @param length  that has been passed to the original IO call
		 *
		 * @return The call, if any, to make to the completion handler
		 */
		public CompletionHandlerCall callHandler(CompletionState state, ByteBuffer[] buffers, int offset, int length);
	}

	/**
	 * This utility CompletionCheck will cause the write to fully write all
	 * remaining data. If the operation completes inline, the completion handler
	 * will not be called.
	 */
	public static final CompletionCheck COMPLETE_WRITE = new CompletionCheck() {
		@Override
		public CompletionHandlerCall callHandler(CompletionState state, ByteBuffer[] buffers, int offset, int length) {
			for (int i = 0; i < length; i++) {
				if (buffers[offset + i].hasRemaining()) {
					return CompletionHandlerCall.CONTINUE;
				}
			}
			return (state == CompletionState.DONE) ? CompletionHandlerCall.DONE : CompletionHandlerCall.NONE;
		}
	};

	/**
	 * This utility CompletionCheck will cause the write to fully write all
	 * remaining data. The completion handler will then be called.
	 */
	public static final CompletionCheck COMPLETE_WRITE_WITH_COMPLETION = new CompletionCheck() {
		@Override
		public CompletionHandlerCall callHandler(CompletionState state, ByteBuffer[] buffers, int offset, int length) {
			for (int i = 0; i < length; i++) {
				if (buffers[offset + i].hasRemaining()) {
					return CompletionHandlerCall.CONTINUE;
				}
			}
			return CompletionHandlerCall.DONE;
		}
	};

	/**
	 * This utility CompletionCheck will cause the completion handler to be called
	 * once some data has been read. If the operation completes inline, the
	 * completion handler will not be called.
	 */
	public static final CompletionCheck READ_DATA = new CompletionCheck() {
		@Override
		public CompletionHandlerCall callHandler(CompletionState state, ByteBuffer[] buffers, int offset, int length) {
			return (state == CompletionState.DONE) ? CompletionHandlerCall.DONE : CompletionHandlerCall.NONE;
		}
	};

	/**
	 * This utility CompletionCheck will cause the completion handler to be called
	 * once the given buffers are full. The completion handler will then be called.
	 */
	public static final CompletionCheck COMPLETE_READ_WITH_COMPLETION = COMPLETE_WRITE_WITH_COMPLETION;

	/**
	 * This utility CompletionCheck will cause the completion handler to be called
	 * once the given buffers are full. If the operation completes inline, the
	 * completion handler will not be called.
	 */
	public static final CompletionCheck COMPLETE_READ = COMPLETE_WRITE;

	public <A> CompletionState read(BlockingMode block, long timeout, TimeUnit unit, A attachment,
			CompletionCheck check, CompletionHandler<Long, ? super A> handler, ByteBuffer... dsts);

	public <A> CompletionState write(BlockingMode block, long timeout, TimeUnit unit, A attachment,
			CompletionCheck check, CompletionHandler<Long, ? super A> handler, ByteBuffer... srcs);

	public boolean isClosed();

	public void close();

}
