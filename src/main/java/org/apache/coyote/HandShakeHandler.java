package org.apache.coyote;

import java.io.IOException;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;

import org.apache.coyote.AbstractProtocol;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.net.Channel;
import org.apache.tomcat.util.net.Endpoint.Handler;
import org.apache.tomcat.util.net.HandShakeable;
import org.apache.tomcat.util.net.NioChannel;
import org.apache.tomcat.util.net.SocketChannel;
import org.apache.tomcat.util.net.SocketEvent;
import org.apache.tomcat.util.res.StringManager;

public class HandShakeHandler implements Handler {

	private static final Log log = LogFactory.getLog(HandShakeHandler.class);

	protected static final StringManager sm = StringManager.getManager(AbstractProtocol.class);

	private Handler next;

	public HandShakeHandler(Handler next) {
		this.next = next;
	}

	@Override
	public AbstractProtocol getProtocol() {
		return null;
	}

	@Override
	public void processSocket(Channel channel, SocketEvent event, boolean dispatch) {
		if (channel instanceof HandShakeable) {

			HandShakeable handShakeable = (HandShakeable) channel;

			// NioSocketWrapper nioSocketWrapper = (NioSocketWrapper) channel;
			// NioChannel socket = nioSocketWrapper.getSocket();
			// if (socket == null || !socket.isOpen()) {
			// channel.close();
			// return;
			// }
			// Poller poller = NioEndpoint.this.poller;
			// if (poller == null) {
			// channel.close();
			// return;
			// }

			try {
				int handshake = HandShakeable.HANDSHAKE_FAIL;
				try {
					if (handShakeable.isHandshakeComplete()) {
						// No TLS handshaking required. Let the handler
						// process this socket / event combination.
						handshake = HandShakeable.HANDSHAKE_COMPLETE;
					} else if (event == SocketEvent.STOP || event == SocketEvent.DISCONNECT
							|| event == SocketEvent.ERROR) {
						// Unable to complete the TLS handshake. Treat it as
						// if the handshake failed.
						handshake = HandShakeable.HANDSHAKE_FAIL;
					} else {
						handshake = handShakeable.handshake(event == SocketEvent.OPEN_READ,
								event == SocketEvent.OPEN_WRITE);
						// The handshake process reads/writes from/to the
						// socket. status may therefore be OPEN_WRITE once
						// the handshake completes. However, the handshake
						// happens when the socket is opened so the status
						// must always be OPEN_READ after it completes. It
						// is OK to always set this as it is only used if
						// the handshake completes.
						event = SocketEvent.OPEN_READ;
					}
				} catch (IOException x) {
					handshake = HandShakeable.HANDSHAKE_FAIL;
					if (log.isDebugEnabled())
						log.debug("Error during SSL handshake", x);
				} catch (CancelledKeyException ckx) {
					handshake = HandShakeable.HANDSHAKE_FAIL;
				}
				if (handshake == HandShakeable.HANDSHAKE_COMPLETE) {
					// Process the request from this socket
					if (event == null) {
						next.processSocket(channel, SocketEvent.OPEN_READ, dispatch);
					} else {
						next.processSocket(channel, event, dispatch);
					}

				} else if (handshake == HandShakeable.HANDSHAKE_FAIL) {
					next.processSocket(channel, SocketEvent.CONNECT_FAIL, dispatch);
					// poller.cancelledKey(socket.getIOChannel().keyFor(poller.getSelector()),
					// socketWrapper);
					channel.close();
				} else if (handshake == HandShakeable.HANDSHAKE_NEEDREAD) {
					if (channel instanceof SocketChannel) {
						((SocketChannel) channel).registerReadInterest();
					}
				} else if (handshake == HandShakeable.HANDSHAKE_NEEDWRITE) {
					if (channel instanceof SocketChannel) {
						((SocketChannel) channel).registerWriteInterest();
					}
				} else if (handshake == HandShakeable.HANDSHAKE_IGNORE) {
					// do nothing
				}
			} catch (CancelledKeyException cx) {
				// poller.cancelledKey(socket.getIOChannel().keyFor(poller.getSelector()),
				// socketWrapper);
				channel.close(cx);
			} catch (VirtualMachineError vme) {
				ExceptionUtils.handleThrowable(vme);
			} catch (Throwable t) {
				log.error(sm.getString("endpoint.processing.fail"), t);
				// poller.cancelledKey(socket.getIOChannel().keyFor(poller.getSelector()),
				// socketWrapper);
				channel.close(t);
			} finally {
				channel = null;
				event = null;
				// return to cache
				// if (isRunning() && !isPaused() && processorCache != null) {
				// processorCache.push(this);
				// }
			}
		} else {
			next.processSocket(channel, event, dispatch);
		}
	}

}
