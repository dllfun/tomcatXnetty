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
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import javax.servlet.http.HttpServletResponse;

import org.apache.coyote.AbstractProcessor;
import org.apache.coyote.Adapter;
import org.apache.coyote.ErrorState;
import org.apache.coyote.ExchangeData;
import org.apache.coyote.Request;
import org.apache.coyote.RequestAction;
import org.apache.coyote.Response;
import org.apache.coyote.ResponseAction;
import org.apache.coyote.UpgradeToken;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.buf.ByteChunk;
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

	/**
	 * Head parser
	 */
	private final Http11HeadParser headParser;

	private final HttpParser httpParser;

	/**
	 * Instance of the new protocol to use after the HTTP connection has been
	 * upgraded.
	 */
	private UpgradeToken upgradeToken = null;

	private boolean checkHttp2Preface = true;

	private boolean isHttp2Preface = false;

	private boolean keptAlive = false;

	/**
	 * HTTP/1.1 flag.
	 */
	protected boolean http11 = true;

	/**
	 * HTTP/0.9 flag.
	 */
	protected boolean http09 = false;

	/**
	 * Keep-alive.
	 */
	protected volatile boolean keepAlive = true;

	public Http11Processor(AbstractHttp11Protocol<?> protocol, Adapter adapter) {
		super(protocol, adapter);

		httpParser = new HttpParser(protocol.getRelaxedPathChars(), protocol.getRelaxedQueryChars());

		headParser = new Http11HeadParser(this, protocol.getMaxHttpHeaderSize(), protocol.getRejectIllegalHeader(),
				httpParser);

	}

	@Override
	protected RequestAction createRequestAction() {
		return new Http11InputBuffer(this);
	}

	@Override
	protected ResponseAction createResponseAction() {
		return new Http11OutputBuffer(this);
	}

	@Override
	public AbstractHttp11Protocol<?> getProtocol() {
		return (AbstractHttp11Protocol<?>) super.getProtocol();
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

			prepareRequestProtocol();

			if (protocol.isPaused()) {
				// 503 - Service unavailable
				exchangeData.setStatus(503);
				setErrorState(ErrorState.CLOSE_CLEAN, null);
				return true;
			} else {
				// Set this every time in case limit has been changed via JMX
				exchangeData.getRequestHeaders().setLimit(protocol.getMaxHeaderCount());
				// Don't parse headers for HTTP/0.9
				if (!http09 && !headParser.parseHeaders()) {
					// We've read part of the request, don't recycle it
					// instead associate it with the socket
//						openSocket = true;
//						readComplete = false;
					return false;
				}
				if (!getProtocol().getDisableUploadTimeout()) {
					((SocketChannel) getChannel()).setReadTimeout(getProtocol().getConnectionUploadTimeout());
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
	protected final void onChannelReady(Channel channel) {
		SocketChannel socketChannel = (SocketChannel) channel;
		headParser.init(socketChannel);
		((Http11InputBuffer) requestAction).init(socketChannel);
		((Http11OutputBuffer) responseAction).init(socketChannel);
	}

	@Override
	protected boolean repeat() {
		return keepAlive;
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
			prepareRequestProtocol();

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
				if (!http09 && !headParser.parseHeaders()) {
					// We've read part of the request, don't recycle it
					// instead associate it with the socket
//					openSocket = true;
//					readComplete = false;
					return false;
				}
				if (!getProtocol().getDisableUploadTimeout()) {
					((SocketChannel) getChannel()).setReadTimeout(getProtocol().getConnectionUploadTimeout());
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

	protected void prepareRequestProtocol() {

		MessageBytes protocolMB = exchangeData.getProtocol();
		if (protocolMB.equals(Constants.HTTP_11)) {
			http09 = false;
			http11 = true;
			protocolMB.setString(Constants.HTTP_11);
		} else if (protocolMB.equals(Constants.HTTP_10)) {
			http09 = false;
			http11 = false;
			keepAlive = false;
			protocolMB.setString(Constants.HTTP_10);
		} else if (protocolMB.equals("")) {
			// HTTP/0.9
			http09 = true;
			http11 = false;
			keepAlive = false;
		} else {
			// Unsupported protocol
			http09 = false;
			http11 = false;
			// Send 505; Unsupported HTTP version
			exchangeData.setStatus(505);
			setErrorState(ErrorState.CLOSE_CLEAN, null);
			if (log.isDebugEnabled()) {
				log.debug(sm.getString("http11processor.request.prepare") + " Unsupported HTTP version \"" + protocolMB
						+ "\"");
			}
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

	/**
	 * After reading the request headers, we have to setup the request filters.
	 */
	@Override
	protected void prepareRequest() throws IOException {

//		contentDelimitation = false;
		if (exchangeData.getRequestBodyType() != -1) {
			throw new RuntimeException();
		}

		AbstractHttp11Protocol<?> protocol = (AbstractHttp11Protocol<?>) getProtocol();

		if (protocol.isSSLEnabled()) {
			exchangeData.getScheme().setString("https");
		}

		MimeHeaders headers = exchangeData.getRequestHeaders();

		// Check connection header
		MessageBytes connectionValueMB = headers.getValue(Constants.CONNECTION);
		if (connectionValueMB != null && !connectionValueMB.isNull()) {
			Set<String> tokens = new HashSet<>();
			TokenList.parseTokenList(headers.values(Constants.CONNECTION), tokens);
			if (tokens.contains(Constants.CLOSE)) {
				keepAlive = false;
			} else if (tokens.contains(Constants.KEEP_ALIVE_HEADER_VALUE_TOKEN)) {
				keepAlive = true;
			}
		}

		if (http11) {
			MessageBytes expectMB = headers.getValue("expect");
			if (expectMB != null && !expectMB.isNull()) {
				if (expectMB.toString().trim().equalsIgnoreCase("100-continue")) {
					requestAction.setSwallowInput(false);
					exchangeData.setExpectation(true);
				} else {
					exchangeData.setStatus(HttpServletResponse.SC_EXPECTATION_FAILED);
					setErrorState(ErrorState.CLOSE_CLEAN, null);
				}
			}
		}

		// Check user-agent header
		Pattern restrictedUserAgents = protocol.getRestrictedUserAgentsPattern();
		if (restrictedUserAgents != null && (http11 || keepAlive)) {
			MessageBytes userAgentValueMB = headers.getValue("user-agent");
			// Check in the restricted list, and adjust the http11
			// and keepAlive flags accordingly
			if (userAgentValueMB != null && !userAgentValueMB.isNull()) {
				String userAgentValue = userAgentValueMB.toString();
				if (restrictedUserAgents.matcher(userAgentValue).matches()) {
					http11 = false;
					keepAlive = false;
				}
			}
		}

		// Check host header
		MessageBytes hostValueMB = null;
		try {
			hostValueMB = headers.getUniqueValue("host");
		} catch (IllegalArgumentException iae) {
			// Multiple Host headers are not permitted
			badRequest("http11processor.request.multipleHosts");
		}
		if (http11 && hostValueMB == null) {
			badRequest("http11processor.request.noHostHeader");
		}

		// Check for an absolute-URI less the query string which has already
		// been removed during the parsing of the request line
		ByteChunk uriBC = exchangeData.getRequestURI().getByteChunk();
		byte[] uriB = uriBC.getBytes();
		if (uriBC.startsWithIgnoreCase("http", 0)) {
			int pos = 4;
			// Check for https
			if (uriBC.startsWithIgnoreCase("s", pos)) {
				pos++;
			}
			// Next 3 characters must be "://"
			if (uriBC.startsWith("://", pos)) {
				pos += 3;
				int uriBCStart = uriBC.getStart();

				// '/' does not appear in the authority so use the first
				// instance to split the authority and the path segments
				int slashPos = uriBC.indexOf('/', pos);
				// '@' in the authority delimits the userinfo
				int atPos = uriBC.indexOf('@', pos);
				if (slashPos > -1 && atPos > slashPos) {
					// First '@' is in the path segments so no userinfo
					atPos = -1;
				}

				if (slashPos == -1) {
					slashPos = uriBC.getLength();
					// Set URI as "/". Use 6 as it will always be a '/'.
					// 01234567
					// http://
					// https://
					exchangeData.getRequestURI().setBytes(uriB, uriBCStart + 6, 1);
				} else {
					exchangeData.getRequestURI().setBytes(uriB, uriBCStart + slashPos, uriBC.getLength() - slashPos);
				}

				// Skip any user info
				if (atPos != -1) {
					// Validate the userinfo
					for (; pos < atPos; pos++) {
						byte c = uriB[uriBCStart + pos];
						if (!HttpParser.isUserInfo(c)) {
							// Strictly there needs to be a check for valid %nn
							// encoding here but skip it since it will never be
							// decoded because the userinfo is ignored
							badRequest("http11processor.request.invalidUserInfo");
							break;
						}
					}
					// Skip the '@'
					pos = atPos + 1;
				}

				if (http11) {
					// Missing host header is illegal but handled above
					if (hostValueMB != null) {
						// Any host in the request line must be consistent with
						// the Host header
						if (!hostValueMB.getByteChunk().equals(uriB, uriBCStart + pos, slashPos - pos)) {
							if (protocol.getAllowHostHeaderMismatch()) {
								// The requirements of RFC 2616 are being
								// applied. If the host header and the request
								// line do not agree, the request line takes
								// precedence
								hostValueMB = headers.setValue("host");
								hostValueMB.setBytes(uriB, uriBCStart + pos, slashPos - pos);
							} else {
								// The requirements of RFC 7230 are being
								// applied. If the host header and the request
								// line do not agree, trigger a 400 response.
								badRequest("http11processor.request.inconsistentHosts");
							}
						}
					}
				} else {
					// Not HTTP/1.1 - no Host header so generate one since
					// Tomcat internals assume it is set
					try {
						hostValueMB = headers.setValue("host");
						hostValueMB.setBytes(uriB, uriBCStart + pos, slashPos - pos);
					} catch (IllegalStateException e) {
						// Edge case
						// If the request has too many headers it won't be
						// possible to create the host header. Ignore this as
						// processing won't reach the point where the Tomcat
						// internals expect there to be a host header.
					}
				}
			} else {
				badRequest("http11processor.request.invalidScheme");
			}
		}

		// Validate the characters in the URI. %nn decoding will be checked at
		// the point of decoding.
		for (int i = uriBC.getStart(); i < uriBC.getEnd(); i++) {
			if (!httpParser.isAbsolutePathRelaxed(uriB[i])) {
				badRequest("http11processor.request.invalidUri");
				break;
			}
		}

		// Input filter setup
		// InputFilter[] inputFilters = this.getFilters();

		// Parse transfer-encoding header
		if (http11) {
			MessageBytes transferEncodingValueMB = headers.getValue("transfer-encoding");
			if (transferEncodingValueMB != null) {
				List<String> encodingNames = new ArrayList<>();
				if (TokenList.parseTokenList(headers.values("transfer-encoding"), encodingNames)) {
					for (String encodingName : encodingNames) {
						// "identity" codings are ignored
						this.addInputFilter(encodingName);// inputFilters,
					}
				} else {
					// Invalid transfer encoding
					badRequest("http11processor.request.invalidTransferEncoding");
				}
			}
		}

		// Parse content-length header
		long contentLength = -1;
		try {
			contentLength = exchangeData.getRequestContentLengthLong();
		} catch (NumberFormatException e) {
			badRequest("http11processor.request.nonNumericContentLength");
		} catch (IllegalArgumentException e) {
			badRequest("http11processor.request.multipleContentLength");
		}
		if (contentLength >= 0) {
			if (exchangeData.getRequestBodyType() == ExchangeData.BODY_TYPE_CHUNKED) {
				// contentDelimitation being true at this point indicates that
				// chunked encoding is being used but chunked encoding should
				// not be used with a content length. RFC 2616, section 4.4,
				// bullet 3 states Content-Length must be ignored in this case -
				// so remove it.
				headers.removeHeader("content-length");
				exchangeData.setRequestContentLength(-1);
			} else {
				requestAction.addActiveFilter(Constants.IDENTITY_FILTER);
//				contentDelimitation = true;
				exchangeData.setRequestBodyType(ExchangeData.BODY_TYPE_FIXEDLENGTH);
			}
		}

		// Validate host name and extract port if present
		requestAction.parseHost(hostValueMB);

		if (exchangeData.getRequestBodyType() == -1) {
			// If there's no content length
			// (broken HTTP/1.0 or HTTP/1.1), assume
			// the client is not broken and didn't send a body
			requestAction.addActiveFilter(Constants.VOID_FILTER);
//			contentDelimitation = true;
			exchangeData.setRequestBodyType(ExchangeData.BODY_TYPE_NOBODY);
		}

		if (!getErrorState().isIoAllowed()) {
			Request request = createRequest();
			Response response = createResponse();
			request.setResponse(response);
			getAdapter().log(request, response, 0);
		}

		int maxKeepAliveRequests = protocol.getMaxKeepAliveRequests();
		if (maxKeepAliveRequests == 1) {
			keepAlive = false;
		} else if (maxKeepAliveRequests > 0
				&& ((SocketChannel) getChannel()).incrementKeepAlive() >= maxKeepAliveRequests) {
			keepAlive = false;
		}

	}

	/**
	 * Add an input filter to the current request. If the encoding is not supported,
	 * a 501 response will be returned to the client.
	 */
	private void addInputFilter(String encodingName) {// InputFilter[] inputFilters,

		// Parsing trims and converts to lower case.

		if (encodingName.equals("identity")) {
			// Skip
		} else if (encodingName.equals("chunked")) {
			requestAction.addActiveFilter(Constants.CHUNKED_FILTER);
//			contentDelimitation = true;
			exchangeData.setRequestBodyType(ExchangeData.BODY_TYPE_CHUNKED);
		} else {
			InputFilter filter = requestAction.getFilterByEncodingName(encodingName);
			if (filter != null) {
				requestAction.addActiveFilter(filter.getId());
				return;
			}
//			for (int i = pluggableFilterIndex; i < inputFilters.length; i++) {
//				if (inputFilters[i].getEncodingName().toString().equals(encodingName)) {
//					this.addActiveFilter(inputFilters[i]);
//					return;
//				}
//			}
			// Unsupported transfer encoding
			// 501 - Unimplemented
			exchangeData.setStatus(501);
			setErrorState(ErrorState.CLOSE_CLEAN, null);
			if (log.isDebugEnabled()) {
				log.debug(sm.getString("http11processor.request.prepare") + " Unsupported transfer encoding ["
						+ encodingName + "]");
			}
		}
	}

	private void badRequest(String errorKey) {
		exchangeData.setStatus(400);
		setErrorState(ErrorState.CLOSE_CLEAN, null);
		if (log.isDebugEnabled()) {
			log.debug(sm.getString(errorKey));
		}
	}

	@Override
	protected void resetSocketReadTimeout() {
		if (!getProtocol().getDisableUploadTimeout()) {
			int connectionTimeout = protocol.getConnectionTimeout();
			if (connectionTimeout > 0) {
				((SocketChannel) getChannel()).setReadTimeout(connectionTimeout);
			} else {
				((SocketChannel) getChannel()).setReadTimeout(0);
			}
		}
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
		if (exchangeData.hasExpectation() && !requestAction.isRequestBodyFullyRead()
				&& (exchangeData.getStatus() < 200 || exchangeData.getStatus() > 299)) {
			// Client sent Expect: 100-continue but received a
			// non-2xx final response. Disable keep-alive (if enabled)
			// to ensure that the connection is closed. Some clients may
			// still send the body, some may send the next request.
			// No way to differentiate, so close the connection to
			// force the client to send the next request.
			requestAction.setSwallowInput(false);
			keepAlive = false;
		}
	}

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
		SendfileDataBase sendfileData = ((Http11OutputBuffer) responseAction).getSendfileData();
		// Do sendfile as needed: add socket to sendfile and end
		if (sendfileData != null && !getErrorState().isError()) {
			if (keepAlive) {
				if (requestAction.getAvailable(false) == 0) {
					sendfileData.keepAliveState = SendfileKeepAliveState.OPEN;
				} else {
					sendfileData.keepAliveState = SendfileKeepAliveState.PIPELINED;
				}
			} else {
				sendfileData.keepAliveState = SendfileKeepAliveState.NONE;
			}
			result = ((SocketChannel) getChannel()).processSendfile(sendfileData);
			switch (result) {
			case ERROR:
				// Write failed
				if (log.isDebugEnabled()) {
					log.debug(sm.getString("http11processor.sendfile.error"));
				}
				setErrorState(ErrorState.CLOSE_CONNECTION_NOW, null);
				//$FALL-THROUGH$
			default:
				((Http11OutputBuffer) responseAction).setSendfileData(null);
			}
		}
		return result;
	}

	/*
	 * No more input will be passed to the application. Remaining input will be
	 * swallowed or the connection dropped depending on the error and expectation
	 * status.
	 */
	@Override
	protected void finishActions() {
		if (getErrorState().isError()) {
			// If we know we are closing the connection, don't drain
			// input. This way uploading a 100GB file doesn't tie up the
			// thread if the servlet has rejected it.
			requestAction.setSwallowInput(false);
		} else {
			// Need to check this again here in case the response was
			// committed before the error that requires the connection
			// to be closed occurred.
			checkExpectationAndResponseStatus();
		}

		// Finish the handling of the request
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
				log.error(sm.getString("http11processor.request.finish"), t);
			}
		}
		if (getErrorState().isIoAllowed()) {
//			try {
			responseAction.finish();
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

	/*
	 * Note: populateHost() is not over-ridden. request.serverName() will be set to
	 * return the default host name by the Mapper.
	 */

	@Override
	protected SocketState dispatchFinishActions() {
		if (!keepAlive || protocol.isPaused()) {
			return SocketState.CLOSED;
		} else {
			finishActions();
			nextRequest();
//			exchangeData.recycle();
//			headParser.nextRequest();
//			inputBuffer.nextRequest();
//			outputBuffer.nextRequest();
			if (((SocketChannel) getChannel()).isReadPending()) {
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

	@Override
	public UpgradeToken getUpgradeToken() {
		return upgradeToken;
	}

	@Override
	protected final void doHttpUpgrade(UpgradeToken upgradeToken) {
		this.upgradeToken = upgradeToken;
		// Stop further HTTP output
		responseAction.setSwallowResponse();
	}

	@Override
	public ByteBuffer getLeftoverInput() {
		return ((Http11InputBuffer) requestAction).getLeftover();
	}

	@Override
	public boolean isUpgrade() {
		return upgradeToken != null;
	}

	@Override
	protected void nextRequestInternal() {
		Request request = createRequest();
		Response response = createResponse();
		request.setResponse(response);
		getAdapter().checkRecycled(request, response);
		// super.recycle();
		headParser.nextRequest();
		((Http11InputBuffer) requestAction).nextRequest();
		((Http11OutputBuffer) responseAction).nextRequest();
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
		requestAction.recycle();
		responseAction.recycle();
		upgradeToken = null;
		checkHttp2Preface = true;
		isHttp2Preface = false;
		keptAlive = false;
		keepAlive = true;

		// sslSupport = null;
	}

	@Override
	public void pause() {
		// NOOP for HTTP
	}
}
