package org.apache.coyote;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.servlet.ReadListener;

import org.apache.tomcat.util.buf.ByteChunk;
import org.apache.tomcat.util.buf.MessageBytes;
import org.apache.tomcat.util.buf.UDecoder;
import org.apache.tomcat.util.http.MimeHeaders;
import org.apache.tomcat.util.http.Parameters;
import org.apache.tomcat.util.http.ServerCookies;
import org.apache.tomcat.util.net.DispatchType;
import org.apache.tomcat.util.net.SocketEvent;
import org.apache.tomcat.util.net.Channel.BufWrapper;
import org.apache.tomcat.util.res.StringManager;

public final class Request implements InputReader {

	private static final StringManager sm = StringManager.getManager(Request.class);

	private RequestData requestData;
	private Response response;
	private volatile ActionHook hook;
	private ChannelHandler channelHandler;
	private AsyncState asyncState;

	// ----------------------------------------------------------- Constructors

	public Request(RequestData requestData, ActionHook hook, AsyncState asyncState, ChannelHandler channelHandler) {
		this.requestData = requestData;
		this.hook = hook;
		this.asyncState = asyncState;
		this.channelHandler = channelHandler;
	}

	// private final RequestInfo reqProcessorMX = new RequestInfo(this);

	// volatile ReadListener listener;

	public ReadListener getReadListener() {
		return asyncState.getReadListener();
	}

	public void setReadListener(ReadListener listener) {
		asyncState.setReadListener(listener);
	}

	public boolean sendAllDataReadEvent() {
		return this.requestData.sendAllDataReadEvent();
	}

	// ------------------------------------------------------------- Properties

	public MimeHeaders getMimeHeaders() {
		return requestData.getMimeHeaders();
	}

	public boolean isTrailerFieldsReady() {
		AtomicBoolean result = new AtomicBoolean(false);
		hook.actionIS_TRAILER_FIELDS_READY(result);
		return result.get();
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
		return channelHandler.getAvailable(param);
	}

	public void actionREQ_SET_BODY_REPLAY(ByteChunk param) {
		ByteChunk body = param;
		channelHandler.setRequestBody(body);
	}

	public void actionIS_IO_ALLOWED(AtomicBoolean param) {
		hook.isIoAllowed(param);
	}

	public void actionDISABLE_SWALLOW_INPUT() {
		// Aborted upload or similar.
		// No point reading the remainder of the request.
		channelHandler.disableSwallowRequest();
		// This is an error state. Make sure it is marked as such.
		hook.setErrorState(ErrorState.CLOSE_CLEAN, null);
	}

	public void actionREQ_HOST_ADDR_ATTRIBUTE() {
		hook.actionREQ_HOST_ADDR_ATTRIBUTE();
	}

	public void actionREQ_HOST_ATTRIBUTE() {
		hook.actionREQ_HOST_ATTRIBUTE();
	}

	public void actionREQ_REMOTEPORT_ATTRIBUTE() {
		hook.actionREQ_REMOTEPORT_ATTRIBUTE();
	}

	public void actionREQ_LOCAL_NAME_ATTRIBUTE() {
		hook.actionREQ_LOCAL_NAME_ATTRIBUTE();
	}

	public void actionREQ_LOCAL_ADDR_ATTRIBUTE() {
		hook.actionREQ_LOCAL_ADDR_ATTRIBUTE();
	}

	public void actionREQ_LOCALPORT_ATTRIBUTE() {
		hook.actionREQ_LOCALPORT_ATTRIBUTE();
	}

	public void actionREQ_SSL_ATTRIBUTE() {
		hook.actionREQ_SSL_ATTRIBUTE();
	}

	public void actionREQ_SSL_CERTIFICATE() {
		hook.actionREQ_SSL_CERTIFICATE();
	}

	// @Override
	public void actionASYNC_POST_PROCESS() {
		asyncState.asyncPostProcess();
	}

	// @Override
	public void actionASYNC_DISPATCHED() {
		asyncState.asyncDispatched();
	}

	// @Override
	public void actionASYNC_RUN(Runnable param) {
		asyncState.asyncRun(param);
	}

	// @Override
	public void actionASYNC_DISPATCH() {
		if (asyncState.asyncDispatch()) {
			hook.processSocketEvent(SocketEvent.OPEN_READ, true);
		}
	}

	// @Override
	public void actionASYNC_START(AsyncContextCallback param) {
		asyncState.asyncStart(param);
	}

	// @Override
	public void actionASYNC_COMPLETE() {
		hook.clearDispatches();
		if (asyncState.asyncComplete()) {
			hook.processSocketEvent(SocketEvent.OPEN_READ, true);
		}
	}

	// @Override
	public void actionASYNC_ERROR() {
		asyncState.asyncError();
	}

	// @Override
	public void actionASYNC_IS_ASYNC(AtomicBoolean param) {
		param.set(asyncState.isAsync());
	}

	// @Override
	public void actionASYNC_IS_COMPLETING(AtomicBoolean param) {
		param.set(asyncState.isCompleting());
	}

	// @Override
	public void actionASYNC_IS_DISPATCHING(AtomicBoolean param) {
		param.set(asyncState.isAsyncDispatching());
	}

	// @Override
	public void actionASYNC_IS_ERROR(AtomicBoolean param) {
		param.set(asyncState.isAsyncError());
	}

	// @Override
	public void actionASYNC_IS_STARTED(AtomicBoolean param) {
		param.set(asyncState.isAsyncStarted());
	}

	// @Override
	public void actionASYNC_IS_TIMINGOUT(AtomicBoolean param) {
		param.set(asyncState.isAsyncTimingOut());
	}

	// @Override
	public void actionASYNC_SETTIMEOUT(Long param) {
		if (param == null) {
			return;
		}
		long timeout = param.longValue();
		asyncState.setAsyncTimeout(timeout);
	}

	// @Override
	public void actionASYNC_TIMEOUT(AtomicBoolean param) {
		AtomicBoolean result = param;
		result.set(asyncState.asyncTimeout());
	}

	public void actionREQUEST_BODY_FULLY_READ(AtomicBoolean param) {
		AtomicBoolean result = param;
		result.set(channelHandler.isRequestBodyFullyRead());
	}

	public void actionNB_READ_INTEREST(AtomicBoolean param) {
		AtomicBoolean isReady = param;
		isReady.set(channelHandler.isReadyForRead());
	}

	public void actionNB_WRITE_INTEREST(AtomicBoolean param) {
		hook.actionNB_WRITE_INTEREST(param);
	}

	public void actionDISPATCH_READ() {
		hook.actionDISPATCH_READ();
	}

	public void actionDISPATCH_WRITE() {
		hook.actionDISPATCH_WRITE();
	}

	public void actionDISPATCH_EXECUTE() {
		hook.actionDISPATCH_EXECUTE();
	}

	public void actionUPGRADE(UpgradeToken param) {
		hook.actionUPGRADE(param);
	}

	public void actionIS_PUSH_SUPPORTED(AtomicBoolean param) {
		hook.actionIS_PUSH_SUPPORTED(param);
	}

	public void actionPUSH_REQUEST(RequestData param) {
		hook.actionPUSH_REQUEST(param);
	}

	public void actionCONNECTION_ID(AtomicReference<Object> param) {
		hook.actionCONNECTION_ID(param);
	}

	public void actionSTREAM_ID(AtomicReference<Object> param) {
		hook.actionSTREAM_ID(param);
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
		AtomicBoolean result = new AtomicBoolean(false);
		actionREQUEST_BODY_FULLY_READ(result);
		return result.get();
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
		BufWrapper bufWrapper = channelHandler.doRead();
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