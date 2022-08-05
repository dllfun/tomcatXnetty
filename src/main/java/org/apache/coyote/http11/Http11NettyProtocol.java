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

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.net.NettyEndpoint;

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
}
