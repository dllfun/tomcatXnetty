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

import java.nio.channels.CancelledKeyException;
import org.apache.coyote.AbstractProtocol;
import org.apache.coyote.DispatchHandler;
import org.apache.coyote.ParseInIoHandler;
import org.apache.coyote.ProcessorHandler;
import org.apache.coyote.http2.Http2Protocol;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.net.NettyEndpoint;
import org.apache.tomcat.util.net.SocketEvent;
import org.apache.tomcat.util.net.Endpoint.Handler;
import org.apache.tomcat.util.net.NettyEndpoint.NettyChannel;

import io.netty.channel.Channel;

/**
 * Abstract the protocol implementation, including threading, etc. Processor is
 * single threaded and specific to stream-based protocols, will not fit Jk
 * protocols like JNI.
 *
 * @author Remy Maucherat
 * @author Costin Manolache
 */
public class Http11NettyProtocol extends AbstractHttp11JsseProtocol<Channel> {

	private static final Log log = LogFactory.getLog(Http11NettyProtocol.class);

	public Http11NettyProtocol() {
		super(new NettyEndpoint());
		// addUpgradeProtocol(new Http2Protocol());
		Handler processorHandler = new ProcessorHandler(this);
		Handler nettyHandler = new NettyHandler(processorHandler);
		Handler dispatchHandler = new DispatchHandler(nettyHandler, this);
		Handler parseInIoHandler = new ParseInIoHandler(dispatchHandler, this);
		setHandler(parseInIoHandler);

		endpoint.setHandler(parseInIoHandler);
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

	// ----------------------------------------------------- JMX related methods

	@Override
	protected String getNamePrefix() {
		if (isSSLEnabled()) {
			return "https-" + getSslImplementationShortName() + "-netty";
		} else {
			return "http-netty";
		}
	}

	private class NettyHandler implements Handler {

		private Handler next;

		public NettyHandler(Handler next) {
			super();
			this.next = next;
		}

		@Override
		public AbstractProtocol getProtocol() {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public void processSocket(org.apache.tomcat.util.net.Channel channel, SocketEvent event, boolean dispatch) {
			if (channel instanceof NettyChannel) {

				NettyChannel nettyChannel = (NettyChannel) channel;

				io.netty.channel.Channel rawChannel = nettyChannel.getSocket();
				if (rawChannel == null) {
					nettyChannel.close();
					return;
				}

				try {

					// SocketState state = SocketState.OPEN;
					// Process the request from this socket
					if (event == null) {
						next.processSocket(nettyChannel, SocketEvent.OPEN_READ, dispatch);
					} else {
						next.processSocket(nettyChannel, event, dispatch);
					}
					// if (state == SocketState.CLOSED) {
					// System.out.println("close netty channel");
					// nettyChannel.close();
					// }

				} catch (CancelledKeyException cx) {
					nettyChannel.close();
				} catch (VirtualMachineError vme) {
					ExceptionUtils.handleThrowable(vme);
					nettyChannel.close();
				} catch (Throwable t) {
					// log.error(sm.getString("endpoint.processing.fail"), t);
					nettyChannel.close();
				}
			} else {
				next.processSocket(channel, event, dispatch);
			}
		}

	}

}
