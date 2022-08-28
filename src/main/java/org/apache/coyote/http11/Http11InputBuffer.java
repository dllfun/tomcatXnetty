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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import javax.servlet.http.HttpServletResponse;

import org.apache.coyote.ErrorState;
import org.apache.coyote.InputReader;
import org.apache.coyote.Request;
import org.apache.coyote.RequestAction;
import org.apache.coyote.RequestData;
import org.apache.coyote.Response;
import org.apache.coyote.http11.filters.BufferedInputFilter;
import org.apache.coyote.http11.filters.ChunkedInputFilter;
import org.apache.coyote.http11.filters.IdentityInputFilter;
import org.apache.coyote.http11.filters.SavedRequestInputFilter;
import org.apache.coyote.http11.filters.VoidInputFilter;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.buf.ByteChunk;
import org.apache.tomcat.util.buf.MessageBytes;
import org.apache.tomcat.util.http.MimeHeaders;
import org.apache.tomcat.util.http.parser.HttpParser;
import org.apache.tomcat.util.http.parser.TokenList;
import org.apache.tomcat.util.net.SSLSupport;
import org.apache.tomcat.util.net.SocketChannel;
import org.apache.tomcat.util.net.SocketChannel.BufWrapper;
import org.apache.tomcat.util.res.StringManager;

/**
 * InputBuffer for HTTP that provides request header parsing as well as transfer
 * encoding.
 */
public class Http11InputBuffer extends RequestAction {

	// -------------------------------------------------------------- Constants

	private static final Log log = LogFactory.getLog(Http11InputBuffer.class);

	/**
	 * The string manager for this package.
	 */
	private static final StringManager sm = StringManager.getManager(Http11InputBuffer.class);

	private Http11Processor processor;

	/**
	 * Associated Coyote request.
	 */
	private final RequestData requestData;

	/**
	 * The read buffer.
	 */
	// private BufWrapper appReadBuffer;

	/**
	 * Pos of the end of the header in the buffer, which is also the start of the
	 * body.
	 */
	// private int end;

	private final HttpParser httpParser;

	/**
	 * Wrapper that provides access to the underlying socket.
	 */
	// private SocketChannel channel;

	/**
	 * Underlying input buffer.
	 */
	private InputReader channelInputBuffer;

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

	/**
	 * Content delimiter for the request (if false, the connection will be closed at
	 * the end of the request).
	 */
	protected boolean contentDelimitation = true;

	// ----------------------------------------------------------- Constructors

	public Http11InputBuffer(Http11Processor processor, HttpParser httpParser) {
		super(processor);
		this.processor = processor;
		this.requestData = processor.getRequestData();
		this.httpParser = httpParser;

		channelInputBuffer = new SocketInputReader();

		AbstractHttp11Protocol<?> protocol = (AbstractHttp11Protocol<?>) processor.getProtocol();

		// Create and add the identity filters.
		addFilter(new IdentityInputFilter(protocol.getMaxSwallowSize()));
		// Create and add the chunked filters.
		addFilter(new ChunkedInputFilter(protocol.getMaxTrailerSize(), protocol.getAllowedTrailerHeadersInternal(),
				protocol.getMaxExtensionSize(), protocol.getMaxSwallowSize()));
		// Create and add the void filters.
		addFilter(new VoidInputFilter());
		// Create and add buffered input filter
		addFilter(new BufferedInputFilter());
		addFilter(new SavedRequestInputFilter(null));
		markPluggableFilterIndex();
	}

	// ------------------------------------------------------------- Properties

	// ---------------------------------------------------- InputBuffer Methods

	// @Override
	// public int doRead(PreInputBuffer handler) throws IOException {

	// if (lastActiveFilter == -1)
	// return channelInputBuffer.doRead(handler);
	// else
	// return activeFilters[lastActiveFilter].doRead(handler);

	// }

	// ------------------------------------------------------- Protected Methods

	@Override
	protected InputReader getBaseInputReader() {
		return channelInputBuffer;
	}

	protected void prepareRequestProtocol() {

		MessageBytes protocolMB = requestData.protocol();
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
			requestData.getResponseData().setStatus(505);
			processor.setErrorState(ErrorState.CLOSE_CLEAN, null);
			if (log.isDebugEnabled()) {
				log.debug(sm.getString("http11processor.request.prepare") + " Unsupported HTTP version \"" + protocolMB
						+ "\"");
			}
		}
	}

	public boolean isHttp11() {
		return http11;
	}

	public boolean isHttp09() {
		return http09;
	}

	public boolean isKeepAlive() {
		return keepAlive;
	}

	/**
	 * After reading the request headers, we have to setup the request filters.
	 */
	protected void prepareRequest() throws IOException {

		contentDelimitation = false;

		AbstractHttp11Protocol<?> protocol = (AbstractHttp11Protocol<?>) processor.getProtocol();

		if (protocol.isSSLEnabled()) {
			requestData.scheme().setString("https");
		}

		MimeHeaders headers = requestData.getMimeHeaders();

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
					this.setSwallowInput(false);
					requestData.setExpectation(true);
				} else {
					requestData.getResponseData().setStatus(HttpServletResponse.SC_EXPECTATION_FAILED);
					processor.setErrorState(ErrorState.CLOSE_CLEAN, null);
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
		ByteChunk uriBC = requestData.requestURI().getByteChunk();
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
					requestData.requestURI().setBytes(uriB, uriBCStart + 6, 1);
				} else {
					requestData.requestURI().setBytes(uriB, uriBCStart + slashPos, uriBC.getLength() - slashPos);
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
			contentLength = requestData.getContentLengthLong();
		} catch (NumberFormatException e) {
			badRequest("http11processor.request.nonNumericContentLength");
		} catch (IllegalArgumentException e) {
			badRequest("http11processor.request.multipleContentLength");
		}
		if (contentLength >= 0) {
			if (contentDelimitation) {
				// contentDelimitation being true at this point indicates that
				// chunked encoding is being used but chunked encoding should
				// not be used with a content length. RFC 2616, section 4.4,
				// bullet 3 states Content-Length must be ignored in this case -
				// so remove it.
				headers.removeHeader("content-length");
				requestData.setContentLength(-1);
			} else {
				this.addActiveFilter(Constants.IDENTITY_FILTER);
				contentDelimitation = true;
			}
		}

		// Validate host name and extract port if present
		parseHost(hostValueMB);

		if (!contentDelimitation) {
			// If there's no content length
			// (broken HTTP/1.0 or HTTP/1.1), assume
			// the client is not broken and didn't send a body
			this.addActiveFilter(Constants.VOID_FILTER);
			contentDelimitation = true;
		}

		if (!processor.getErrorState().isIoAllowed()) {
			Request request = processor.createRequest();
			Response response = processor.createResponse();
			request.setResponse(response);
			processor.getAdapter().log(request, response, 0);
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
			this.addActiveFilter(Constants.CHUNKED_FILTER);
			contentDelimitation = true;
		} else {
			InputFilter filter = getFilterByEncodingName(encodingName);
			if (filter != null) {
				this.addActiveFilter(filter.getId());
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
			requestData.getResponseData().setStatus(501);
			processor.setErrorState(ErrorState.CLOSE_CLEAN, null);
			if (log.isDebugEnabled()) {
				log.debug(sm.getString("http11processor.request.prepare") + " Unsupported transfer encoding ["
						+ encodingName + "]");
			}
		}
	}

	private void badRequest(String errorKey) {
		requestData.getResponseData().setStatus(400);
		processor.setErrorState(ErrorState.CLOSE_CLEAN, null);
		if (log.isDebugEnabled()) {
			log.debug(sm.getString(errorKey));
		}
	}

	/**
	 * End request (consumes leftover bytes).
	 *
	 * @throws IOException an underlying I/O error occurred
	 */
	void endRequest() throws IOException {

		BufWrapper byteBuffer = ((SocketChannel) processor.getChannel()).getAppReadBuffer();
		if (isSwallowInput() && (hasActiveFilters())) {
			int extraBytes = (int) getLastActiveFilter().end();
			byteBuffer.setPosition(byteBuffer.getPosition() - extraBytes);
		}

	}

	@Override
	public int getAvailable(Object param) {
		int available = available(Boolean.TRUE.equals(param));
		// requestData.setAvailable(available);
		return available;
	}

	/**
	 * Available bytes in the buffers (note that due to encoding, this may not
	 * correspond).
	 */
	int available(boolean read) {
		BufWrapper byteBuffer = ((SocketChannel) processor.getChannel()).getAppReadBuffer();

		int available = byteBuffer.getRemaining();
		if (available == 0) {
			available = getAvailableInFilters();
		}
//		if ((available == 0) && (hasActiveFilters())) {
//			for (int i = 0; (available == 0) && (i < getActiveFiltersCount()); i++) {
//				available = getActiveFilter(i).available();
//			}
//		}
		if (available > 0 || !read) {
			return available;
		}

		try {
			if (((SocketChannel) processor.getChannel()).hasDataToRead()) {
				((SocketChannel) processor.getChannel()).fillAppReadBuffer(false);
				available = byteBuffer.getRemaining();
			}
		} catch (IOException ioe) {
			if (log.isDebugEnabled()) {
				log.debug(sm.getString("iib.available.readFail"), ioe);
			}
			// Not ideal. This will indicate that data is available which should
			// trigger a read which in turn will trigger another IOException and
			// that one can be thrown.
			available = 1;
		}
		return available;
	}

	@Override
	public boolean isReadyForRead() {
		if (available(true) > 0) {
			return true;
		}

		if (!isRequestBodyFullyRead()) {
			registerReadInterest();
		}

		return false;
	}

	@Override
	public final boolean isRequestBodyFullyRead() {
		return this.isFinished();
	}

	@Override
	public boolean isTrailerFieldsReady() {
		if (this.isChunking()) {
			return this.isFinished();
		} else {
			return true;
		}
	}

	/**
	 * Has all of the request body been read? There are subtle differences between
	 * this and available() &gt; 0 primarily because of having to handle faking
	 * non-blocking reads with the blocking IO connector.
	 */
	boolean isFinished() {
		BufWrapper byteBuffer = ((SocketChannel) processor.getChannel()).getAppReadBuffer();

		if (byteBuffer.hasRemaining()) {
			// Data to read in the buffer so not finished
			return false;
		}

		/*
		 * Don't use fill(false) here because in the following circumstances BIO will
		 * block - possibly indefinitely - client is using keep-alive and connection is
		 * still open - client has sent the complete request - client has not sent any
		 * of the next request (i.e. no pipelining) - application has read the complete
		 * request
		 */

		// Check the InputFilters

		if (hasActiveFilters()) {
			return getLastActiveFilter().isFinished();
		} else {
			// No filters. Assume request is not finished. EOF will signal end of
			// request.
			return false;
		}
	}

	@Override
	public final void registerReadInterest() {
		((SocketChannel) processor.getChannel()).registerReadInterest();
	}

	/**
	 * Populate the remote host request attribute. Processors (e.g. AJP) that
	 * populate this from an alternative source should override this method.
	 */
	protected void populateRequestAttributeRemoteHost() {
		if (getPopulateRequestAttributesFromSocket() && ((SocketChannel) processor.getChannel()) != null) {
			requestData.remoteHost().setString(((SocketChannel) processor.getChannel()).getRemoteHost());
		}
	}

	@Override
	public void actionREQ_HOST_ADDR_ATTRIBUTE() {
		if (getPopulateRequestAttributesFromSocket() && ((SocketChannel) processor.getChannel()) != null) {
			requestData.remoteAddr().setString(((SocketChannel) processor.getChannel()).getRemoteAddr());
		}
	}

	@Override
	public void actionREQ_HOST_ATTRIBUTE() {
		populateRequestAttributeRemoteHost();
	}

	@Override
	public void actionREQ_LOCALPORT_ATTRIBUTE() {
		if (getPopulateRequestAttributesFromSocket() && ((SocketChannel) processor.getChannel()) != null) {
			requestData.setLocalPort(((SocketChannel) processor.getChannel()).getLocalPort());
		}
	}

	@Override
	public void actionREQ_LOCAL_ADDR_ATTRIBUTE() {
		if (getPopulateRequestAttributesFromSocket() && ((SocketChannel) processor.getChannel()) != null) {
			requestData.localAddr().setString(((SocketChannel) processor.getChannel()).getLocalAddr());
		}
	}

	@Override
	public void actionREQ_LOCAL_NAME_ATTRIBUTE() {
		if (getPopulateRequestAttributesFromSocket() && ((SocketChannel) processor.getChannel()) != null) {
			requestData.localName().setString(((SocketChannel) processor.getChannel()).getLocalName());
		}
	}

	@Override
	public void actionREQ_REMOTEPORT_ATTRIBUTE() {
		if (getPopulateRequestAttributesFromSocket() && ((SocketChannel) processor.getChannel()) != null) {
			requestData.setRemotePort(((SocketChannel) processor.getChannel()).getRemotePort());
		}
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * This implementation provides the server port from the local port.
	 */
	@Override
	protected void populatePort() {
		// Ensure the local port field is populated before using it.
		this.actionREQ_LOCALPORT_ATTRIBUTE();
		requestData.setServerPort(requestData.getLocalPort());
	}

	@Override
	public final void setRequestBody(ByteChunk body) {
		SavedRequestInputFilter savedBody = (SavedRequestInputFilter) getFilterById(Constants.SAVEDREQUEST_FILTER);// SavedRequestInputFilter(body);
		savedBody.setInput(body);
		// Http11InputBuffer internalBuffer = (Http11InputBuffer)
		// request.getInputBuffer();
		this.addActiveFilter(savedBody.getId());
	}

	@Override
	public final void disableSwallowRequest() {
		this.setSwallowInput(false);
	}

	ByteBuffer getLeftover() {
		BufWrapper byteBuffer = ((SocketChannel) processor.getChannel()).getAppReadBuffer();
		int available = byteBuffer.getRemaining();
		if (available > 0) {
			return ByteBuffer.wrap(byteBuffer.getArray(), byteBuffer.getPosition(), available);
		} else {
			return null;
		}
	}

	/**
	 * Populate the TLS related request attributes from the {@link SSLSupport}
	 * instance associated with this processor. Protocols that populate TLS
	 * attributes from a different source (e.g. AJP) should override this method.
	 */
	protected void populateSslRequestAttributes() {
		try {
			SSLSupport sslSupport = ((SocketChannel) processor.getChannel()).getSslSupport();
			if (sslSupport != null) {
				Object sslO = sslSupport.getCipherSuite();
				if (sslO != null) {
					requestData.setAttribute(SSLSupport.CIPHER_SUITE_KEY, sslO);
				}
				sslO = sslSupport.getPeerCertificateChain();
				if (sslO != null) {
					requestData.setAttribute(SSLSupport.CERTIFICATE_KEY, sslO);
				}
				sslO = sslSupport.getKeySize();
				if (sslO != null) {
					requestData.setAttribute(SSLSupport.KEY_SIZE_KEY, sslO);
				}
				sslO = sslSupport.getSessionId();
				if (sslO != null) {
					requestData.setAttribute(SSLSupport.SESSION_ID_KEY, sslO);
				}
				sslO = sslSupport.getProtocol();
				if (sslO != null) {
					requestData.setAttribute(SSLSupport.PROTOCOL_VERSION_KEY, sslO);
				}
				requestData.setAttribute(SSLSupport.SESSION_MGR, sslSupport);
			}
		} catch (Exception e) {
			log.warn(sm.getString("abstractProcessor.socket.ssl"), e);
		}
	}

	@Override
	public void actionREQ_SSL_ATTRIBUTE() {
		populateSslRequestAttributes();
	}

	@Override
	public void actionREQ_SSL_CERTIFICATE() {
		try {
			sslReHandShake();
		} catch (IOException ioe) {
			processor.setErrorState(ErrorState.CLOSE_CONNECTION_NOW, ioe);
		}
	}

	/**
	 * Processors that can perform a TLS re-handshake (e.g. HTTP/1.1) should
	 * override this method and implement the re-handshake.
	 *
	 * @throws IOException If authentication is required then there will be I/O with
	 *                     the client and this exception will be thrown if that goes
	 *                     wrong
	 */
	// @Override
	protected final void sslReHandShake() throws IOException {
		SSLSupport sslSupport = ((SocketChannel) processor.getChannel()).getSslSupport();
		if (sslSupport != null) {
			// Consume and buffer the request body, so that it does not
			// interfere with the client's handshake messages
			// InputFilter[] inputFilters = this.getFilters();
			((BufferedInputFilter) getFilterById(Constants.BUFFERED_FILTER))
					.setLimit(((AbstractHttp11Protocol<?>) processor.getProtocol()).getMaxSavePostSize());
			this.addActiveFilter(Constants.BUFFERED_FILTER);

			/*
			 * Outside the try/catch because we want I/O errors during renegotiation to be
			 * thrown for the caller to handle since they will be fatal to the connection.
			 */
			((SocketChannel) processor.getChannel()).doClientAuth(sslSupport);
			try {
				/*
				 * Errors processing the cert chain do not affect the client connection so they
				 * can be logged and swallowed here.
				 */
				Object sslO = sslSupport.getPeerCertificateChain();
				if (sslO != null) {
					requestData.setAttribute(SSLSupport.CERTIFICATE_KEY, sslO);
				}
			} catch (IOException ioe) {
				log.warn(sm.getString("http11processor.socket.ssl"), ioe);
			}
		}
	}

	void init(SocketChannel channel) {

		// this.channel = channel;

	}

	/**
	 * End processing of current HTTP request. Note: All bytes of the current
	 * request should have been already consumed. This method only resets all the
	 * pointers so that we are ready to parse the next HTTP request.
	 */
	void nextRequest() {
		// requestData.recycle();

		((SocketChannel) processor.getChannel()).getAppReadBuffer().nextRequest();

		resetFilters();
	}

	/**
	 * Recycle the input buffer. This should be called when closing the connection.
	 */
	void recycle() {
		if (((SocketChannel) processor.getChannel()) != null) {
			((SocketChannel) processor.getChannel()).getAppReadBuffer().reset();
		}
		// requestData.recycle();

		resetFilters();

	}

	// ------------------------------------- InputStreamInputBuffer Inner Class

	/**
	 * This class is an input buffer which will read its data from an input stream.
	 */
	private class SocketInputReader implements InputReader {

		// @Override
		// public int doRead(PreInputBuffer handler) throws IOException {

		// if (channel.getAppReadBuffer().hasNoRemaining()) {
		// // The application is reading the HTTP request body which is
		// // always a blocking operation.
		// if (!channel.getAppReadBuffer().fill(true))
		// return -1;
		// }

		// int length = channel.getAppReadBuffer().getRemaining();
		// handler.setBufWrapper(channel.getAppReadBuffer().duplicate());
		// channel.getAppReadBuffer().setPosition(channel.getAppReadBuffer().getLimit());

		// return length;
		// }

		@Override
		public BufWrapper doRead() throws IOException {
			if (((SocketChannel) processor.getChannel()).getAppReadBuffer().hasNoRemaining()) {
				// The application is reading the HTTP request body which is
				// always a blocking operation.
				if (!((SocketChannel) processor.getChannel()).fillAppReadBuffer(true))
					return null;
			}

			int length = ((SocketChannel) processor.getChannel()).getAppReadBuffer().getRemaining();
			BufWrapper bufWrapper = ((SocketChannel) processor.getChannel()).getAppReadBuffer();// .duplicate()
			// ((SocketChannel)
			// processor.getChannel()).getAppReadBuffer().setPosition(((SocketChannel)
			// processor.getChannel()).getAppReadBuffer().getLimit());
			return bufWrapper;
		}

	}

}
