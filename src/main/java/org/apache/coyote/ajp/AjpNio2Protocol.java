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

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.net.Channel;
import org.apache.tomcat.util.net.Nio2Channel;
import org.apache.tomcat.util.net.Nio2Endpoint;
import org.apache.tomcat.util.net.NioChannel;
import org.apache.tomcat.util.net.SocketEvent;
import org.apache.tomcat.util.net.Endpoint.Handler.SocketState;
import org.apache.tomcat.util.net.Nio2Endpoint.Nio2SocketWrapper;

/**
 * This the NIO2 based protocol handler implementation for AJP.
 */
public class AjpNio2Protocol extends AbstractAjpProtocol<Nio2Channel> {

	private static final Log log = LogFactory.getLog(AjpNio2Protocol.class);

	@Override
	protected Log getLog() {
		return log;
	}

	// ------------------------------------------------------------ Constructor

	public AjpNio2Protocol() {
		super(new Nio2Endpoint());
	}

	// ----------------------------------------------------- JMX related methods

	@Override
	protected String getNamePrefix() {
		return "ajp-nio2";
	}

	@Override
	public void processSocket(Channel<Nio2Channel> channel, SocketEvent event) {
		Nio2Channel socket = channel.getSocket();
		if (socket == null || !socket.isOpen()) {
			channel.close();
			return;
		}

		boolean launch = false;
		try {
			int handshake = -1;

			try {

				if (channel.getSocket().isHandshakeComplete()) {
					// No TLS handshaking required. Let the handler
					// process this socket / event combination.
					handshake = 0;
				} else if (event == SocketEvent.STOP || event == SocketEvent.DISCONNECT || event == SocketEvent.ERROR) {
					// Unable to complete the TLS handshake. Treat it as
					// if the handshake failed.
					handshake = -1;
				} else {
					handshake = channel.getSocket().handshake();
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
				SocketState state = SocketState.OPEN;
				// Process the request from this socket
				if (event == null) {
					state = process(channel, SocketEvent.OPEN_READ);
				} else {
					state = process(channel, event);
				}
				if (state == SocketState.CLOSED) {
					// Close socket and pool
					channel.close();
				} else if (state == SocketState.UPGRADING) {
					launch = true;
				}
			} else if (handshake == -1) {
				process(channel, SocketEvent.CONNECT_FAIL);
				channel.close();
			}
		} catch (VirtualMachineError vme) {
			ExceptionUtils.handleThrowable(vme);
		} catch (Throwable t) {
			log.error(sm.getString("endpoint.processing.fail"), t);
			if (channel != null) {
				((Nio2SocketWrapper) channel).close();
			}
		} finally {
			if (launch) {
				try {
					getExecutor().execute(new Runnable() {

						@Override
						public void run() {
							processSocket(channel, SocketEvent.OPEN_READ);
						}
					});
				} catch (NullPointerException npe) {
					if (endpoint.isRunning()) {
						log.error(sm.getString("endpoint.launch.fail"), npe);
					}
				}
			}
			// channel = null;
			// event = null;
			// return to cache
			// if (isRunning() && !isPaused() && processorCache != null) {
			// processorCache.push(this);
			// }
		}
	}
}
