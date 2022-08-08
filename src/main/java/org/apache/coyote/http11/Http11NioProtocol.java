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
package org.apache.coyote.http11;

import java.io.IOException;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.net.Channel;
import org.apache.tomcat.util.net.NioChannel;
import org.apache.tomcat.util.net.NioEndpoint;
import org.apache.tomcat.util.net.SocketEvent;

/**
 * Abstract the protocol implementation, including threading, etc. Processor is
 * single threaded and specific to stream-based protocols, will not fit Jk
 * protocols like JNI.
 *
 * @author Remy Maucherat
 * @author Costin Manolache
 */
public class Http11NioProtocol extends AbstractHttp11JsseProtocol<NioChannel> {

	private static final Log log = LogFactory.getLog(Http11NioProtocol.class);

	public Http11NioProtocol() {
		super(new NioEndpoint());
	}

	@Override
	protected Log getLog() {
		return log;
	}

	// -------------------- Pool setup --------------------

	/**
	 * NO-OP.
	 *
	 * @param count Unused
	 *
	 * @deprecated This setter will be removed in Tomcat 10.
	 */
	@Deprecated
	public void setPollerThreadCount(int count) {
	}

	/**
	 * Always returns 1.
	 *
	 * @return 1
	 *
	 * @deprecated This getter will be removed in Tomcat 10.
	 */
	@Deprecated
	public int getPollerThreadCount() {
		return 1;
	}

	public void setSelectorTimeout(long timeout) {
		((NioEndpoint) getEndpoint()).setSelectorTimeout(timeout);
	}

	public long getSelectorTimeout() {
		return ((NioEndpoint) getEndpoint()).getSelectorTimeout();
	}

	public void setPollerThreadPriority(int threadPriority) {
		((NioEndpoint) getEndpoint()).setPollerThreadPriority(threadPriority);
	}

	public int getPollerThreadPriority() {
		return ((NioEndpoint) getEndpoint()).getPollerThreadPriority();
	}

	// ----------------------------------------------------- JMX related methods

	@Override
	protected String getNamePrefix() {
		if (isSSLEnabled()) {
			return "https-" + getSslImplementationShortName() + "-nio";
		} else {
			return "http-nio";
		}
	}

	@Override
	public void processSocket(Channel<NioChannel> channel, SocketEvent event) {

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
				SocketState state = SocketState.OPEN;
				// Process the request from this socket
				if (event == null) {
					System.out.println(socket.getIOChannel().socket().getPort() + " process " + SocketEvent.OPEN_READ);
					state = process(channel, SocketEvent.OPEN_READ);
				} else {
					System.out.println(socket.getIOChannel().socket().getPort() + " process " + event);
					state = process(channel, event);
				}
				if (state == SocketState.CLOSED) {
					// poller.cancelledKey(socket.getIOChannel().keyFor(poller.getSelector()),
					// socketWrapper);
					channel.close();
				}
			} else if (handshake == -1) {
				process(channel, SocketEvent.CONNECT_FAIL);
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
