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

import javax.servlet.http.HttpUpgradeHandler;

import org.apache.tomcat.util.net.Endpoint.Handler.SocketState;
import org.apache.tomcat.util.net.SocketChannel;
import org.apache.tomcat.util.net.SocketEvent;

/**
 * This Tomcat specific interface is implemented by handlers that require direct
 * access to Tomcat's I/O layer rather than going through the Servlet API.
 */
public interface InternalHttpUpgradeHandler extends HttpUpgradeHandler {

	boolean processInIoThread(SocketEvent event) throws IOException;

	SocketState upgradeDispatch(SocketEvent status);

	void timeoutAsync(long now);

	void setChannel(SocketChannel channel);

	// void setSslSupport(SSLSupport sslSupport);

	void pause();

	default boolean hasAsyncIO() {
		return false;
	}
}