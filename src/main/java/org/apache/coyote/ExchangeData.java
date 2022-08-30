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
package org.apache.coyote;

import java.io.IOException;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.buf.B2CConverter;
import org.apache.tomcat.util.buf.MessageBytes;
import org.apache.tomcat.util.buf.UDecoder;
import org.apache.tomcat.util.http.MimeHeaders;
import org.apache.tomcat.util.http.Parameters;
import org.apache.tomcat.util.http.ServerCookies;
import org.apache.tomcat.util.http.parser.MediaType;
import org.apache.tomcat.util.res.StringManager;

/**
 * This is a low-level, efficient representation of a server request. Most
 * fields are GC-free, expensive operations are delayed until the user code
 * needs the information.
 *
 * Processing is delegated to modules, using a hook mechanism.
 *
 * This class is not intended for user code - it is used internally by tomcat
 * for processing the request in the most efficient way. Users ( servlets ) can
 * access the information using a facade, which provides the high-level view of
 * the request.
 *
 * Tomcat defines a number of attributes:
 * <ul>
 * <li>"org.apache.tomcat.request" - allows access to the low-level request
 * object in trusted applications
 * </ul>
 *
 * @author James Duncan Davidson [duncan@eng.sun.com]
 * @author James Todd [gonzo@eng.sun.com]
 * @author Jason Hunter [jch@eng.sun.com]
 * @author Harish Prabandham
 * @author Alex Cruikshank [alex@epitonic.com]
 * @author Hans Bergsten [hans@gefionsoftware.com]
 * @author Costin Manolache
 * @author Remy Maucherat
 */
public final class ExchangeData {

	private static final StringManager sm = StringManager.getManager(Response.class);

	private static final Log log = LogFactory.getLog(Response.class);

	public static final int BODY_TYPE_NOBODY = 1;

	public static final int BODY_TYPE_FIXEDLENGTH = 2;

	public static final int BODY_TYPE_CHUNKED = 3;

	/**
	 * for http2 nolimit until stream closed
	 */
	public static final int BODY_TYPE_NOLIMIT = 4;

	// Expected maximum typical number of cookies per request.
	private static final int INITIAL_COOKIE_SIZE = 4;

	/**
	 * Default locale as mandated by the spec.
	 */
	private static final Locale DEFAULT_LOCALE = Locale.getDefault();

	// ----------------------------------------------------- Instance Variables

	private int serverPort = -1;
	private final MessageBytes serverName = MessageBytes.newInstance();

	private int remotePort;
	private int localPort;

	private final MessageBytes scheme = MessageBytes.newInstance();

	private final MessageBytes method = MessageBytes.newInstance();
	private final MessageBytes requestUri = MessageBytes.newInstance();
	private final MessageBytes decodedUri = MessageBytes.newInstance();
	private final MessageBytes queryString = MessageBytes.newInstance();
	private final MessageBytes protocol = MessageBytes.newInstance();

	// remote address/host
	private final MessageBytes remoteAddr = MessageBytes.newInstance();
	private final MessageBytes localName = MessageBytes.newInstance();
	private final MessageBytes remoteHost = MessageBytes.newInstance();
	private final MessageBytes localAddr = MessageBytes.newInstance();

	private final MimeHeaders requestHeaders = new MimeHeaders();
	private final Map<String, String> trailerFields = new HashMap<>();

	/**
	 * Path parameters
	 */
	private final Map<String, String> pathParameters = new HashMap<>();

	/**
	 * Associated input buffer.
	 */
	// private InputBuffer inputBuffer = null;

	/**
	 * URL decoder.
	 */
	private final UDecoder urlDecoder = new UDecoder();

	/**
	 * HTTP specific fields. (remove them ?)
	 */
	private int requestBodyType = -1;
	private long requestContentLength = -1;
	private MessageBytes requestContentType = null;
	private Charset requestCharset = null;
	// Retain the original, user specified character encoding so it can be
	// returned even if it is invalid
	private String requestCharacterEncoding = null;

	/**
	 * Is there an expectation ?
	 */
	private boolean expectation = false;

	private final ServerCookies serverCookies = new ServerCookies(INITIAL_COOKIE_SIZE);
	private final Parameters parameters = new Parameters();

	private final MessageBytes remoteUser = MessageBytes.newInstance();
	private boolean remoteUserNeedsAuthorization = false;
	private final MessageBytes authType = MessageBytes.newInstance();
	private final HashMap<String, Object> attributes = new HashMap<>();

//	private ResponseData responseData;
	// private volatile ActionHook hook;

	private long bytesRead = 0;
	// Time of the request - useful to avoid repeated calls to System.currentTime
	private long startTime = -1;
	// private int available = 0;

	private final RequestInfo reqProcessorMX = new RequestInfo(this);

	private boolean sendfile = true;

	// volatile ReadListener listener;

	private final AtomicBoolean allDataReadEventSent = new AtomicBoolean(false);

	// ----------------------------------------------------- Class Variables

	/**
	 * Status code.
	 */
	private int status = 200;

	/**
	 * Status message.
	 */
	private String message = null;

	/**
	 * Response headers.
	 */
	private final MimeHeaders responseHeaders = new MimeHeaders();

	private Supplier<Map<String, String>> trailerFieldsSupplier = null;

	/**
	 * Associated output buffer.
	 */
	// private OutputBuffer outputBuffer;

	/**
	 * Committed flag.
	 */
	private volatile boolean committed = false;
	private long commitTime = -1;

	/**
	 * Action hook.
	 */
	// private volatile ActionHook hook;

	/**
	 * HTTP specific fields.
	 */
	private int responseBodyType = -1;
	private long responseContentLength = -1;
	private String responseContentType = null;
	private String contentLanguage = null;
	private Charset responseCharset = null;
	// Retain the original name used to set the charset so exactly that name is
	// used in the ContentType header. Some (arguably non-specification
	// compliant) user agents are very particular
	private String responseCharacterEncoding = null;
	private Locale locale = DEFAULT_LOCALE;

	// General informations
	private long bytesWrite = 0;

	/**
	 * Holds request error exception.
	 */
	private Exception errorException = null;

	/**
	 * With the introduction of async processing and the possibility of
	 * non-container threads calling sendError() tracking the current error state
	 * and ensuring that the correct error page is called becomes more complicated.
	 * This state attribute helps by tracking the current error state and informing
	 * callers that attempt to change state if the change was successful or if
	 * another thread got there first.
	 *
	 * <pre>
	 * The state machine is very simple:
	 *
	 * 0 - NONE
	 * 1 - NOT_REPORTED
	 * 2 - REPORTED
	 *
	 *
	 *   -->---->-- >NONE
	 *   |   |        |
	 *   |   |        | setError()
	 *   ^   ^        |
	 *   |   |       \|/
	 *   |   |-<-NOT_REPORTED
	 *   |            |
	 *   ^            | report()
	 *   |            |
	 *   |           \|/
	 *   |----<----REPORTED
	 * </pre>
	 */
	private final AtomicInteger errorState = new AtomicInteger(0);

	// private RequestData req;

	// ----------------------------------------------------- Instance Variables

	// ----------------------------------------------------------- Constructors

	public ExchangeData() {
		this.parameters.setQuery(queryString);
		this.parameters.setURLDecoder(urlDecoder);
	}

	public boolean sendAllDataReadEvent() {
		return allDataReadEventSent.compareAndSet(false, true);
	}

	// ------------------------------------------------------------- Properties

	public MimeHeaders getRequestHeaders() {
		return requestHeaders;
	}

	public Map<String, String> getTrailerFields() {
		return trailerFields;
	}

	public UDecoder getURLDecoder() {
		return urlDecoder;
	}

	// -------------------- Request data --------------------

	public MessageBytes getScheme() {
		return scheme;
	}

	public MessageBytes getMethod() {
		return method;
	}

	public MessageBytes getRequestURI() {
		return requestUri;
	}

	public MessageBytes getDecodedURI() {
		return decodedUri;
	}

	public MessageBytes getQueryString() {
		return queryString;
	}

	public MessageBytes getProtocol() {
		return protocol;
	}

	/**
	 * Get the "virtual host", derived from the Host: header associated with this
	 * request.
	 *
	 * @return The buffer holding the server name, if any. Use isNull() to check if
	 *         there is no value set.
	 */
	public MessageBytes getServerName() {
		return serverName;
	}

	public int getServerPort() {
		return serverPort;
	}

	public void setServerPort(int serverPort) {
		this.serverPort = serverPort;
	}

	public MessageBytes getRemoteAddr() {
		return remoteAddr;
	}

	public MessageBytes getRemoteHost() {
		return remoteHost;
	}

	public MessageBytes getLocalName() {
		return localName;
	}

	public MessageBytes getLocalAddr() {
		return localAddr;
	}

	public int getRemotePort() {
		return remotePort;
	}

	public void setRemotePort(int port) {
		this.remotePort = port;
	}

	public int getLocalPort() {
		return localPort;
	}

	public void setLocalPort(int port) {
		this.localPort = port;
	}

	// -------------------- encoding/type --------------------

	/**
	 * Get the character encoding used for this request.
	 *
	 * @return The value set via {@link #setCharset(Charset)} or if no call has been
	 *         made to that method try to obtain if from the content type.
	 */
	public String getRequestCharacterEncoding() {
		if (requestCharacterEncoding == null) {
			requestCharacterEncoding = getCharsetFromContentType(getRequestContentTypeString());
		}

		return requestCharacterEncoding;
	}

	/**
	 * Get the character encoding used for this request.
	 *
	 * @return The value set via {@link #setCharset(Charset)} or if no call has been
	 *         made to that method try to obtain if from the content type.
	 *
	 * @throws UnsupportedEncodingException If the user agent has specified an
	 *                                      invalid character encoding
	 */
	public Charset getRequestCharset() throws UnsupportedEncodingException {
		if (requestCharset == null) {
			getRequestCharacterEncoding();
			if (requestCharacterEncoding != null) {
				requestCharset = B2CConverter.getCharset(requestCharacterEncoding);
			}
		}

		return requestCharset;
	}

	public void setRequestCharset(Charset charset) {
		this.requestCharset = charset;
		this.requestCharacterEncoding = charset.name();
	}

	public int getRequestBodyType() {
		return requestBodyType;
	}

	public void setRequestBodyType(int requestBodyType) {
		this.requestBodyType = requestBodyType;
	}

	public void setRequestContentLength(long len) {
		this.requestContentLength = len;
	}

	public int getContentLength() {
		long length = getRequestContentLengthLong();

		if (length < Integer.MAX_VALUE) {
			return (int) length;
		}
		return -1;
	}

	public long getRequestContentLengthLong() {
		if (requestContentLength > -1) {
			return requestContentLength;
		}

		MessageBytes clB = requestHeaders.getUniqueValue("content-length");
		requestContentLength = (clB == null || clB.isNull()) ? -1 : clB.getLong();

		return requestContentLength;
	}

	public String getRequestContentTypeString() {
		getRequestContentType();
		if ((requestContentType == null) || requestContentType.isNull()) {
			return null;
		}
		return requestContentType.toString();
	}

	public void setRequestContentType(String type) {
		requestContentType.setString(type);
	}

	public MessageBytes getRequestContentType() {
		if (requestContentType == null) {
			requestContentType = requestHeaders.getValue("content-type");
		}
		return requestContentType;
	}

	public void setRequestContentType(MessageBytes mb) {
		requestContentType = mb;
	}

	public String getRequestHeader(String name) {
		return requestHeaders.getHeader(name);
	}

	public void setExpectation(boolean expectation) {
		this.expectation = expectation;
	}

	public boolean hasExpectation() {
		return expectation;
	}

	// -------------------- Associated response --------------------

//	public ResponseData getResponseData() {
//		return responseData;
//	}

//	public void setResponseData(ResponseData responseData) {
//		this.responseData = responseData;
//		this.responseData.setRequestData(this);
//	}

	// protected void setHook(ActionHook hook) {
	// this.hook = hook;
	// }

	// public void action(ActionCode actionCode, Object param) {
	// if (hook != null) {
	// if (param == null) {
	// hook.action(actionCode, this);
	// } else {
	// hook.action(actionCode, param);
	// }
	// }
	// }

	// public void actionASYNC_DISPATCHED(Object param) {
	// hook.actionASYNC_DISPATCHED(param);
	// }

	// public void actionASYNC_RUN(Runnable param) {
	// hook.actionASYNC_RUN(param);
	// }

	// public void actionASYNC_DISPATCH(Object param) {
	// hook.actionASYNC_DISPATCH(param);
	// }

	// public void actionASYNC_START(Object param) {
	// hook.actionASYNC_START(param);
	// }

	// -------------------- Cookies --------------------

	public ServerCookies getCookies() {
		return serverCookies;
	}

	// -------------------- Parameters --------------------

	public Parameters getParameters() {
		return parameters;
	}

	public void addPathParameter(String name, String value) {
		pathParameters.put(name, value);
	}

	public String getPathParameter(String name) {
		return pathParameters.get(name);
	}

	// -------------------- Other attributes --------------------
	// We can use notes for most - need to discuss what is of general interest

	public void setAttribute(String name, Object o) {
		attributes.put(name, o);
	}

	public HashMap<String, Object> getAttributes() {
		return attributes;
	}

	public Object getAttribute(String name) {
		return attributes.get(name);
	}

	public MessageBytes getRemoteUser() {
		return remoteUser;
	}

	public boolean getRemoteUserNeedsAuthorization() {
		return remoteUserNeedsAuthorization;
	}

	public void setRemoteUserNeedsAuthorization(boolean remoteUserNeedsAuthorization) {
		this.remoteUserNeedsAuthorization = remoteUserNeedsAuthorization;
	}

	public MessageBytes getAuthType() {
		return authType;
	}

	// public int getAvailable() {
	// return available;
	// }

	// public void setAvailable(int available) {
	// this.available = available;
	// }

	public boolean getSendfile() {
		return sendfile;
	}

	public void setSendfile(boolean sendfile) {
		this.sendfile = sendfile;
	}

	// -------------------- debug --------------------

	@Override
	public String toString() {
		return "R( " + getRequestURI().toString() + ")";
	}

	public long getStartTime() {
		return startTime;
	}

	public void setStartTime(long startTime) {
		this.startTime = startTime;
	}

	public MimeHeaders getResponseHeaders() {
		return responseHeaders;
	}

	// protected void setHook(ActionHook hook) {
	// this.hook = hook;
	// }

	// -------------------- Actions --------------------

	// -------------------- State --------------------

	public int getStatus() {
		return status;
	}

	/**
	 * Set the response status.
	 *
	 * @param status The status value to set
	 */
	public void setStatus(int status) {
		this.status = status;
	}

	/**
	 * Get the status message.
	 *
	 * @return The message associated with the current status
	 */
	public String getMessage() {
		return message;
	}

	/**
	 * Set the status message.
	 *
	 * @param message The status message to set
	 */
	public void setMessage(String message) {
		this.message = message;
	}

	public boolean isCommitted() {
		return committed;
	}

	public void setCommitted(boolean v) {
		if (v && !this.committed) {
			this.commitTime = System.currentTimeMillis();
		}
		this.committed = v;
	}

	/**
	 * Return the time the response was committed (based on
	 * System.currentTimeMillis).
	 *
	 * @return the time the response was committed
	 */
	public long getCommitTime() {
		return commitTime;
	}

	// -----------------Error State --------------------

	/**
	 * Set the error Exception that occurred during request processing.
	 *
	 * @param ex The exception that occurred
	 */
	public void setErrorException(Exception ex) {
		errorException = ex;
	}

	/**
	 * Get the Exception that occurred during request processing.
	 *
	 * @return The exception that occurred
	 */
	public Exception getErrorException() {
		return errorException;
	}

	public boolean isExceptionPresent() {
		return (errorException != null);
	}

	/**
	 * Set the error flag.
	 *
	 * @return <code>false</code> if the error flag was already set
	 */
	public boolean setError() {
		return errorState.compareAndSet(0, 1);
	}

	/**
	 * Error flag accessor.
	 *
	 * @return <code>true</code> if the response has encountered an error
	 */
	public boolean isError() {
		return errorState.get() > 0;
	}

	public boolean isErrorReportRequired() {
		return errorState.get() == 1;
	}

	public boolean setErrorReported() {
		return errorState.compareAndSet(1, 2);
	}

	// -------------------- Methods --------------------

	public void reset() throws IllegalStateException {

		if (committed) {
			throw new IllegalStateException();
		}

		recycleResponse();
	}

	// -------------------- Headers --------------------
	/**
	 * Does the response contain the given header. <br>
	 * Warning: This method always returns <code>false</code> for Content-Type and
	 * Content-Length.
	 *
	 * @param name The name of the header of interest
	 *
	 * @return {@code true} if the response contains the header.
	 */
	public boolean containsResponseHeader(String name) {
		return responseHeaders.getHeader(name) != null;
	}

	public void setResponseHeader(String name, String value) {
		char cc = name.charAt(0);
		if (cc == 'C' || cc == 'c') {
			if (checkSpecialHeader(name, value))
				return;
		}
		responseHeaders.setValue(name).setString(value);
	}

	public void addResponseHeader(String name, String value) {
		addResponseHeader(name, value, null);
	}

	public void addResponseHeader(String name, String value, Charset charset) {
		char cc = name.charAt(0);
		if (cc == 'C' || cc == 'c') {
			if (checkSpecialHeader(name, value))
				return;
		}
		MessageBytes mb = responseHeaders.addValue(name);
		if (charset != null) {
			mb.setCharset(charset);
		}
		mb.setString(value);
	}

	public void setTrailerFieldsSupplier(Supplier<Map<String, String>> supplier) {
		this.trailerFieldsSupplier = supplier;
	}

	public Supplier<Map<String, String>> getTrailerFieldsSupplier() {
		return trailerFieldsSupplier;
	}

	/**
	 * Set internal fields for special header names. Called from set/addHeader.
	 * Return true if the header is special, no need to set the header.
	 */
	private boolean checkSpecialHeader(String name, String value) {
		// XXX Eliminate redundant fields !!!
		// ( both header and in special fields )
		if (name.equalsIgnoreCase("Content-Type")) {
			setResponseContentType(value);
			return true;
		}
		if (name.equalsIgnoreCase("Content-Length")) {
			try {
				long cL = Long.parseLong(value);
				setResponseContentLength(cL);
				return true;
			} catch (NumberFormatException ex) {
				// Do nothing - the spec doesn't have any "throws"
				// and the user might know what he's doing
				return false;
			}
		}
		return false;
	}

	// -------------------- I18N --------------------

	public Locale getLocale() {
		return locale;
	}

	/**
	 * Called explicitly by user to set the Content-Language and the default
	 * encoding.
	 *
	 * @param locale The locale to use for this response
	 */
	public void setLocale(Locale locale) {

		if (locale == null) {
			return; // throw an exception?
		}

		// Save the locale for use by getLocale()
		this.locale = locale;

		// Set the contentLanguage for header output
		contentLanguage = locale.toLanguageTag();
	}

	/**
	 * Return the content language.
	 *
	 * @return The language code for the language currently associated with this
	 *         response
	 */
	public String getContentLanguage() {
		return contentLanguage;
	}

	/**
	 * Overrides the character encoding used in the body of the response. This
	 * method must be called prior to writing output using getWriter().
	 *
	 * @param characterEncoding The name of character encoding.
	 *
	 * @throws UnsupportedEncodingException If the specified name is not recognised
	 */
	public void setResponseCharacterEncoding(String characterEncoding) throws UnsupportedEncodingException {
		if (isCommitted()) {
			return;
		}
		if (characterEncoding == null) {
			return;
		}

		this.responseCharset = B2CConverter.getCharset(characterEncoding);
		this.responseCharacterEncoding = characterEncoding;
	}

	public Charset getResponseCharset() {
		return responseCharset;
	}

	/**
	 * @return The name of the current encoding
	 */
	public String getResponseCharacterEncoding() {
		return responseCharacterEncoding;
	}

	/**
	 * Sets the content type.
	 *
	 * This method must preserve any response charset that may already have been set
	 * via a call to response.setContentType(), response.setLocale(), or
	 * response.setCharacterEncoding().
	 *
	 * @param type the content type
	 */
	public void setResponseContentType(String type) {

		if (type == null) {
			this.responseContentType = null;
			return;
		}

		MediaType m = null;
		try {
			m = MediaType.parseMediaType(new StringReader(type));
		} catch (IOException e) {
			// Ignore - null test below handles this
		}
		if (m == null) {
			// Invalid - Assume no charset and just pass through whatever
			// the user provided.
			this.responseContentType = type;
			return;
		}

		this.responseContentType = m.toStringNoCharset();

		String charsetValue = m.getCharset();

		if (charsetValue != null) {
			charsetValue = charsetValue.trim();
			if (charsetValue.length() > 0) {
				try {
					responseCharset = B2CConverter.getCharset(charsetValue);
				} catch (UnsupportedEncodingException e) {
					log.warn(sm.getString("response.encoding.invalid", charsetValue), e);
				}
			}
		}
	}

	public void setContentTypeNoCharset(String type) {
		this.responseContentType = type;
	}

	public String getResponseContentType() {

		String ret = responseContentType;

		if (ret != null && responseCharset != null) {
			ret = ret + ";charset=" + responseCharacterEncoding;
		}

		return ret;
	}

	public int getResponseBodyType() {
		return responseBodyType;
	}

	public void setResponseBodyType(int responseBodyType) {
		this.responseBodyType = responseBodyType;
	}

	public void setResponseContentLength(long contentLength) {
		this.responseContentLength = contentLength;
	}

	public int getResponseContentLength() {
		long length = getResponseContentLengthLong();

		if (length < Integer.MAX_VALUE) {
			return (int) length;
		}
		return -1;
	}

	public long getResponseContentLengthLong() {
		return responseContentLength;
	}

	/**
	 * Bytes written by application - i.e. before compression, chunking, etc.
	 *
	 * @return The total number of bytes written to the response by the application.
	 *         This will not be the number of bytes written to the network which may
	 *         be more or less than this value.
	 */
//	public long getContentWritten() {
//		return bytesWrite;
//	}

//	public void setContentWritten(long contentWritten) {
//		this.bytesWrite = contentWritten;
//	}

	public long getBytesWrite() {
		return bytesWrite;
	}

	public void setBytesWrite(long bytesWrite) {
		this.bytesWrite = bytesWrite;
	}

	// -------------------- Recycling --------------------

	public void recycle() {
		recycleRequest();
		recycleResponse();
	}

	private void recycleRequest() {
		bytesRead = 0;
		requestBodyType = -1;
		requestContentLength = -1;
		requestContentType = null;
		requestCharset = null;
		requestCharacterEncoding = null;
		expectation = false;
		requestHeaders.recycle();
		trailerFields.clear();
		serverName.recycle();
		serverPort = -1;
		localAddr.recycle();
		localName.recycle();
		localPort = -1;
		remoteAddr.recycle();
		remoteHost.recycle();
		remotePort = -1;
		// available = 0;
		sendfile = true;

		serverCookies.recycle();
		parameters.recycle();
		pathParameters.clear();

		requestUri.recycle();
		decodedUri.recycle();
		queryString.recycle();
		method.recycle();
		protocol.recycle();

		scheme.recycle();

		remoteUser.recycle();
		remoteUserNeedsAuthorization = false;
		authType.recycle();
		attributes.clear();

		// listener = null;
		allDataReadEventSent.set(false);

		startTime = -1;

	}

	private void recycleResponse() {
		responseBodyType = -1;
		responseContentType = null;
		contentLanguage = null;
		locale = DEFAULT_LOCALE;
		responseCharset = null;
		responseCharacterEncoding = null;
		responseContentLength = -1;
		status = 200;
		message = null;
		committed = false;
		commitTime = -1;
		errorException = null;
		errorState.set(0);
		responseHeaders.clear();
		trailerFieldsSupplier = null;
		// Servlet 3.1 non-blocking write listener
		// listener = null;
		// hook.setWriteListener(null);

		// update counters
		bytesWrite = 0;
	}

	// -------------------- Info --------------------
	public void updateCounters() {
		reqProcessorMX.updateCounters();
	}

	public RequestInfo getRequestProcessor() {
		return reqProcessorMX;
	}

	public long getBytesRead() {
		return bytesRead;
	}

	public void setBytesRead(long bytesRead) {
		this.bytesRead = bytesRead;
	}

	public boolean isProcessing() {
		return reqProcessorMX.getStage() == org.apache.coyote.Constants.STAGE_SERVICE;
	}

	/**
	 * Parse the character encoding from the specified content type header. If the
	 * content type is null, or there is no explicit character encoding,
	 * <code>null</code> is returned.
	 *
	 * @param contentType a content type header
	 */
	private static String getCharsetFromContentType(String contentType) {

		if (contentType == null) {
			return null;
		}
		int start = contentType.indexOf("charset=");
		if (start < 0) {
			return null;
		}
		String encoding = contentType.substring(start + 8);
		int end = encoding.indexOf(';');
		if (end >= 0) {
			encoding = encoding.substring(0, end);
		}
		encoding = encoding.trim();
		if ((encoding.length() > 2) && (encoding.startsWith("\"")) && (encoding.endsWith("\""))) {
			encoding = encoding.substring(1, encoding.length() - 1);
		}

		return encoding.trim();
	}

}
