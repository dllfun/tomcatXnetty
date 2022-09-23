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

import java.io.IOException;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousByteChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.CompletionHandler;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.tomcat.util.buf.ByteBufferUtils;
import org.apache.tomcat.util.net.SocketWrapperBase.ByteBufferWrapper;

/**
 * Base class for a SocketChannel wrapper used by the endpoint. This way, logic
 * for an SSL socket channel remains the same as for a non SSL, making sure we
 * don't need to code for any exception cases.
 */
public class Nio2Channel extends SocketBufferHandler {// implements AsynchronousByteChannel

	protected static final ByteBuffer emptyBuf = ByteBuffer.allocate(0);

	// protected final SocketBufferHandler socketBufferHandler;
	private AsynchronousSocketChannel socketChannel = null;
	private SocketWrapperBase<Nio2Channel> socketWrapper = null;

	public Nio2Channel(int readBufferSize, int writeBufferSize, boolean direct) {
		super(readBufferSize, writeBufferSize, direct);
	}

	/**
	 * Reset the channel.
	 *
	 * @param channel       The new async channel to associate with this NIO2
	 *                      channel
	 * @param socketWrapper The new socket to associate with this NIO2 channel
	 *
	 * @throws IOException If a problem was encountered resetting the channel
	 */
	public void reset(AsynchronousSocketChannel channel, SocketWrapperBase<Nio2Channel> socketWrapper)
			throws IOException {
		this.socketChannel = channel;
		this.socketWrapper = socketWrapper;
		super.reset();
	}

	public AsynchronousSocketChannel getSocketChannel() {
		return socketChannel;
	}

	public SocketWrapperBase<Nio2Channel> getSocketWrapper() {
		return socketWrapper;
	}

	/**
	 * Free the channel memory
	 */
	public void free() {
		super.free();
	}

	// SocketWrapperBase<Nio2Channel> getSocketWrapper() {
	// return socketWrapper;
	// }

	/**
	 * Closes this channel.
	 *
	 * @throws IOException If an I/O error occurs
	 */
	// @Override
	public void close() throws IOException {
		socketChannel.close();
	}

	/**
	 * Close the connection.
	 *
	 * @param force Should the underlying socket be forcibly closed?
	 *
	 * @throws IOException If closing the secure channel fails.
	 */
	public void close(boolean force) throws IOException {
		if (isOpen() || force) {
			close();
		}
	}

	/**
	 * Tells whether or not this channel is open.
	 *
	 * @return <code>true</code> if, and only if, this channel is open
	 */
	// @Override
	public boolean isOpen() {
		return socketChannel.isOpen();
	}

	// protected SocketBufferHandler getBufHandler() {
	// return socketBufferHandler;
	// }

	public AsynchronousSocketChannel getIOChannel() {
		return socketChannel;
	}

	public boolean isClosing() {
		return false;
	}

	public boolean isHandshakeComplete() {
		return true;
	}

	/**
	 * Performs SSL handshake hence is a no-op for the non-secure implementation.
	 *
	 * @return Always returns zero
	 *
	 * @throws IOException Never for non-secure channel
	 */
	public int handshake() throws IOException {
		return 0;
	}

	@Override
	public String toString() {
		return super.toString() + ":" + socketChannel.toString();
	}

	// @Override
	public Future<Integer> read(ByteBufferWrapper dst) {
		return socketChannel.read(dst.getByteBuffer());
	}

	// @Override
	public <A> void read(ByteBufferWrapper dst, A attachment, CompletionHandler<Integer, ? super A> handler) {
		read(dst, 0L, TimeUnit.MILLISECONDS, attachment, handler);
	}

	protected <A> void read(ByteBufferWrapper dst, long timeout, TimeUnit unit, A attachment,
			CompletionHandler<Integer, ? super A> handler) {
		if (!dst.isWriteMode()) {
			throw new RuntimeException();
		}
		socketChannel.read(dst.getByteBuffer(), timeout, unit, attachment, handler);
	}

	public <A> void read(ByteBufferWrapper[] dsts, int offset, int length, A attachment,
			CompletionHandler<Long, ? super A> handler) {
		read(dsts, offset, length, 0L, TimeUnit.MILLISECONDS, attachment, handler);
	}

	protected <A> void read(BufWrapper[] dsts, int offset, int length, long timeout, TimeUnit unit, A attachment,
			CompletionHandler<Long, ? super A> handler) {
		ByteBuffer[] buffers = new ByteBuffer[dsts.length];
		for (int i = 0; i < dsts.length; i++) {
			if (!dsts[i].isWriteMode()) {
				throw new RuntimeException();
			}
			buffers[i] = ((ByteBufferWrapper) dsts[i]).getByteBuffer();
		}
		socketChannel.read(buffers, offset, length, timeout, unit, attachment, handler);
	}

	// @Override
	public Future<Integer> write(ByteBufferWrapper src) {
		if (!src.isReadMode()) {
			throw new RuntimeException();
		}
		return socketChannel.write(src.getByteBuffer());
	}

	// @Override
	public <A> void write(ByteBufferWrapper src, A attachment, CompletionHandler<Integer, ? super A> handler) {
		write(src, 0L, TimeUnit.MILLISECONDS, attachment, handler);
	}

	public <A> void write(BufWrapper src, long timeout, TimeUnit unit, A attachment,
			CompletionHandler<Integer, ? super A> handler) {
		if (!src.isReadMode()) {
			throw new RuntimeException();
		}
		socketChannel.write(((ByteBufferWrapper) src).getByteBuffer(), timeout, unit, attachment, handler);
	}

	public <A> void write(BufWrapper[] srcs, int offset, int length, long timeout, TimeUnit unit, A attachment,
			CompletionHandler<Long, ? super A> handler) {
		ByteBuffer[] buffers = new ByteBuffer[srcs.length];
		for (int i = 0; i < srcs.length; i++) {
			if (!srcs[i].isReadMode()) {
				throw new RuntimeException();
			}
			buffers[i] = ((ByteBufferWrapper) srcs[i]).getByteBuffer();
		}
		socketChannel.write(buffers, offset, length, timeout, unit, attachment, handler);
	}

	private static final Future<Boolean> DONE = new Future<Boolean>() {
		@Override
		public boolean cancel(boolean mayInterruptIfRunning) {
			return false;
		}

		@Override
		public boolean isCancelled() {
			return false;
		}

		@Override
		public boolean isDone() {
			return true;
		}

		@Override
		public Boolean get() throws InterruptedException, ExecutionException {
			return Boolean.TRUE;
		}

		@Override
		public Boolean get(long timeout, TimeUnit unit)
				throws InterruptedException, ExecutionException, TimeoutException {
			return Boolean.TRUE;
		}
	};

	public Future<Boolean> flush() {
		return DONE;
	}

	// private ApplicationBufferHandler appReadBufHandler;

	// public void setAppReadBufHandler(ApplicationBufferHandler handler) {
	// this.appReadBufHandler = handler;
	// }

	// protected ApplicationBufferHandler getAppReadBufHandler() {
	// return appReadBufHandler;
	// }

	private static final Future<Integer> DONE_INT = new Future<Integer>() {
		@Override
		public boolean cancel(boolean mayInterruptIfRunning) {
			return false;
		}

		@Override
		public boolean isCancelled() {
			return false;
		}

		@Override
		public boolean isDone() {
			return true;
		}

		@Override
		public Integer get() throws InterruptedException, ExecutionException {
			return Integer.valueOf(-1);
		}

		@Override
		public Integer get(long timeout, TimeUnit unit)
				throws InterruptedException, ExecutionException, TimeoutException {
			return Integer.valueOf(-1);
		}
	};

	static final Nio2Channel CLOSED_NIO2_CHANNEL = new Nio2Channel(0, 0, false) {

		@Override
		public void expand(int newSize) {
		}

		@Override
		public void close() throws IOException {
		}

		@Override
		public boolean isOpen() {
			return false;
		}

		@Override
		public void reset(AsynchronousSocketChannel channel, SocketWrapperBase<Nio2Channel> socket) throws IOException {
		}

		@Override
		public void free() {
		}

		// @Override
		// public void setAppReadBufHandler(ApplicationBufferHandler handler) {
		// }

		@Override
		public Future<Integer> read(ByteBufferWrapper dst) {
			return DONE_INT;
		}

		@Override
		public <A> void read(ByteBufferWrapper dst, long timeout, TimeUnit unit, A attachment,
				CompletionHandler<Integer, ? super A> handler) {
			handler.failed(new ClosedChannelException(), attachment);
		}

		@Override
		public <A> void read(BufWrapper[] dsts, int offset, int length, long timeout, TimeUnit unit, A attachment,
				CompletionHandler<Long, ? super A> handler) {
			handler.failed(new ClosedChannelException(), attachment);
		}

		@Override
		public Future<Integer> write(ByteBufferWrapper src) {
			return DONE_INT;
		}

		@Override
		public <A> void write(BufWrapper src, long timeout, TimeUnit unit, A attachment,
				CompletionHandler<Integer, ? super A> handler) {
			handler.failed(new ClosedChannelException(), attachment);
		}

		@Override
		public <A> void write(BufWrapper[] srcs, int offset, int length, long timeout, TimeUnit unit, A attachment,
				CompletionHandler<Long, ? super A> handler) {
			handler.failed(new ClosedChannelException(), attachment);
		}

		@Override
		public String toString() {
			return "Closed Nio2Channel";
		}
	};

}
