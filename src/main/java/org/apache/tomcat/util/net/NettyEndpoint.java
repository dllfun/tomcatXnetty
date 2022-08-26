package org.apache.tomcat.util.net;

import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.CompletionHandler;
import java.nio.channels.SelectionKey;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

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

	public static class NettyChannel extends AbstractSocketChannel<io.netty.channel.Channel> {

		private static final Cumulator cumulator = ByteToMessageDecoder.MERGE_CUMULATOR;

		private SocketChannel channel;

		private NettyEndpoint endpoint;

		private final BlockingDeque<ByteBuf> queue;

		private ByteBufWrapper appReadBuffer = new ByteBufWrapper(this);

		private volatile boolean needDispatch = true;

		protected SSLEngine sslEngine;

		public NettyChannel(SocketChannel channel, NettyEndpoint endpoint) {
			super(channel, endpoint);
			this.channel = channel;
			this.endpoint = endpoint;
			queue = new LinkedBlockingDeque<>();
		}

		protected void offer(ByteBuf byteBuf) {
			synchronized (queue) {
				queue.offer(byteBuf);
			}
		}

		protected boolean peekIsEmpty() {
			synchronized (queue) {
				boolean empty = queue.isEmpty();
				if (empty) {
					this.needDispatch = true;
				}
				return empty;
			}
		}

		protected boolean needDispatch() {
			boolean needDispatch = this.needDispatch;
			if (needDispatch) {
				this.needDispatch = false;
			}
			return needDispatch;
		}

		protected void releaseBuf() {
			ByteBuf byteBuf = null;
			while ((byteBuf = queue.poll()) != null) {
				byteBuf.release();
			}
			appReadBuffer.releaseBuf();
		}

		@Override
		public void initAppReadBuffer(int headerBufferSize) {
			// TODO Auto-generated method stub

		}

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

		@Override
		public BufWrapper allocate(int size) {
			ByteBuf byteBuf = channel.alloc().buffer(size);
			ByteBufWrapper byteBufWrapper = new ByteBufWrapper(this);
			byteBufWrapper.delegate = byteBuf;
			return byteBufWrapper;
		}

//		@Override
//		public io.netty.channel.Channel getSocket() {
//			return channel;
//		}

		@Override
		protected void populateRemoteAddr() {
			InetSocketAddress ipSocket = channel.remoteAddress();
			remoteAddr = ipSocket.getAddress().getHostAddress();
		}

		@Override
		protected void populateRemoteHost() {
			InetSocketAddress ipSocket = channel.remoteAddress();
			remoteHost = ipSocket.getAddress().getHostName();
		}

		@Override
		protected void populateRemotePort() {
			InetSocketAddress ipSocket = channel.remoteAddress();
			remotePort = ipSocket.getPort();
		}

		@Override
		protected void populateLocalName() {
			localName = channel.localAddress().getAddress().getHostName();
		}

		@Override
		protected void populateLocalAddr() {
			localAddr = channel.localAddress().getAddress().getHostAddress();
		}

		@Override
		protected void populateLocalPort() {
			localPort = channel.localAddress().getPort();
		}

		@Override
		public boolean hasDataToRead() {
			// TODO Auto-generated method stub
			return true;
		}

		@Override
		public boolean hasDataToWrite() {
			// TODO Auto-generated method stub
			return false;
		}

		@Override
		public boolean isReadyForWrite() {
			// TODO Auto-generated method stub
			return true;
		}

		@Override
		public boolean canWrite() {
			// TODO Auto-generated method stub
			return true;
		}

		@Override
		public int read(boolean block, byte[] b, int off, int len) throws IOException {
			// return byteBufWrapper.read(block, b, off, len);
			ByteBuf byteBuf = null;
			if (block) {
				while (byteBuf == null) {
					try {
						byteBuf = queue.take();
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
				}
			} else {
				byteBuf = queue.poll();
				if (byteBuf == null) {
					return 0;
				}
			}
			int minLength = byteBuf.readableBytes() < len ? byteBuf.readableBytes() : len;
			for (int index = 0; index < minLength; index++) {
				b[off + index] = byteBuf.readByte();
			}
			if (byteBuf.readableBytes() > 0) {
				queue.offerFirst(byteBuf);
			} else {
				byteBuf.release();
			}
			return minLength;
		}

		@Override
		public int read(boolean block, ByteBuffer to) throws IOException {
			// return byteBufWrapper.read(block, to);
			ByteBuf byteBuf = null;
			if (block) {
				while (byteBuf == null) {
					try {
						byteBuf = queue.take();
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
				}
			} else {
				byteBuf = queue.poll();
				if (byteBuf == null) {
					return 0;
				}
			}
			int minLength = byteBuf.readableBytes() < to.remaining() ? byteBuf.readableBytes() : to.remaining();
			for (int index = 0; index < minLength; index++) {
				to.put(byteBuf.readByte());
			}
			if (byteBuf.readableBytes() > 0) {
				queue.offerFirst(byteBuf);
			} else {
				byteBuf.release();
			}
			return minLength;
		}

		@Override
		public int read(boolean block, BufWrapper to) throws IOException {
			if (to instanceof ByteBufWrapper) {
				ByteBufWrapper byteBufWrapper = (ByteBufWrapper) to;
				ByteBuf byteBuf = null;
				if (block) {
					while (byteBuf == null) {
						try {
							byteBuf = queue.take();
						} catch (InterruptedException e) {
							e.printStackTrace();
						}
					}
				} else {
					byteBuf = queue.poll();
					if (byteBuf == null) {
						return 0;
					}
				}
				int length = byteBuf.readableBytes();
				if (byteBufWrapper.delegate == null || byteBufWrapper.delegate == Unpooled.EMPTY_BUFFER) {
					byteBufWrapper.delegate = byteBuf;
				} else {
					if (byteBufWrapper.parsingHeader) {
						byteBufWrapper.delegate = cumulator.cumulate(channel.alloc(), byteBufWrapper.delegate, byteBuf);
					} else {
						// byteBuf.release();
						if (byteBufWrapper.delegate.readerIndex() >= byteBufWrapper.delegate.writerIndex()) {
							// 正常情况
						} else {
							System.out.println("byteBuf还有未读数据");
							// delegate.readerIndex(delegate.writerIndex());
						}
						byteBufWrapper.delegate.release();
						byteBufWrapper.delegate = byteBuf;
					}
				}
				return length;
			} else if (to instanceof ByteBufferWrapper) {
				ByteBufferWrapper byteBufferWrapper = (ByteBufferWrapper) to;
				return read(block, byteBufferWrapper.getByteBuffer());
			} else {
				throw new RuntimeException();
			}
		}

		@Override
		public boolean isReadyForRead() throws IOException {
			// TODO Auto-generated method stub
			return true;
		}

		@Override
		public void unRead(ByteBuffer returnedInput) {
			if (returnedInput != null && returnedInput.remaining() > 0) {
				ByteBuf byteBuf = channel.alloc().buffer(returnedInput.remaining());
				while (returnedInput.hasRemaining()) {
					byteBuf.writeByte(returnedInput.get());
				}
				queue.offerFirst(byteBuf);
			}
		}

		// @Override
		// public void processSocket(SocketEvent socketStatus, boolean dispatch) {
		// endpoint.getHandler().processSocket(this, socketStatus, dispatch);
		// }

		@Override
		public void registerReadInterest() {
			// System.out.println("registe read");
			if (!peekIsEmpty()) {
				System.out.println(getRemotePort() + " processSocket again");
				endpoint.getHandler().processSocket(this, SocketEvent.OPEN_READ, true);
			} else {
				channel.read();
			}
		}

		@Override
		public void registerWriteInterest() {

		}

		@Override
		protected void writeBlocking(byte[] buf, int off, int len) throws IOException {
			// System.out.println("write " + buf + " start");
			ChannelFuture future = channel.writeAndFlush(Unpooled.wrappedBuffer(buf, off, len));
			try {
				future.sync();
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			// System.out.println("write " + buf + " finish");
		}

		@Override
		protected void writeBlocking(ByteBuffer from) throws IOException {
			// System.out.println("writeBlocking");
			ByteBuf byteBuf = channel.alloc().buffer(from.remaining());
			while (from.hasRemaining()) {
				byteBuf.writeByte(from.get());
			}
			// System.out.println("write " + byteBuf + " start");
			ChannelFuture future = channel.writeAndFlush(byteBuf);
			try {
				future.sync();
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			// System.out.println("write " + byteBuf + " finish");
		}

		@Override
		protected void writeNonBlocking(byte[] buf, int off, int len) throws IOException {
			ChannelFuture future = channel.write(Unpooled.wrappedBuffer(buf, off, len));
		}

		@Override
		protected void writeNonBlocking(ByteBuffer from) throws IOException {
			ByteBuf byteBuf = channel.alloc().buffer(from.remaining());
			while (from.hasRemaining()) {
				byteBuf.writeByte(from.get());
			}
			ChannelFuture future = channel.write(byteBuf);
		}

		@Override
		protected void writeBlocking(BufWrapper from) throws IOException {
			if (from instanceof ByteBufWrapper) {
				ByteBufWrapper byteBufWrapper = (ByteBufWrapper) from;
				// System.out.println("write " + buf + " start");
				ChannelFuture future = channel.writeAndFlush(byteBufWrapper.delegate);
				try {
					future.sync();
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				// System.out.println("write " + buf + " finish");
			} else if (from instanceof ByteBufferWrapper) {
				ByteBufferWrapper byteBufferWrapper = (ByteBufferWrapper) from;
				writeBlocking(byteBufferWrapper.getByteBuffer());
			}
		}

		@Override
		protected void writeNonBlocking(BufWrapper from) throws IOException {
			if (from instanceof ByteBufWrapper) {
				ByteBufWrapper byteBufWrapper = (ByteBufWrapper) from;
				// System.out.println("write " + buf + " start");
				ChannelFuture future = channel.write(byteBufWrapper.delegate);
				// System.out.println("write " + buf + " finish");
			} else if (from instanceof ByteBufferWrapper) {
				ByteBufferWrapper byteBufferWrapper = (ByteBufferWrapper) from;
				writeNonBlocking(byteBufferWrapper.getByteBuffer());
			}
		}

		@Override
		protected void flushBlocking() throws IOException {
			// System.out.println("channel.flush();");
			channel.flush();
		}

		@Override
		protected boolean flushNonBlocking() throws IOException {
			channel.flush();
			return false;
		}

		@Override
		protected void doClose() {
			// releaseBuf();
			ChannelFuture future = channel.close();
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
				ByteBuffer[] buffers, int offset, int length, BlockingMode block, long timeout, TimeUnit unit,
				A attachment, CompletionCheck check, CompletionHandler<Long, ? super A> handler, Semaphore semaphore,
				AbstractSocketChannel<Channel>.VectoredIOCompletionHandler<A> completion) {
			// TODO Auto-generated method stub
			return null;
		}

		private static class ByteBufWrapper implements BufWrapper {

			private NettyChannel channel;

			private ByteBuf delegate = Unpooled.EMPTY_BUFFER;

			private boolean parsingHeader;

			private boolean parsingRequestLine;

			private boolean trace = false;

			private boolean readMode = true;

			public ByteBufWrapper(NettyChannel channel) {
				super();
				this.channel = channel;
			}

			protected void releaseBuf() {
				delegate.release();
			}

			@Override
			public void switchToWriteMode() {
				if (readMode) {
					readMode = false;
				}
			}

			@Override
			public void switchToReadMode() {
				if (!readMode) {
					delegate.readerIndex(0);
					readMode = true;
				}
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
					if (delegate.capacity() < limit) {
						ByteBuf buf = channel.channel.alloc().buffer(limit);
						buf.writeBytes(delegate);
						delegate.release();
						delegate = buf;
					} else if (delegate.capacity() == limit) {

					} else {
						delegate.capacity(limit);
					}
				}
			}

			@Override
			public byte getByte() {
				if (trace) {
					System.out.println("getByte");
				}
				return delegate.readByte();
			}

			@Override
			public void getByte(byte[] b, int off, int len) {
				if (trace) {
					System.out.println("getByte b");
				}
				if (delegate.refCnt() == 0) {
					System.out.println("refCnt is zero");
					System.out.println(delegate.refCnt());
				}
				delegate.readBytes(b, off, len);
			}

			@Override
			public byte getByte(int index) {
				return delegate.getByte(index);
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
				if (trace) {
					System.out.println("setByte");
				}
				delegate.setByte(index, b);
			}

			@Override
			public void putByte(byte b) {
				delegate.writeByte(b);
			}

			@Override
			public void putBytes(byte[] b) {
				delegate.writeBytes(b);
			}

			@Override
			public void putBytes(byte[] b, int off, int len) {
				delegate.writeBytes(b, off, len);
			}

//			@Override
//			public boolean fill(boolean block) throws IOException {
//				if (channel != null) {
//					int nRead = channel.read(block, this);
//					if (nRead > 0) {
//						return true;
//					} else if (nRead == -1) {
//						throw new EOFException(sm.getString("iib.eof.error"));
//					} else {
//						return false;
//					}
//				} else {
//					throw new CloseNowException(sm.getString("iib.eof.error"));
//				}
//			}

//			@Override
//			public int doRead(PreInputBuffer handler) throws IOException {
//				if (trace) {
//					System.out.println("doRead");
//				}
//				if (hasNoRemaining()) {
//					// byteBuf.release();
//					fill(true);
//				}
//				int length = delegate.writerIndex() - delegate.readerIndex();
//				handler.setBufWrapper(this.duplicate());//
//				delegate.readerIndex(delegate.writerIndex());
//				return length;
//			}

			@Override
			public void startParsingHeader(int headerBufferSize) {
				parsingHeader = true;
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
			}

			@Override
			public void nextRequest() {
				// TODO Auto-generated method stub
				if (delegate.readableBytes() > 0) {

				} else {
					// byteBuf.release();
				}
			}

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
				delegate.readerIndex(delegate.writerIndex());
			}

			@Override
			public ByteBuffer nioBuffer() {
				if (trace) {
					System.out.println("nioBuffer");
				}
				return delegate.nioBuffer();
			}

			// @Override
			public BufWrapper duplicate() {
				if (trace) {
					System.out.println("duplicate");
				}
				ByteBufWrapper bufWrapper = new ByteBufWrapper(channel);
				bufWrapper.delegate = delegate.duplicate();
				return bufWrapper;
			}

			@Override
			public void startTrace() {
				// trace = true;
			}

			@Override
			public boolean released() {
				if (delegate.refCnt() == 0) {
					return true;
				}
				return false;
			}

			@Override
			public void release() {

			}

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

		@Override
		public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
			NettyChannel nettyChannel = getOrAddChannel((SocketChannel) ctx.channel());
			if (msg instanceof ByteBuf) {

				// 解密
				first = netInBuffer == null;
				netInBuffer = cumulator.cumulate(ctx.alloc(), first ? Unpooled.EMPTY_BUFFER : netInBuffer,
						(ByteBuf) msg);

				while (netInBuffer.isReadable()) {
					if (handshakeComplete) {

						int readerIndex = netInBuffer.readerIndex();
						ByteBuf unwraped = unwrap(nettyChannel, ctx);
						if (readerIndex == netInBuffer.readerIndex() || unwraped == null) {
							break;
						}
						if (unwraped.isReadable()) {
							ctx.fireChannelRead(unwraped);
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
			byteBuffer.position(netInBuffer.readerIndex());
			byteBuffer.limit(netInBuffer.writerIndex());
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
			netInBuffer.readerIndex(byteBuffer.position());
			return result;
		}

		private ByteBuf unwrap(NettyChannel nettyChannel, ChannelHandlerContext ctx) throws IOException {
			ByteBuffer byteBuffer = netInBuffer.nioBuffer();
			byteBuffer.position(netInBuffer.readerIndex());
			byteBuffer.limit(netInBuffer.writerIndex());

			ByteBuf dstBuf = ctx.alloc().buffer(65535);
			dstBuf.writerIndex(dstBuf.capacity());
			ByteBuffer dst = dstBuf.nioBuffer();
			dst.position(0);
			dst.limit(dst.capacity());
			// the data read
			int read = 0;
			// the SSL engine result
			SSLEngineResult unwrap;
			do {
				// prepare the buffer
				// unwrap the data
				unwrap = nettyChannel.sslEngine.unwrap(byteBuffer, dst);
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
						break;
					}
				} else if (unwrap.getStatus() == Status.BUFFER_OVERFLOW) {

					// Buffer overflow can happen if we have read data. Return
					// so the destination buffer can be emptied before another
					// read is attempted
					break;

				} else if (unwrap.getStatus() == Status.CLOSED) {
					return null;
				} else {
					// Something else went wrong
					throw new IOException(sm.getString("channel.nio.ssl.unwrapFail", unwrap.getStatus()));
				}
			} while (byteBuffer.position() != 0); // continue to unwrapping as long as the input buffer has stuff
			netInBuffer.readerIndex(byteBuffer.position());
			netInBuffer.writerIndex(byteBuffer.limit());
			dstBuf.readerIndex(0);
			dstBuf.writerIndex(dst.position());
			return dstBuf;
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

			SSLEngineResult result = nettyChannel.sslEngine.wrap(src, dst);
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

	private class NettyTomcatHandler extends ChannelInboundHandlerAdapter {

		@Override
		public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
			// TODO Auto-generated method stub
			NettyChannel nettyChannel = getOrAddChannel((SocketChannel) ctx.channel());
			System.out.println(nettyChannel.getRemotePort() + " " + "channelRegistered");
			super.channelRegistered(ctx);
		}

		@Override
		public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
			// TODO Auto-generated method stub
			NettyChannel nettyChannel = removeChannel((SocketChannel) ctx.channel());
			if (nettyChannel != null) {
				System.out.println(nettyChannel.getRemotePort() + " " + "channelUnregistered");
				nettyChannel.releaseBuf();
			} else {
				System.out.println(ctx.channel());
			}
			super.channelUnregistered(ctx);
		}

		@Override
		public void channelActive(ChannelHandlerContext ctx) throws Exception {
			// TODO Auto-generated method stub
			NettyChannel nettyChannel = getOrAddChannel((SocketChannel) ctx.channel());
			System.out.println(nettyChannel.getRemotePort() + " " + "channelActive");
			super.channelActive(ctx);
		}

		@Override
		public void channelInactive(ChannelHandlerContext ctx) throws Exception {
			// TODO Auto-generated method stub
			NettyChannel nettyChannel = getOrAddChannel((SocketChannel) ctx.channel());
			System.out.println(nettyChannel.getRemotePort() + " " + "channelInactive");
			if (nettyChannel.needDispatch()) {
				NettyEndpoint.this.getHandler().processSocket(nettyChannel, SocketEvent.OPEN_READ, true);
			}
			super.channelInactive(ctx);
		}

		@Override
		public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
			if (msg instanceof ByteBuf) {

				ByteBuf byteBuf = (ByteBuf) msg;

				NettyChannel nettyChannel = getOrAddChannel((SocketChannel) ctx.channel());
				nettyChannel.offer(byteBuf);

				if (nettyChannel.needDispatch()) {
					System.out.println(nettyChannel.getRemotePort() + " " + "processSocketRead");
					NettyEndpoint.this.getHandler().processSocket(nettyChannel, SocketEvent.OPEN_READ, true);
				}

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
		public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
			// TODO Auto-generated method stub
			NettyChannel nettyChannel = getOrAddChannel((SocketChannel) ctx.channel());
			System.out.println(nettyChannel.getRemotePort() + " " + "channelWritabilityChanged");
			super.channelWritabilityChanged(ctx);
		}

		@Override
		public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
			// TODO Auto-generated method stub
			NettyChannel nettyChannel = getOrAddChannel((SocketChannel) ctx.channel());
			System.out.println(nettyChannel.getRemotePort() + " " + "exceptionCaught");
			cause.printStackTrace();
			NettyEndpoint.this.getHandler().processSocket(nettyChannel, SocketEvent.ERROR, true);
			// super.exceptionCaught(ctx, cause);
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
