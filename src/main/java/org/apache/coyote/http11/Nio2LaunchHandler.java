package org.apache.coyote.http11;

import java.io.IOException;

import org.apache.coyote.AbstractProtocol;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.net.Channel;
import org.apache.tomcat.util.net.Nio2Channel;
import org.apache.tomcat.util.net.SocketEvent;
import org.apache.tomcat.util.net.Endpoint.Handler;
import org.apache.tomcat.util.net.Endpoint.Handler.SocketState;
import org.apache.tomcat.util.net.Nio2Endpoint.Nio2SocketWrapper;
import org.apache.tomcat.util.net.SocketChannel;
import org.apache.tomcat.util.res.StringManager;

public class Nio2LaunchHandler implements Handler {

	private static final Log log = LogFactory.getLog(Http11Nio2Protocol.class);

	protected static final StringManager sm = StringManager.getManager(AbstractHttp11Protocol.class);

	private Handler next;

	private AbstractProtocol<Nio2Channel> protocol;

	public Nio2LaunchHandler(Handler next, AbstractProtocol<Nio2Channel> protocol) {
		this.next = next;
		this.protocol = protocol;
	}

	@Override
	public AbstractProtocol getProtocol() {
		return protocol;
	}

	@Override
	public void processSocket(Channel channel, SocketEvent event, boolean dispatch) {
		SocketChannel socketChannel = (SocketChannel) channel;
		if (channel instanceof Nio2SocketWrapper) {
			Nio2SocketWrapper nio2SocketWrapper = (Nio2SocketWrapper) channel;

			Nio2Channel socket = nio2SocketWrapper.getSocket();
			if (socket == null || !socket.isOpen()) {
				socketChannel.close();
				return;
			}

			// TODO check launch
			boolean launch = false;
			try {
				int handshake = -1;

				try {

					if (socket.isHandshakeComplete()) {
						// No TLS handshaking required. Let the handler
						// process this socket / event combination.
						handshake = 0;
					} else if (event == SocketEvent.STOP || event == SocketEvent.DISCONNECT
							|| event == SocketEvent.ERROR) {
						// Unable to complete the TLS handshake. Treat it as
						// if the handshake failed.
						handshake = -1;
					} else {
						handshake = socket.handshake();
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
					handshake = -1;
					if (log.isDebugEnabled()) {
						log.debug(sm.getString("endpoint.err.handshake"), x);
					}
				}
				if (handshake == 0) {
					// SocketState state = SocketState.OPEN;
					// Process the request from this socket
					if (event == null) {
						next.processSocket(channel, SocketEvent.OPEN_READ, dispatch);
					} else {
						next.processSocket(channel, event, dispatch);
					}
					// if (state == SocketState.CLOSED) {
					// Close socket and pool
					// channel.close();
					// } else if (state == SocketState.UPGRADING) {
					// launch = true;
					// }
				} else if (handshake == -1) {
					next.processSocket(channel, SocketEvent.CONNECT_FAIL, dispatch);
					socketChannel.close();
				}
			} catch (VirtualMachineError vme) {
				ExceptionUtils.handleThrowable(vme);
			} catch (Throwable t) {
				log.error(sm.getString("endpoint.processing.fail"), t);
				if (channel != null) {
					((Nio2SocketWrapper) channel).close();
				}
			} finally {
//			if (launch) {
//				try {
//					getExecutor().execute(new Runnable() {
//
//						@Override
//						public void run() {
//							processSocket(channel, SocketEvent.OPEN_READ);
//						}
//					});
//				} catch (NullPointerException npe) {
//					if (endpoint.isRunning()) {
//						log.error(sm.getString("endpoint.launch.fail"), npe);
//					}
//				}
//			}
				// channel = null;
				// event = null;
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
