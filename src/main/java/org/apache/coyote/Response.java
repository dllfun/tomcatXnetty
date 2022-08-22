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
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import javax.servlet.WriteListener;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.http.MimeHeaders;
import org.apache.tomcat.util.res.StringManager;

/**
 * Response object.
 *
 * @author James Duncan Davidson [duncan@eng.sun.com]
 * @author Jason Hunter [jch@eng.sun.com]
 * @author James Todd [gonzo@eng.sun.com]
 * @author Harish Prabandham
 * @author Hans Bergsten [hans@gefionsoftware.com]
 * @author Remy Maucherat
 */
public final class Response {

	private static final StringManager sm = StringManager.getManager(Response.class);

	private static final Log log = LogFactory.getLog(Response.class);

	// ----------------------------------------------------- Instance Variables

	private ResponseData responseData;
	/**
	 * Action hook.
	 */
	private volatile AbstractProcessor processor;

	private ResponseAction responseAction;

	private Request request;

	// private boolean fireListener = false;
	// private boolean registeredForWrite = false;
	// private final Object nonBlockingStateLock = new Object();

	public Response(ResponseData responseData, AbstractProcessor processor, ResponseAction responseAction) {
		this.responseData = responseData;
		this.processor = processor;
		this.responseAction = responseAction;
	}

	// ------------------------------------------------------------- Properties

	public Request getRequest() {
		return request;
	}

	public void setRequest(Request request) {
		this.request = request;
	}

	// public void setOutputBuffer(OutputBuffer outputBuffer) {
	// this.outputBuffer = outputBuffer;
	// }

	public MimeHeaders getMimeHeaders() {
		return this.responseData.getMimeHeaders();
	}

	// -------------------- Per-Response "notes" --------------------

	public final void setNote(int pos, Object value) {
		this.responseData.setNote(pos, value);
	}

	public final Object getNote(int pos) {
		return this.responseData.getNote(pos);
	}

	// -------------------- Actions --------------------

	// public void action(ActionCode actionCode, Object param) {
	// if (hook != null) {
	// if (param == null) {
	// hook.action(actionCode, this);
	// } else {
	// hook.action(actionCode, param);
	// }
	// }
	// }

	public void actionCOMMIT() {
		responseAction.commit();
	}

	public void actionACK() {
		responseAction.sendAck();
	}

	public void actionCLIENT_FLUSH() {
		responseAction.clientFlush();
	}

	public void actionIS_ERROR(AtomicBoolean param) {
		processor.isError(param);
	}

	public void actionIS_IO_ALLOWED(AtomicBoolean param) {
		processor.isIoAllowed(param);
	}

	public void actionCLOSE() {
		responseAction.close();
	}

	public void actionCLOSE_NOW(Object param) {
		responseAction.closeNow(param);
	}

	// -------------------- State --------------------

	public int getStatus() {
		return this.responseData.getStatus();
	}

	/**
	 * Set the response status.
	 *
	 * @param status The status value to set
	 */
	public void setStatus(int status) {
		this.responseData.setStatus(status);
	}

	/**
	 * Get the status message.
	 *
	 * @return The message associated with the current status
	 */
	public String getMessage() {
		return this.responseData.getMessage();
	}

	/**
	 * Set the status message.
	 *
	 * @param message The status message to set
	 */
	public void setMessage(String message) {
		this.responseData.setMessage(message);
	}

	public boolean isCommitted() {
		return this.responseData.isCommitted();
	}

	public void setCommitted(boolean v) {
		this.responseData.setCommitted(v);
	}

	/**
	 * Return the time the response was committed (based on
	 * System.currentTimeMillis).
	 *
	 * @return the time the response was committed
	 */
	public long getCommitTime() {
		return responseData.getCommitTime();
	}

	// -----------------Error State --------------------

	/**
	 * Set the error Exception that occurred during request processing.
	 *
	 * @param ex The exception that occurred
	 */
	public void setErrorException(Exception ex) {
		this.responseData.setErrorException(ex);
	}

	/**
	 * Get the Exception that occurred during request processing.
	 *
	 * @return The exception that occurred
	 */
	public Exception getErrorException() {
		return this.responseData.getErrorException();
	}

	public boolean isExceptionPresent() {
		return this.responseData.isExceptionPresent();
	}

	/**
	 * Set the error flag.
	 *
	 * @return <code>false</code> if the error flag was already set
	 */
	public boolean setError() {
		return this.responseData.setError();
	}

	/**
	 * Error flag accessor.
	 *
	 * @return <code>true</code> if the response has encountered an error
	 */
	public boolean isError() {
		return this.responseData.isError();
	}

	public boolean isErrorReportRequired() {
		return this.responseData.isErrorReportRequired();
	}

	public boolean setErrorReported() {
		return this.responseData.setErrorReported();
	}

	// -------------------- Methods --------------------

	public void reset() throws IllegalStateException {
		this.responseData.reset();
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
	public boolean containsHeader(String name) {
		return this.responseData.containsHeader(name);
	}

	public void setHeader(String name, String value) {
		this.responseData.setHeader(name, value);
	}

	public void addHeader(String name, String value) {
		addHeader(name, value, null);
	}

	public void addHeader(String name, String value, Charset charset) {
		this.responseData.addHeader(name, value, charset);
	}

	public void setTrailerFields(Supplier<Map<String, String>> supplier) {
		AtomicBoolean trailerFieldsSupported = new AtomicBoolean(false);
		// processor.actionIS_TRAILER_FIELDS_SUPPORTED(trailerFieldsSupported);
		trailerFieldsSupported.set(responseAction.isTrailerFieldsSupported());
		if (!trailerFieldsSupported.get()) {
			throw new IllegalStateException(sm.getString("response.noTrailers.notSupported"));
		}

		this.responseData.setTrailerFields(supplier);
	}

	public Supplier<Map<String, String>> getTrailerFields() {
		return this.responseData.getTrailerFields();
	}

	/**
	 * Signal that we're done with the headers, and body will follow. Any
	 * implementation needs to notify ContextManager, to allow interceptors to fix
	 * headers.
	 */
	public void sendHeaders() {
		actionCOMMIT();
		setCommitted(true);
	}

	// -------------------- I18N --------------------

	public Locale getLocale() {
		return this.responseData.getLocale();
	}

	/**
	 * Called explicitly by user to set the Content-Language and the default
	 * encoding.
	 *
	 * @param locale The locale to use for this response
	 */
	public void setLocale(Locale locale) {
		this.responseData.setLocale(locale);
	}

	/**
	 * Return the content language.
	 *
	 * @return The language code for the language currently associated with this
	 *         response
	 */
	public String getContentLanguage() {
		return this.responseData.getContentLanguage();
	}

	/**
	 * Overrides the character encoding used in the body of the response. This
	 * method must be called prior to writing output using getWriter().
	 *
	 * @param characterEncoding The name of character encoding.
	 *
	 * @throws UnsupportedEncodingException If the specified name is not recognised
	 */
	public void setCharacterEncoding(String characterEncoding) throws UnsupportedEncodingException {
		this.responseData.setCharacterEncoding(characterEncoding);
	}

	public Charset getCharset() {
		return this.responseData.getCharset();
	}

	/**
	 * @return The name of the current encoding
	 */
	public String getCharacterEncoding() {
		return this.responseData.getCharacterEncoding();
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
	public void setContentType(String type) {
		this.responseData.setContentType(type);
	}

	public void setContentTypeNoCharset(String type) {
		this.responseData.setContentTypeNoCharset(type);
	}

	public String getContentType() {
		return this.responseData.getContentType();
	}

	public void setContentLength(long contentLength) {
		this.responseData.setContentLength(contentLength);
	}

	public int getContentLength() {
		return this.responseData.getContentLength();
	}

	public long getContentLengthLong() {
		return this.responseData.getContentLengthLong();
	}

	/**
	 * Write a chunk of bytes.
	 *
	 * @param chunk The ByteBuffer to write
	 *
	 * @throws IOException If an I/O error occurs during the write
	 */
	public void doWrite(ByteBuffer chunk) throws IOException {
		if (!responseData.isCommitted()) {
			// Send the connector a request for commit. The connector should
			// then validate the headers, send them (using sendHeaders) and
			// set the filters accordingly.
			responseAction.commit();
		}
		int len = chunk.remaining();
		responseAction.doWrite(chunk);
		long contentWritten = this.responseData.getContentWritten();
		contentWritten += len - chunk.remaining();
		this.responseData.setContentWritten(contentWritten);
	}

	// --------------------

	public void recycle() {

		this.responseData.recycle();
		// Servlet 3.1 non-blocking write listener
		// listener = null;
		// processor.setWriteListener(null);
		// fireListener = false;
		// registeredForWrite = false;

	}

	/**
	 * Bytes written by application - i.e. before compression, chunking, etc.
	 *
	 * @return The total number of bytes written to the response by the application.
	 *         This will not be the number of bytes written to the network which may
	 *         be more or less than this value.
	 */
	public long getContentWritten() {
		return this.responseData.getContentWritten();
	}

	/**
	 * Bytes written to socket - i.e. after compression, chunking, etc.
	 *
	 * @param flush Should any remaining bytes be flushed before returning the
	 *              total? If {@code false} bytes remaining in the buffer will not
	 *              be included in the returned value
	 *
	 * @return The total number of bytes written to the socket for this response
	 */
	public long getBytesWritten(boolean flush) {
		if (flush) {
			actionCLIENT_FLUSH();
		}
		return responseAction.getBytesWritten();
	}

	public WriteListener getWriteListener() {
		return responseData.getRequestData().getAsyncStateMachine().getWriteListener();
	}

	public void setWriteListener(WriteListener listener) {
		responseData.getRequestData().getAsyncStateMachine().setWriteListener(listener);

		// The container is responsible for the first call to
		// listener.onWritePossible(). If isReady() returns true, the container
		// needs to call listener.onWritePossible() from a new thread. If
		// isReady() returns false, the socket will be registered for write and
		// the container will call listener.onWritePossible() once data can be
		// written.
		if (isReady()) {
			synchronized (responseData.getNonBlockingStateLock()) {
				// Ensure we don't get multiple write registrations if
				// ServletOutputStream.isReady() returns false during a call to
				// onDataAvailable()
				responseData.setRegisteredForWrite(true);
				// Need to set the fireListener flag otherwise when the
				// container tries to trigger onWritePossible, nothing will
				// happen
				responseData.setFireListener(true);
			}
			processor.actionDISPATCH_WRITE();
			if (!ContainerThreadMarker.isContainerThread()) {
				// Not on a container thread so need to execute the dispatch
				processor.actionDISPATCH_EXECUTE();
			}
		}
	}

	public boolean isReady() {
		if (responseData.getRequestData().getAsyncStateMachine().getWriteListener() == null) {
			if (log.isDebugEnabled()) {
				log.debug(sm.getString("response.notNonBlocking"));
			}
			return false;
		}
		// Assume write is not possible
		boolean ready = false;
		synchronized (responseData.getNonBlockingStateLock()) {
			if (responseData.isRegisteredForWrite()) {
				responseData.setFireListener(true);
				return false;
			}
			ready = checkRegisterForWrite();
			responseData.setFireListener(!ready);
		}
		return ready;
	}

	public boolean checkRegisterForWrite() {
		AtomicBoolean ready = new AtomicBoolean(false);
		synchronized (responseData.getNonBlockingStateLock()) {
			if (!responseData.isRegisteredForWrite()) {
				// processor.actionNB_WRITE_INTEREST(ready);
				ready.set(responseAction.isReadyForWrite());
				responseData.setRegisteredForWrite(!ready.get());
			}
		}
		return ready.get();
	}

	public void onWritePossible() throws IOException {
		// Any buffered data left over from a previous non-blocking write is
		// written in the Processor so if this point is reached the app is able
		// to write data.
		boolean fire = false;
		synchronized (responseData.getNonBlockingStateLock()) {
			responseData.setRegisteredForWrite(false);
			if (responseData.isFireListener()) {
				responseData.setFireListener(false);
				fire = true;
			}
		}
		if (fire) {
			getWriteListener().onWritePossible();
		}
	}
}
