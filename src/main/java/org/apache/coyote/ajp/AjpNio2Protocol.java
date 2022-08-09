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

import org.apache.coyote.AbstractProtocol.HeadHandler;
import org.apache.coyote.AbstractProtocol.TailHandler;
import org.apache.coyote.http11.AprHandler;
import org.apache.coyote.http11.Nio2Handler;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.net.Channel;
import org.apache.tomcat.util.net.Nio2Channel;
import org.apache.tomcat.util.net.Nio2Endpoint;
import org.apache.tomcat.util.net.NioChannel;
import org.apache.tomcat.util.net.SocketEvent;
import org.apache.tomcat.util.net.Endpoint.Handler;
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

		Handler tailHandler = new TailHandler();
		Handler nio2Handler = new Nio2Handler(tailHandler, this);
		Handler headHandler = new HeadHandler<>(nio2Handler);
		setHandler(headHandler);

		endpoint.setHandler(headHandler);
	}

	// ----------------------------------------------------- JMX related methods

	@Override
	protected String getNamePrefix() {
		return "ajp-nio2";
	}

}
