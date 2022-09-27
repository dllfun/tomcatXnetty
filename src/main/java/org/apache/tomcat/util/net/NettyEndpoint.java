package org.apache.tomcat.util.net;

import static java.lang.Integer.MAX_VALUE;

import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.CompletionHandler;
import java.nio.channels.SelectionKey;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLEngineResult.Status;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.compat.JreCompat;
import org.apache.tomcat.util.net.SocketWrapperBase.ByteBufferWrapper;
import org.apache.tomcat.util.net.TLSClientHelloExtractor.ExtractorResult;
import org.apache.tomcat.util.net.openssl.ciphers.Cipher;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.ByteToMessageDecoder.Cumulator;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;

public class NettyEndpoint extends AbstractJsseEndpoint<io.netty.channel.Channel, io.netty.channel.Channel> {

	private static final Log logger = LogFactory.getLog(NettyEndpoint.class);

	// private Map<String, Channel<Object>> channels; // <ip:port, channel>

	private ServerBootstrap bootstrap;

	private ServerSocketChannel channel;

	private EventLoopGroup bossGroup;
	private EventLoopGroup workerGroup;

	public NettyEndpoint() {

	}

	@Override
	protected InetSocketAddress getLocalAddress() throws IOException {
		if (channel == null) {
			return null;
		}
		return channel.localAddress();
	}

	@Override
	protected boolean getDeferAccept() {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public void bind() throws Exception {
		// TODO Auto-generated method stub
		// Initialize SSL if needed
		initialiseSsl();
	}

	@Override
	public void unbind() throws Exception {
		// TODO Auto-generated method stub
		destroySsl();
		super.unbind();
	}

	@Override
	public void startInternal() throws Exception {

		bootstrap = new ServerBootstrap();
		if (Epoll.isAvailable()) {
			bootstrap.channel(EpollServerSocketChannel.class);
			bootstrap.option(ChannelOption.SO_BACKLOG, 1024);
			// .childOption(ChannelOption.SO_LINGER, 0)
			bootstrap.childOption(ChannelOption.SO_REUSEADDR, true);
			bootstrap.childOption(ChannelOption.SO_KEEPALIVE, true);
			logger.info("epoll init");
			bossGroup = new EpollEventLoopGroup(1, new DefaultThreadFactory("NettyServerBoss", true));
			workerGroup = new EpollEventLoopGroup(2, new DefaultThreadFactory("NettyServerWorker", true));
		} else {
			bootstrap.channel(NioServerSocketChannel.class);
			logger.info("not epoll init");
			bossGroup = new NioEventLoopGroup(1, new DefaultThreadFactory("NettyServerBoss", true));
			workerGroup = new NioEventLoopGroup(2, new DefaultThreadFactory("NettyServerWorker", true));
		}

		// final NettyServerHandler nettyServerHandler = new
		// NettyServerHandler(getUrl(), this);
		// channels = nettyServerHandler.getChannels();

		// NettyTomcatHandler nettyTomcatHandler = new NettyTomcatHandler();

		bootstrap.group(bossGroup, workerGroup).option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
				.childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
				.childOption(ChannelOption.TCP_NODELAY, true).handler(new ChannelInitializer<ServerSocketChannel>() {
					@Override
					protected void initChannel(ServerSocketChannel ch) throws Exception {

						// ch.config().setAutoRead(false);
						// ch.pipeline().addLast(new HttpResponseEncoder());
						// ch.pipeline().addLast(new ReadTimeoutHandler(5, TimeUnit.SECONDS));
						// ch.pipeline().addLast(new WriteTimeoutHandler(5, TimeUnit.SECONDS));
						ch.pipeline().addLast(new BossHandler());
						// ch.read();

					}
				}).childHandler(new ChannelInitializer<SocketChannel>() {
					@Override
					protected void initChannel(SocketChannel ch) throws Exception {
						if (isSSLEnabled()) {
							ch.pipeline().addFirst(new NettySslHandler());
						}
						// ch.config().setAutoRead(false);
						// ch.pipeline().addLast(new HttpResponseEncoder());
						// ch.pipeline().addLast(new ReadTimeoutHandler(5, TimeUnit.SECONDS));
						// ch.pipeline().addLast(new WriteTimeoutHandler(5, TimeUnit.SECONDS));
						ch.pipeline().addLast(new NettyTomcatHandler());
						// ch.read();

					}
				});
		// bind
		InetSocketAddress addr = new InetSocketAddress(getAddress(), getPortWithOffset());
		ChannelFuture channelFuture = bootstrap.bind(addr);
		channelFuture.syncUninterruptibly();
		channel = (ServerSocketChannel) channelFuture.channel();
	}

	@Override
	public void stopInternal() throws Exception {
		try {
			if (channel != null) {
				// unbind.
				channel.close();
			}
		} catch (Throwable e) {
			logger.warn(e.getMessage(), e);
		}
		try {
			if (bootstrap != null) {
				bossGroup.shutdownGracefully();
				workerGroup.shutdownGracefully();
			}
		} catch (Throwable e) {
			logger.warn(e.getMessage(), e);
		}
		try {
			if (connections != null) {
				connections.clear();
			}
		} catch (Throwable e) {
			logger.warn(e.getMessage(), e);
		}
	}

	@Override
	protected void pauseInternal() {
		// TODO Auto-generated method stub

	}

	@Override
	protected Log getLog() {
		return logger;
	}

	@Override
	protected void doCloseServerSocket() throws IOException {
		try {
			if (channel != null) {
				// unbind.
				channel.close();
			}
		} catch (Throwable e) {
			logger.warn(e.getMessage(), e);
		}
		try {
			if (bootstrap != null) {
				bossGroup.shutdownGracefully();
				workerGroup.shutdownGracefully();
			}
		} catch (Throwable e) {
			logger.warn(e.getMessage(), e);
		}
		try {
			if (connections != null) {
				connections.clear();
			}
		} catch (Throwable e) {
			logger.warn(e.getMessage(), e);
		}
	}

	NettyChannel getOrAddChannel(SocketChannel channel) {
		if (channel == null) {
			return null;
		}
		NettyChannel ret = (NettyChannel) connections.get(channel);
		if (ret == null) {
			NettyChannel nettyChannel = new NettyChannel(channel, this);
			if (channel.isActive()) {
				ret = (NettyChannel) connections.putIfAbsent(channel, nettyChannel);
			}
			if (ret == null) {
				ret = nettyChannel;
			}
		}
		return ret;
	}

	NettyChannel removeChannel(SocketChannel channel) {
		NettyChannel ret = (NettyChannel) connections.remove(channel);
		return ret;
	}

	private static class ByteBufWrapper implements BufWrapper {

//		private NettyChannel channel;

		private int readCount = 0;

		protected volatile ByteBuf delegate = Unpooled.EMPTY_BUFFER;

		private volatile int mainRefCount = 0;

		private volatile AtomicInteger sliceRefCount = new AtomicInteger(0);

		private boolean trace = false;

		private boolean readMode = true;

		public ByteBufWrapper() {
			super();
//			this.channel = channel;
		}

		public ByteBufWrapper(ByteBuf delegate) {
//			this(channel);
			this.delegate = delegate;
			this.mainRefCount = delegate.refCnt();
		}

		public void setDelegate(ByteBuf delegate) {
			this.delegate = delegate;
		}

		// protected void releaseBuf() {
		// delegate.release();
		// }

		@Override
		public void switchToWriteMode(boolean compact) {
			if (readMode) {
				if (compact) {
					if (delegate.readerIndex() == delegate.writerIndex() && delegate.readerIndex() > 0) {
//						delegate.discardReadBytes();
						int refCnt = refCount();
						while (refCnt-- > 0) {
							delegate.release();
						}
						delegate = Unpooled.EMPTY_BUFFER;
					} else {
//					int capacity = delegate.capacity();
//						delegate.discardReadBytes();
//					if (capacity != delegate.capacity()) {
//						System.err.println("capacity changed after compact");
//					}
					}
				}
//				if (compact && delegate.readerIndex() > 0) {
//					if (!delegate.isReadable() && !delegate.isWritable()) {
//						// 正常情况
//						if (channel != null) {
//							int capacity = delegate.capacity();
//							releaseDelegate();
//							if (NettyEndpoint.debug) {
//								System.out.println("delegate.release();");
//							}
//							delegate = channel.channel.alloc().buffer(capacity);
//							System.out.println(this + " delegate after compact:" + delegate);
//						} else {
//							delegate.discardReadBytes();
//						}
//					} else {
//						if (delegate.isReadable()) {
//							System.err.println(channel.getRemotePort() + " byteBuf还有未读数据");
//						}
//						// delegate.readerIndex(delegate.writerIndex());
//						delegate.discardReadBytes();
//					}
//				}
				readMode = false;
			}
		}

		@Override
		public void switchToWriteMode() {
			switchToWriteMode(true);
		}

		@Override
		public void setRetain(int retain) {
			throw new RuntimeException();
		}

		@Override
		public void clearRetain() {
			throw new RuntimeException();
		}

		@Override
		public int getRetain() {
			throw new RuntimeException();
		}

		@Override
		public boolean isWriteMode() {
			return !readMode;
		}

		@Override
		public void switchToReadMode() {
			if (!readMode) {
				readMode = true;
			}
		}

		@Override
		public boolean isReadMode() {
			return readMode;
		}

		@Override
		public boolean reuseable() {
			return false;
		}

		@Override
		public int getLimit() {
			if (readMode) {
				return delegate.writerIndex();
			} else {
				return delegate.capacity();
			}
		}

		@Override
		public void setLimit(int limit) {
			if (trace) {
				System.out.println("setLimit");
			}
			if (readMode) {
				delegate.writerIndex(limit);
			} else {
				delegate.capacity(limit);
//				if (delegate.capacity() < limit) {
//					ByteBuf buf = channel.channel.alloc().buffer(limit);
//					buf.writeBytes(delegate);
//					releaseDelegate();
//					if (NettyEndpoint.debug) {
//						System.err.println(this + " set Limit release");
//					}
//					delegate = buf;
//					System.out.println(this + " delegate changed");
//				} else if (delegate.capacity() == limit) {
//
//				} else {
//					delegate.capacity(limit);
//				}
			}
		}

		@Override
		public byte getByte() {
			if (!readMode) {
				throw new RuntimeException();
			}
			if (trace) {
				System.out.println("getByte");
			}
			return delegate.readByte();
		}

		@Override
		public void getBytes(byte[] b, int off, int len) {
			if (!readMode) {
				throw new RuntimeException();
			}
			if (trace) {
				System.out.println("getByte b");
			}
			if (delegate.refCnt() == 0) {
				throw new RuntimeException();
			}
			delegate.readBytes(b, off, len);
		}

		@Override
		public byte getByte(int index) {
			if (!readMode) {
				throw new RuntimeException();
			}
			return delegate.getByte(index);
		}

		@Override
		public ByteBufWrapper getSlice(int len) {
			if (!readMode) {
				throw new RuntimeException();
			}
			if (delegate == Unpooled.EMPTY_BUFFER) {
				throw new RuntimeException();
			}
			ByteBuf slice = delegate.slice(delegate.readerIndex(), len);
			delegate.readerIndex(delegate.readerIndex() + len);
			return new SlicedByteBufWrapper(slice, this.sliceRefCount);
		}

		@Override
		public BufWrapper getRetaindSlice(int len) {
			ByteBufWrapper slice = getSlice(len);
			slice.retain();
			return slice;
		}

		@Override
		public int getPosition() {
			if (readMode) {
				return delegate.readerIndex();
			} else {
				return delegate.writerIndex();
			}
		}

		@Override
		public void setPosition(int position) {
			if (trace) {
				System.out.println("setPosition");
			}
			if (readMode) {
				delegate.readerIndex(position);
			} else {
				delegate.writerIndex(position);
			}
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
		public int getArrayOffset() {
			return delegate.arrayOffset();
		}

		@Override
		public boolean isDirect() {
			return delegate.isDirect();
		}

		@Override
		public boolean isEmpty() {
			return delegate.readerIndex() == delegate.writerIndex();
		}

		@Override
		public boolean isWritable() {
			return delegate.writableBytes() > 0;
		}

		@Override
		public int getRemaining() {
			if (readMode) {
				return delegate.writerIndex() - delegate.readerIndex();
			} else {
				return delegate.capacity() - delegate.writerIndex();
			}
		}

		@Override
		public boolean hasRemaining() {
			if (readMode) {
				return delegate.writerIndex() > delegate.readerIndex();
			} else {
				return delegate.capacity() > delegate.writerIndex();
			}
		}

		@Override
		public boolean hasNoRemaining() {
			if (readMode) {
				return delegate.writerIndex() <= delegate.readerIndex();
			} else {
				return delegate.capacity() <= delegate.writerIndex();
			}
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
			if (trace) {
				System.out.println("setByte");
			}
			delegate.setByte(index, b);
		}

		@Override
		public void putByte(byte b) {
			if (readMode) {
				throw new RuntimeException();
			}
			delegate.writeByte(b);
		}

		@Override
		public void putBytes(byte[] b) {
			if (readMode) {
				throw new RuntimeException();
			}
			delegate.writeBytes(b);
		}

		@Override
		public void putBytes(byte[] b, int off, int len) {
			if (readMode) {
				throw new RuntimeException();
			}
			delegate.writeBytes(b, off, len);
		}

		@Override
		public void putBytes(ByteBuffer byteBuffer) {
			if (readMode) {
				throw new RuntimeException();
			}
			delegate.writeBytes(byteBuffer);
		}

		@Override
		public int transferTo(BufWrapper to) {
			if (!this.isReadMode() || this.hasNoRemaining()) {
				throw new RuntimeException();
			}
			if (!to.isWriteMode() || to.hasNoRemaining()) {
				throw new RuntimeException();
			}
			int len = Math.min(to.getRemaining(), this.getRemaining());
			if (len > 0) {
//				int oldLimit = this.getLimit();
//				this.setLimit(this.getPosition() + max);
				if (to instanceof ByteBufferWrapper) {
					if (len < this.getRemaining()) {
						ByteBufWrapper slice = this.getSlice(len);
						((ByteBufferWrapper) to).getByteBuffer().put(slice.delegate.nioBuffer());
					} else {
						((ByteBufferWrapper) to).getByteBuffer().put(this.delegate.nioBuffer());
						this.delegate.skipBytes(len);
					}
				} else if (to instanceof ByteBufWrapper) {
					if (len < this.getRemaining()) {
						ByteBufWrapper slice = this.getSlice(len);
						((ByteBufWrapper) to).delegate.writeBytes(slice.delegate);
					} else {
						((ByteBufWrapper) to).delegate.writeBytes(this.delegate);
					}
				} else {
					throw new RuntimeException();
				}

//				this.setLimit(oldLimit);
			}
			return len;
		}

		@Override
		public void clearWrite() {
			delegate.writerIndex(delegate.readerIndex());
		}

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
//			if (trace) {
//				System.out.println("doRead");
//			}
//			if (hasNoRemaining()) {
//				// byteBuf.release();
//				fill(true);
//			}
//			int length = delegate.writerIndex() - delegate.readerIndex();
//			handler.setBufWrapper(this.duplicate());//
//			delegate.readerIndex(delegate.writerIndex());
//			return length;
//		}

		@Override
		public void reset() {
			if (trace) {
				System.out.println("reset");
			}
			// TODO Auto-generated method stub
			// byteBuf.release();
			if (delegate.writerIndex() > delegate.capacity()) {
				System.out.println("writerIndex bigger then capacity");
			}
//			delegate.readerIndex(delegate.writerIndex());
			switchToWriteMode();
			delegate.writerIndex(0);
		}

		@Override
		public ByteBuffer nioBuffer() {
			if (trace) {
				System.out.println("nioBuffer");
			}
			return delegate.nioBuffer();
		}

		@Override
		public BufWrapper duplicate() {
			if (trace) {
				System.out.println("duplicate");
			}
			ByteBufWrapper bufWrapper = new ByteBufWrapper(delegate.duplicate());
//			bufWrapper.delegate = delegate.duplicate();
			return bufWrapper;
		}

		@Override
		public void startTrace() {
			// trace = true;
		}

		@Override
		public void stopTrace() {
			// TODO Auto-generated method stub

		}

		@Override
		public void retain() {
			if (delegate == Unpooled.EMPTY_BUFFER) {
				mainRefCount++;
			} else {
				retainDelegate();
				mainRefCount++;
			}
		}

		@Override
		public boolean released() {
			if (refCount() == 0) {
				return true;
			}
			return false;
		}

		@Override
		public void release() {
			if (delegate == Unpooled.EMPTY_BUFFER) {
				mainRefCount--;
				if (mainRefCount < 0) {
					throw new RuntimeException();
				}
				return;
			} else {
				mainRefCount--;
				if (delegate.isReadable()) {
					byte[] bytes = new byte[Math.min(delegate.readableBytes(), 100)];
					for (int i = 0; i < bytes.length; i++) {
						bytes[i] = delegate.readByte();
					}
//					System.out.println("释放buffer时还有未读数据：" + new String(bytes));
				}
				releaseDelegate();
			}
//			delegate = Unpooled.EMPTY_BUFFER;
		}

		@Override
		public int refCount() {
			if (delegate == Unpooled.EMPTY_BUFFER) {
				return mainRefCount;
			} else {
				if (mainRefCount + sliceRefCount.get() != delegate.refCnt()) {
					System.out.println("mainRefCount:" + mainRefCount + " sliceRefCount:" + sliceRefCount
							+ " delegate.refCnt():" + delegate.refCnt());
					throw new RuntimeException();
				}
				return mainRefCount;
			}
		}

		@Override
		public String printInfo() {
			StringBuffer sb = new StringBuffer();
			sb.append("\r\n");
			sb.append("isEmptyBuffer : " + (delegate == Unpooled.EMPTY_BUFFER) + "\t");
			sb.append("refCnt : " + delegate.refCnt() + "\t");
			sb.append("readerIndex : " + delegate.readerIndex() + "\t");
			sb.append("writerIndex : " + delegate.writerIndex() + "\t");
			sb.append("hashCode : " + delegate.hashCode() + "\t");
//			sb.append("nioBuffer : " + delegate.nioBuffer());
			return sb.toString();
		}

		@Override
		public void expand(int newSize) {
			// NO-OP
		}

		private void retainDelegate() {
			delegate.retain();
		}

		private void releaseDelegate() {
			if (mainRefCount > 0) {
				delegate.release();
			} else if (mainRefCount == 0) {
				if (sliceRefCount.get() + 1 != delegate.refCnt()) {
					throw new RuntimeException();
				}
				delegate.release();
				delegate = Unpooled.EMPTY_BUFFER;
				sliceRefCount = new AtomicInteger(0);
			} else {
				throw new RuntimeException();
			}
		}

	}

	private static class SlicedByteBufWrapper extends ByteBufWrapper {

		private final AtomicInteger sliceRefCount;

		public SlicedByteBufWrapper(ByteBuf delegate, AtomicInteger sliceRefCount) {
			super(delegate);
			this.sliceRefCount = sliceRefCount;
		}

		@Override
		public void retain() {
			delegate.retain();
			sliceRefCount.incrementAndGet();
		}

		@Override
		public int refCount() {
			return delegate.refCnt();
		}

		@Override
		public boolean released() {
			return delegate.refCnt() == 0;
		}

		@Override
		public void release() {
			delegate.release();
			sliceRefCount.decrementAndGet();
		}

	}

	private static class TimedWrapper {

		private ByteBuf byteBuf;

		private long createTime;

		public TimedWrapper(ByteBuf byteBuf) {
			super();
			this.byteBuf = byteBuf;
			this.createTime = System.currentTimeMillis();
		}

		public ByteBuf getByteBuf() {
			return byteBuf;
		}

		public long getCreateTime() {
			return createTime;
		}

	}

	public static class NettyChannel extends AbstractSocketChannel<io.netty.channel.Channel> {

		/**
		 * Cumulate {@link ByteBuf}s.
		 */
		public interface Cumulator {
			/**
			 * Cumulate the given {@link ByteBuf}s and return the {@link ByteBuf} that holds
			 * the cumulated bytes. The implementation is responsible to correctly handle
			 * the life-cycle of the given {@link ByteBuf}s and so call
			 * {@link ByteBuf#release()} if a {@link ByteBuf} is fully consumed.
			 */
			void cumulate(ByteBufAllocator alloc, ByteBufWrapper cumulation, ByteBuf in);
		}

		/**
		 * Cumulate {@link ByteBuf}s by merge them into one {@link ByteBuf}'s, using
		 * memory copies.
		 */
		public static final Cumulator MERGE_CUMULATOR = new Cumulator() {

			// @Override
			public void cumulate(ByteBufAllocator alloc, ByteBufWrapper cumulation, ByteBuf in) {
//				if (cumulation.readerIndex() == 0 && cumulation.writerIndex() == 0 && in.isContiguous()) {
//					// If cumulation is empty and input buffer is contiguous, use it directly
//					int refCnt = cumulation.refCnt();
//					while (cumulation.refCnt() > 0) {
//						cumulation.release();
//					}
//					in.retain(refCnt - 1);
//					return in;
//				}
				try {
					final int required = in.readableBytes();
					if (required > cumulation.delegate.maxWritableBytes()
							|| (required > cumulation.delegate.maxFastWritableBytes()
									&& cumulation.delegate.refCnt() > 1)
							|| cumulation.delegate.isReadOnly()) {
						// Expand cumulation (by replacing it) under the following conditions:
						// - cumulation cannot be resized to accommodate the additional data
						// - cumulation can be expanded with a reallocation operation to accommodate but
						// the buffer is
						// assumed to be shared (e.g. refCnt() > 1) and the reallocation may not be
						// safe.
						expandCumulation(alloc, cumulation, in);
						return;
					}
					cumulation.delegate.writeBytes(in, in.readerIndex(), required);
					in.readerIndex(in.writerIndex());
				} finally {
					// We must release in in all cases as otherwise it may produce a leak if
					// writeBytes(...) throw
					// for whatever release (for example because of OutOfMemoryError)
					in.release();
				}
			}

			void expandCumulation(ByteBufAllocator alloc, ByteBufWrapper cumulation, ByteBuf in) {
				int refCnt = cumulation.mainRefCount;
				int readerIndex = cumulation.delegate.readerIndex();
				int writerIndex = cumulation.delegate.writerIndex();
				int newBytes = in.readableBytes();
				int totalBytes = writerIndex + newBytes;
				ByteBuf newDelegate = alloc.buffer(alloc.calculateNewCapacity(totalBytes, MAX_VALUE));
				ByteBuf toRelease = newDelegate;
				try {
					// This avoids redundant checks and stack depth compared to calling
					// writeBytes(...)
					newDelegate.setBytes(0, cumulation.delegate, 0, writerIndex)
							.setBytes(writerIndex, in, in.readerIndex(), newBytes).writerIndex(totalBytes)
							.readerIndex(readerIndex);
					in.readerIndex(in.writerIndex());
					toRelease = cumulation.delegate;
					if (refCnt > 1) {
						newDelegate.retain(refCnt - 1);
					}
					cumulation.sliceRefCount = new AtomicInteger(0);
					cumulation.delegate = newDelegate;
				} finally {
					while (refCnt-- > 0) {
						toRelease.release();
					}
				}
			}
		};

		/**
		 * Cumulate {@link ByteBuf}s by add them to a {@link CompositeByteBuf} and so do
		 * no memory copy whenever possible. Be aware that {@link CompositeByteBuf} use
		 * a more complex indexing implementation so depending on your use-case and the
		 * decoder implementation this may be slower then just use the
		 * {@link #MERGE_CUMULATOR}.
		 */
		public static final Cumulator COMPOSITE_CUMULATOR = new Cumulator() {
			@Override
			public void cumulate(ByteBufAllocator alloc, ByteBufWrapper cumulation, ByteBuf in) {
//				if (!cumulation.isReadable()) {
//					cumulation.release();
//					return in;
//				}
				CompositeByteBuf composite = null;
				try {
					if (cumulation.delegate instanceof CompositeByteBuf && cumulation.delegate.refCnt() == 1) {
						composite = (CompositeByteBuf) cumulation.delegate;
						// Writer index must equal capacity if we are going to "write"
						// new components to the end
						if (composite.writerIndex() != composite.capacity()) {
							composite.capacity(composite.writerIndex());
						}
					} else {
						composite = alloc.compositeBuffer(Integer.MAX_VALUE).addFlattenedComponents(true,
								cumulation.delegate);
					}
					composite.addFlattenedComponents(true, in);
					in = null;
					cumulation.sliceRefCount = new AtomicInteger(0);
					cumulation.delegate = composite;
				} finally {
					if (in != null) {
						// We must release if the ownership was not transferred as otherwise it may
						// produce a leak
						in.release();
						// Also release any new buffer allocated if we're not returning it
						if (composite != null && composite != cumulation.delegate) {
							composite.release();
						}
					}
				}
			}
		};

		private SocketChannel socketChannel;

		private NettyEndpoint endpoint;

		private final ReentrantLock dequeLock = new ReentrantLock();

		private final Condition notEmpty = dequeLock.newCondition();

		private final Deque<ByteBufWrapper> deque = new ArrayDeque<>();;

		private ByteBufWrapper appReadBuffer = new ByteBufWrapper();

		private volatile boolean needDispatchRead = true;

		private final ReentrantLock writableLock = new ReentrantLock();

		private volatile boolean needDispatchWrite = false;

		private volatile IOException exception = null;

		protected SSLEngine sslEngine;

		private volatile long writeUseTotalTime = 0;

		private volatile ChannelFuture lastWriteFuture;

		public NettyChannel(SocketChannel socketChannel, NettyEndpoint endpoint) {
			super(socketChannel, endpoint);
			this.socketChannel = socketChannel;
			this.endpoint = endpoint;
		}

		protected void offer(ByteBufWrapper byteBuf) {
			if (byteBuf.released()) {
				throw new RuntimeException();
			}
			if (!byteBuf.isReadMode()) {
				throw new RuntimeException();
			}
			if (byteBuf.getRemaining() == 0) {
				throw new RuntimeException();
			}
			try {
				dequeLock.lock();
//				for (BufWrapper bufWrapper : deque) {
//					if (bufWrapper.hasNoRemaining()) {
//						System.err.println("has bug here");
//					}
//				}
				deque.offer(byteBuf);
				if (needDispatchRead) {
					needDispatchRead = false;
					if (registeReadTimeStamp != -1) {
						long useTime = System.currentTimeMillis() - registeReadTimeStamp;
						awaitReadTime += useTime;
//						System.out.println(getRemotePort() + " await read use " + useTime + " total wait use "
//								+ awaitReadTime + " count " + registeReadCount);
					}
					startProcessTimeStamp = System.currentTimeMillis();
//					System.out.println(nettyChannel.getRemotePort() + " " + "processSocketRead");
					endpoint.getHandler().processSocket(this, SocketEvent.OPEN_READ, true);
				} else {
					notEmpty.signalAll();
				}
			} finally {
				dequeLock.unlock();
			}
		}

		protected void setIOException(IOException exception) {
			try {
				dequeLock.lock();
				this.exception = exception;
				notEmpty.signalAll();
			} finally {
				dequeLock.unlock();
			}
		}

		protected boolean dispatchReadIfEmpty() {
			try {
				dequeLock.lock();
				boolean empty = deque.isEmpty();
				if (empty) {
					this.needDispatchRead = true;
				}
				return empty;
			} finally {
				dequeLock.unlock();
			}
		}

		protected boolean isDequeEmpty() {
			try {
				dequeLock.lock();
				boolean empty = deque.isEmpty();
				return empty;
			} finally {
				dequeLock.unlock();
			}
		}

		protected boolean needDispatchRead() {
			boolean needDispatch = this.needDispatchRead;
			if (needDispatch) {
				this.needDispatchRead = false;
			}
			return needDispatch;
		}

		protected void releaseBuf() {
			try {
				dequeLock.lock();
				BufWrapper byteBuf = null;
				while ((byteBuf = deque.pollLast()) != null) {
					byteBuf.release();
				}
			} finally {
				dequeLock.unlock();
			}
			if (!appReadBuffer.released()) {
				appReadBuffer.release();
//				System.out.println(getRemotePort() + " " + appReadBuffer + " released by channel!" + " info:"
//						+ appReadBuffer.printInfo());
			}
		}

		@Override
		public void initAppReadBuffer(int headerBufferSize) {
			// TODO Auto-generated method stub
			if (appReadBuffer.delegate == Unpooled.EMPTY_BUFFER) {
				if (appReadBuffer.mainRefCount != 0) {
					throw new RuntimeException();
				}
				appReadBuffer.mainRefCount++;
			} else {
				if (appReadBuffer.mainRefCount != appReadBuffer.delegate.refCnt() || appReadBuffer.mainRefCount != 1) {
					throw new RuntimeException();
				}
			}
		}

		@Override
		public BufWrapper getAppReadBuffer() {
			return appReadBuffer;
		}

		@Override
		public BufWrapper allocate(int size) {
			ByteBuf byteBuf = socketChannel.alloc().buffer(size);
			ByteBufWrapper byteBufWrapper = new ByteBufWrapper(byteBuf);
//			byteBufWrapper.delegate = byteBuf;
			return byteBufWrapper;
		}

//		@Override
//		public io.netty.channel.Channel getSocket() {
//			return channel;
//		}

		@Override
		protected void populateRemoteAddr() {
			InetSocketAddress ipSocket = socketChannel.remoteAddress();
			remoteAddr = ipSocket.getAddress().getHostAddress();
		}

		@Override
		protected void populateRemoteHost() {
			InetSocketAddress ipSocket = socketChannel.remoteAddress();
			remoteHost = ipSocket.getAddress().getHostName();
		}

		@Override
		protected void populateRemotePort() {
			InetSocketAddress ipSocket = socketChannel.remoteAddress();
			remotePort = ipSocket.getPort();
		}

		@Override
		protected void populateLocalName() {
			localName = socketChannel.localAddress().getAddress().getHostName();
		}

		@Override
		protected void populateLocalAddr() {
			localAddr = socketChannel.localAddress().getAddress().getHostAddress();
		}

		@Override
		protected void populateLocalPort() {
			localPort = socketChannel.localAddress().getPort();
		}

		@Override
		public int getAvailable() {
			try {
				dequeLock.lock();
				if (deque.isEmpty()) {
					return 0;
				} else {
					int available = 0;
					for (BufWrapper buf : deque) {
						available += buf.getRemaining();
						break;
					}
					return available;
				}
			} finally {
				dequeLock.unlock();
			}
		}

		@Override
		public boolean isReadyForRead() throws IOException {
			if (exception != null) {
				throw exception;
			}
			// TODO Auto-generated method stub
			return true;
		}

		@Override
		public boolean hasDataToRead() {
			// TODO Auto-generated method stub
			return true;
		}

		@Override
		public boolean hasDataToWrite() {
			// TODO Auto-generated method stub
			return !nonBlockingWriteBuffer.isEmpty();
		}

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
			// TODO Auto-generated method stub
			return socketChannel.isWritable() && nonBlockingWriteBuffer.isEmpty();
		}

		private ByteBufWrapper getFromQueue(boolean block) throws IOException {
			if (exception != null) {
				throw exception;
			}
			try {
				ByteBufWrapper byteBuf = null;
				dequeLock.lock();
				if (block) {
					long time = System.currentTimeMillis(); // start the timeout timer

					if (deque.isEmpty()) {
						if (getReadTimeout() > 0) {
							for (BufWrapper wrapper : deque) {
								if (wrapper.hasNoRemaining()) {
									System.err.println("has bug here");
								}
							}
							try {
								notEmpty.await(getReadTimeout(), TimeUnit.MICROSECONDS);
							} catch (InterruptedException e) {
								e.printStackTrace();
								throw new EOFException();
							}
						} else {
							for (BufWrapper wrapper : deque) {
								if (wrapper.hasNoRemaining()) {
									System.err.println("has bug here");
								}
							}
							try {
								notEmpty.await();
							} catch (InterruptedException e) {
								e.printStackTrace();
								throw new EOFException();
							}
						}
					}

					byteBuf = deque.poll();

					if (exception != null) {
						throw exception;
					}

					if (byteBuf == null) {
						if (getReadTimeout() >= 0) {
							boolean timedout = (System.currentTimeMillis() - time) >= getReadTimeout();
							if (timedout) {
								throw new SocketTimeoutException();
							}
						}
					}

				} else {
					byteBuf = deque.poll();
				}
				if (byteBuf != null) {
					if (byteBuf.refCount() != 1) {
						throw new RuntimeException();
					}
				}
				return byteBuf;
			} finally {
				dequeLock.unlock();
			}
		}

		@Override
		public int read(boolean block, byte[] b, int off, int len) throws IOException {

			if (exception != null) {
				throw exception;
			}
			// return byteBufWrapper.read(block, b, off, len);
			int read = 0;
			do {
				ByteBufWrapper byteBuf = getFromQueue(block);
				if (byteBuf == null) {
					return read;
				}

				int minLength = Math.min(byteBuf.getRemaining(), len);
				for (int index = 0; index < minLength; index++) {
					b[off + index] = byteBuf.getByte();
				}
				off += minLength;
				len -= minLength;
				read += minLength;
				if (byteBuf.getRemaining() > 0) {
					try {
						dequeLock.lock();
						deque.offerFirst(byteBuf);
					} finally {
						dequeLock.unlock();
					}
				} else {
					byteBuf.release();
				}
			} while (!isDequeEmpty() && len > 0);
			return read;
		}

		@Override
		public int read(boolean block, BufWrapper to) throws IOException {

			if (exception != null) {
				throw exception;
			}
			if (to instanceof ByteBufWrapper) {
				ByteBufWrapper byteBufWrapper = (ByteBufWrapper) to;
				int read = read(block, byteBufWrapper);
				return read;
			} else if (to instanceof ByteBufferWrapper) {
				ByteBufferWrapper byteBufferWrapper = (ByteBufferWrapper) to;
				int read = read(block, byteBufferWrapper);
				return read;
			} else {
				throw new RuntimeException();
			}
		}

		public int read(boolean block, ByteBufWrapper to) throws IOException {
			if (exception != null) {
				throw exception;
			}
			// return byteBufWrapper.read(block, to);
			int read = 0;
			do {
				ByteBufWrapper tempBuf = getFromQueue(block);
				if (tempBuf == null) {
					return read;
				}
				read += tempBuf.getRemaining();
				if (tempBuf.released()) {
					throw new RuntimeException();
				}
				if (tempBuf.hasNoRemaining()) {
					throw new RuntimeException();
				}
				if (to.delegate == null || (to.delegate.readerIndex() == 0 && to.delegate.writerIndex() == 0)) {
//				System.err.println("use buffer exchange");
					to.readCount++;
					int refCnt = to.mainRefCount;
					ByteBuf toRelease = to.delegate;
					to.sliceRefCount = new AtomicInteger(0);
					to.delegate = tempBuf.delegate;
					if (refCnt > 1) {
						to.delegate.retain(refCnt - 1);
					}
					if (toRelease == Unpooled.EMPTY_BUFFER) {

					} else {
						while (refCnt-- > 0) {
							toRelease.release();
						}
					}
					if (to.delegate.refCnt() == 0) {
						throw new RuntimeException();
					}
				} else {
//				System.err.println("use buffer cumulator");
					MERGE_CUMULATOR.cumulate(socketChannel.alloc(), to, tempBuf.delegate);
					to.readCount++;
					if (to.delegate.refCnt() == 0) {
						throw new RuntimeException();
					}
				}
			} while (!isDequeEmpty());
			return read;
		}

		// @Override
		protected int read(boolean block, ByteBufferWrapper to) throws IOException {

			if (exception != null) {
				throw exception;
			}
			// return byteBufWrapper.read(block, to);
			int read = 0;
			do {
				ByteBufWrapper byteBuf = getFromQueue(block);
				if (byteBuf == null) {
					return read;
				}

				int minLength = Math.min(byteBuf.getRemaining(), to.getRemaining());
				to.getByteBuffer().put(byteBuf.getSlice(minLength).nioBuffer());
				read += minLength;
				if (byteBuf.getRemaining() > 0) {
					try {
						dequeLock.lock();
						deque.offerFirst(byteBuf);
					} finally {
						dequeLock.unlock();
					}
				} else {
					byteBuf.release();
				}
			} while (!isDequeEmpty() && to.hasRemaining());
			return read;
		}

		@Override
		public void unRead(ByteBufferWrapper returnedInput) {
			if (returnedInput != null && returnedInput.getByteBuffer() != null) {
				returnedInput.switchToReadMode();
				if (returnedInput.hasRemaining()) {
					ByteBuf delegate = socketChannel.alloc().buffer(returnedInput.getRemaining());
					delegate.writeBytes(returnedInput.getByteBuffer());
					ByteBufWrapper byteBufWrapper = new ByteBufWrapper(delegate);
					byteBufWrapper.switchToReadMode();
					try {
						dequeLock.lock();
						deque.offerFirst(byteBufWrapper);
					} finally {
						dequeLock.unlock();
					}
				}
			}
		}

		// @Override
		// public void processSocket(SocketEvent socketStatus, boolean dispatch) {
		// endpoint.getHandler().processSocket(this, socketStatus, dispatch);
		// }

		@Override
		public boolean registerReadInterest() {
			// System.out.println("registe read");
			if (!dispatchReadIfEmpty()) {
//				System.out.println(getRemotePort() + " processSocketRead again");
//				endpoint.getHandler().processSocket(this, SocketEvent.OPEN_READ, true);
				return false;
			} else {
//				System.out.println(getRemotePort() + " registerReadInterest 处理时长:"
//						+ (System.currentTimeMillis() - startProcessTimeStamp));
//				socketChannel.read();
				registeReadTimeStamp = System.currentTimeMillis();
				registeReadCount++;
				return true;
			}
		}

		protected boolean dispatchWriteIfNotWriteable() {
			try {
				writableLock.lock();
				boolean writable = socketChannel.isWritable();
				if (!writable) {
					needDispatchWrite = true;
				} else {
					needDispatchWrite = false;
				}
				return !writable;
			} finally {
				writableLock.unlock();
			}

		}

		protected boolean needDispatchWrite() {
			try {
				writableLock.lock();
				boolean needDispatch = this.needDispatchWrite;
				if (needDispatch) {
					this.needDispatchWrite = false;
				}
				return needDispatch;
			} finally {
				writableLock.unlock();
			}
		}

		@Override
		public boolean registerWriteInterest() {
			if (!dispatchWriteIfNotWriteable()) {
				System.out.println(getRemotePort() + " processSocketWrite again");
				endpoint.getHandler().processSocket(this, SocketEvent.OPEN_WRITE, true);
				return true;
			} else {
				System.out.println(getRemotePort() + " registerWriteInterest");
				return true;
			}
		}

		@Override
		protected void writeBlocking(byte[] buf, int off, int len) throws IOException {
			if (exception != null) {
				throw exception;
			}

			ByteBuf byteBuf = socketChannel.alloc().buffer(len);
			byteBuf.writeBytes(buf, off, len);
			// System.out.println("write " + buf + " start");
			TimedWrapper wrapper = new TimedWrapper(byteBuf);
			ChannelFuture future = socketChannel.writeAndFlush(wrapper);
//			future.addListener(new GenericFutureListener<Future<? super Void>>() {

//				@Override
//				public void operationComplete(Future<? super Void> future) throws Exception {
			// TODO Auto-generated method stub
//					long useTime = System.currentTimeMillis() - wrapper.getCreateTime();
//					writeUseTotalTime += useTime;
//					System.out.println(getRemotePort() + " 写出TimedWrapper用时：" + useTime + " 总用时：" + writeUseTotalTime);
//				}
//			});

			try {
				lastWriteFuture = future;
				if (lastWriteFuture != null && !lastWriteFuture.isDone()) {
					lastWriteFuture.await();
				}
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}

			// System.out.println("write " + buf + " finish");
		}

		@Override
		protected void writeBlocking(BufWrapper from) throws IOException {
			if (exception != null) {
				throw exception;
			}
			if (from instanceof ByteBufWrapper) {
				ByteBufWrapper byteBufWrapper = (ByteBufWrapper) from;
				// System.out.println("write " + buf + " start");
				ByteBuf delegate = byteBufWrapper.delegate;
				if (from instanceof SlicedByteBufWrapper) {
					delegate.retain();
					System.err.println("slice retain");
				} else {
					if (byteBufWrapper.mainRefCount != 1) {
						throw new RuntimeException();
					}
					ByteBuf newDelegate = socketChannel.alloc().buffer(delegate.capacity());
//					if (delegate.refCnt() > 1) {
//						newDelegate.retain(delegate.refCnt() - 1);
//						while (delegate.refCnt() > 1) {
//							delegate.release();
//						}
//					}
					byteBufWrapper.delegate = newDelegate;
				}
				ChannelFuture future = socketChannel.writeAndFlush(new TimedWrapper(delegate));
				try {
					lastWriteFuture = future;
					if (lastWriteFuture != null && !lastWriteFuture.isDone()) {
						lastWriteFuture.await();
					}
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				// System.out.println("write " + buf + " finish");
			} else if (from instanceof ByteBufferWrapper) {
				// System.out.println("writeBlocking");
				ByteBuf byteBuf = socketChannel.alloc().buffer(from.getRemaining());
				byteBuf.writeBytes(((ByteBufferWrapper) from).getByteBuffer());
				// System.out.println("write " + byteBuf + " start");
				ChannelFuture future = socketChannel.writeAndFlush(new TimedWrapper(byteBuf));
				try {
					lastWriteFuture = future;
					if (lastWriteFuture != null && !lastWriteFuture.isDone()) {
						lastWriteFuture.await();
					}
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		}

		@Override
		protected void writeNonBlocking(byte[] buf, int off, int len) throws IOException {
			if (exception != null) {
				throw exception;
			}
			if (len > 0 && nonBlockingWriteBuffer.isEmpty() && socketChannel.isWritable()) {//
				if (!socketChannel.isWritable()) {
					System.err.println("not writeable but still write");
				}

				ByteBuf byteBuf = socketChannel.alloc().buffer(len);
				byteBuf.writeBytes(buf, off, len);
				ChannelFuture future = socketChannel.writeAndFlush(new TimedWrapper(byteBuf));

			} else {
				if (len > 0) {
					// Remaining data must be buffered
					nonBlockingWriteBuffer.add(buf, off, len);
				}
			}

		}

		@Override
		protected void writeNonBlocking(BufWrapper from) throws IOException {
			if (from.hasRemaining() && nonBlockingWriteBuffer.isEmpty() && socketChannel.isWritable()) {//
				writeNonBlockingInternal(from);
			}

			if (from.hasRemaining()) {
				// Remaining data must be buffered
				nonBlockingWriteBuffer.add(from);
			}
		}

		@Override
		protected void writeNonBlockingInternal(BufWrapper from) throws IOException {
			if (exception != null) {
				throw exception;
			}
			if (from instanceof ByteBufWrapper) {
				if (!socketChannel.isWritable()) {
					System.err.println("not writeable but still write");
				}

				ByteBufWrapper byteBufWrapper = (ByteBufWrapper) from;
				// System.out.println("write " + buf + " start");
				ByteBuf delegate = byteBufWrapper.delegate;
				if (from instanceof SlicedByteBufWrapper) {
					delegate.retain();
					System.err.println("slice retain");
				} else {
					if (byteBufWrapper.mainRefCount != 1) {
						throw new RuntimeException();
					}
					ByteBuf newDelegate = socketChannel.alloc().buffer(delegate.capacity());
//					if (delegate.refCnt() > 1) {
//						newDelegate.retain(delegate.refCnt() - 1);
//						while (delegate.refCnt() > 1) {
//							delegate.release();
//						}
//					}
					byteBufWrapper.delegate = newDelegate;
				}
				ChannelFuture future = socketChannel.writeAndFlush(new TimedWrapper(delegate));
				// System.out.println("write " + buf + " finish");
			} else if (from instanceof ByteBufferWrapper) {
				ByteBufferWrapper byteBufferWrapper = (ByteBufferWrapper) from;
				if (!socketChannel.isWritable()) {
					System.err.println("not writeable but still write");
				}

				ByteBuf byteBuf = socketChannel.alloc().buffer(from.getRemaining());
				byteBuf.writeBytes(((ByteBufferWrapper) from).getByteBuffer());
				ChannelFuture future = socketChannel.writeAndFlush(new TimedWrapper(byteBuf));
			}
		}

		@Override
		protected void flushBlocking() throws IOException {
			if (exception != null) {
				throw exception;
			}
			// System.out.println("channel.flush();");
			socketChannel.flush();
		}

		@Override
		protected boolean flushNonBlocking() throws IOException {
			if (exception != null) {
				throw exception;
			}
			// socketChannel.flush();
			boolean dataLeft = false;
			if (!nonBlockingWriteBuffer.isEmpty()) {
				dataLeft = nonBlockingWriteBuffer.write(this, false);
			}
			return dataLeft;
		}

		@Override
		protected void doClose() {
			try {
				if (lastWriteFuture != null && !lastWriteFuture.isDone()) {
					lastWriteFuture.await();
				}
				lastWriteFuture = null;
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			// releaseBuf();
			ChannelFuture future = socketChannel.close();
			try {
				future.sync();
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}

		@Override
		public SendfileDataBase createSendfileData(String filename, long pos, long length) {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public SendfileState processSendfile(SendfileDataBase sendfileData) {
			return null;
		}

		@Override
		public void doClientAuth(SSLSupport sslSupport) throws IOException {
			// TODO Auto-generated method stub

		}

		@Override
		public SSLSupport initSslSupport(String clientCertProvider) {
			SSLEngine sslEngine = this.sslEngine;
			if (sslEngine != null) {
				SSLSession session = sslEngine.getSession();
				return ((NettyEndpoint) getEndpoint()).getSslImplementation().getSSLSupport(session);
			}
			return null;
		}

		@Override
		public boolean hasAsyncIO() {
			// TODO Auto-generated method stub
			return false;
		}

		@Override
		public boolean isReadPending() {
			// TODO Auto-generated method stub
			return false;
		}

		@Override
		protected <A> AbstractSocketChannel<Channel>.OperationState<A> newOperationState(boolean read,
				BufWrapper[] buffers, int offset, int length, BlockingMode block, long timeout, TimeUnit unit,
				A attachment, CompletionCheck check, CompletionHandler<Long, ? super A> handler, Semaphore semaphore,
				AbstractSocketChannel<Channel>.VectoredIOCompletionHandler<A> completion) {
			// TODO Auto-generated method stub
			return null;
		}

	}

	private class NettySslHandler extends ChannelDuplexHandler {

		protected boolean handshakeComplete = false;

		protected boolean sniComplete = false;

		protected ByteBuf netInBuffer;
		// protected ByteBuf netOutBuffer;
		private Cumulator cumulator = ByteToMessageDecoder.MERGE_CUMULATOR;
		private boolean first;

		protected HandshakeStatus handshakeStatus; // gets set by handshake

		private long unwrapTime = 0;

		private long wrapTime = 0;

		private ByteBuffer tempBuf = null;

		public NettySslHandler() {

		}

		@Override
		public void channelInactive(ChannelHandlerContext ctx) throws Exception {
			super.channelInactive(ctx);
		}

		@Override
		public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
			NettyChannel nettyChannel = getOrAddChannel((SocketChannel) ctx.channel());
			if (msg instanceof ByteBuf) {

				// 解密
				first = netInBuffer == null;
				netInBuffer = cumulator.cumulate(ctx.alloc(), first ? Unpooled.EMPTY_BUFFER : netInBuffer,
						(ByteBuf) msg);

				while (true) {
					if (handshakeComplete) {

						int readerIndex = netInBuffer.readerIndex();
						ByteBuf unwraped = unwrap(nettyChannel, ctx);
						if (unwraped == null) {
							break;
						} else {
							if (unwraped.isReadable()) {
								ctx.fireChannelRead(unwraped);
							} else {
								unwraped.release();
								if (readerIndex == netInBuffer.readerIndex()) {
									break;
								}
							}
						}
					} else {

						if (!sniComplete) {
							int sniResult = processSNI(nettyChannel, ctx);
							if (sniResult == 0) {
								sniComplete = true;
							} else {
								// close
								return;
							}
						}

						SSLEngineResult handshake = null;
						while (!handshakeComplete) {
							switch (handshakeStatus) {
							case NOT_HANDSHAKING:
								// should never happen
								throw new IOException(sm.getString("channel.nio.ssl.notHandshaking"));
							case FINISHED:
								System.out.println(nettyChannel.getRemotePort() + " finished");
								if (NettyEndpoint.this.hasNegotiableProtocols()) {
									if (nettyChannel.sslEngine instanceof SSLUtil.ProtocolInfo) {
										nettyChannel
												.setNegotiatedProtocol(((SSLUtil.ProtocolInfo) nettyChannel.sslEngine)
														.getNegotiatedProtocol());
									} else if (JreCompat.isAlpnSupported()) {
										nettyChannel.setNegotiatedProtocol(
												JreCompat.getInstance().getApplicationProtocol(nettyChannel.sslEngine));
									}
								}
								// we are complete if we have delivered the last package
								handshakeComplete = true;// !netOutBuffer.hasRemaining();
								// return 0 if we are complete, otherwise we still have data to write
								// return handshakeComplete ? 0 : SelectionKey.OP_WRITE;
								break;
							case NEED_WRAP:
								System.out.println(nettyChannel.getRemotePort() + " need wrap");
								// perform the wrap function
								try {
									handshake = handshakeWrap(nettyChannel, ctx);
								} catch (SSLException e) {
									handshake = handshakeWrap(nettyChannel, ctx);
								}
								if (handshake.getStatus() == Status.OK) {
									if (handshakeStatus == HandshakeStatus.NEED_TASK) {
										handshakeStatus = tasks(nettyChannel);
									}
								} else if (handshake.getStatus() == Status.CLOSED) {
									// close
									return;
								} else {
									// wrap should always work with our buffers
									throw new IOException(sm.getString("channel.nio.ssl.unexpectedStatusDuringWrap",
											handshake.getStatus()));
								}
								if (handshakeStatus != HandshakeStatus.NEED_UNWRAP) {// || (!flush(netOutBuffer))
									// should actually return OP_READ if we have NEED_UNWRAP
									// return SelectionKey.OP_WRITE;
									break;
								} else {
									if (netInBuffer.readableBytes() == 0) {
										return;
									}
								}
								// fall down to NEED_UNWRAP on the same call, will result in a
								// BUFFER_UNDERFLOW if it needs data
								// $FALL-THROUGH$
							case NEED_UNWRAP:
								System.out.println(nettyChannel.getRemotePort() + " need unwrap");
								// perform the unwrap function
								handshake = handshakeUnwrap(nettyChannel, true);
								if (handshake.getStatus() == Status.OK) {
									if (handshakeStatus == HandshakeStatus.NEED_TASK) {
										handshakeStatus = tasks(nettyChannel);
									}
								} else if (handshake.getStatus() == Status.BUFFER_UNDERFLOW) {
									// read more data, reregister for OP_READ
									// return SelectionKey.OP_READ;
									return;
								} else {
									throw new IOException(sm.getString("channel.nio.ssl.unexpectedStatusDuringWrap",
											handshake.getStatus()));
								}
								break;
							case NEED_TASK:
								handshakeStatus = tasks(nettyChannel);
								break;
							default:
								throw new IllegalStateException(
										sm.getString("channel.nio.ssl.invalidStatus", handshakeStatus));
							}
						}
						// Handshake is complete if this point is reached

					}
				}
			} else {
				ctx.fireChannelRead(msg);
			}
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
		private int processSNI(NettyChannel nettyChannel, ChannelHandlerContext ctx) throws IOException {
			System.out.println(nettyChannel.getRemotePort() + " processSNI start");
			// Read some data into the network input buffer so we can peek at it.
			if (netInBuffer.readableBytes() == 0) {
				// Reached end of stream before SNI could be processed.
				return -1;
			}
			ByteBuffer byteBuffer = netInBuffer.nioBuffer();
			byteBuffer.position(netInBuffer.writerIndex());
			byteBuffer.limit(byteBuffer.capacity());
			TLSClientHelloExtractor extractor = new TLSClientHelloExtractor(byteBuffer);
			// byte[] b = netInBuffer.array();
			if (extractor.getResult() == ExtractorResult.UNDERFLOW) {
				return -1;
			}

			String hostName = null;
			List<Cipher> clientRequestedCiphers = null;
			List<String> clientRequestedApplicationProtocols = null;
			switch (extractor.getResult()) {
			case COMPLETE:
				hostName = extractor.getSNIValue();
				clientRequestedApplicationProtocols = extractor.getClientRequestedApplicationProtocols();
				System.out.println(nettyChannel.getRemotePort() + " protocols: " + clientRequestedApplicationProtocols);
				//$FALL-THROUGH$ to set the client requested ciphers
			case NOT_PRESENT:
				clientRequestedCiphers = extractor.getClientRequestedCiphers();
				break;
			case NEED_READ:
				return SelectionKey.OP_READ;
			case UNDERFLOW:
				// Unable to buffer enough data to read SNI extension data
				hostName = NettyEndpoint.this.getDefaultSSLHostConfigName();
				clientRequestedCiphers = Collections.emptyList();
				break;
			case NON_SECURE:
				ByteBuf netOutBuffer = ctx.alloc().buffer();
				netOutBuffer.clear();
				netOutBuffer.writeBytes(TLSClientHelloExtractor.USE_TLS_RESPONSE);
				ctx.writeAndFlush(netOutBuffer);
				throw new IOException(sm.getString("channel.nio.ssl.foundHttp"));
			}

			nettyChannel.sslEngine = NettyEndpoint.this.createSSLEngine(hostName, clientRequestedCiphers,
					clientRequestedApplicationProtocols);

			// Initiate handshake
			nettyChannel.sslEngine.beginHandshake();
			handshakeStatus = nettyChannel.sslEngine.getHandshakeStatus();
			System.out.println(nettyChannel.getRemotePort() + " processSNI end");
			return 0;
		}

		/**
		 * Performs the WRAP function
		 * 
		 * @param doWrite boolean
		 * @return the result
		 * @throws IOException An IO error occurred
		 */
		protected SSLEngineResult handshakeWrap(NettyChannel nettyChannel, ChannelHandlerContext ctx)
				throws IOException {
			// this should never be called with a network buffer that contains data
			// so we can clear it here.
			ByteBuf netOutBuffer = ctx.alloc().buffer(nettyChannel.sslEngine.getSession().getPacketBufferSize());
			netOutBuffer.writerIndex(netOutBuffer.capacity());
			// perform the wrap
			ByteBuffer byteBuffer = netOutBuffer.nioBuffer();

			// getBufHandler().configureWriteBufferForRead();
			SSLEngineResult result = nettyChannel.sslEngine.wrap(Unpooled.EMPTY_BUFFER.nioBuffer(), byteBuffer);
			// prepare the results to be written
			// set the status
			handshakeStatus = result.getHandshakeStatus();
			// optimization, if we do have a writable channel, write it now
			netOutBuffer.readerIndex(0);
			netOutBuffer.writerIndex(byteBuffer.position());
			ctx.writeAndFlush(netOutBuffer);
			return result;
		}

		/**
		 * Executes all the tasks needed on the same thread.
		 * 
		 * @return the status
		 */
		protected SSLEngineResult.HandshakeStatus tasks(NettyChannel nettyChannel) {
			Runnable r = null;
			while ((r = nettyChannel.sslEngine.getDelegatedTask()) != null) {
				r.run();
			}
			return nettyChannel.sslEngine.getHandshakeStatus();
		}

		/**
		 * Perform handshake unwrap
		 * 
		 * @param doread boolean
		 * @return the result
		 * @throws IOException An IO error occurred
		 */
		protected SSLEngineResult handshakeUnwrap(NettyChannel nettyChannel, boolean doread) throws IOException {

			SSLEngineResult result;
			boolean cont = false;
			// loop while we can perform pure SSLEngine data
			ByteBuffer byteBuffer = netInBuffer.nioBuffer();
			byteBuffer.position(0);
			byteBuffer.limit(netInBuffer.readableBytes());
			do {
				// prepare the buffer with the incoming data
				// netInBuffer.flip();
				// call unwrap
				// getBufHandler().configureReadBufferForWrite();
				result = nettyChannel.sslEngine.unwrap(byteBuffer, byteBuffer);
				// compact the buffer, this is an optional method, wonder what would happen if
				// we didn't
				// netInBuffer.compact();
				// read in the status
				handshakeStatus = result.getHandshakeStatus();
				if (result.getStatus() == SSLEngineResult.Status.OK
						&& result.getHandshakeStatus() == HandshakeStatus.NEED_TASK) {
					// execute tasks if we need to
					handshakeStatus = tasks(nettyChannel);
				}
				// perform another unwrap?
				cont = result.getStatus() == SSLEngineResult.Status.OK
						&& handshakeStatus == HandshakeStatus.NEED_UNWRAP;
			} while (cont);
			netInBuffer.readerIndex(netInBuffer.readerIndex() + byteBuffer.position());
			return result;
		}

		private ByteBuf unwrap(NettyChannel nettyChannel, ChannelHandlerContext ctx) throws IOException {
//			ByteBuffer byteBuffer = netInBuffer.nioBuffer();
//			byteBuffer.position(netInBuffer.readerIndex());
//			byteBuffer.limit(netInBuffer.writerIndex());

			ByteBuf dstBuf = ctx.alloc().buffer(65535, Integer.MAX_VALUE);
			ByteBuffer dst = getByteBufferForWrite(dstBuf);

			// the data read
			int read = 0;
			// the SSL engine result
			SSLEngineResult unwrap;
			if (tempBuf == null) {
				tempBuf = ctx.alloc().buffer(16384).writerIndex(16384).nioBuffer();
			}
//			while (read == 0) {
			while (netInBuffer.isReadable() && tempBuf.hasRemaining()) {
				tempBuf.put(netInBuffer.readByte());
			}
			do {

				tempBuf.flip();
				// prepare the buffer
				// unwrap the data

				if (tempBuf.remaining() == 0) {
//					System.err.println("before tempBuf remaining" + tempBuf.remaining());
				}
				long startTime = System.currentTimeMillis();
				unwrap = nettyChannel.sslEngine.unwrap(tempBuf, dst);
				long useTime = System.currentTimeMillis() - startTime;
				unwrapTime += useTime;
				// System.out.println(nettyChannel.getRemotePort() + " 解密用时：" + useTime + "
				// 总用时：" + unwrapTime);
				if (dst.position() == 0 && tempBuf.remaining() != 0) {
//					System.err.println("after tempBuf remaining" + tempBuf.remaining());
				}
				tempBuf.compact();

				// if (netread > 0) {
				// System.out.println(this + "read内容:" + new String(dst.array(), 0,
				// dst.limit()));
				// }
				// compact the buffer
				// byteBuffer.compact();

				if (unwrap.getStatus() == Status.OK || unwrap.getStatus() == Status.BUFFER_UNDERFLOW) {
					// we did receive some data, add it to our total
					read += unwrap.bytesProduced();
					// perform any tasks if needed
					if (unwrap.getHandshakeStatus() == HandshakeStatus.NEED_TASK) {
						tasks(nettyChannel);
					}
					// if we need more network data, then bail out for now.
					if (unwrap.getStatus() == Status.BUFFER_UNDERFLOW) {
//						System.err.println("underflow break" + tempBuf.remaining());
						break;
					}
				} else if (unwrap.getStatus() == Status.BUFFER_OVERFLOW) {
					if (read > 0) {
						// Buffer overflow can happen if we have read data. Return
						// so the destination buffer can be emptied before another
						// read is attempted
						break;
					} else {
						// Buffer overflow can happen if we have read data. Return
						// so the destination buffer can be emptied before another
						// read is attempted
						System.err.println("overflow");

						dstBuf.ensureWritable(dst.capacity() * 2, true);
						dst = getByteBufferForWrite(dstBuf);
					}

				} else if (unwrap.getStatus() == Status.CLOSED) {
					System.err.println("close return null");
					return null;
				} else {
					// Something else went wrong
					throw new IOException(sm.getString("channel.nio.ssl.unwrapFail", unwrap.getStatus()));
				}
			} while (tempBuf.position() != 0); // continue to unwrapping as long

			// as
//			} // the input buffer has stuff
//			netInBuffer.readerIndex(byteBuffer.position());
//			netInBuffer.writerIndex(byteBuffer.limit());
			dstBuf.readerIndex(0);
			dstBuf.writerIndex(dst.position());
			return dstBuf;
		}

		private ByteBuffer getByteBufferForWrite(ByteBuf byteBuf) {
			int oldWriterIndex = byteBuf.writerIndex();
			byteBuf.writerIndex(byteBuf.capacity());
			ByteBuffer dst = byteBuf.nioBuffer();
			dst.position(oldWriterIndex);
			dst.limit(dst.capacity());
			byteBuf.writerIndex(oldWriterIndex);
			return dst;
		}

		@Override
		public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
			NettyChannel nettyChannel = getOrAddChannel((SocketChannel) ctx.channel());
			if (msg instanceof ByteBuf) {

				ByteBuf msg2 = wrap(nettyChannel, ctx, (ByteBuf) msg);

				super.write(ctx, msg2, promise);
			} else {
				super.write(ctx, msg, promise);
			}
		}

		private ByteBuf wrap(NettyChannel nettyChannel, ChannelHandlerContext ctx, ByteBuf buf) throws IOException {
			ByteBuffer src = buf.nioBuffer();
			ByteBuf dstBuf = ctx.alloc().buffer(65535);
			dstBuf.writerIndex(dstBuf.capacity());
			ByteBuffer dst = dstBuf.nioBuffer();

			long startTime = System.currentTimeMillis();
			SSLEngineResult result = nettyChannel.sslEngine.wrap(src, dst);
			long useTime = System.currentTimeMillis() - startTime;
			wrapTime += useTime;
			// System.out.println(nettyChannel.getRemotePort() + " 加密用时：" + useTime + "
			// 总用时：" + wrapTime);
			// The number of bytes written
			int written = result.bytesConsumed();
			dstBuf.readerIndex(0);
			dstBuf.writerIndex(dst.position());

			if (result.getStatus() == Status.OK) {
				if (result.getHandshakeStatus() == HandshakeStatus.NEED_TASK) {
					tasks(nettyChannel);
				}
			} else {
				throw new IOException(sm.getString("channel.nio.ssl.wrapFail", result.getStatus()));
			}
			buf.release();
			return dstBuf;
		}

		@Override
		public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
			if (netInBuffer != null) {
				netInBuffer.release();
			}
			super.channelUnregistered(ctx);
		}

		@Override
		public boolean isSharable() {
			return false;
		}

	}

	private class NettyTomcatHandler extends ChannelDuplexHandler {

		@Override
		public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
			System.out.println(ctx.channel() + " channel added");
			super.handlerAdded(ctx);
		}

		@Override
		public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
			// TODO Auto-generated method stub
			NettyChannel nettyChannel = getOrAddChannel((SocketChannel) ctx.channel());
			System.out.println(nettyChannel.getRemotePort() + " " + "channelRegistered");
			super.channelRegistered(ctx);
		}

		@Override
		public void channelActive(ChannelHandlerContext ctx) throws Exception {
			// TODO Auto-generated method stub
			NettyChannel nettyChannel = getOrAddChannel((SocketChannel) ctx.channel());
			System.out.println(nettyChannel.getRemotePort() + " " + "channelActive");
			super.channelActive(ctx);
		}

		@Override
		public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
			if (msg instanceof ByteBuf) {

				ByteBuf byteBuf = (ByteBuf) msg;
				NettyChannel nettyChannel = getOrAddChannel((SocketChannel) ctx.channel());
				nettyChannel.offer(new ByteBufWrapper(byteBuf));

			} else {
				ctx.fireChannelRead(msg);
			}
		}

		@Override
		public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
			// TODO Auto-generated method stub
			NettyChannel nettyChannel = getOrAddChannel((SocketChannel) ctx.channel());
			super.channelReadComplete(ctx);
		}

		@Override
		public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
			// TODO Auto-generated method stub
			NettyChannel nettyChannel = getOrAddChannel((SocketChannel) ctx.channel());
			System.out.println(nettyChannel.getRemotePort() + " " + "userEventTriggered");
			super.userEventTriggered(ctx, evt);
		}

		@Override
		public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
			if (msg instanceof TimedWrapper) {
				super.write(ctx, ((TimedWrapper) msg).getByteBuf(), promise);
			} else {
				super.write(ctx, msg, promise);
			}
		}

		@Override
		public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
			// TODO Auto-generated method stub
			NettyChannel nettyChannel = getOrAddChannel((SocketChannel) ctx.channel());
			boolean writable = ctx.channel().isWritable();
			System.out.println(nettyChannel.getRemotePort() + " " + "channelWritabilityChanged:" + writable);
			// nettyChannel.updateIsWritable(ctx.channel().isWritable());
			if (writable && nettyChannel.needDispatchWrite()) {
//				System.out.println(nettyChannel.getRemotePort() + " " + "processSocketWrite");
				NettyEndpoint.this.getHandler().processSocket(nettyChannel, SocketEvent.OPEN_WRITE, true);
			}
			super.channelWritabilityChanged(ctx);
		}

		@Override
		public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
			// TODO Auto-generated method stub
			NettyChannel nettyChannel = getOrAddChannel((SocketChannel) ctx.channel());
			System.out.println(nettyChannel.getRemotePort() + " " + "exceptionCaught");
			cause.printStackTrace();
			if (nettyChannel.needDispatchRead()) {
				NettyEndpoint.this.getHandler().processSocket(nettyChannel, SocketEvent.ERROR, true);
			} else {
				nettyChannel
						.setIOException(cause instanceof IOException ? (IOException) cause : new IOException(cause));
			}
			// super.exceptionCaught(ctx, cause);
		}

		@Override
		public void channelInactive(ChannelHandlerContext ctx) throws Exception {
			// TODO Auto-generated method stub
			NettyChannel nettyChannel = getOrAddChannel((SocketChannel) ctx.channel());
			System.out.println(nettyChannel.getRemotePort() + " " + "channelInactive");
//			if (nettyChannel.needDispatch()) {
//				NettyEndpoint.this.getHandler().processSocket(nettyChannel, SocketEvent.OPEN_READ, true);
//			}
			super.channelInactive(ctx);
		}

		@Override
		public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
			// TODO Auto-generated method stub
			NettyChannel nettyChannel = removeChannel((SocketChannel) ctx.channel());
			if (nettyChannel != null) {
				System.out.println(nettyChannel.getRemotePort() + " " + "channelUnregistered");
				nettyChannel.releaseBuf();
			} else {
				System.out.println(ctx.channel() + " channel close");
			}
			super.channelUnregistered(ctx);
		}

		@Override
		public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
			System.out.println(ctx.channel() + " channel removed");
			super.handlerRemoved(ctx);
		}

		@Override
		public void close(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
			super.close(ctx, promise);
		}

		@Override
		public boolean isSharable() {
			return false;
		}

	}

	private class BossHandler extends ChannelInboundHandlerAdapter {

		@Override
		public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
			// TODO Auto-generated method stub
			countUpOrAwaitConnection();
			// System.out.println("收到连接" + msg);
			super.channelRead(ctx, msg);
		}

	}

}
