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

import org.apache.coyote.AbstractProtocol.HeadHandler;
import org.apache.coyote.AbstractProtocol.TailHandler;
import org.apache.coyote.http11.AprHandler;
import org.apache.coyote.http11.HandShakeHandler;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.net.Channel;
import org.apache.tomcat.util.net.NioChannel;
import org.apache.tomcat.util.net.NioEndpoint;
import org.apache.tomcat.util.net.SocketEvent;
import org.apache.tomcat.util.net.Endpoint.Handler;
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

		Handler tailHandler = new TailHandler();
		Handler handShakeHandler = new HandShakeHandler(tailHandler);
		Handler headHandler = new HeadHandler<>(handShakeHandler);
		setHandler(headHandler);

		endpoint.setHandler(headHandler);
	}

	// ----------------------------------------------------- JMX related methods

	@Override
	protected String getNamePrefix() {
		return "ajp-nio";
	}

}
