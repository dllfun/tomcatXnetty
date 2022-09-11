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

	private final ExchangeData exchangeData;
	/**
	 * Action hook.
	 */
	private final AbstractProcessor processor;

	private final ResponseAction responseAction;

	/**
	 * Notes.
	 */
	private final Object responseNotes[] = new Object[Constants.MAX_NOTES];

	private Request request;

	// private boolean fireListener = false;
	// private boolean registeredForWrite = false;
	// private final Object nonBlockingStateLock = new Object();

	public Response(ExchangeData exchangeData, AbstractProcessor processor, ResponseAction responseAction) {
		this.exchangeData = exchangeData;
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
		return this.exchangeData.getResponseHeaders();
	}

	// -------------------- Per-Response "notes" --------------------

	public final void setNote(int pos, Object value) {
		responseNotes[pos] = value;
	}

	public final Object getNote(int pos) {
		return responseNotes[pos];
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

	public void commit(boolean finished) {
		this.responseAction.commit(finished);
	}

	public void ack() {
		this.responseAction.sendAck();
	}

	public void clientFlush() {
		this.responseAction.clientFlush();
	}

	public void isProcessorError(AtomicBoolean param) {
		processor.isError(param);
	}

	public void isProcessorIoAllowed(AtomicBoolean param) {
		processor.isIoAllowed(param);
	}

	public void close() {
		this.responseAction.finish();
	}

	public void closeNow(Object param) {
		this.responseAction.closeNow(param);
	}

	// -------------------- State --------------------

	public int getStatus() {
		return this.exchangeData.getStatus();
	}

	/**
	 * Set the response status.
	 *
	 * @param status The status value to set
	 */
	public void setStatus(int status) {
		this.exchangeData.setStatus(status);
	}

	/**
	 * Get the status message.
	 *
	 * @return The message associated with the current status
	 */
	public String getMessage() {
		return this.exchangeData.getMessage();
	}

	/**
	 * Set the status message.
	 *
	 * @param message The status message to set
	 */
	public void setMessage(String message) {
		this.exchangeData.setMessage(message);
	}

	public boolean isCommitted() {
		return this.exchangeData.isCommitted();
	}

//	public void setCommitted(boolean v) {
//		this.exchangeData.setCommitted(v);
//	}

	/**
	 * Return the time the response was committed (based on
	 * System.currentTimeMillis).
	 *
	 * @return the time the response was committed
	 */
	public long getCommitTime() {
		return exchangeData.getCommitTime();
	}

	// -----------------Error State --------------------

	/**
	 * Set the error Exception that occurred during request processing.
	 *
	 * @param ex The exception that occurred
	 */
	public void setErrorException(Exception ex) {
		this.exchangeData.setErrorException(ex);
	}

	/**
	 * Get the Exception that occurred during request processing.
	 *
	 * @return The exception that occurred
	 */
	public Exception getErrorException() {
		return this.exchangeData.getErrorException();
	}

	public boolean isExceptionPresent() {
		return this.exchangeData.isExceptionPresent();
	}

	/**
	 * Set the error flag.
	 *
	 * @return <code>false</code> if the error flag was already set
	 */
	public boolean setError() {
		return this.exchangeData.setError();
	}

	/**
	 * Error flag accessor.
	 *
	 * @return <code>true</code> if the response has encountered an error
	 */
	public boolean isError() {
		return this.exchangeData.isError();
	}

	public boolean isErrorReportRequired() {
		return this.exchangeData.isErrorReportRequired();
	}

	public boolean setErrorReported() {
		return this.exchangeData.setErrorReported();
	}

	// -------------------- Methods --------------------

	public void reset() throws IllegalStateException {
		this.exchangeData.reset();
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
		return this.exchangeData.containsResponseHeader(name);
	}

	public void setHeader(String name, String value) {
		this.exchangeData.setResponseHeader(name, value);
	}

	public void addHeader(String name, String value) {
		addHeader(name, value, null);
	}

	public void addHeader(String name, String value, Charset charset) {
		this.exchangeData.addResponseHeader(name, value, charset);
	}

	public void setTrailerFields(Supplier<Map<String, String>> supplier) {
		AtomicBoolean trailerFieldsSupported = new AtomicBoolean(false);
		// processor.actionIS_TRAILER_FIELDS_SUPPORTED(trailerFieldsSupported);
		trailerFieldsSupported.set(this.responseAction.isTrailerFieldsSupported());
		if (!trailerFieldsSupported.get()) {
			throw new IllegalStateException(sm.getString("response.noTrailers.notSupported"));
		}

		this.exchangeData.setTrailerFieldsSupplier(supplier);
	}

	public Supplier<Map<String, String>> getTrailerFieldsSupplier() {
		return this.exchangeData.getTrailerFieldsSupplier();
	}

	/**
	 * Signal that we're done with the headers, and body will follow. Any
	 * implementation needs to notify ContextManager, to allow interceptors to fix
	 * headers.
	 */
//	public void sendHeaders(boolean finished) {
//		actionCOMMIT(finished);
//		setCommitted(true);
//	}

	// -------------------- I18N --------------------

	public Locale getLocale() {
		return this.exchangeData.getLocale();
	}

	/**
	 * Called explicitly by user to set the Content-Language and the default
	 * encoding.
	 *
	 * @param locale The locale to use for this response
	 */
	public void setLocale(Locale locale) {
		this.exchangeData.setLocale(locale);
	}

	/**
	 * Return the content language.
	 *
	 * @return The language code for the language currently associated with this
	 *         response
	 */
	public String getContentLanguage() {
		return this.exchangeData.getContentLanguage();
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
		this.exchangeData.setResponseCharacterEncoding(characterEncoding);
	}

	public Charset getCharset() {
		return this.exchangeData.getResponseCharset();
	}

	/**
	 * @return The name of the current encoding
	 */
	public String getCharacterEncoding() {
		return this.exchangeData.getResponseCharacterEncoding();
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
		this.exchangeData.setResponseContentType(type);
	}

	public void setContentTypeNoCharset(String type) {
		this.exchangeData.setContentTypeNoCharset(type);
	}

	public String getContentType() {
		return this.exchangeData.getResponseContentType();
	}

	public void setContentLength(long contentLength) {
		this.exchangeData.setResponseContentLength(contentLength);
	}

	public int getContentLength() {
		return this.exchangeData.getResponseContentLength();
	}

	public long getContentLengthLong() {
		return this.exchangeData.getResponseContentLengthLong();
	}

	/**
	 * Write a chunk of bytes.
	 *
	 * @param chunk The ByteBuffer to write
	 *
	 * @throws IOException If an I/O error occurs during the write
	 */
	public void doWrite(ByteBuffer chunk) throws IOException {
		if (!exchangeData.isCommitted()) {
			// Send the connector a request for commit. The connector should
			// then validate the headers, send them (using sendHeaders) and
			// set the filters accordingly.
			this.responseAction.commit(false);
		}
		int len = chunk.remaining();
		this.responseAction.doWrite(chunk);
		long bytesWrite = this.exchangeData.getBytesWrite();
		bytesWrite += len - chunk.remaining();
		this.exchangeData.setBytesWrite(bytesWrite);
	}

	// --------------------

	public void recycle() {

		this.exchangeData.recycle();
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
	public long getBytesWrite() {
		return this.exchangeData.getBytesWrite();
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
			clientFlush();
		}
		return this.responseAction.getBytesWritten();
	}

	public WriteListener getWriteListener() {
		return processor.getAsyncStateMachine().getWriteListener();
	}

	public void setWriteListener(WriteListener listener) {
		processor.getAsyncStateMachine().setWriteListener(listener);

		// The container is responsible for the first call to
		// listener.onWritePossible(). If isReady() returns true, the container
		// needs to call listener.onWritePossible() from a new thread. If
		// isReady() returns false, the socket will be registered for write and
		// the container will call listener.onWritePossible() once data can be
		// written.
		if (isReady()) {
			synchronized (processor.getAsyncStateMachine().getNonBlockingStateLock()) {
				// Ensure we don't get multiple write registrations if
				// ServletOutputStream.isReady() returns false during a call to
				// onDataAvailable()
				processor.getAsyncStateMachine().setRegisteredForWrite(true);
				// Need to set the fireListener flag otherwise when the
				// container tries to trigger onWritePossible, nothing will
				// happen
				processor.getAsyncStateMachine().setFireListener(true);
			}
			processor.dispatchWrite();
			if (!ContainerThreadMarker.isContainerThread()) {
				// Not on a container thread so need to execute the dispatch
				processor.dispatchExecute();
			}
		}
	}

	public boolean isReady() {
		if (processor.getAsyncStateMachine().getWriteListener() == null) {
			if (log.isDebugEnabled()) {
				log.debug(sm.getString("response.notNonBlocking"));
			}
			return false;
		}
		// Assume write is not possible
		boolean ready = false;
		synchronized (processor.getAsyncStateMachine().getNonBlockingStateLock()) {
			if (processor.getAsyncStateMachine().isRegisteredForWrite()) {
				processor.getAsyncStateMachine().setFireListener(true);
				return false;
			}
			ready = checkRegisterForWrite();
			processor.getAsyncStateMachine().setFireListener(!ready);
		}
		return ready;
	}

	public boolean checkRegisterForWrite() {
		AtomicBoolean ready = new AtomicBoolean(false);
		synchronized (processor.getAsyncStateMachine().getNonBlockingStateLock()) {
			if (!processor.getAsyncStateMachine().isRegisteredForWrite()) {
				// processor.actionNB_WRITE_INTEREST(ready);
				ready.set(this.responseAction.isReadyForWrite());
				processor.getAsyncStateMachine().setRegisteredForWrite(!ready.get());
			}
		}
		return ready.get();
	}

	public void onWritePossible() throws IOException {
		// Any buffered data left over from a previous non-blocking write is
		// written in the Processor so if this point is reached the app is able
		// to write data.
		boolean fire = false;
		synchronized (processor.getAsyncStateMachine().getNonBlockingStateLock()) {
			processor.getAsyncStateMachine().setRegisteredForWrite(false);
			if (processor.getAsyncStateMachine().isFireListener()) {
				processor.getAsyncStateMachine().setFireListener(false);
				fire = true;
			}
		}
		if (fire) {
			getWriteListener().onWritePossible();
		}
	}
}
