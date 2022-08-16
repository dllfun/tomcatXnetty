package org.apache.coyote;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.servlet.ReadListener;

import org.apache.catalina.core.AsyncContextImpl;
import org.apache.tomcat.util.buf.ByteChunk;
import org.apache.tomcat.util.buf.MessageBytes;
import org.apache.tomcat.util.buf.UDecoder;
import org.apache.tomcat.util.http.MimeHeaders;
import org.apache.tomcat.util.http.Parameters;
import org.apache.tomcat.util.http.ServerCookies;
import org.apache.tomcat.util.net.DispatchType;
import org.apache.tomcat.util.net.SocketChannel.BufWrapper;
import org.apache.tomcat.util.net.SocketEvent;
import org.apache.tomcat.util.res.StringManager;

public final class Request implements InputReader {

	private static final StringManager sm = StringManager.getManager(Request.class);

	private RequestData requestData;
	private Response response;
	private volatile AbstractProcessor processor;
	private RequestAction requestAction;

	// ----------------------------------------------------------- Constructors

	public Request(RequestData requestData, AbstractProcessor processor, RequestAction requestAction) {
		this.requestData = requestData;
		this.processor = processor;
		this.requestAction = requestAction;
	}

	// private final RequestInfo reqProcessorMX = new RequestInfo(this);

	// volatile ReadListener listener;

	public ReadListener getReadListener() {
		return requestData.getAsyncStateMachine().getReadListener();
	}

	public void setReadListener(ReadListener listener) {
		requestData.getAsyncStateMachine().setReadListener(listener);
	}

	public boolean sendAllDataReadEvent() {
		return this.requestData.sendAllDataReadEvent();
	}

	// ------------------------------------------------------------- Properties

	public MimeHeaders getMimeHeaders() {
		return requestData.getMimeHeaders();
	}

	public boolean isTrailerFieldsReady() {
		return requestAction.isTrailerFieldsReady();
	}

	public Map<String, String> getTrailerFields() {
		return requestData.getTrailerFields();
	}

	public UDecoder getURLDecoder() {
		return requestData.getURLDecoder();
	}

	// -------------------- Request data --------------------

	public MessageBytes scheme() {
		return requestData.scheme();
	}

	public MessageBytes method() {
		return requestData.method();
	}

	public MessageBytes requestURI() {
		return requestData.requestURI();
	}

	public MessageBytes decodedURI() {
		return requestData.decodedURI();
	}

	public MessageBytes queryString() {
		return requestData.queryString();
	}

	public MessageBytes protocol() {
		return requestData.protocol();
	}

	/**
	 * Get the "virtual host", derived from the Host: header associated with this
	 * request.
	 *
	 * @return The buffer holding the server name, if any. Use isNull() to check if
	 *         there is no value set.
	 */
	public MessageBytes serverName() {
		return requestData.serverName();
	}

	public int getServerPort() {
		return requestData.getServerPort();
	}

	public void setServerPort(int serverPort) {
		this.requestData.setServerPort(serverPort);
	}

	public MessageBytes remoteAddr() {
		return requestData.remoteAddr();
	}

	public MessageBytes remoteHost() {
		return requestData.remoteHost();
	}

	public MessageBytes localName() {
		return requestData.localName();
	}

	public MessageBytes localAddr() {
		return requestData.localAddr();
	}

	public int getRemotePort() {
		return requestData.getRemotePort();
	}

	public void setRemotePort(int port) {
		this.requestData.setRemotePort(port);
	}

	public int getLocalPort() {
		return requestData.getLocalPort();
	}

	public void setLocalPort(int port) {
		this.requestData.setLocalPort(port);
	}

	// -------------------- encoding/type --------------------

	/**
	 * Get the character encoding used for this request.
	 *
	 * @return The value set via {@link #setCharset(Charset)} or if no call has been
	 *         made to that method try to obtain if from the content type.
	 */
	public String getCharacterEncoding() {
		return requestData.getCharacterEncoding();
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
		return requestData.getCharset();
	}

	public void setCharset(Charset charset) {
		this.requestData.setCharset(charset);
	}

	public void setContentLength(long len) {
		this.requestData.setContentLength(len);
	}

	public int getContentLength() {
		return this.requestData.getContentLength();
	}

	public long getContentLengthLong() {
		return this.requestData.getContentLengthLong();
	}

	public String getContentType() {
		return this.requestData.getContentType();
	}

	public void setContentType(String type) {
		this.requestData.setContentType(type);
	}

	public MessageBytes contentType() {
		return this.requestData.contentType();
	}

	public void setContentType(MessageBytes mb) {
		this.requestData.setContentType(mb);
	}

	public String getHeader(String name) {
		return this.requestData.getHeader(name);
	}

	public void setExpectation(boolean expectation) {
		this.requestData.setExpectation(expectation);
	}

	public boolean hasExpectation() {
		return this.requestData.hasExpectation();
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

	public int actionAVAILABLE(Object param) {
		return requestAction.getAvailable(param);
	}

	public void actionREQ_SET_BODY_REPLAY(ByteChunk param) {
		ByteChunk body = param;
		requestAction.setRequestBody(body);
	}

	public void actionIS_IO_ALLOWED(AtomicBoolean param) {
		processor.isIoAllowed(param);
	}

	public void actionDISABLE_SWALLOW_INPUT() {
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
		processor.actionREQ_SSL_ATTRIBUTE();
	}

	public void actionREQ_SSL_CERTIFICATE() {
		processor.actionREQ_SSL_CERTIFICATE();
	}

	// @Override
	public void asyncPostProcess() {
		requestData.getAsyncStateMachine().asyncPostProcess();
	}

	// @Override
	// public void asyncDispatched() {
	// requestData.getAsyncStateMachine().asyncDispatched();
	// }

	// @Override
	public void asyncRun(Runnable runnable) {
		requestData.getAsyncStateMachine().asyncRun(processor.getProtocol().getExecutor(), runnable);
	}

	// @Override
	public void asyncDispatch() {
		if (requestData.getAsyncStateMachine().asyncDispatch()) {
			processor.processSocketEvent(SocketEvent.OPEN_READ, true);
		}
	}

	// @Override
	public void asyncStart(AsyncContextImpl param) {
		requestData.getAsyncStateMachine().asyncStart(param);
	}

	public AsyncContextImpl getAsyncContext() {
		return requestData.getAsyncStateMachine().getAsyncCtxt();
	}

	// @Override
	public void asyncComplete() {
		processor.clearDispatches();
		if (requestData.getAsyncStateMachine().asyncComplete()) {
			processor.processSocketEvent(SocketEvent.OPEN_READ, true);
		}
	}

	// @Override
	public void asyncError() {
		requestData.getAsyncStateMachine().asyncError();
	}

	// @Override
	public void asyncIsAsync(AtomicBoolean param) {
		param.set(requestData.getAsyncStateMachine().isAsync());
	}

	// @Override
	public void asyncIsCompleting(AtomicBoolean param) {
		param.set(requestData.getAsyncStateMachine().isCompleting());
	}

	// @Override
	public void asyncIsDispatching(AtomicBoolean param) {
		param.set(requestData.getAsyncStateMachine().isAsyncDispatching());
	}

	// @Override
	public void asyncIsError(AtomicBoolean param) {
		param.set(requestData.getAsyncStateMachine().isAsyncError());
	}

	// @Override
	public void asyncIsStarted(AtomicBoolean param) {
		param.set(requestData.getAsyncStateMachine().isAsyncStarted());
	}

	// @Override
	public void asyncIsTimingOut(AtomicBoolean param) {
		param.set(requestData.getAsyncStateMachine().isAsyncTimingOut());
	}

	// @Override
	public void asyncSetTimeout(Long param) {
		if (param == null) {
			return;
		}
		long timeout = param.longValue();
		requestData.getAsyncStateMachine().setAsyncTimeout(timeout);
	}

	// @Override
	public void asyncTimeout(AtomicBoolean param) {
		AtomicBoolean result = param;
		result.set(requestData.getAsyncStateMachine().asyncTimeout());
	}

	public void pushDispatchingState() {
		requestData.getAsyncStateMachine().pushDispatchingState();
	}

	public void popDispatchingState() {
		requestData.getAsyncStateMachine().popDispatchingState();
	}

	public boolean hasStackedState() {
		return requestData.getAsyncStateMachine().hasStackedState();
	}

	public void actionREQUEST_BODY_FULLY_READ(AtomicBoolean param) {
		AtomicBoolean result = param;
		result.set(requestAction.isRequestBodyFullyRead());
	}

	public void actionNB_READ_INTEREST(AtomicBoolean param) {
		AtomicBoolean isReady = param;
		isReady.set(requestAction.isReadyForRead());
	}

	public void actionDISPATCH_READ() {
		processor.actionDISPATCH_READ();
	}

	public void actionDISPATCH_WRITE() {
		processor.actionDISPATCH_WRITE();
	}

	public void actionDISPATCH_EXECUTE() {
		processor.actionDISPATCH_EXECUTE();
	}

	public void actionUPGRADE(UpgradeToken param) {
		processor.actionUPGRADE(param);
	}

	public void actionIS_PUSH_SUPPORTED(AtomicBoolean param) {
		processor.actionIS_PUSH_SUPPORTED(param);
	}

	public void actionPUSH_REQUEST(RequestData param) {
		processor.actionPUSH_REQUEST(param);
	}

	public void actionCONNECTION_ID(AtomicReference<Object> param) {
		processor.actionCONNECTION_ID(param);
	}

	public void actionSTREAM_ID(AtomicReference<Object> param) {
		processor.actionSTREAM_ID(param);
	}

	// -------------------- Cookies --------------------

	public ServerCookies getCookies() {
		return this.requestData.getCookies();
	}

	// -------------------- Parameters --------------------

	public Parameters getParameters() {
		return this.requestData.getParameters();
	}

	public void addPathParameter(String name, String value) {
		this.requestData.addPathParameter(name, value);
	}

	public String getPathParameter(String name) {
		return this.requestData.getPathParameter(name);
	}

	// -------------------- Other attributes --------------------
	// We can use notes for most - need to discuss what is of general interest

	public void setAttribute(String name, Object o) {
		this.requestData.setAttribute(name, o);
	}

	public HashMap<String, Object> getAttributes() {
		return this.requestData.getAttributes();
	}

	public Object getAttribute(String name) {
		return this.requestData.getAttribute(name);
	}

	public MessageBytes getRemoteUser() {
		return this.requestData.getRemoteUser();
	}

	public boolean getRemoteUserNeedsAuthorization() {
		return this.requestData.getRemoteUserNeedsAuthorization();
	}

	public void setRemoteUserNeedsAuthorization(boolean remoteUserNeedsAuthorization) {
		this.requestData.setRemoteUserNeedsAuthorization(remoteUserNeedsAuthorization);
	}

	public MessageBytes getAuthType() {
		return this.requestData.getAuthType();
	}

	// public int getAvailable() {
	// return this.requestData.getAvailable();
	// }

	public boolean getSendfile() {
		return this.requestData.getSendfile();
	}

	public void setSendfile(boolean sendfile) {
		this.requestData.setSendfile(sendfile);
	}

	public boolean isFinished() {
		return requestAction.isRequestBodyFullyRead();
	}

	public boolean getSupportsRelativeRedirects() {
		if (protocol().equals("") || protocol().equals("HTTP/1.0")) {
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
	// long bytesRead = this.requestData.getBytesRead();
	// int n = inputBuffer.doRead(handler);
	// if (n > 0) {
	// bytesRead += n;
	// }
	// this.requestData.setBytesRead(bytesRead);
	// return n;
	// }

	@Override
	public BufWrapper doRead() throws IOException {
		long bytesRead = this.requestData.getBytesRead();
		int n = -1;// inputBuffer.doRead(handler);
		BufWrapper bufWrapper = requestAction.doRead();
		if (bufWrapper != null) {
			n = bufWrapper.getRemaining();
		}
		if (n > 0) {
			bytesRead += n;
		}
		this.requestData.setBytesRead(bytesRead);
		return bufWrapper;
	}

	// -------------------- debug --------------------

	@Override
	public String toString() {
		return "R( " + requestURI().toString() + ")";
	}

	public long getStartTime() {
		return this.requestData.getStartTime();
	}

	public void setStartTime(long startTime) {
		this.requestData.setStartTime(startTime);
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
		this.requestData.setNote(pos, value);
	}

	public final Object getNote(int pos) {
		return this.requestData.getNote(pos);
	}

	// -------------------- Recycling --------------------

	public void recycle() {

		this.requestData.recycle();

		// listener = null;
		// hook.setReadListener(null);

	}

	// -------------------- Info --------------------
	public void updateCounters() {
		this.requestData.updateCounters();
	}

	public RequestInfo getRequestProcessor() {
		return this.requestData.getRequestProcessor();
	}

	public long getBytesRead() {
		return this.requestData.getBytesRead();
	}

	public boolean isProcessing() {
		return this.requestData.isProcessing();
	}

}
