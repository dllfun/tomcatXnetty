package org.apache.tomcat.util.net;

import static java.lang.Integer.MAX_VALUE;

import java.io.IOException;
import java.util.List;
import org.apache.tomcat.util.net.NettyEndpoint.NettyChannel;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.socket.ChannelInputShutdownEvent;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.ByteToMessageDecoder.Cumulator;
import io.netty.handler.codec.DecoderException;

//@Sharable
public class NettyHttpHandler extends ChannelInboundHandlerAdapter {

	private NettyEndpoint endpoint;

	private ByteBuf cumulation;
	private Cumulator cumulator = ByteToMessageDecoder.MERGE_CUMULATOR;
	private boolean first;
	private int count = 1;
	private long lastReadTime = -1;

	/**
	 * This flag is used to determine if we need to call
	 * {@link ChannelHandlerContext#read()} to consume more data when
	 * {@link ChannelConfig#isAutoRead()} is {@code false}.
	 */
	// private boolean firedChannelRead;

	public NettyHttpHandler(NettyEndpoint endpoint) {
		super();
		this.endpoint = endpoint;
	}

	/**
	 * Returns the actual number of readable bytes in the internal cumulative buffer
	 * of this decoder. You usually do not need to rely on this value to write a
	 * decoder. Use it only when you must use it at your own risk. This method is a
	 * shortcut to {@link #internalBuffer() internalBuffer().readableBytes()}.
	 */
	protected int actualReadableBytes() {
		return internalBuffer().readableBytes();
	}

	/**
	 * Returns the internal cumulative buffer of this decoder. You usually do not
	 * need to access the internal buffer directly to write a decoder. Use it only
	 * when you must use it at your own risk.
	 */
	protected ByteBuf internalBuffer() {
		if (cumulation != null) {
			return cumulation;
		} else {
			return Unpooled.EMPTY_BUFFER;
		}
	}

	@Override
	public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
		super.channelRegistered(ctx);
		System.out.println("channel " + ctx.channel().hashCode() + " registered");
	}

	@Override
	public final void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
		// if (decodeState == STATE_CALLING_CHILD_DECODE) {
		// decodeState = STATE_HANDLER_REMOVED_PENDING;
		// return;
		// }
		ByteBuf buf = cumulation;
		if (buf != null) {
			// Directly set this to null so we are sure we not access it in any other method
			// here anymore.
			cumulation = null;
			int readable = buf.readableBytes();
			if (readable > 0) {
				ctx.fireChannelRead(buf);
				ctx.fireChannelReadComplete();
			} else {
				buf.release();
				System.out.println("handlerRemoved" + buf + "released");
			}
		}
		handlerRemoved0(ctx);
	}

	/**
	 * Gets called after the {@link ByteToMessageDecoder} was removed from the
	 * actual context and it doesn't handle events anymore.
	 */
	protected void handlerRemoved0(ChannelHandlerContext ctx) throws Exception {
	}

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
		if (msg instanceof ByteBuf) {
			try {
				first = cumulation == null;
				cumulation = cumulator.cumulate(ctx.alloc(), first ? Unpooled.EMPTY_BUFFER : cumulation, (ByteBuf) msg);

				if (count == 1) {
					// System.out.println("第1次读取");
				} else {
					long timeused = System.currentTimeMillis() - lastReadTime;
					// System.out.println("第" + count + "次读取，上次读取处理用时" + timeused);
				}
				count++;
				lastReadTime = System.currentTimeMillis();
				// System.out.println("读取了一部分数据" + cumulation.readableBytes());

				NettyChannel nettyChannel = endpoint.getOrAddChannel((SocketChannel) ctx.channel());
				//nettyChannel.setByteBuf(cumulation);

				ctx.channel().config().setAutoRead(false);
				// System.out.println("consume read");

				//if (nettyChannel.isBlock()) {
				//	nettyChannel.getCountDownLatch().countDown();
				//} else {
				//	System.out.println("setByteBuf:" + cumulation.readableBytes());
				//	endpoint.processSocket(nettyChannel, SocketEvent.OPEN_READ, true);
				//}

				// callDecode(ctx, cumulation, out);
			} catch (DecoderException e) {
				throw e;
			} catch (Exception e) {
				throw new DecoderException(e);
			} finally {
				/*
				 * try { if (cumulation != null && !cumulation.isReadable()) { numReads = 0;
				 * cumulation.release(); System.out.println("channelRead" + cumulation +
				 * "released"); cumulation = null; } else if (++numReads >= discardAfterReads) {
				 * // We did enough reads already try to discard some bytes so we not risk to
				 * see a // OOME. // See https://github.com/netty/netty/issues/4275 numReads =
				 * 0; discardSomeReadBytes(); }
				 * 
				 * // int size = out.size(); // firedChannelRead |= out.insertSinceRecycled();
				 * // fireChannelRead(ctx, out, size); } finally { // out.recycle(); }
				 */
			}
		} else {
			ctx.fireChannelRead(msg);
		}
	}

	@Override
	public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
		discardSomeReadBytes();
		// if (!firedChannelRead && !ctx.channel().config().isAutoRead()) {
		// ctx.read();
		// }
		// firedChannelRead = false;
		ctx.fireChannelReadComplete();
	}

	protected final void discardSomeReadBytes() {
		if (cumulation != null && !first && cumulation.refCnt() == 1) {
			// discard some bytes if possible to make more room in the
			// buffer but only if the refCnt == 1 as otherwise the user may have
			// used slice().retain() or duplicate().retain().
			//
			// See:
			// - https://github.com/netty/netty/issues/2327
			// - https://github.com/netty/netty/issues/1764
			cumulation.discardSomeReadBytes();
		}
	}

	@Override
	public void channelInactive(ChannelHandlerContext ctx) throws Exception {
		channelInputClosed(ctx, true);
		// System.out.println("Inactive");
	}

	@Override
	public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
		if (evt instanceof ChannelInputShutdownEvent) {
			// The decodeLast method is invoked when a channelInactive event is encountered.
			// This method is responsible for ending requests in some situations and must be
			// called
			// when the input has been shutdown.
			// channelInputClosed(ctx, false);
		}
		super.userEventTriggered(ctx, evt);
	}

	private void channelInputClosed(ChannelHandlerContext ctx, boolean callChannelInactive) {
		// CodecOutputList out = CodecOutputList.newInstance();
		try {
			channelInputClosed(ctx);
		} catch (DecoderException e) {
			throw e;
		} catch (Exception e) {
			throw new DecoderException(e);
		} finally {
			/*
			 * try { if (cumulation != null && cumulation.readableBytes() > 0) { //
			 * System.out.println(); } if (cumulation != null && !cumulation.isReadable()) {
			 * cumulation.release(); // System.out.println("" + cumulation + "released");
			 * cumulation = null; } // int size = out.size(); // fireChannelRead(ctx, out,
			 * size); // if (size > 0) { // Something was read, call
			 * fireChannelReadComplete() ctx.fireChannelReadComplete(); // } if
			 * (callChannelInactive) { ctx.fireChannelInactive(); } } finally { // Recycle
			 * in all cases // out.recycle(); }
			 */
		}
	}

	/**
	 * Called when the input of the channel was closed which may be because it
	 * changed to inactive or because of {@link ChannelInputShutdownEvent}.
	 */
	void channelInputClosed(ChannelHandlerContext ctx) throws Exception {
		if (cumulation != null) {
			// callDecode(ctx, cumulation, out);
			decodeLast(ctx, cumulation);
		} else {
			decodeLast(ctx, Unpooled.EMPTY_BUFFER);
		}
	}

	/**
	 * Decode the from one {@link ByteBuf} to an other. This method will be called
	 * till either the input {@link ByteBuf} has nothing to read when return from
	 * this method or till nothing was read from the input {@link ByteBuf}.
	 *
	 * @param ctx the {@link ChannelHandlerContext} which this
	 *            {@link ByteToMessageDecoder} belongs to
	 * @param in  the {@link ByteBuf} from which to read data
	 * @param out the {@link List} to which decoded messages should be added
	 * @throws Exception is thrown if an error occurs
	 */
	final void decodeRemovalReentryProtection(ChannelHandlerContext ctx, ByteBuf in) throws Exception {
		try {
			// decode(ctx, in, out);
			NettyChannel nettyChannel = endpoint.getOrAddChannel((SocketChannel) ctx.channel());
			//nettyChannel.setByteBuf(cumulation);

			ctx.channel().config().setAutoRead(false);

			//if (nettyChannel.isBlock()) {
			//	nettyChannel.getCountDownLatch().countDown();
			//} else {
			//	endpoint.processSocket(nettyChannel, SocketEvent.OPEN_READ, true);
			//}

		} finally {
			// fireChannelRead(ctx, out, out.size());
			handlerRemoved(ctx);
		}
	}

	/**
	 * Is called one last time when the {@link ChannelHandlerContext} goes
	 * in-active. Which means the {@link #channelInactive(ChannelHandlerContext)}
	 * was triggered.
	 *
	 * By default this will just call
	 * {@link #decode(ChannelHandlerContext, ByteBuf, List)} but sub-classes may
	 * override this for some special cleanup operation.
	 */
	protected void decodeLast(ChannelHandlerContext ctx, ByteBuf in) throws Exception {
		if (in.isReadable()) {
			// Only call decode() if there is something left in the buffer to decode.
			// See https://github.com/netty/netty/issues/4386
			decodeRemovalReentryProtection(ctx, in);
		}
	}

	static ByteBuf expandCumulation(ByteBufAllocator alloc, ByteBuf oldCumulation, ByteBuf in) {
		int oldBytes = oldCumulation.readableBytes();
		int newBytes = in.readableBytes();
		int totalBytes = oldBytes + newBytes;
		ByteBuf newCumulation = alloc.buffer(alloc.calculateNewCapacity(totalBytes, MAX_VALUE));
		ByteBuf toRelease = newCumulation;
		try {
			// This avoids redundant checks and stack depth compared to calling
			// writeBytes(...)
			newCumulation.setBytes(0, oldCumulation, oldCumulation.readerIndex(), oldBytes)
					.setBytes(oldBytes, in, in.readerIndex(), newBytes).writerIndex(totalBytes);
			in.readerIndex(in.writerIndex());
			toRelease = oldCumulation;
			return newCumulation;
		} finally {
			toRelease.release();
		}
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
		System.out.println("channel " + ctx.channel().hashCode() + " exception caught");
		cause.printStackTrace();
		NettyChannel nettyChannel = endpoint.getOrAddChannel((SocketChannel) ctx.channel());
		if (cause instanceof IOException) {
			nettyChannel.setError((IOException) cause);
		}
		//if (nettyChannel.isBlock()) {
		//	nettyChannel.getCountDownLatch().countDown();
		//} else {
		//	endpoint.processSocket(nettyChannel, SocketEvent.ERROR, true);
		//}
		// super.exceptionCaught(ctx, cause);
	}

	@Override
	public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
		System.out.println("channel " + ctx.channel().hashCode() + " unregistered");
		super.channelUnregistered(ctx);
	}

}
