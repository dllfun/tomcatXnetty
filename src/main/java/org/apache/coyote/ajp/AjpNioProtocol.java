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
package org.apache.coyote.ajp;

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
import org.apache.tomcat.util.net.Endpoint.Handler.SocketState;

/**
 * This the NIO based protocol handler implementation for AJP.
 */
public class AjpNioProtocol extends AbstractAjpProtocol<NioChannel> {

	private static final Log log = LogFactory.getLog(AjpNioProtocol.class);

	@Override
	protected Log getLog() {
		return log;
	}

	// ------------------------------------------------------------ Constructor

	public AjpNioProtocol() {
		super(new NioEndpoint());
	}

	// ----------------------------------------------------- JMX related methods

	@Override
	protected String getNamePrefix() {
		return "ajp-nio";
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
					channel.close();
					// poller.cancelledKey(socket.getIOChannel().keyFor(poller.getSelector()),
					// socketWrapper);
				}
			} else if (handshake == -1) {
				process(channel, SocketEvent.CONNECT_FAIL);
				channel.close();
				// poller.cancelledKey(socket.getIOChannel().keyFor(poller.getSelector()),
				// socketWrapper);
			} else if (handshake == SelectionKey.OP_READ) {
				System.out.println(socket.getIOChannel().socket().getPort() + " registerReadInterest ");
				channel.registerReadInterest();
			} else if (handshake == SelectionKey.OP_WRITE) {
				System.out.println(socket.getIOChannel().socket().getPort() + " registerWriteInterest ");
				channel.registerWriteInterest();
			}
		} catch (CancelledKeyException cx) {
			channel.close();
			// poller.cancelledKey(socket.getIOChannel().keyFor(poller.getSelector()),
			// socketWrapper);
		} catch (VirtualMachineError vme) {
			ExceptionUtils.handleThrowable(vme);
		} catch (Throwable t) {
			log.error(sm.getString("endpoint.processing.fail"), t);
			channel.close();
			// poller.cancelledKey(socket.getIOChannel().keyFor(poller.getSelector()),
			// socketWrapper);
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
