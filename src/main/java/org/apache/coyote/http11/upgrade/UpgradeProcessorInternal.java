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
package org.apache.coyote.http11.upgrade;

import java.io.IOException;

import javax.servlet.ServletInputStream;
import javax.servlet.ServletOutputStream;

import org.apache.coyote.AbstractProtocol;
import org.apache.coyote.UpgradeToken;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.net.Endpoint.Handler.SocketState;
import org.apache.tomcat.util.net.SSLSupport;
import org.apache.tomcat.util.net.SocketChannel;
import org.apache.tomcat.util.net.SocketEvent;

public class UpgradeProcessorInternal extends UpgradeProcessorBase {

	private static final Log log = LogFactory.getLog(UpgradeProcessorInternal.class);

	private final InternalHttpUpgradeHandler internalHttpUpgradeHandler;

	public UpgradeProcessorInternal(AbstractProtocol<?> protocol, SocketChannel channel, UpgradeToken upgradeToken) {
		super(protocol, upgradeToken);
		this.internalHttpUpgradeHandler = (InternalHttpUpgradeHandler) upgradeToken.getHttpUpgradeHandler();
		/*
		 * Leave timeouts in the hands of the upgraded protocol.
		 */
		channel.setReadTimeout(INFINITE_TIMEOUT);
		channel.setWriteTimeout(INFINITE_TIMEOUT);

		internalHttpUpgradeHandler.setChannel(channel);
	}

	@Override
	public SocketState dispatch(SocketEvent status) {
		return internalHttpUpgradeHandler.upgradeDispatch(status);
	}

	@Override
	public final void setSslSupport(SSLSupport sslSupport) {
		internalHttpUpgradeHandler.setSslSupport(sslSupport);
	}

	@Override
	public void pause() {
		internalHttpUpgradeHandler.pause();
	}

	@Override
	protected Log getLog() {
		return log;
	}

	@Override
	public void timeoutAsync(long now) {
		internalHttpUpgradeHandler.timeoutAsync(now);
	}

	public boolean hasAsyncIO() {
		return internalHttpUpgradeHandler.hasAsyncIO();
	}

	// --------------------------------------------------- AutoCloseable methods

	@Override
	public void close() throws Exception {
		internalHttpUpgradeHandler.destroy();
	}

	// --------------------------------------------------- WebConnection methods

	@Override
	public ServletInputStream getInputStream() throws IOException {
		return null;
	}

	@Override
	public ServletOutputStream getOutputStream() throws IOException {
		return null;
	}
}
