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

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import javax.servlet.http.HttpServletResponse;

import org.apache.coyote.AbstractProcessor;
import org.apache.coyote.Adapter;
import org.apache.coyote.ErrorState;
import org.apache.coyote.ExchangeData;
import org.apache.coyote.Request;
import org.apache.coyote.RequestInfo;
import org.apache.coyote.Response;
import org.apache.coyote.UpgradeProtocol;
import org.apache.coyote.UpgradeToken;
import org.apache.coyote.http11.upgrade.InternalHttpUpgradeHandler;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.buf.MessageBytes;
import org.apache.tomcat.util.http.MimeHeaders;
import org.apache.tomcat.util.http.parser.HttpParser;
import org.apache.tomcat.util.http.parser.TokenList;
import org.apache.tomcat.util.log.UserDataHelper;
import org.apache.tomcat.util.net.Channel;
import org.apache.tomcat.util.net.Endpoint.Handler.SocketState;
import org.apache.tomcat.util.net.SocketChannel.BufWrapper;
import org.apache.tomcat.util.net.SendfileDataBase;
import org.apache.tomcat.util.net.SendfileKeepAliveState;
import org.apache.tomcat.util.net.SendfileState;
import org.apache.tomcat.util.net.SocketChannel;
import org.apache.tomcat.util.net.SocketEvent;
import org.apache.tomcat.util.res.StringManager;

public class Http11Processor extends AbstractProcessor {

	private static final Log log = LogFactory.getLog(Http11Processor.class);

	/**
	 * The string manager for this package.
	 */
	private static final StringManager sm = StringManager.getManager(Http11Processor.class);

	private static final byte[] CLIENT_PREFACE_START = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n"
			.getBytes(StandardCharsets.ISO_8859_1);

	private final AbstractHttp11Protocol<?> protocol;

	/**
	 * Head parser
	 */
	private final Http11HeadParser headParser;

	/**
	 * Input.
	 */
	private final Http11InputBuffer inputBuffer;

	/**
	 * Output.
	 */
	private final Http11OutputBuffer outputBuffer;

	private final HttpParser httpParser;

	/**
	 * Flag used to indicate that the socket should be kept open (e.g. for keep
	 * alive or send file.
	 */
//	private boolean openSocket = false;

	/**
	 * Flag that indicates if the request headers have been completely read.
	 */
//	private boolean readComplete = true;

	/**
	 * Instance of the new protocol to use after the HTTP connection has been
	 * upgraded.
	 */
	private UpgradeToken upgradeToken = null;

	private boolean checkHttp2Preface = true;

	private boolean isHttp2Preface = false;

	private boolean keptAlive = false;

	private SocketChannel channel;

	public Http11Processor(AbstractHttp11Protocol<?> protocol, Adapter adapter) {
		super(protocol, adapter);
		this.protocol = protocol;

		httpParser = new HttpParser(protocol.getRelaxedPathChars(), protocol.getRelaxedQueryChars());

		headParser = new Http11HeadParser(this, protocol.getMaxHttpHeaderSize(), protocol.getRejectIllegalHeader(),
				httpParser);

		inputBuffer = new Http11InputBuffer(this, httpParser);
		// request.setInputBuffer(inputBuffer);

		outputBuffer = new Http11OutputBuffer(this, protocol.getMaxHttpHeaderSize());
		outputBuffer.setInputBuffer(inputBuffer);
		// responseData.setOutputBuffer(outputBuffer);

		// inputHandler = inputBuffer;
	}

	@Override
	protected Request createRequest() {
		return new Request(this.exchangeData, this, inputBuffer);
	}

	@Override
	protected Response createResponse() {
		return new Response(this.exchangeData, this, outputBuffer);
	}

	@Override
	public boolean processInIoThread(SocketEvent event) throws IOException {// SocketChannel channel,

		if (event != SocketEvent.OPEN_READ) {
			return true;
		}

		// Setting up the I/O
		// setChannel(channel);

		try {
			System.out.println("parse in io thread start");
			if (!headParser.parseRequestLine(false, protocol.getConnectionTimeout(), protocol.getKeepAliveTimeout())) {
				if (headParser.getParsingRequestLinePhase() == -1) {
					return true;
				} else {
					if (headParser.getParsingRequestLinePhase() > 1) {
						// Started to read request line.
						if (protocol.isPaused()) {
							// Partially processed the request so need to respond
							exchangeData.setStatus(503);
							setErrorState(ErrorState.CLOSE_CLEAN, null);
							return true;
						}
					}
					return false;
				}
			}

			inputBuffer.prepareRequestProtocol();

			if (protocol.isPaused()) {
				// 503 - Service unavailable
				exchangeData.setStatus(503);
				setErrorState(ErrorState.CLOSE_CLEAN, null);
				return true;
			} else {
				// Set this every time in case limit has been changed via JMX
				exchangeData.getRequestHeaders().setLimit(protocol.getMaxHeaderCount());
				// Don't parse headers for HTTP/0.9
				if (!inputBuffer.http09 && !headParser.parseHeaders()) {
					// We've read part of the request, don't recycle it
					// instead associate it with the socket
//						openSocket = true;
//						readComplete = false;
					return false;
				}
				if (!protocol.getDisableUploadTimeout()) {
					channel.setReadTimeout(protocol.getConnectionUploadTimeout());
				}

				return true;
			}
		} catch (IOException e) {
			e.printStackTrace();
			System.err.println(e.getMessage());
			if (log.isDebugEnabled()) {
				log.debug(sm.getString("http11processor.header.parse"), e);
			}
			setErrorState(ErrorState.CLOSE_CONNECTION_NOW, e);
			return true;
		} catch (Throwable t) {
			if (exchangeData.getProtocol().isNull()) {
				// Avoid unknown protocol triggering an additional error
				exchangeData.getProtocol().setString(Constants.HTTP_11);
			}
			ExceptionUtils.handleThrowable(t);
			UserDataHelper.Mode logMode = userDataHelper.getNextMode();
			if (logMode != null) {
				String message = sm.getString("http11processor.header.parse");
				switch (logMode) {
				case INFO_THEN_DEBUG:
					message += sm.getString("http11processor.fallToDebug");
					//$FALL-THROUGH$
				case INFO:
					log.info(message, t);
					break;
				case DEBUG:
					log.debug(message, t);
				}
			}
			// 400 - Bad Request
			exchangeData.setStatus(400);
			setErrorState(ErrorState.CLOSE_CLEAN, t);
			return true;
		} finally {
			System.out.println("parse in io thread end");
		}

	}

	@Override
	protected boolean repeat() {
		return inputBuffer.keepAlive;
	}

	@Override
	public SocketState serviceInternal() throws IOException {// Channel channel
//		SocketChannel socketChannel = (SocketChannel) channel;
		RequestInfo rp = exchangeData.getRequestProcessor();
		rp.setStage(org.apache.coyote.Constants.STAGE_PARSE);

		// Setting up the I/O
		// setChannel(channel);

		// Flags
//		inputBuffer.keepAlive = true;
//		openSocket = false;
//		readComplete = true;
		SendfileState sendfileState = SendfileState.DONE;

		while (!getErrorState().isError() && repeat() && !asyncStateMachine.isAsync() && !isUpgrade()
				&& sendfileState == SendfileState.DONE && !protocol.isPaused()) {

			// Parsing the request header
			if (!parsingHeader()) {
				if (!getErrorState().isError()) {
					if (isHttp2Preface()) {
						return SocketState.UPGRADING;
					} else {
						if (canReleaseProcessor()) {
							return SocketState.OPEN;
						} else {
							return SocketState.LONG;
						}
					}
				}
			}

			if (!getErrorState().isError() && channel instanceof SocketChannel) {
				SocketChannel socketChannel = (SocketChannel) channel;
				// Has an upgrade been requested?
				if (isConnectionToken(exchangeData.getRequestHeaders(), "upgrade")) {
					// Check the protocol
					String requestedProtocol = exchangeData.getRequestHeader("Upgrade");

					UpgradeProtocol upgradeProtocol = protocol.getUpgradeProtocol(requestedProtocol);
					if (upgradeProtocol != null) {
						if (upgradeProtocol.accept(exchangeData)) {
							exchangeData.setStatus(HttpServletResponse.SC_SWITCHING_PROTOCOLS);
							exchangeData.setResponseHeader("Connection", "Upgrade");
							exchangeData.setResponseHeader("Upgrade", requestedProtocol);
							outputBuffer.close();
							Request request = createRequest();
							Response response = createResponse();
							request.setResponse(response);
							getAdapter().log(request, response, 0);

							InternalHttpUpgradeHandler upgradeHandler = upgradeProtocol.getInternalUpgradeHandler(
									socketChannel, getAdapter(), cloneRequest(this.exchangeData));
							UpgradeToken upgradeToken = new UpgradeToken(upgradeHandler, null, null);
							upgrade(upgradeToken);
							return SocketState.UPGRADING;
						}
					}
				}
			}

			if (getErrorState().isIoAllowed()) {
				// Setting up filters, and parse some request headers
				rp.setStage(org.apache.coyote.Constants.STAGE_PREPARE);
				try {
					inputBuffer.prepareRequest();
				} catch (Throwable t) {
					ExceptionUtils.handleThrowable(t);
					if (log.isDebugEnabled()) {
						log.debug(sm.getString("http11processor.request.prepare"), t);
					}
					// 500 - Internal Server Error
					exchangeData.setStatus(500);
					setErrorState(ErrorState.CLOSE_CLEAN, t);
				}
			}

			if (!getErrorState().isIoAllowed()) {
				Request request = createRequest();
				Response response = createResponse();
				request.setResponse(response);
				getAdapter().log(request, response, 0);
			} else {

				if (protocol.isPaused()) {// && !cping
					// 503 - Service unavailable
					exchangeData.setStatus(503);
					setErrorState(ErrorState.CLOSE_CLEAN, null);
				}
			}

			// Process the request in the adapter
			if (getErrorState().isIoAllowed()) {
				try {
					rp.setStage(org.apache.coyote.Constants.STAGE_SERVICE);
					Request request = createRequest();
					Response response = createResponse();
					request.setResponse(response);
					getAdapter().service(request, response);
					// Handle when the response was committed before a serious
					// error occurred. Throwing a ServletException should both
					// set the status to 500 and set the errorException.
					// If we fail here, then the response is likely already
					// committed, so we can't try and set headers.
					if (repeat() && !getErrorState().isError() && !asyncStateMachine.isAsync()
							&& statusDropsConnection(this.exchangeData.getStatus())) {
						setErrorState(ErrorState.CLOSE_CLEAN, null);
					}
				} catch (InterruptedIOException e) {
					setErrorState(ErrorState.CLOSE_CONNECTION_NOW, e);
				} catch (HeadersTooLargeException e) {
					log.error(sm.getString("http11processor.request.process"), e);
					// The response should not have been committed but check it
					// anyway to be safe
					if (exchangeData.isCommitted()) {
						setErrorState(ErrorState.CLOSE_NOW, e);
					} else {
						exchangeData.reset();
						exchangeData.setStatus(500);
						setErrorState(ErrorState.CLOSE_CLEAN, e);
						exchangeData.setResponseHeader("Connection", "close"); // TODO: Remove
					}
				} catch (Throwable t) {
					ExceptionUtils.handleThrowable(t);
					log.error(sm.getString("http11processor.request.process"), t);
					// 500 - Internal Server Error
					exchangeData.setStatus(500);
					setErrorState(ErrorState.CLOSE_CLEAN, t);
					Request request = createRequest();
					Response response = createResponse();
					request.setResponse(response);
					getAdapter().log(request, response, 0);
				}
			}

			// Finish the handling of the request
			rp.setStage(org.apache.coyote.Constants.STAGE_ENDINPUT);
			if (!asyncStateMachine.isAsync()) {
				// If this is an async request then the request ends when it has
				// been completed. The AsyncContext is responsible for calling
				// endRequest() in that case.
				endRequest();
			}
			rp.setStage(org.apache.coyote.Constants.STAGE_ENDOUTPUT);

			// If there was an error, make sure the request is counted as
			// and error, and update the statistics counter
			if (getErrorState().isError()) {
				exchangeData.setStatus(500);
			}

			if ((!asyncStateMachine.isAsync() && !isUpgrade()) || getErrorState().isError()) {
				exchangeData.updateCounters();
				if (getErrorState().isIoAllowed()) {
					nextRequest();
//					exchangeData.recycle();
//					headParser.nextRequest();
//					inputBuffer.nextRequest();
//					outputBuffer.nextRequest();
				}
			}

			if (repeat()) {
				resetSocketReadTimeout();
				rp.setStage(org.apache.coyote.Constants.STAGE_KEEPALIVE);
			}

			sendfileState = processSendfile();
		}

		rp.setStage(org.apache.coyote.Constants.STAGE_ENDED);

		if (getErrorState().isError() || (protocol.isPaused() && !asyncStateMachine.isAsync())) {
			return SocketState.CLOSED;
		} else if (asyncStateMachine.isAsync()) {
			return SocketState.SUSPENDED;
		} else if (isUpgrade()) {
			return SocketState.UPGRADING;
		} else {
			if (sendfileState == SendfileState.PENDING) {
				return SocketState.SENDFILE;
			} else {
				if (repeat()) {
//					if (readComplete) {
					return SocketState.OPEN;
//					} else {
//						return SocketState.LONG;
//					}
				} else {
					return SocketState.CLOSED;
				}
			}
		}
	}

	@Override
	protected final void initChannel(Channel channel) {
		SocketChannel socketChannel = (SocketChannel) channel;
		// super.setChannel(channel);
		this.channel = socketChannel;
		headParser.init(socketChannel);
		inputBuffer.init(socketChannel);
		outputBuffer.init(socketChannel);
	}

	@Override
	protected boolean isHttp2Preface() {
		return isHttp2Preface;
	}

	@Override
	protected boolean parsingHeader() throws IOException {

		if (checkHttp2Preface) {
			BufWrapper byteBuffer = ((SocketChannel) getChannel()).getAppReadBuffer();
			byteBuffer.startParsingHeader(CLIENT_PREFACE_START.length);
			if (byteBuffer.hasNoRemaining() || byteBuffer.getRemaining() < CLIENT_PREFACE_START.length) {
				if (!((SocketChannel) getChannel()).fillAppReadBuffer(false)) {
					// A read is pending, so no longer in initial state
					return false;
				}
				// At least one byte of the request has been received.
				// Switch to the socket timeout.
				int matchCount = 0;
				for (int i = 0; i < byteBuffer.getRemaining() && i < CLIENT_PREFACE_START.length; i++) {
					if (CLIENT_PREFACE_START[i] != byteBuffer.getByte(i)) {
						checkHttp2Preface = false;
						isHttp2Preface = false;
						break;
					} else {
						matchCount++;
					}
				}
				if (checkHttp2Preface) {
					if (matchCount < CLIENT_PREFACE_START.length) {
						return false;
					} else {
						isHttp2Preface = true;
						return false;
					}
				}
			}
		}

		try {

			if (!headParser.parseRequestLine(keptAlive, protocol.getConnectionTimeout(),
					protocol.getKeepAliveTimeout())) {
//			if (headParser.getParsingRequestLinePhase() == -1) {
//				return SocketState.UPGRADING;
//			} else
				handleIncompleteRequestLineRead();
//				if (handleIncompleteRequestLineRead()) {
//					break;
//				}
				return false;
			}

			// Process the Protocol component of the request line
			// Need to know if this is an HTTP 0.9 request before trying to
			// parse headers.
			inputBuffer.prepareRequestProtocol();

			if (protocol.isPaused()) {
				// 503 - Service unavailable
				exchangeData.setStatus(503);
				setErrorState(ErrorState.CLOSE_CLEAN, null);
				return false;
			} else {
				keptAlive = true;
				// Set this every time in case limit has been changed via JMX
				exchangeData.getRequestHeaders().setLimit(protocol.getMaxHeaderCount());
				// Don't parse headers for HTTP/0.9
				if (!inputBuffer.http09 && !headParser.parseHeaders()) {
					// We've read part of the request, don't recycle it
					// instead associate it with the socket
//					openSocket = true;
//					readComplete = false;
					return false;
				}
				if (!protocol.getDisableUploadTimeout()) {
					this.channel.setReadTimeout(protocol.getConnectionUploadTimeout());
				}
				return true;
			}
		} catch (IOException e) {
			System.err.println(e.getMessage());
			if (log.isDebugEnabled()) {
				log.debug(sm.getString("http11processor.header.parse"), e);
			}
			setErrorState(ErrorState.CLOSE_CONNECTION_NOW, e);
			return false;
		} catch (Throwable t) {
			if (exchangeData.getProtocol().isNull()) {
				// Avoid unknown protocol triggering an additional error
				exchangeData.getProtocol().setString(Constants.HTTP_11);
			}
			ExceptionUtils.handleThrowable(t);
			UserDataHelper.Mode logMode = userDataHelper.getNextMode();
			if (logMode != null) {
				String message = sm.getString("http11processor.header.parse");
				switch (logMode) {
				case INFO_THEN_DEBUG:
					message += sm.getString("http11processor.fallToDebug");
					//$FALL-THROUGH$
				case INFO:
					log.info(message, t);
					break;
				case DEBUG:
					log.debug(message, t);
				}
			}
			// 400 - Bad Request
			exchangeData.setStatus(400);
			setErrorState(ErrorState.CLOSE_CLEAN, t);
			return false;
		}

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

	@Override
	protected void resetSocketReadTimeout() {
		if (!protocol.getDisableUploadTimeout()) {
			int connectionTimeout = protocol.getConnectionTimeout();
			if (connectionTimeout > 0) {
				channel.setReadTimeout(connectionTimeout);
			} else {
				channel.setReadTimeout(0);
			}
		}
	}

	private ExchangeData cloneRequest(ExchangeData source) throws IOException {
		ExchangeData dest = new ExchangeData();

		// Transfer the minimal information required for the copy of the Request
		// that is passed to the HTTP upgrade process

		dest.getDecodedURI().duplicate(source.getDecodedURI());
		dest.getMethod().duplicate(source.getMethod());
		dest.getRequestHeaders().duplicate(source.getRequestHeaders());
		dest.getRequestURI().duplicate(source.getRequestURI());
		dest.getQueryString().duplicate(source.getQueryString());

		return dest;

	}

	private void handleIncompleteRequestLineRead() {
		// Haven't finished reading the request so keep the socket
		// open
//		openSocket = true;
		// Check to see if we have read any of the request line yet
		if (headParser.getParsingRequestLinePhase() > 1) {
			// Started to read request line.
			if (protocol.isPaused()) {
				// Partially processed the request so need to respond
				exchangeData.setStatus(503);
				setErrorState(ErrorState.CLOSE_CLEAN, null);
//				return false;
			} else {
				// Need to keep processor associated with socket
//				readComplete = false;
			}
		}
//		return true;
	}

	protected void checkExpectationAndResponseStatus() {
		if (exchangeData.hasExpectation() && !inputBuffer.isRequestBodyFullyRead()
				&& (exchangeData.getStatus() < 200 || exchangeData.getStatus() > 299)) {
			// Client sent Expect: 100-continue but received a
			// non-2xx final response. Disable keep-alive (if enabled)
			// to ensure that the connection is closed. Some clients may
			// still send the body, some may send the next request.
			// No way to differentiate, so close the connection to
			// force the client to send the next request.
			inputBuffer.setSwallowInput(false);
			inputBuffer.keepAlive = false;
		}
	}

	protected static boolean isConnectionToken(MimeHeaders headers, String token) throws IOException {
		MessageBytes connection = headers.getValue(Constants.CONNECTION);
		if (connection == null) {
			return false;
		}

		Set<String> tokens = new HashSet<>();
		TokenList.parseTokenList(headers.values(Constants.CONNECTION), tokens);
		return tokens.contains(token);
	}

	/*
	 * Note: populateHost() is not over-ridden. request.serverName() will be set to
	 * return the default host name by the Mapper.
	 */

	@Override
	protected boolean flushBufferedWrite() throws IOException {
		if (outputBuffer.hasDataToWrite()) {
			if (outputBuffer.flushBuffer(false)) {
				// The buffer wasn't fully flushed so re-register the
				// socket for write. Note this does not go via the
				// Response since the write registration state at
				// that level should remain unchanged. Once the buffer
				// has been emptied then the code below will call
				// Adaptor.asyncDispatch() which will enable the
				// Response to respond to this event.
				outputBuffer.registerWriteInterest();
				return true;
			}
		}
		return false;
	}

	@Override
	protected SocketState dispatchEndRequest() {
		if (!inputBuffer.keepAlive || protocol.isPaused()) {
			return SocketState.CLOSED;
		} else {
			endRequest();
			nextRequest();
//			exchangeData.recycle();
//			headParser.nextRequest();
//			inputBuffer.nextRequest();
//			outputBuffer.nextRequest();
			if (channel.isReadPending()) {
				return SocketState.LONG;
			} else {
				return SocketState.OPEN;
			}
		}
	}

	@Override
	protected Log getLog() {
		return log;
	}

	/*
	 * No more input will be passed to the application. Remaining input will be
	 * swallowed or the connection dropped depending on the error and expectation
	 * status.
	 */
	@Override
	protected void endRequest() {
		if (getErrorState().isError()) {
			// If we know we are closing the connection, don't drain
			// input. This way uploading a 100GB file doesn't tie up the
			// thread if the servlet has rejected it.
			inputBuffer.setSwallowInput(false);
		} else {
			// Need to check this again here in case the response was
			// committed before the error that requires the connection
			// to be closed occurred.
			checkExpectationAndResponseStatus();
		}

		// Finish the handling of the request
		if (getErrorState().isIoAllowed()) {
			try {
				inputBuffer.endRequest();
			} catch (IOException e) {
				setErrorState(ErrorState.CLOSE_CONNECTION_NOW, e);
			} catch (Throwable t) {
				ExceptionUtils.handleThrowable(t);
				// 500 - Internal Server Error
				// Can't add a 500 to the access log since that has already been
				// written in the Adapter.service method.
				exchangeData.setStatus(500);
				setErrorState(ErrorState.CLOSE_NOW, t);
				log.error(sm.getString("http11processor.request.finish"), t);
			}
		}
		if (getErrorState().isIoAllowed()) {
//			try {
			outputBuffer.close();
			// outputBuffer.commit();
			// outputBuffer.finishResponse();
//			} catch (IOException e) {
//				setErrorState(ErrorState.CLOSE_CONNECTION_NOW, e);
//			} catch (Throwable t) {
//				ExceptionUtils.handleThrowable(t);
//				setErrorState(ErrorState.CLOSE_NOW, t);
//				log.error(sm.getString("http11processor.response.finish"), t);
//			}
		}
	}

//	@Override
//	protected final void ack() {
//		// Acknowledge request
//		// Send a 100 status back if it makes sense (response not committed
//		// yet, and client specified an expectation for 100-continue)
//		if (!responseData.isCommitted() && exchangeData.hasExpectation()) {
//			inputBuffer.setSwallowInput(true);
//			try {
//				outputBuffer.sendAck();
//			} catch (IOException e) {
//				setErrorState(ErrorState.CLOSE_CONNECTION_NOW, e);
//			}
//		}
//	}

	// @Override
	// protected final int available(boolean doRead) {
	// return inputBuffer.available(doRead);
	// }

//	@Override
//	protected final boolean isReadyForWrite() {
//		return outputBuffer.isReady();
//	}

	@Override
	public UpgradeToken getUpgradeToken() {
		return upgradeToken;
	}

	@Override
	protected final void doHttpUpgrade(UpgradeToken upgradeToken) {
		this.upgradeToken = upgradeToken;
		// Stop further HTTP output
		outputBuffer.setSwallowResponse();
	}

	@Override
	public ByteBuffer getLeftoverInput() {
		return inputBuffer.getLeftover();
	}

	@Override
	public boolean isUpgrade() {
		return upgradeToken != null;
	}

//	@Override
//	protected boolean isTrailerFieldsSupported() {
//		// Request must be HTTP/1.1 to support trailer fields
//		if (!http11) {
//			return false;
//		}
//
//		// If the response is not yet committed, chunked encoding can be used
//		// and the trailer fields sent
//		if (!responseData.isCommitted()) {
//			return true;
//		}
//
//		// Response has been committed - need to see if chunked is being used
//		return outputBuffer.isChunking();
//	}

	/**
	 * Trigger sendfile processing if required.
	 *
	 * @return The state of send file processing
	 * @throws IOException
	 */
	@Override
	protected SendfileState processSendfile() throws IOException {
//		openSocket = inputBuffer.keepAlive;
		// Done is equivalent to sendfile not being used
		SendfileState result = SendfileState.DONE;
		SendfileDataBase sendfileData = outputBuffer.getSendfileData();
		// Do sendfile as needed: add socket to sendfile and end
		if (sendfileData != null && !getErrorState().isError()) {
			if (inputBuffer.keepAlive) {
				if (inputBuffer.available(false) == 0) {
					sendfileData.keepAliveState = SendfileKeepAliveState.OPEN;
				} else {
					sendfileData.keepAliveState = SendfileKeepAliveState.PIPELINED;
				}
			} else {
				sendfileData.keepAliveState = SendfileKeepAliveState.NONE;
			}
			result = channel.processSendfile(sendfileData);
			switch (result) {
			case ERROR:
				// Write failed
				if (log.isDebugEnabled()) {
					log.debug(sm.getString("http11processor.sendfile.error"));
				}
				setErrorState(ErrorState.CLOSE_CONNECTION_NOW, null);
				//$FALL-THROUGH$
			default:
				outputBuffer.setSendfileData(null);
			}
		}
		return result;
	}

	@Override
	protected void nextRequestInternal() {
		Request request = createRequest();
		Response response = createResponse();
		request.setResponse(response);
		getAdapter().checkRecycled(request, response);
		// super.recycle();
		headParser.nextRequest();
		inputBuffer.nextRequest();
		outputBuffer.nextRequest();
		upgradeToken = null;
	}

	@Override
	protected void recycleInternal() {
		Request request = createRequest();
		Response response = createResponse();
		request.setResponse(response);
		getAdapter().checkRecycled(request, response);
		// super.recycle();
		headParser.recycle();
		inputBuffer.recycle();
		outputBuffer.recycle();
		upgradeToken = null;
		channel = null;
		checkHttp2Preface = true;
		isHttp2Preface = false;
		keptAlive = false;
		// sslSupport = null;
	}

	@Override
	public void pause() {
		// NOOP for HTTP
	}
}
