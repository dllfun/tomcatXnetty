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
import org.apache.tomcat.util.net.Channel;
import org.apache.tomcat.util.net.Endpoint.Handler.SocketState;
import org.apache.tomcat.util.net.SSLSupport;
import org.apache.tomcat.util.net.SocketChannel;
import org.apache.tomcat.util.net.SocketEvent;
import org.apache.tomcat.util.res.StringManager;

public class UpgradeProcessorExternal extends UpgradeProcessorBase {

	private static final Log log = LogFactory.getLog(UpgradeProcessorExternal.class);
	private static final StringManager sm = StringManager.getManager(UpgradeProcessorExternal.class);

	private final UpgradeServletInputStream upgradeServletInputStream;
	private final UpgradeServletOutputStream upgradeServletOutputStream;

	public UpgradeProcessorExternal(AbstractProtocol<?> protocol, SocketChannel channel, UpgradeToken upgradeToken) {
		super(protocol, upgradeToken);
		this.upgradeServletInputStream = new UpgradeServletInputStream(this, channel);
		this.upgradeServletOutputStream = new UpgradeServletOutputStream(this, channel);

		/*
		 * Leave timeouts in the hands of the upgraded protocol.
		 */
		channel.setReadTimeout(INFINITE_TIMEOUT);
		channel.setWriteTimeout(INFINITE_TIMEOUT);
	}

	@Override
	protected Log getLog() {
		return log;
	}

	// --------------------------------------------------- AutoCloseable methods

	@Override
	public void close() throws Exception {
		upgradeServletInputStream.close();
		upgradeServletOutputStream.close();
	}

	// --------------------------------------------------- WebConnection methods

	@Override
	public ServletInputStream getInputStream() throws IOException {
		return upgradeServletInputStream;
	}

	@Override
	public ServletOutputStream getOutputStream() throws IOException {
		return upgradeServletOutputStream;
	}

	// ------------------------------------------- Implemented Processor methods

	@Override
	public boolean processInIoThread(SocketEvent event) throws IOException {
		return true;
	}

	@Override
	public final SocketState dispatch(SocketEvent status) {
		if (status == SocketEvent.OPEN_READ) {
			upgradeServletInputStream.onDataAvailable();
		} else if (status == SocketEvent.OPEN_WRITE) {
			upgradeServletOutputStream.onWritePossible();
		} else if (status == SocketEvent.STOP) {
			if (log.isDebugEnabled()) {
				log.debug(sm.getString("upgradeProcessor.stop"));
			}
			try {
				upgradeServletInputStream.close();
			} catch (IOException ioe) {
				log.debug(sm.getString("upgradeProcessor.isCloseFail", ioe));
			}
			try {
				upgradeServletOutputStream.close();
			} catch (IOException ioe) {
				log.debug(sm.getString("upgradeProcessor.osCloseFail", ioe));
			}
			return SocketState.CLOSED;
		} else {
			// Unexpected state
			if (log.isDebugEnabled()) {
				log.debug(sm.getString("upgradeProcessor.unexpectedState"));
			}
			return SocketState.CLOSED;
		}
		if (upgradeServletInputStream.isClosed() && upgradeServletOutputStream.isClosed()) {
			if (log.isDebugEnabled()) {
				log.debug(sm.getString("upgradeProcessor.requiredClose",
						Boolean.valueOf(upgradeServletInputStream.isClosed()),
						Boolean.valueOf(upgradeServletOutputStream.isClosed())));
			}
			return SocketState.CLOSED;
		}
		return SocketState.UPGRADED;
	}

	// ----------------------------------------- Unimplemented Processor methods

	// @Override
	// public final void setSslSupport(SSLSupport sslSupport) {
	// NO-OP
	// }

	@Override
	public void pause() {
		// NOOP
	}
}
