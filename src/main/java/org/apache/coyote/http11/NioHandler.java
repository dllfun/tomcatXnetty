package org.apache.coyote.http11;

import java.io.IOException;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;

import org.apache.coyote.AbstractProtocol;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.net.Channel;
import org.apache.tomcat.util.net.NioChannel;
import org.apache.tomcat.util.net.SocketEvent;
import org.apache.tomcat.util.net.Endpoint.Handler;
import org.apache.tomcat.util.res.StringManager;

public class NioHandler implements Handler<NioChannel> {

	private static final Log log = LogFactory.getLog(NioHandler.class);

	protected static final StringManager sm = StringManager.getManager(AbstractHttp11Protocol.class);

	private Handler next;

	public NioHandler(Handler next) {
		this.next = next;
	}

	@Override
	public AbstractProtocol<NioChannel> getProtocol() {
		return null;
	}

	@Override
	public void processSocket(Channel<NioChannel> channel, SocketEvent event, boolean dispatch) {

		NioChannel socket = channel.getSocket();
		if (socket == null || !socket.isOpen()) {
			channel.close();
			return;
		}
		// Poller poller = NioEndpoint.this.poller;
		// if (poller == null) {
		// channel.close();
		// return;
		// }

		try {
			int handshake = -1;
			try {
				if (socket.isHandshakeComplete()) {
					// No TLS handshaking required. Let the handler
					// process this socket / event combination.
					handshake = 0;
				} else if (event == SocketEvent.STOP || event == SocketEvent.DISCONNECT || event == SocketEvent.ERROR) {
					// Unable to complete the TLS handshake. Treat it as
					// if the handshake failed.
					handshake = -1;
				} else {
					handshake = socket.handshake(event == SocketEvent.OPEN_READ, event == SocketEvent.OPEN_WRITE);
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
				if (log.isDebugEnabled())
					log.debug("Error during SSL handshake", x);
			} catch (CancelledKeyException ckx) {
				handshake = -1;
			}
			if (handshake == 0) {
				// Process the request from this socket
				if (event == null) {
					System.out.println(socket.getIOChannel().socket().getPort() + " process " + SocketEvent.OPEN_READ);
					next.processSocket(channel, SocketEvent.OPEN_READ, dispatch);
				} else {
					System.out.println(socket.getIOChannel().socket().getPort() + " process " + event);
					next.processSocket(channel, event, dispatch);
				}

			} else if (handshake == -1) {
				next.processSocket(channel, SocketEvent.CONNECT_FAIL, dispatch);
				// poller.cancelledKey(socket.getIOChannel().keyFor(poller.getSelector()),
				// socketWrapper);
				channel.close();
			} else if (handshake == SelectionKey.OP_READ) {
				System.out.println(socket.getIOChannel().socket().getPort() + " registerReadInterest ");
				channel.registerReadInterest();
			} else if (handshake == SelectionKey.OP_WRITE) {
				System.out.println(socket.getIOChannel().socket().getPort() + " registerWriteInterest ");
				channel.registerWriteInterest();
			}
		} catch (CancelledKeyException cx) {
			// poller.cancelledKey(socket.getIOChannel().keyFor(poller.getSelector()),
			// socketWrapper);
			channel.close();
		} catch (VirtualMachineError vme) {
			ExceptionUtils.handleThrowable(vme);
		} catch (Throwable t) {
			log.error(sm.getString("endpoint.processing.fail"), t);
			// poller.cancelledKey(socket.getIOChannel().keyFor(poller.getSelector()),
			// socketWrapper);
			channel.close();
		} finally {
			channel = null;
			event = null;
			// return to cache
			// if (isRunning() && !isPaused() && processorCache != null) {
			// processorCache.push(this);
			// }
		}

	}

}
