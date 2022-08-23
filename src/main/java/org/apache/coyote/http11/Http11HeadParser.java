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
import java.nio.charset.StandardCharsets;
import org.apache.coyote.RequestData;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.buf.MessageBytes;
import org.apache.tomcat.util.http.HeaderUtil;
import org.apache.tomcat.util.http.MimeHeaders;
import org.apache.tomcat.util.http.parser.HttpParser;
import org.apache.tomcat.util.net.SocketChannel;
import org.apache.tomcat.util.net.SocketChannel.BufWrapper;
import org.apache.tomcat.util.res.StringManager;

/**
 * InputBuffer for HTTP that provides request header parsing as well as transfer
 * encoding.
 */
public class Http11HeadParser {

	// -------------------------------------------------------------- Constants

	private static final Log log = LogFactory.getLog(Http11HeadParser.class);

	/**
	 * The string manager for this package.
	 */
	private static final StringManager sm = StringManager.getManager(Http11HeadParser.class);

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
	// private SocketChannel channel;

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

	// ----------------------------------------------------------- Constructors

	public Http11HeadParser(Http11Processor processor, int headerBufferSize, boolean rejectIllegalHeader,
			HttpParser httpParser) {
		this.processor = processor;
		this.requestData = processor.getRequestData();
		headers = requestData.getMimeHeaders();

		this.headerBufferSize = headerBufferSize;
		this.rejectIllegalHeader = rejectIllegalHeader;
		this.httpParser = httpParser;

		parsingHeader = true;
		parsingRequestLine = true;
		parsingRequestLinePhase = 0;
		parsingRequestLineEol = false;
		parsingRequestLineStart = 0;
		parsingRequestLineQPos = -1;
		headerParsePos = HeaderParsePosition.HEADER_START;

	}

	// ------------------------------------------------------------- Properties

	/**
	 * Recycle the input buffer. This should be called when closing the connection.
	 */
	void recycle() {
		if (((SocketChannel) processor.getChannel()) != null) {
			((SocketChannel) processor.getChannel()).getAppReadBuffer().reset();
		}
		// requestData.recycle();

		parsingHeader = true;

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

		((SocketChannel) processor.getChannel()).getAppReadBuffer().nextRequest();

		parsingHeader = true;

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

		BufWrapper byteBuffer = ((SocketChannel) processor.getChannel()).getAppReadBuffer();
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
						((SocketChannel) processor.getChannel()).setReadTimeout(keepAliveTimeout);
					}
					if (!((SocketChannel) processor.getChannel()).fillAppReadBuffer(false)) {
						// A read is pending, so no longer in initial state
						parsingRequestLinePhase = 1;
						return false;
					}
					// At least one byte of the request has been received.
					// Switch to the socket timeout.
					((SocketChannel) processor.getChannel()).setReadTimeout(connectionTimeout);
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
					if (!((SocketChannel) processor.getChannel()).fillAppReadBuffer(false)) // request line parsing
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
					if (!((SocketChannel) processor.getChannel()).fillAppReadBuffer(false)) // request line parsing
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
					if (!((SocketChannel) processor.getChannel()).fillAppReadBuffer(false)) // request line parsing
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
					if (!((SocketChannel) processor.getChannel()).fillAppReadBuffer(false)) // request line parsing
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
					if (!((SocketChannel) processor.getChannel()).fillAppReadBuffer(false)) // request line parsing
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

		BufWrapper byteBuffer = ((SocketChannel) processor.getChannel()).getAppReadBuffer();
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

	void init(SocketChannel channel) {

		// this.channel = channel;

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

		BufWrapper byteBuffer = ((SocketChannel) processor.getChannel()).getAppReadBuffer();

		while (headerParsePos == HeaderParsePosition.HEADER_START) {

			// Read new bytes if needed
			if (byteBuffer.hasNoRemaining()) {
				if (!((SocketChannel) processor.getChannel()).fillAppReadBuffer(false)) {// parse header
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
				if (!((SocketChannel) processor.getChannel()).fillAppReadBuffer(false)) { // parse header
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
						if (!((SocketChannel) processor.getChannel()).fillAppReadBuffer(false)) {// parse header
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
						if (!((SocketChannel) processor.getChannel()).fillAppReadBuffer(false)) {// parse header
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
				if (!((SocketChannel) processor.getChannel()).fillAppReadBuffer(false)) {// parse header
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
		BufWrapper byteBuffer = ((SocketChannel) processor.getChannel()).getAppReadBuffer();

		headerParsePos = HeaderParsePosition.HEADER_SKIPLINE;
		boolean eol = false;

		// Reading bytes until the end of the line
		while (!eol) {

			// Read new bytes if needed
			if (byteBuffer.hasNoRemaining()) {
				if (!((SocketChannel) processor.getChannel()).fillAppReadBuffer(false)) {
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

}
