package org.apache.coyote;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.servlet.ReadListener;
import javax.servlet.RequestDispatcher;

import org.apache.catalina.core.AsyncContextImpl;
import org.apache.tomcat.util.buf.ByteChunk;
import org.apache.tomcat.util.buf.MessageBytes;
import org.apache.tomcat.util.buf.UDecoder;
import org.apache.tomcat.util.http.MimeHeaders;
import org.apache.tomcat.util.http.Parameters;
import org.apache.tomcat.util.http.ServerCookies;
import org.apache.tomcat.util.net.BufWrapper;
import org.apache.tomcat.util.net.SocketEvent;

public final class Request implements InputReader {

	private ExchangeData exchangeData;
	private Response response;
	private volatile AbstractProcessor processor;
	private RequestAction requestAction;
	/**
	 * Notes.
	 */
	private final Object requestNotes[] = new Object[Constants.MAX_NOTES];

	// ----------------------------------------------------------- Constructors

	public Request(ExchangeData exchangeData, AbstractProcessor processor, RequestAction requestAction) {
		this.exchangeData = exchangeData;
		this.processor = processor;
		this.requestAction = requestAction;
	}

	// private final RequestInfo reqProcessorMX = new RequestInfo(this);

	// volatile ReadListener listener;

	public ReadListener getReadListener() {
		return processor.getAsyncStateMachine().getReadListener();
	}

	public void setReadListener(ReadListener listener) {
		processor.getAsyncStateMachine().setReadListener(listener);
	}

	public boolean sendAllDataReadEvent() {
		return this.exchangeData.sendAllDataReadEvent();
	}

	// ------------------------------------------------------------- Properties

	public MimeHeaders getMimeHeaders() {
		return exchangeData.getRequestHeaders();
	}

	public boolean isTrailerFieldsReady() {
		return requestAction.isTrailerFieldsReady();
	}

	public Map<String, String> getTrailerFields() {
		return exchangeData.getTrailerFields();
	}

	public UDecoder getURLDecoder() {
		return exchangeData.getURLDecoder();
	}

	// -------------------- Request data --------------------

	public MessageBytes getScheme() {
		return exchangeData.getScheme();
	}

	public MessageBytes getMethod() {
		return exchangeData.getMethod();
	}

	public MessageBytes getRequestURI() {
		return exchangeData.getRequestURI();
	}

	public MessageBytes getDecodedURI() {
		return exchangeData.getDecodedURI();
	}

	public MessageBytes getQueryString() {
		return exchangeData.getQueryString();
	}

	public MessageBytes getProtocol() {
		return exchangeData.getProtocol();
	}

	/**
	 * Get the "virtual host", derived from the Host: header associated with this
	 * request.
	 *
	 * @return The buffer holding the server name, if any. Use isNull() to check if
	 *         there is no value set.
	 */
	public MessageBytes getServerName() {
		return exchangeData.getServerName();
	}

	public int getServerPort() {
		return exchangeData.getServerPort();
	}

	public void setServerPort(int serverPort) {
		this.exchangeData.setServerPort(serverPort);
	}

	public MessageBytes getRemoteAddr() {
		return exchangeData.getRemoteAddr();
	}

	public MessageBytes getRemoteHost() {
		return exchangeData.getRemoteHost();
	}

	public MessageBytes getLocalName() {
		return exchangeData.getLocalName();
	}

	public MessageBytes getLocalAddr() {
		return exchangeData.getLocalAddr();
	}

	public int getRemotePort() {
		return exchangeData.getRemotePort();
	}

	public void setRemotePort(int port) {
		this.exchangeData.setRemotePort(port);
	}

	public int getLocalPort() {
		return exchangeData.getLocalPort();
	}

	public void setLocalPort(int port) {
		this.exchangeData.setLocalPort(port);
	}

	// -------------------- encoding/type --------------------

	/**
	 * Get the character encoding used for this request.
	 *
	 * @return The value set via {@link #setCharset(Charset)} or if no call has been
	 *         made to that method try to obtain if from the content type.
	 */
	public String getCharacterEncoding() {
		return exchangeData.getRequestCharacterEncoding();
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
	public Charset getCharset() throws UnsupportedEncodingException {
		return exchangeData.getRequestCharset();
	}

	public void setCharset(Charset charset) {
		this.exchangeData.setRequestCharset(charset);
	}

	public void setContentLength(long len) {
		this.exchangeData.setRequestContentLength(len);
	}

	public int getContentLength() {
		return this.exchangeData.getContentLength();
	}

	public long getContentLengthLong() {
		return this.exchangeData.getRequestContentLengthLong();
	}

	public String getContentTypeString() {
		return this.exchangeData.getRequestContentTypeString();
	}

	public void setContentType(String type) {
		this.exchangeData.setRequestContentType(type);
	}

	public MessageBytes getContentType() {
		return this.exchangeData.getRequestContentType();
	}

	public void setContentType(MessageBytes mb) {
		this.exchangeData.setRequestContentType(mb);
	}

	public String getHeader(String name) {
		return this.exchangeData.getRequestHeader(name);
	}

	public void setExpectation(boolean expectation) {
		this.exchangeData.setExpectation(expectation);
	}

	public boolean hasExpectation() {
		return this.exchangeData.hasExpectation();
	}

	// -------------------- Associated response --------------------

	public Response getResponse() {
		return response;
	}

	public void setResponse(Response response) {
		this.response = response;
		response.setRequest(this);
	}

	// public void action(ActionCode actionCode, Object param) {
	// if (hook != null) {
	// if (param == null) {
	// hook.action(actionCode, this);
	// } else {
	// hook.action(actionCode, param);
	// }
	// }
	// }

	public int available(Object param) {
		return requestAction.getAvailable(param);
	}

	public void reqSetBodyReplay(ByteChunk param) {
		ByteChunk body = param;
		requestAction.setRequestBody(body);
	}

	public void isIoAllowed(AtomicBoolean param) {
		processor.isIoAllowed(param);
	}

	public void disableSwallowInput() {
		// Aborted upload or similar.
		// No point reading the remainder of the request.
		requestAction.disableSwallowRequest();
		// This is an error state. Make sure it is marked as such.
		processor.setErrorState(ErrorState.CLOSE_CLEAN, null);
	}

	public void actionREQ_HOST_ADDR_ATTRIBUTE() {
		requestAction.actionREQ_HOST_ADDR_ATTRIBUTE();
	}

	public void actionREQ_HOST_ATTRIBUTE() {
		requestAction.actionREQ_HOST_ATTRIBUTE();
	}

	public void actionREQ_REMOTEPORT_ATTRIBUTE() {
		requestAction.actionREQ_REMOTEPORT_ATTRIBUTE();
	}

	public void actionREQ_LOCAL_NAME_ATTRIBUTE() {
		requestAction.actionREQ_LOCAL_NAME_ATTRIBUTE();
	}

	public void actionREQ_LOCAL_ADDR_ATTRIBUTE() {
		requestAction.actionREQ_LOCAL_ADDR_ATTRIBUTE();
	}

	public void actionREQ_LOCALPORT_ATTRIBUTE() {
		requestAction.actionREQ_LOCALPORT_ATTRIBUTE();
	}

	public void actionREQ_SSL_ATTRIBUTE() {
		requestAction.actionREQ_SSL_ATTRIBUTE();
	}

	public void actionREQ_SSL_CERTIFICATE() {
		requestAction.actionREQ_SSL_CERTIFICATE();
	}

	// @Override
	public void asyncPostProcess() {
		processor.getAsyncStateMachine().asyncPostProcess();
	}

	// @Override
	// public void asyncDispatched() {
	// exchangeData.getAsyncStateMachine().asyncDispatched();
	// }

	// @Override
	public void asyncRun(Runnable runnable) {
		processor.getAsyncStateMachine().asyncRun(processor.getProtocol().getExecutor(), runnable);
	}

	// @Override
	public void asyncDispatch(Runnable dispatcher) {
		if (processor.getAsyncStateMachine().asyncDispatch(dispatcher)) {
			// processor.processSocketEvent(SocketEvent.OPEN_READ, true);
			processor.getProtocol().getHandler().processSocket(processor.getChannel(), SocketEvent.OPEN_READ, true);
		}
	}

	// @Override
	public void asyncStart(AsyncContextImpl param) {
		processor.getAsyncStateMachine().asyncStart(this, param);
	}

	public AsyncContextImpl getAsyncContext() {
		return processor.getAsyncStateMachine().getAsyncCtxt();
	}

	// @Override
	public void asyncComplete() {
		processor.clearDispatches();
		if (processor.getAsyncStateMachine().asyncComplete()) {
			// processor.processSocketEvent(SocketEvent.OPEN_READ, true);
			processor.getProtocol().getHandler().processSocket(processor.getChannel(), SocketEvent.OPEN_READ, true);
		}
	}

	// @Override
	public void asyncError() {
		processor.getAsyncStateMachine().asyncError();
	}

	// @Override
	public void asyncIsAsync(AtomicBoolean param) {
		param.set(processor.getAsyncStateMachine().isAsync());
	}

	// @Override
	public void asyncIsCompleting(AtomicBoolean param) {
		param.set(processor.getAsyncStateMachine().isCompleting());
	}

	// @Override
	public void asyncIsDispatching(AtomicBoolean param) {
		param.set(processor.getAsyncStateMachine().isAsyncDispatching());
	}

	// @Override
	public void asyncIsError(AtomicBoolean param) {
		param.set(processor.getAsyncStateMachine().isAsyncError());
	}

	// @Override
	public void asyncIsStarted(AtomicBoolean param) {
		param.set(processor.getAsyncStateMachine().isAsyncStarted());
	}

	// @Override
	public void asyncIsTimingOut(AtomicBoolean param) {
		param.set(processor.getAsyncStateMachine().isAsyncTimingOut());
	}

	// @Override
	public void asyncSetTimeout(Long param) {
		if (param == null) {
			return;
		}
		long timeout = param.longValue();
		processor.getAsyncStateMachine().setAsyncTimeout(timeout);
	}

	// @Override
	public void asyncTimeout(AtomicBoolean param) {
		AtomicBoolean result = param;
		result.set(processor.getAsyncStateMachine().asyncTimeout());
	}

	public Runnable pushDispatchingState() {
		return processor.getAsyncStateMachine().pushDispatchingState();
	}

	public void popDispatchingState() {
		processor.getAsyncStateMachine().popDispatchingState();
	}

	public boolean hasStackedState() {
		return processor.getAsyncStateMachine().hasStackedState();
	}

	public void actionREQUEST_BODY_FULLY_READ(AtomicBoolean param) {
		AtomicBoolean result = param;
		result.set(requestAction.isRequestBodyFullyRead());
	}

	public void actionNB_READ_INTEREST(AtomicBoolean param) {
		AtomicBoolean isReady = param;
		isReady.set(requestAction.isReadyForRead());
	}

	public void dispatchRead() {
		processor.dispatchRead();
	}

	public void dispatchWrite() {
		processor.dispatchWrite();
	}

	public void dispatchExecute() {
		processor.dispatchExecute();
	}

	public void upgrade(UpgradeToken param) {
		processor.upgrade(param);
	}

	public void isPushSupported(AtomicBoolean param) {
		processor.isPushSupported(param);
	}

	public void pushRequest(ExchangeData param) {
		processor.pushRequest(param);
	}

//	public void actionCONNECTION_ID(AtomicReference<Object> param) {
//		processor.actionCONNECTION_ID(param);
//	}

//	public void actionSTREAM_ID(AtomicReference<Object> param) {
//		processor.actionSTREAM_ID(param);
//	}

	// @Override
	public void actionCONNECTION_ID(AtomicReference<Object> param) {
		AtomicReference<Object> result = param;
		result.set(processor.getChannel().getConnectionID());
	}

	// @Override
	public void actionSTREAM_ID(AtomicReference<Object> param) {
		AtomicReference<Object> result = param;
		result.set(processor.getChannel().getStreamID());
	}

	// -------------------- Cookies --------------------

	public ServerCookies getCookies() {
		return this.exchangeData.getCookies();
	}

	// -------------------- Parameters --------------------

	public Parameters getParameters() {
		return this.exchangeData.getParameters();
	}

	public void addPathParameter(String name, String value) {
		this.exchangeData.addPathParameter(name, value);
	}

	public String getPathParameter(String name) {
		return this.exchangeData.getPathParameter(name);
	}

	// -------------------- Other attributes --------------------
	// We can use notes for most - need to discuss what is of general interest

	public void setAttribute(String name, Object o) {
		this.exchangeData.setAttribute(name, o);
	}

	public HashMap<String, Object> getAttributes() {
		return this.exchangeData.getAttributes();
	}

	public Object getAttribute(String name) {
		return this.exchangeData.getAttribute(name);
	}

	public MessageBytes getRemoteUser() {
		return this.exchangeData.getRemoteUser();
	}

	public boolean getRemoteUserNeedsAuthorization() {
		return this.exchangeData.getRemoteUserNeedsAuthorization();
	}

	public void setRemoteUserNeedsAuthorization(boolean remoteUserNeedsAuthorization) {
		this.exchangeData.setRemoteUserNeedsAuthorization(remoteUserNeedsAuthorization);
	}

	public MessageBytes getAuthType() {
		return this.exchangeData.getAuthType();
	}

	// public int getAvailable() {
	// return this.exchangeData.getAvailable();
	// }

	public boolean getSendfile() {
		return this.exchangeData.getSendfile();
	}

	public void setSendfile(boolean sendfile) {
		this.exchangeData.setSendfile(sendfile);
	}

	public boolean isFinished() {
		return requestAction.isRequestBodyFullyRead();
	}

	public boolean getSupportsRelativeRedirects() {
		if (getProtocol().equals("") || getProtocol().equals("HTTP/1.0")) {
			return false;
		}
		return true;
	}

	// -------------------- Input Buffer --------------------

	// public InputBuffer getInputBuffer() {
	// return inputBuffer;
	// }

	// public void setInputBuffer(InputBuffer inputBuffer) {
	// this.inputBuffer = inputBuffer;
	// }

	/**
	 * Read data from the input buffer and put it into ApplicationBufferHandler.
	 *
	 * The buffer is owned by the protocol implementation - it will be reused on the
	 * next read. The Adapter must either process the data in place or copy it to a
	 * separate buffer if it needs to hold it. In most cases this is done during
	 * byte-&gt;char conversions or via InputStream. Unlike InputStream, this
	 * interface allows the app to process data in place, without copy.
	 *
	 * @param handler The destination to which to copy the data
	 *
	 * @return The number of bytes copied
	 *
	 * @throws IOException If an I/O error occurs during the copy
	 */
	// public int doRead(PreInputBuffer handler) throws IOException {
	// long bytesRead = this.exchangeData.getBytesRead();
	// int n = inputBuffer.doRead(handler);
	// if (n > 0) {
	// bytesRead += n;
	// }
	// this.exchangeData.setBytesRead(bytesRead);
	// return n;
	// }

	@Override
	public BufWrapper doRead() throws IOException {
		long bytesRead = this.exchangeData.getBytesRead();
		int n = -1;// inputBuffer.doRead(handler);
		BufWrapper bufWrapper = requestAction.doRead();
		if (bufWrapper != null) {
			n = bufWrapper.getRemaining();
		}
		if (n > 0) {
			bytesRead += n;
		}
		this.exchangeData.setBytesRead(bytesRead);
		return bufWrapper;
	}

	// -------------------- debug --------------------

	@Override
	public String toString() {
		return "R( " + getRequestURI().toString() + ")";
	}

	public long getStartTime() {
		return this.exchangeData.getStartTime();
	}

	public void setStartTime(long startTime) {
		this.exchangeData.setStartTime(startTime);
	}

	// -------------------- Per-Request "notes" --------------------

	/**
	 * Used to store private data. Thread data could be used instead - but if you
	 * have the req, getting/setting a note is just an array access, may be faster
	 * than ThreadLocal for very frequent operations.
	 *
	 * Example use: Catalina CoyoteAdapter: ADAPTER_NOTES = 1 - stores the
	 * HttpServletRequest object ( req/res)
	 *
	 * To avoid conflicts, note in the range 0 - 8 are reserved for the servlet
	 * container ( catalina connector, etc ), and values in 9 - 16 for connector
	 * use.
	 *
	 * 17-31 range is not allocated or used.
	 *
	 * @param pos   Index to use to store the note
	 * @param value The value to store at that index
	 */
	public final void setNote(int pos, Object value) {
		requestNotes[pos] = value;
	}

	public final Object getNote(int pos) {
		return requestNotes[pos];
	}

	// -------------------- Recycling --------------------

	public void recycle() {

		this.exchangeData.recycle();

		// listener = null;
		// hook.setReadListener(null);

	}

	// -------------------- Info --------------------
	public void updateCounters() {
		this.exchangeData.updateCounters();
	}

	public RequestInfo getRequestProcessor() {
		return this.exchangeData.getRequestProcessor();
	}

	public long getBytesRead() {
		return this.exchangeData.getBytesRead();
	}

	public boolean isProcessing() {
		return this.exchangeData.isProcessing();
	}

}
