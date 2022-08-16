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
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import javax.servlet.http.HttpServletResponse;

import org.apache.coyote.ErrorState;
import org.apache.coyote.RequestAction;
import org.apache.coyote.InputReader;
import org.apache.coyote.Request;
import org.apache.coyote.RequestData;
import org.apache.coyote.Response;
import org.apache.coyote.http11.filters.SavedRequestInputFilter;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.buf.ByteChunk;
import org.apache.tomcat.util.buf.MessageBytes;
import org.apache.tomcat.util.http.HeaderUtil;
import org.apache.tomcat.util.http.MimeHeaders;
import org.apache.tomcat.util.http.parser.HttpParser;
import org.apache.tomcat.util.http.parser.TokenList;
import org.apache.tomcat.util.net.SocketChannel;
import org.apache.tomcat.util.net.SocketChannel.BufWrapper;
import org.apache.tomcat.util.res.StringManager;

/**
 * InputBuffer for HTTP that provides request header parsing as well as transfer
 * encoding.
 */
public class Http11InputBuffer implements RequestAction {

	// -------------------------------------------------------------- Constants

	private static final Log log = LogFactory.getLog(Http11InputBuffer.class);

	/**
	 * The string manager for this package.
	 */
	private static final StringManager sm = StringManager.getManager(Http11InputBuffer.class);

	private static final byte[] CLIENT_PREFACE_START = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n"
			.getBytes(StandardCharsets.ISO_8859_1);

	private Http11Processor processor;

	/**
	 * Associated Coyote request.
	 */
	private final RequestData requestData;

	/**
	 * Headers of the associated request.
	 */
	private final MimeHeaders headers;

	private final boolean rejectIllegalHeader;

	/**
	 * State.
	 */
	private boolean parsingHeader;

	/**
	 * Swallow input ? (in the case of an expectation)
	 */
	private boolean swallowInput;

	/**
	 * The read buffer.
	 */
	// private BufWrapper appReadBuffer;

	/**
	 * Pos of the end of the header in the buffer, which is also the start of the
	 * body.
	 */
	// private int end;

	/**
	 * Wrapper that provides access to the underlying socket.
	 */
	private SocketChannel channel;

	/**
	 * Underlying input buffer.
	 */
	private InputReader channelInputBuffer;

	/**
	 * Filter library. Note: Filter[Constants.CHUNKED_FILTER] is always the
	 * "chunked" filter.
	 */
	private InputFilter[] filterLibrary;

	/**
	 * Active filters (in order).
	 */
	private InputFilter[] activeFilters;

	/**
	 * Index of the last active filter.
	 */
	private int lastActiveFilter;

	/**
	 * Parsing state - used for non blocking parsing so that when more data arrives,
	 * we can pick up where we left off.
	 */
	private byte prevChr = 0;
	private byte chr = 0;
	private boolean parsingRequestLine;
	private int parsingRequestLinePhase = 0;
	private boolean parsingRequestLineEol = false;
	private int parsingRequestLineStart = 0;
	private int parsingRequestLineQPos = -1;
	private HeaderParsePosition headerParsePos;
	private final HeaderParseData headerData = new HeaderParseData();
	private final HttpParser httpParser;

	/**
	 * Maximum allowed size of the HTTP request line plus headers plus any leading
	 * blank lines.
	 */
	private final int headerBufferSize;

	/**
	 * Known size of the NioChannel read buffer.
	 */
	private final int socketReadBufferSize = 0;

	/**
	 * Tracks how many internal filters are in the filter library so they are
	 * skipped when looking for pluggable filters.
	 */
	private int pluggableFilterIndex = Integer.MAX_VALUE;

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

	// ----------------------------------------------------------- Constructors

	public Http11InputBuffer(Http11Processor processor, int headerBufferSize, boolean rejectIllegalHeader,
			HttpParser httpParser) {
		this.processor = processor;
		this.requestData = processor.getRequestData();
		headers = requestData.getMimeHeaders();

		this.headerBufferSize = headerBufferSize;
		this.rejectIllegalHeader = rejectIllegalHeader;
		this.httpParser = httpParser;

		filterLibrary = new InputFilter[0];
		activeFilters = new InputFilter[0];
		lastActiveFilter = -1;

		parsingHeader = true;
		parsingRequestLine = true;
		parsingRequestLinePhase = 0;
		parsingRequestLineEol = false;
		parsingRequestLineStart = 0;
		parsingRequestLineQPos = -1;
		headerParsePos = HeaderParsePosition.HEADER_START;
		swallowInput = true;

		channelInputBuffer = new SocketInputReader();
	}

	// ------------------------------------------------------------- Properties

	/**
	 * Add an input filter to the filter library.
	 *
	 * @throws NullPointerException if the supplied filter is null
	 */
	void addFilter(InputFilter filter) {

		if (filter == null) {
			throw new NullPointerException(sm.getString("iib.filter.npe"));
		}

		InputFilter[] newFilterLibrary = Arrays.copyOf(filterLibrary, filterLibrary.length + 1);
		newFilterLibrary[filterLibrary.length] = filter;
		filterLibrary = newFilterLibrary;

		activeFilters = new InputFilter[filterLibrary.length];
	}

	void resetPluggableFilterIndex() {
		pluggableFilterIndex = this.getFilters().length;
	}

	/**
	 * Get filters.
	 */
	InputFilter[] getFilters() {
		return filterLibrary;
	}

	/**
	 * Add an input filter to the filter library.
	 */
	void addActiveFilter(InputFilter filter) {

		if (lastActiveFilter == -1) {
			filter.setBuffer(channelInputBuffer);
		} else {
			for (int i = 0; i <= lastActiveFilter; i++) {
				if (activeFilters[i] == filter)
					return;
			}
			filter.setBuffer(activeFilters[lastActiveFilter]);
		}

		activeFilters[++lastActiveFilter] = filter;

		filter.setRequest(requestData);
	}

	/**
	 * Set the swallow input flag.
	 */
	void setSwallowInput(boolean swallowInput) {
		this.swallowInput = swallowInput;
	}

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
	public BufWrapper doRead() throws IOException {
		if (lastActiveFilter == -1)
			return channelInputBuffer.doRead();
		else
			return activeFilters[lastActiveFilter].doRead();
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
	 * Recycle the input buffer. This should be called when closing the connection.
	 */
	void recycle() {
		if (channel != null) {
			channel.getAppReadBuffer().reset();
			channel = null;
		}
		// requestData.recycle();

		for (int i = 0; i <= lastActiveFilter; i++) {
			activeFilters[i].recycle();
		}

		lastActiveFilter = -1;
		parsingHeader = true;
		swallowInput = true;

		chr = 0;
		prevChr = 0;
		headerParsePos = HeaderParsePosition.HEADER_START;
		parsingRequestLine = true;
		parsingRequestLinePhase = 0;
		parsingRequestLineEol = false;
		parsingRequestLineStart = 0;
		parsingRequestLineQPos = -1;
		headerData.recycle();
	}

	/**
	 * End processing of current HTTP request. Note: All bytes of the current
	 * request should have been already consumed. This method only resets all the
	 * pointers so that we are ready to parse the next HTTP request.
	 */
	void nextRequest() {
		// requestData.recycle();

		channel.getAppReadBuffer().nextRequest();

		// Recycle filters
		for (int i = 0; i <= lastActiveFilter; i++) {
			activeFilters[i].recycle();
		}

		// Reset pointers
		lastActiveFilter = -1;
		parsingHeader = true;
		swallowInput = true;

		headerParsePos = HeaderParsePosition.HEADER_START;
		parsingRequestLine = true;
		parsingRequestLinePhase = 0;
		parsingRequestLineEol = false;
		parsingRequestLineStart = 0;
		parsingRequestLineQPos = -1;
		headerData.recycle();
	}

	/**
	 * Read the request line. This function is meant to be used during the HTTP
	 * request header parsing. Do NOT attempt to read the request body using it.
	 *
	 * @throws IOException If an exception occurs during the underlying socket read
	 *                     operations, or if the given buffer is not big enough to
	 *                     accommodate the whole line.
	 *
	 * @return true if data is properly fed; false if no data is available
	 *         immediately and thread should be freed
	 */
	boolean parseRequestLine(boolean keptAlive, int connectionTimeout, int keepAliveTimeout) throws IOException {

		// check state
		if (!parsingRequestLine) {
			return true;
		}

		BufWrapper byteBuffer = channel.getAppReadBuffer();
		byteBuffer.startParsingHeader(headerBufferSize);
		byteBuffer.startParsingRequestLine();

		//
		// Skipping blank lines
		//
		if (parsingRequestLinePhase < 2) {
			do {
				// Read new bytes if needed
				if (byteBuffer.hasNoRemaining()) {
					if (keptAlive) {
						// Haven't read any request data yet so use the keep-alive
						// timeout.
						channel.setReadTimeout(keepAliveTimeout);
					}
					if (!channel.fillAppReadBuffer(false)) {
						// A read is pending, so no longer in initial state
						parsingRequestLinePhase = 1;
						return false;
					}
					// At least one byte of the request has been received.
					// Switch to the socket timeout.
					channel.setReadTimeout(connectionTimeout);
				}
				if (!keptAlive && byteBuffer.getPosition() == 0
						&& byteBuffer.getLimit() >= CLIENT_PREFACE_START.length - 1) {
					boolean prefaceMatch = true;
					for (int i = 0; i < CLIENT_PREFACE_START.length && prefaceMatch; i++) {
						if (CLIENT_PREFACE_START[i] != byteBuffer.getByte(i)) {
							prefaceMatch = false;
						}
					}
					if (prefaceMatch) {
						// HTTP/2 preface matched
						parsingRequestLinePhase = -1;
						return false;
					}
				}
				// Set the start time once we start reading data (even if it is
				// just skipping blank lines)
				if (requestData.getStartTime() < 0) {
					requestData.setStartTime(System.currentTimeMillis());
				}
				chr = byteBuffer.getByte();
			} while ((chr == Constants.CR) || (chr == Constants.LF));
			byteBuffer.setPosition(byteBuffer.getPosition() - 1);

			parsingRequestLineStart = byteBuffer.getPosition();
			parsingRequestLinePhase = 2;
			if (log.isDebugEnabled()) {
				if (byteBuffer.hasArray()) {
					log.debug("Received [" + new String(byteBuffer.getArray(), byteBuffer.getPosition(),
							byteBuffer.getRemaining(), StandardCharsets.ISO_8859_1) + "]");
				} else {
					int start = byteBuffer.getPosition();
					int length = byteBuffer.getRemaining();
					byte[] array = new byte[length];
					for (int index = 0; index < length; index++) {
						array[index] = byteBuffer.getByte(start + index);
					}
					log.debug("Received [" + new String(array, 0, length, StandardCharsets.ISO_8859_1) + "]");
				}
			}
		}
		if (parsingRequestLinePhase == 2) {
			//
			// Reading the method name
			// Method name is a token
			//
			boolean space = false;
			while (!space) {
				// Read new bytes if needed
				if (byteBuffer.hasNoRemaining()) {
					if (!channel.fillAppReadBuffer(false)) // request line parsing
						return false;
				}
				// Spec says method name is a token followed by a single SP but
				// also be tolerant of multiple SP and/or HT.
				int pos = byteBuffer.getPosition();
				chr = byteBuffer.getByte();
				if (chr == Constants.SP || chr == Constants.HT) {
					space = true;
					if (byteBuffer.hasArray()) {
						requestData.method().setBytes(byteBuffer.getArray(), parsingRequestLineStart,
								pos - parsingRequestLineStart);
					} else {
						int start = parsingRequestLineStart;
						int length = pos - parsingRequestLineStart;
						byte[] array = new byte[length];
						for (int index = 0; index < length; index++) {
							array[index] = byteBuffer.getByte(start + index);
						}
						requestData.method().setBytes(array, 0, length);
					}
				} else if (!HttpParser.isToken(chr)) {
					// Avoid unknown protocol triggering an additional error
					requestData.protocol().setString(Constants.HTTP_11);
					String invalidMethodValue = parseInvalid(parsingRequestLineStart, byteBuffer);
					throw new IllegalArgumentException(sm.getString("iib.invalidmethod", invalidMethodValue));
				}
			}
			parsingRequestLinePhase = 3;
		}
		if (parsingRequestLinePhase == 3) {
			// Spec says single SP but also be tolerant of multiple SP and/or HT
			boolean space = true;
			while (space) {
				// Read new bytes if needed
				if (byteBuffer.hasNoRemaining()) {
					if (!channel.fillAppReadBuffer(false)) // request line parsing
						return false;
				}
				chr = byteBuffer.getByte();
				if (!(chr == Constants.SP || chr == Constants.HT)) {
					space = false;
					byteBuffer.setPosition(byteBuffer.getPosition() - 1);
				}
			}
			parsingRequestLineStart = byteBuffer.getPosition();
			parsingRequestLinePhase = 4;
		}
		if (parsingRequestLinePhase == 4) {
			// Mark the current buffer position

			int end = 0;
			//
			// Reading the URI
			//
			boolean space = false;
			while (!space) {
				// Read new bytes if needed
				if (byteBuffer.hasNoRemaining()) {
					if (!channel.fillAppReadBuffer(false)) // request line parsing
						return false;
				}
				int pos = byteBuffer.getPosition();
				prevChr = chr;
				chr = byteBuffer.getByte();
				if (prevChr == Constants.CR && chr != Constants.LF) {
					// CR not followed by LF so not an HTTP/0.9 request and
					// therefore invalid. Trigger error handling.
					// Avoid unknown protocol triggering an additional error
					requestData.protocol().setString(Constants.HTTP_11);
					String invalidRequestTarget = parseInvalid(parsingRequestLineStart, byteBuffer);
					throw new IllegalArgumentException(sm.getString("iib.invalidRequestTarget", invalidRequestTarget));
				}
				if (chr == Constants.SP || chr == Constants.HT) {
					space = true;
					end = pos;
				} else if (chr == Constants.CR) {
					// HTTP/0.9 style request. CR is optional. LF is not.
				} else if (chr == Constants.LF) {
					// HTTP/0.9 style request
					// Stop this processing loop
					space = true;
					// Set blank protocol (indicates HTTP/0.9)
					requestData.protocol().setString("");
					// Skip the protocol processing
					parsingRequestLinePhase = 7;
					if (prevChr == Constants.CR) {
						end = pos - 1;
					} else {
						end = pos;
					}
				} else if (chr == Constants.QUESTION && parsingRequestLineQPos == -1) {
					parsingRequestLineQPos = pos;
				} else if (parsingRequestLineQPos != -1 && !httpParser.isQueryRelaxed(chr)) {
					// Avoid unknown protocol triggering an additional error
					requestData.protocol().setString(Constants.HTTP_11);
					// %nn decoding will be checked at the point of decoding
					String invalidRequestTarget = parseInvalid(parsingRequestLineStart, byteBuffer);
					throw new IllegalArgumentException(sm.getString("iib.invalidRequestTarget", invalidRequestTarget));
				} else if (httpParser.isNotRequestTargetRelaxed(chr)) {
					// Avoid unknown protocol triggering an additional error
					requestData.protocol().setString(Constants.HTTP_11);
					// This is a general check that aims to catch problems early
					// Detailed checking of each part of the request target will
					// happen in Http11Processor#prepareRequest()
					String invalidRequestTarget = parseInvalid(parsingRequestLineStart, byteBuffer);
					throw new IllegalArgumentException(sm.getString("iib.invalidRequestTarget", invalidRequestTarget));
				}
			}
			if (parsingRequestLineQPos >= 0) {
				if (byteBuffer.hasArray()) {
					requestData.queryString().setBytes(byteBuffer.getArray(), parsingRequestLineQPos + 1,
							end - parsingRequestLineQPos - 1);
				} else {
					int start = parsingRequestLineQPos + 1;
					int length = end - parsingRequestLineQPos - 1;
					byte[] array = new byte[length];
					for (int index = 0; index < length; index++) {
						array[index] = byteBuffer.getByte(start + index);
					}
					requestData.queryString().setBytes(array, 0, length);
				}
				if (byteBuffer.hasArray()) {
					requestData.requestURI().setBytes(byteBuffer.getArray(), parsingRequestLineStart,
							parsingRequestLineQPos - parsingRequestLineStart);
				} else {
					int start = parsingRequestLineStart;
					int length = parsingRequestLineQPos - parsingRequestLineStart;
					byte[] array = new byte[length];
					for (int index = 0; index < length; index++) {
						array[index] = byteBuffer.getByte(start + index);
					}
					requestData.requestURI().setBytes(array, 0, length);
				}
			} else {
				if (byteBuffer.hasArray()) {
					requestData.requestURI().setBytes(byteBuffer.getArray(), parsingRequestLineStart,
							end - parsingRequestLineStart);
				} else {
					int start = parsingRequestLineStart;
					int length = end - parsingRequestLineStart;
					byte[] array = new byte[length];
					for (int index = 0; index < length; index++) {
						array[index] = byteBuffer.getByte(start + index);
					}
					requestData.requestURI().setBytes(array, 0, length);
				}
			}
			// HTTP/0.9 processing jumps to stage 7.
			// Don't want to overwrite that here.
			if (parsingRequestLinePhase == 4) {
				parsingRequestLinePhase = 5;
			}
		}
		if (parsingRequestLinePhase == 5) {
			// Spec says single SP but also be tolerant of multiple and/or HT
			boolean space = true;
			while (space) {
				// Read new bytes if needed
				if (byteBuffer.hasNoRemaining()) {
					if (!channel.fillAppReadBuffer(false)) // request line parsing
						return false;
				}
				byte chr = byteBuffer.getByte();
				if (!(chr == Constants.SP || chr == Constants.HT)) {
					space = false;
					byteBuffer.setPosition(byteBuffer.getPosition() - 1);
				}
			}
			parsingRequestLineStart = byteBuffer.getPosition();
			parsingRequestLinePhase = 6;

			// Mark the current buffer position
		}
		if (parsingRequestLinePhase == 6) {
			int end = 0;
			//
			// Reading the protocol
			// Protocol is always "HTTP/" DIGIT "." DIGIT
			//
			while (!parsingRequestLineEol) {
				// Read new bytes if needed
				if (byteBuffer.hasNoRemaining()) {
					if (!channel.fillAppReadBuffer(false)) // request line parsing
						return false;
				}

				int pos = byteBuffer.getPosition();
				prevChr = chr;
				chr = byteBuffer.getByte();
				if (chr == Constants.CR) {
					// Possible end of request line. Need LF next.
				} else if (prevChr == Constants.CR && chr == Constants.LF) {
					end = pos - 1;
					parsingRequestLineEol = true;
				} else if (!HttpParser.isHttpProtocol(chr)) {
					String invalidProtocol = parseInvalid(parsingRequestLineStart, byteBuffer);
					throw new IllegalArgumentException(sm.getString("iib.invalidHttpProtocol", invalidProtocol));
				}
			}

			if ((end - parsingRequestLineStart) > 0) {
				if (byteBuffer.hasArray()) {
					requestData.protocol().setBytes(byteBuffer.getArray(), parsingRequestLineStart,
							end - parsingRequestLineStart);
				} else {
					int start = parsingRequestLineStart;
					int length = end - parsingRequestLineStart;
					byte[] array = new byte[length];
					for (int index = 0; index < length; index++) {
						array[index] = byteBuffer.getByte(start + index);
					}
					requestData.protocol().setBytes(array, 0, length);
				}
				parsingRequestLinePhase = 7;
			}
			// If no protocol is found, the ISE below will be triggered.
		}
		if (parsingRequestLinePhase == 7) {
			// Parsing is complete. Return and clean-up.
			parsingRequestLine = false;
			byteBuffer.finishParsingRequestLine();
			parsingRequestLinePhase = 0;
			parsingRequestLineEol = false;
			parsingRequestLineStart = 0;
			return true;
		}
		throw new IllegalStateException(sm.getString("iib.invalidPhase", Integer.valueOf(parsingRequestLinePhase)));
	}

	/**
	 * Parse the HTTP headers.
	 */
	boolean parseHeaders() throws IOException {
		if (!parsingHeader) {
			// throw new IllegalStateException(sm.getString("iib.parseheaders.ise.error"));
			return true;
		}

		BufWrapper byteBuffer = channel.getAppReadBuffer();
		byteBuffer.startParsingHeader(headerBufferSize);

		HeaderParseStatus status = HeaderParseStatus.HAVE_MORE_HEADERS;

		do {
			status = parseHeader();
			// Checking that
			// (1) Headers plus request line size does not exceed its limit
			// (2) There are enough bytes to avoid expanding the buffer when
			// reading body
			// Technically, (2) is technical limitation, (1) is logical
			// limitation to enforce the meaning of headerBufferSize
			// From the way how buf is allocated and how blank lines are being
			// read, it should be enough to check (1) only.
			if (byteBuffer.getPosition() > headerBufferSize
					|| byteBuffer.getCapacity() - byteBuffer.getPosition() < socketReadBufferSize) {
				throw new IllegalArgumentException(sm.getString("iib.requestheadertoolarge.error"));
			}
		} while (status == HeaderParseStatus.HAVE_MORE_HEADERS);
		if (status == HeaderParseStatus.DONE) {
			parsingHeader = false;
			byteBuffer.finishParsingHeader(true);
			// end = byteBuffer.getPosition();
			return true;
		} else {
			return false;
		}
	}

	int getParsingRequestLinePhase() {
		return parsingRequestLinePhase;
	}

	private String parseInvalid(int startPos, BufWrapper buffer) {
		// Look for the next space
		byte b = 0;
		while (buffer.hasRemaining() && b != 0x20) {
			b = buffer.getByte();
		}
		String result = "";
		if (buffer.hasArray()) {
			result = HeaderUtil.toPrintableString(buffer.getArray(), startPos, buffer.getPosition() - startPos - 1);// buffer.arrayOffset()
																													// +
		} else {
			int start = startPos;
			int length = buffer.getPosition() - startPos - 1;
			byte[] array = new byte[length];
			for (int index = 0; index < length; index++) {
				array[index] = buffer.getByte(start + index);
			}
			result = HeaderUtil.toPrintableString(array, 0, length);// buffer.arrayOffset()
		}
		if (b != 0x20) {
			// Ran out of buffer rather than found a space
			result = result + "...";
		}
		return result;
	}

	/**
	 * After reading the request headers, we have to setup the request filters.
	 */
	protected void prepareRequest() throws IOException {

		processor.contentDelimitation = false;

		AbstractHttp11Protocol protocol = (AbstractHttp11Protocol) processor.getProtocol();

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
		InputFilter[] inputFilters = this.getFilters();

		// Parse transfer-encoding header
		if (http11) {
			MessageBytes transferEncodingValueMB = headers.getValue("transfer-encoding");
			if (transferEncodingValueMB != null) {
				List<String> encodingNames = new ArrayList<>();
				if (TokenList.parseTokenList(headers.values("transfer-encoding"), encodingNames)) {
					for (String encodingName : encodingNames) {
						// "identity" codings are ignored
						this.addInputFilter(inputFilters, encodingName);
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
			if (processor.contentDelimitation) {
				// contentDelimitation being true at this point indicates that
				// chunked encoding is being used but chunked encoding should
				// not be used with a content length. RFC 2616, section 4.4,
				// bullet 3 states Content-Length must be ignored in this case -
				// so remove it.
				headers.removeHeader("content-length");
				requestData.setContentLength(-1);
			} else {
				this.addActiveFilter(inputFilters[Constants.IDENTITY_FILTER]);
				processor.contentDelimitation = true;
			}
		}

		// Validate host name and extract port if present
		processor.parseHost(hostValueMB);

		if (!processor.contentDelimitation) {
			// If there's no content length
			// (broken HTTP/1.0 or HTTP/1.1), assume
			// the client is not broken and didn't send a body
			this.addActiveFilter(inputFilters[Constants.VOID_FILTER]);
			processor.contentDelimitation = true;
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
	private void addInputFilter(InputFilter[] inputFilters, String encodingName) {

		// Parsing trims and converts to lower case.

		if (encodingName.equals("identity")) {
			// Skip
		} else if (encodingName.equals("chunked")) {
			this.addActiveFilter(inputFilters[Constants.CHUNKED_FILTER]);
			processor.contentDelimitation = true;
		} else {
			for (int i = pluggableFilterIndex; i < inputFilters.length; i++) {
				if (inputFilters[i].getEncodingName().toString().equals(encodingName)) {
					this.addActiveFilter(inputFilters[i]);
					return;
				}
			}
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

		BufWrapper byteBuffer = channel.getAppReadBuffer();

		if (swallowInput && (lastActiveFilter != -1)) {
			int extraBytes = (int) activeFilters[lastActiveFilter].end();
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
		BufWrapper byteBuffer = channel.getAppReadBuffer();

		int available = byteBuffer.getRemaining();
		if ((available == 0) && (lastActiveFilter >= 0)) {
			for (int i = 0; (available == 0) && (i <= lastActiveFilter); i++) {
				available = activeFilters[i].available();
			}
		}
		if (available > 0 || !read) {
			return available;
		}

		try {
			if (channel.hasDataToRead()) {
				channel.fillAppReadBuffer(false);
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

	/**
	 * Has all of the request body been read? There are subtle differences between
	 * this and available() &gt; 0 primarily because of having to handle faking
	 * non-blocking reads with the blocking IO connector.
	 */
	boolean isFinished() {
		BufWrapper byteBuffer = channel.getAppReadBuffer();

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

		if (lastActiveFilter >= 0) {
			return activeFilters[lastActiveFilter].isFinished();
		} else {
			// No filters. Assume request is not finished. EOF will signal end of
			// request.
			return false;
		}
	}

	@Override
	public final void registerReadInterest() {
		channel.registerReadInterest();
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
	 * Processors that populate request attributes directly (e.g. AJP) should
	 * over-ride this method and return {@code false}.
	 *
	 * @return {@code true} if the SocketWrapper should be used to populate the
	 *         request attributes, otherwise {@code false}.
	 */
	protected boolean getPopulateRequestAttributesFromSocket() {
		return true;
	}

	/**
	 * Populate the remote host request attribute. Processors (e.g. AJP) that
	 * populate this from an alternative source should override this method.
	 */
	protected void populateRequestAttributeRemoteHost() {
		if (getPopulateRequestAttributesFromSocket() && channel != null) {
			requestData.remoteHost().setString(channel.getRemoteHost());
		}
	}

	// @Override
	public void actionREQ_HOST_ADDR_ATTRIBUTE() {
		if (getPopulateRequestAttributesFromSocket() && channel != null) {
			requestData.remoteAddr().setString(channel.getRemoteAddr());
		}
	}

	// @Override
	public void actionREQ_HOST_ATTRIBUTE() {
		populateRequestAttributeRemoteHost();
	}

	// @Override
	public void actionREQ_LOCALPORT_ATTRIBUTE() {
		if (getPopulateRequestAttributesFromSocket() && channel != null) {
			requestData.setLocalPort(channel.getLocalPort());
		}
	}

	// @Override
	public void actionREQ_LOCAL_ADDR_ATTRIBUTE() {
		if (getPopulateRequestAttributesFromSocket() && channel != null) {
			requestData.localAddr().setString(channel.getLocalAddr());
		}
	}

	// @Override
	public void actionREQ_LOCAL_NAME_ATTRIBUTE() {
		if (getPopulateRequestAttributesFromSocket() && channel != null) {
			requestData.localName().setString(channel.getLocalName());
		}
	}

	// @Override
	public void actionREQ_REMOTEPORT_ATTRIBUTE() {
		if (getPopulateRequestAttributesFromSocket() && channel != null) {
			requestData.setRemotePort(channel.getRemotePort());
		}
	}

	@Override
	public final void setRequestBody(ByteChunk body) {
		InputFilter savedBody = new SavedRequestInputFilter(body);
		// Http11InputBuffer internalBuffer = (Http11InputBuffer)
		// request.getInputBuffer();
		this.addActiveFilter(savedBody);
	}

	@Override
	public final void disableSwallowRequest() {
		this.setSwallowInput(false);
	}

	ByteBuffer getLeftover() {
		BufWrapper byteBuffer = channel.getAppReadBuffer();
		int available = byteBuffer.getRemaining();
		if (available > 0) {
			return ByteBuffer.wrap(byteBuffer.getArray(), byteBuffer.getPosition(), available);
		} else {
			return null;
		}
	}

	boolean isChunking() {
		for (int i = 0; i < lastActiveFilter; i++) {
			if (activeFilters[i] == filterLibrary[Constants.CHUNKED_FILTER]) {
				return true;
			}
		}
		return false;
	}

	void init(SocketChannel socketWrapper) {

		channel = socketWrapper;

		channel.initAppReadBuffer(headerBufferSize);

		// this.appReadBuffer = channel.getAppReadBuffer();

	}

	// --------------------------------------------------------- Private Methods

	/**
	 * Parse an HTTP header.
	 *
	 * @return false after reading a blank line (which indicates that the HTTP
	 *         header parsing is done
	 */
	private HeaderParseStatus parseHeader() throws IOException {

		BufWrapper byteBuffer = channel.getAppReadBuffer();

		while (headerParsePos == HeaderParsePosition.HEADER_START) {

			// Read new bytes if needed
			if (byteBuffer.hasNoRemaining()) {
				if (!channel.fillAppReadBuffer(false)) {// parse header
					headerParsePos = HeaderParsePosition.HEADER_START;
					return HeaderParseStatus.NEED_MORE_DATA;
				}
			}

			prevChr = chr;
			chr = byteBuffer.getByte();

			if (chr == Constants.CR && prevChr != Constants.CR) {
				// Possible start of CRLF - process the next byte.
			} else if (prevChr == Constants.CR && chr == Constants.LF) {
				return HeaderParseStatus.DONE;
			} else {
				if (prevChr == Constants.CR) {
					// Must have read two bytes (first was CR, second was not LF)
					byteBuffer.setPosition(byteBuffer.getPosition() - 2);
				} else {
					// Must have only read one byte
					byteBuffer.setPosition(byteBuffer.getPosition() - 1);
				}
				break;
			}
		}

		if (headerParsePos == HeaderParsePosition.HEADER_START) {
			// Mark the current buffer position
			headerData.start = byteBuffer.getPosition();
			headerData.lineStart = headerData.start;
			headerParsePos = HeaderParsePosition.HEADER_NAME;
		}

		//
		// Reading the header name
		// Header name is always US-ASCII
		//

		while (headerParsePos == HeaderParsePosition.HEADER_NAME) {

			// Read new bytes if needed
			if (byteBuffer.hasNoRemaining()) {
				if (!channel.fillAppReadBuffer(false)) { // parse header
					return HeaderParseStatus.NEED_MORE_DATA;
				}
			}

			int pos = byteBuffer.getPosition();
			chr = byteBuffer.getByte();
			if (chr == Constants.COLON) {
				headerParsePos = HeaderParsePosition.HEADER_VALUE_START;
				if (byteBuffer.hasArray()) {
					headerData.headerValue = headers.addValue(byteBuffer.getArray(), headerData.start,
							pos - headerData.start);
				} else {
					int start = headerData.start;
					int length = pos - headerData.start;
					byte[] array = new byte[length];
					for (int index = 0; index < length; index++) {
						array[index] = byteBuffer.getByte(start + index);
					}
					headerData.headerValue = headers.addValue(array, 0, length);
				}
				pos = byteBuffer.getPosition();
				// Mark the current buffer position
				headerData.start = pos;
				headerData.realPos = pos;
				headerData.lastSignificantChar = pos;
				break;
			} else if (!HttpParser.isToken(chr)) {
				// Non-token characters are illegal in header names
				// Parsing continues so the error can be reported in context
				headerData.lastSignificantChar = pos;
				byteBuffer.setPosition(byteBuffer.getPosition() - 1);
				// skipLine() will handle the error
				return skipLine();
			}

			// chr is next byte of header name. Convert to lowercase.
			if ((chr >= Constants.A) && (chr <= Constants.Z)) {
				byteBuffer.setByte(pos, (byte) (chr - Constants.LC_OFFSET));
			}
		}

		// Skip the line and ignore the header
		if (headerParsePos == HeaderParsePosition.HEADER_SKIPLINE) {
			return skipLine();
		}

		//
		// Reading the header value (which can be spanned over multiple lines)
		//

		while (headerParsePos == HeaderParsePosition.HEADER_VALUE_START
				|| headerParsePos == HeaderParsePosition.HEADER_VALUE
				|| headerParsePos == HeaderParsePosition.HEADER_MULTI_LINE) {

			if (headerParsePos == HeaderParsePosition.HEADER_VALUE_START) {
				// Skipping spaces
				while (true) {
					// Read new bytes if needed
					if (byteBuffer.hasNoRemaining()) {
						if (!channel.fillAppReadBuffer(false)) {// parse header
							// HEADER_VALUE_START
							return HeaderParseStatus.NEED_MORE_DATA;
						}
					}

					chr = byteBuffer.getByte();
					if (!(chr == Constants.SP || chr == Constants.HT)) {
						headerParsePos = HeaderParsePosition.HEADER_VALUE;
						byteBuffer.setPosition(byteBuffer.getPosition() - 1);
						break;
					}
				}
			}
			if (headerParsePos == HeaderParsePosition.HEADER_VALUE) {

				// Reading bytes until the end of the line
				boolean eol = false;
				while (!eol) {

					// Read new bytes if needed
					if (byteBuffer.hasNoRemaining()) {
						if (!channel.fillAppReadBuffer(false)) {// parse header
							// HEADER_VALUE
							return HeaderParseStatus.NEED_MORE_DATA;
						}
					}

					prevChr = chr;
					chr = byteBuffer.getByte();
					if (chr == Constants.CR) {
						// Possible start of CRLF - process the next byte.
					} else if (prevChr == Constants.CR && chr == Constants.LF) {
						eol = true;
					} else if (prevChr == Constants.CR) {
						// Invalid value
						// Delete the header (it will be the most recent one)
						headers.removeHeader(headers.size() - 1);
						return skipLine();
					} else if (chr != Constants.HT && HttpParser.isControl(chr)) {
						// Invalid value
						// Delete the header (it will be the most recent one)
						headers.removeHeader(headers.size() - 1);
						return skipLine();
					} else if (chr == Constants.SP || chr == Constants.HT) {
						byteBuffer.setByte(headerData.realPos, chr);
						headerData.realPos++;
					} else {
						byteBuffer.setByte(headerData.realPos, chr);
						headerData.realPos++;
						headerData.lastSignificantChar = headerData.realPos;
					}
				}

				// Ignore whitespaces at the end of the line
				headerData.realPos = headerData.lastSignificantChar;

				// Checking the first character of the new line. If the character
				// is a LWS, then it's a multiline header
				headerParsePos = HeaderParsePosition.HEADER_MULTI_LINE;
			}
			// Read new bytes if needed
			if (byteBuffer.hasNoRemaining()) {
				if (!channel.fillAppReadBuffer(false)) {// parse header
					// HEADER_MULTI_LINE
					return HeaderParseStatus.NEED_MORE_DATA;
				}
			}

			byte peek = byteBuffer.getByte(byteBuffer.getPosition());
			if (headerParsePos == HeaderParsePosition.HEADER_MULTI_LINE) {
				if ((peek != Constants.SP) && (peek != Constants.HT)) {
					headerParsePos = HeaderParsePosition.HEADER_START;
					break;
				} else {
					// Copying one extra space in the buffer (since there must
					// be at least one space inserted between the lines)
					byteBuffer.setByte(headerData.realPos, peek);
					headerData.realPos++;
					headerParsePos = HeaderParsePosition.HEADER_VALUE_START;
				}
			}
		}
		// Set the header value
		if (byteBuffer.hasArray()) {
			headerData.headerValue.setBytes(byteBuffer.getArray(), headerData.start,
					headerData.lastSignificantChar - headerData.start);
		} else {
			int start = headerData.start;
			int length = headerData.lastSignificantChar - headerData.start;
			byte[] array = new byte[length];
			for (int index = 0; index < length; index++) {
				array[index] = byteBuffer.getByte(start + index);
			}
			headerData.headerValue.setBytes(array, 0, length);
		}
		headerData.recycle();
		return HeaderParseStatus.HAVE_MORE_HEADERS;
	}

	private HeaderParseStatus skipLine() throws IOException {
		BufWrapper byteBuffer = channel.getAppReadBuffer();

		headerParsePos = HeaderParsePosition.HEADER_SKIPLINE;
		boolean eol = false;

		// Reading bytes until the end of the line
		while (!eol) {

			// Read new bytes if needed
			if (byteBuffer.hasNoRemaining()) {
				if (!channel.fillAppReadBuffer(false)) {
					return HeaderParseStatus.NEED_MORE_DATA;
				}
			}

			int pos = byteBuffer.getPosition();
			prevChr = chr;
			chr = byteBuffer.getByte();
			if (chr == Constants.CR) {
				// Skip
			} else if (prevChr == Constants.CR && chr == Constants.LF) {
				eol = true;
			} else {
				headerData.lastSignificantChar = pos;
			}
		}
		if (rejectIllegalHeader || log.isDebugEnabled()) {
			String message = "";
			if (byteBuffer.hasArray()) {
				message = sm.getString("iib.invalidheader", HeaderUtil.toPrintableString(byteBuffer.getArray(),
						headerData.lineStart, headerData.lastSignificantChar - headerData.lineStart + 1));
			} else {
				int start = headerData.lineStart;
				int length = headerData.lastSignificantChar - headerData.lineStart + 1;
				byte[] array = new byte[length];
				for (int index = 0; index < length; index++) {
					array[index] = byteBuffer.getByte(start + index);
				}
				message = sm.getString("iib.invalidheader", HeaderUtil.toPrintableString(array, 0, length));
			}
			if (rejectIllegalHeader) {
				throw new IllegalArgumentException(message);
			}
			log.debug(message);
		}

		headerParsePos = HeaderParsePosition.HEADER_START;
		return HeaderParseStatus.HAVE_MORE_HEADERS;
	}

	// ----------------------------------------------------------- Inner classes

	private enum HeaderParseStatus {
		DONE, HAVE_MORE_HEADERS, NEED_MORE_DATA
	}

	private enum HeaderParsePosition {
		/**
		 * Start of a new header. A CRLF here means that there are no more headers. Any
		 * other character starts a header name.
		 */
		HEADER_START,
		/**
		 * Reading a header name. All characters of header are HTTP_TOKEN_CHAR. Header
		 * name is followed by ':'. No whitespace is allowed.<br>
		 * Any non-HTTP_TOKEN_CHAR (this includes any whitespace) encountered before ':'
		 * will result in the whole line being ignored.
		 */
		HEADER_NAME,
		/**
		 * Skipping whitespace before text of header value starts, either on the first
		 * line of header value (just after ':') or on subsequent lines when it is known
		 * that subsequent line starts with SP or HT.
		 */
		HEADER_VALUE_START,
		/**
		 * Reading the header value. We are inside the value. Either on the first line
		 * or on any subsequent line. We come into this state from HEADER_VALUE_START
		 * after the first non-SP/non-HT byte is encountered on the line.
		 */
		HEADER_VALUE,
		/**
		 * Before reading a new line of a header. Once the next byte is peeked, the
		 * state changes without advancing our position. The state becomes either
		 * HEADER_VALUE_START (if that first byte is SP or HT), or HEADER_START
		 * (otherwise).
		 */
		HEADER_MULTI_LINE,
		/**
		 * Reading all bytes until the next CRLF. The line is being ignored.
		 */
		HEADER_SKIPLINE
	}

	private static class HeaderParseData {
		/**
		 * The first character of the header line.
		 */
		int lineStart = 0;
		/**
		 * When parsing header name: first character of the header.<br>
		 * When skipping broken header line: first character of the header.<br>
		 * When parsing header value: first character after ':'.
		 */
		int start = 0;
		/**
		 * When parsing header name: not used (stays as 0).<br>
		 * When skipping broken header line: not used (stays as 0).<br>
		 * When parsing header value: starts as the first character after ':'. Then is
		 * increased as far as more bytes of the header are harvested. Bytes from
		 * buf[pos] are copied to buf[realPos]. Thus the string from [start] to
		 * [realPos-1] is the prepared value of the header, with whitespaces removed as
		 * needed.<br>
		 */
		int realPos = 0;
		/**
		 * When parsing header name: not used (stays as 0).<br>
		 * When skipping broken header line: last non-CR/non-LF character.<br>
		 * When parsing header value: position after the last not-LWS character.<br>
		 */
		int lastSignificantChar = 0;
		/**
		 * MB that will store the value of the header. It is null while parsing header
		 * name and is created after the name has been parsed.
		 */
		MessageBytes headerValue = null;

		public void recycle() {
			lineStart = 0;
			start = 0;
			realPos = 0;
			lastSignificantChar = 0;
			headerValue = null;
		}
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
			if (channel.getAppReadBuffer().hasNoRemaining()) {
				// The application is reading the HTTP request body which is
				// always a blocking operation.
				if (!channel.fillAppReadBuffer(true))
					return null;
			}

			int length = channel.getAppReadBuffer().getRemaining();
			BufWrapper bufWrapper = channel.getAppReadBuffer();// .duplicate()
			// channel.getAppReadBuffer().setPosition(channel.getAppReadBuffer().getLimit());
			return bufWrapper;
		}

	}

}
