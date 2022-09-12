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

import org.apache.coyote.AbstractProcessor;
import org.apache.coyote.Adapter;
import org.apache.coyote.ErrorState;
import org.apache.coyote.Request;
import org.apache.coyote.RequestAction;
import org.apache.coyote.Response;
import org.apache.coyote.ResponseAction;
import org.apache.coyote.http11.Http11InputBuffer;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.net.BufWrapper;
import org.apache.tomcat.util.net.Channel;
import org.apache.tomcat.util.net.Endpoint.Handler.SocketState;
import org.apache.tomcat.util.net.SendfileState;
import org.apache.tomcat.util.net.SocketChannel;
import org.apache.tomcat.util.res.StringManager;

/**
 * AJP Processor implementation.
 */
public class AjpProcessor extends AbstractProcessor {

	private static final Log log = LogFactory.getLog(AjpProcessor.class);
	/**
	 * The string manager for this package.
	 */
	private static final StringManager sm = StringManager.getManager(AjpProcessor.class);

	// ----------------------------------------------------- Instance Variables

	// Expected to block on the first read as there should be at least one
	// AJP message to read.
	boolean firstRead = true;

	// ------------------------------------------------------------ Constructor

	public AjpProcessor(AbstractAjpProtocol<?> protocol, Adapter adapter) {
		super(protocol, adapter);
	}

	@Override
	protected RequestAction createRequestAction() {
		return new SocketInputReader(this);
	}

	@Override
	protected ResponseAction createResponseAction() {
		return new SocketOutputBuffer(this);
	}

	public AbstractAjpProtocol<?> getProtocol() {
		return (AbstractAjpProtocol<?>) super.getProtocol();
	}

	// --------------------------------------------------------- Public Methods

	@Override
	protected void onChannelReady(Channel channel) {

	}

	@Override
	protected boolean repeat() {
		return true;
	}

	@Override
	protected boolean isHttp2Preface() {
		return false;
	}

	@Override
	protected boolean parsingHeader() {
		// Parsing the request header
//		boolean cping = false;

		try {
			// Get first message of the request
			if (!((SocketInputReader) requestAction).fillHeaderMessage(firstRead)) {
				return false;
			}
			firstRead = false;

			// Processing the request so make sure the connection rather
			// than keep-alive timeout is used
			((SocketChannel) getChannel()).setReadTimeout(protocol.getConnectionTimeout());
			exchangeData.setStartTime(System.currentTimeMillis());

		} catch (IOException e) {
			setErrorState(ErrorState.CLOSE_CONNECTION_NOW, e);
		} catch (Throwable t) {
			ExceptionUtils.handleThrowable(t);
			getLog().debug(sm.getString("ajpprocessor.header.error"), t);
			// 400 - Bad Request
			exchangeData.setStatus(400);
			setErrorState(ErrorState.CLOSE_CLEAN, t);
		}
		return true;

	}

	@Override
	protected boolean canReleaseProcessor() {
		BufWrapper byteBuffer = ((SocketChannel) getChannel()).getAppReadBuffer();
		if (byteBuffer.getPosition() == 0 && byteBuffer.getRemaining() == 0) {
			return true;
		} else {
			return false;
		}
	}

	/**
	 * After reading the request headers, we have to setup the request filters.
	 */
	@Override
	protected void prepareRequest() {
		((SocketInputReader) requestAction).prepareRequest();
	}

	@Override
	protected void resetSocketReadTimeout() {
		// Set keep alive timeout for next request
		((SocketChannel) getChannel()).setReadTimeout(protocol.getKeepAliveTimeout());
	}

	@Override
	protected void finishActions() {
		// Swallow the unread body packet if present
		if (getErrorState().isIoAllowed()) {
			try {
				requestAction.finish();
			} catch (IOException e) {
				setErrorState(ErrorState.CLOSE_CONNECTION_NOW, e);
			} catch (Throwable t) {
				ExceptionUtils.handleThrowable(t);
				// 500 - Internal Server Error
				// Can't add a 500 to the access log since that has already been
				// written in the Adapter.service method.
				exchangeData.setStatus(500);
				setErrorState(ErrorState.CLOSE_NOW, t);
				log.error(sm.getString("ajpprocessor.request.finish"), t);
			}
		}
		// Finish the response if not done yet
		if (!responseAction.isResponseFinished() && getErrorState().isIoAllowed()) {
//						try {
			responseAction.finish();
			// outputBuffer.commit(true);
			// outputBuffer.finishResponse();
//						} catch (IOException ioe) {
//							setErrorState(ErrorState.CLOSE_CONNECTION_NOW, ioe);
//						} catch (Throwable t) {
//							ExceptionUtils.handleThrowable(t);
//							setErrorState(ErrorState.CLOSE_NOW, t);
//						}
		}

	}

	@Override
	protected SendfileState processSendfile() throws IOException {
		return SendfileState.DONE;
	}

	@Override
	protected void dispatchNonBlockingRead() {
		if (requestAction.getAvailable(true) > 0) {
			super.dispatchNonBlockingRead();
		}
	}

	@Override
	protected SocketState dispatchFinishActions() {
		// Set keep alive timeout for next request
		((SocketChannel) getChannel()).setReadTimeout(protocol.getKeepAliveTimeout());
		nextRequest();
		if (protocol.isPaused()) {
			return SocketState.CLOSED;
		} else {
			return SocketState.OPEN;
		}
	}

	@Override
	protected void nextRequestInternal() {
		Request request = createRequest();
		Response response = createResponse();
		request.setResponse(response);
		getAdapter().checkRecycled(request, response);
		// super.recycle();
		requestAction.recycle();
		responseAction.recycle();
	}

	@Override
	protected void recycleInternal() {
		Request request = createRequest();
		Response response = createResponse();
		request.setResponse(response);
		getAdapter().checkRecycled(request, response);
		// super.recycle();
//		first = true;
		requestAction.recycle();
		responseAction.recycle();
//		waitingForBodyMessage = false;
//		empty = true;
//		replay = false;
//		responseFinished = false;
//		swallowResponse = false;
//		bytesWritten = 0;
	}

	@Override
	public void pause() {
		// NOOP for AJP
	}

	// ------------------------------------------------------ Protected Methods

	@Override
	protected Log getLog() {
		return log;
	}

}
