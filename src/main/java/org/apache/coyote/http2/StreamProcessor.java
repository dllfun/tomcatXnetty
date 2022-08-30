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
package org.apache.coyote.http2;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.security.AccessController;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.Iterator;

import org.apache.coyote.AbstractProcessor;
import org.apache.coyote.Adapter;
import org.apache.coyote.ContainerThreadMarker;
import org.apache.coyote.ErrorState;
import org.apache.coyote.ExchangeData;
import org.apache.coyote.Request;
import org.apache.coyote.RequestInfo;
import org.apache.coyote.Response;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.http.FastHttpDateFormat;
import org.apache.tomcat.util.http.MimeHeaders;
import org.apache.tomcat.util.net.Channel;
import org.apache.tomcat.util.net.DispatchType;
import org.apache.tomcat.util.net.Endpoint.Handler.SocketState;
import org.apache.tomcat.util.net.SendfileState;
import org.apache.tomcat.util.net.SocketEvent;
import org.apache.tomcat.util.res.StringManager;

class StreamProcessor extends AbstractProcessor {

	private static final Log log = LogFactory.getLog(StreamProcessor.class);
	private static final StringManager sm = StringManager.getManager(StreamProcessor.class);

	private final Http2UpgradeHandler handler;
	private final StreamChannel stream;
	private final Http2InputBuffer http2InputBuffer;
	private final Http2OutputBuffer http2OutputBuffer;
	private Channel channel;
	private boolean repeat = true;

	StreamProcessor(Http2UpgradeHandler handler, StreamChannel stream, Adapter adapter) {
		super(handler.getProtocol().getHttp11Protocol(), adapter, stream.getExchangeData());
		this.handler = handler;
		this.stream = stream;
		this.stream.setCurrentProcessor(this);
		this.http2InputBuffer = new Http2InputBuffer(this);
		this.http2OutputBuffer = new Http2OutputBuffer(this);
		// inputHandler = this.stream.getInputBuffer();
		// setChannel(stream);
	}

	@Override
	protected Request createRequest() {
		return new Request(this.exchangeData, this, http2InputBuffer);
	}

	@Override
	protected Response createResponse() {
		return new Response(this.exchangeData, this, http2OutputBuffer);
	}

	@Override
	protected void initChannel(Channel channel) {
		this.channel = channel;
	}

	final void processOld(SocketEvent event) {
		try {
			// FIXME: the regular processor syncs on socketWrapper, but here this deadlocks
			synchronized (this) {
				// HTTP/2 equivalent of AbstractConnectionHandler#process() without the
				// socket <-> processor mapping
				ContainerThreadMarker.set();
				SocketState state = SocketState.CLOSED;
				try {
					state = process(event);

					if (state == SocketState.LONG) {
						handler.getProtocol().getHttp11Protocol().addWaitingProcessor(this);
					} else if (state == SocketState.CLOSED) {
						handler.getProtocol().getHttp11Protocol().removeWaitingProcessor(this);
						if (!getErrorState().isConnectionIoAllowed()) {
							ConnectionException ce = new ConnectionException(
									sm.getString("streamProcessor.error.connection", stream.getConnectionId(),
											stream.getIdentifier()),
									Http2Error.INTERNAL_ERROR);
							stream.close(ce);
						} else if (!getErrorState().isIoAllowed()) {
							StreamException se = stream.getResetException();
							if (se == null) {
								se = new StreamException(
										sm.getString("streamProcessor.error.stream", stream.getConnectionId(),
												stream.getIdentifier()),
										Http2Error.INTERNAL_ERROR, stream.getIdAsInt());
							}
							stream.close(se);
						}
					}
				} catch (Exception e) {
					String msg = sm.getString("streamProcessor.error.connection", stream.getConnectionId(),
							stream.getIdentifier());
					if (log.isDebugEnabled()) {
						log.debug(msg, e);
					}
					ConnectionException ce = new ConnectionException(msg, Http2Error.INTERNAL_ERROR);
					ce.initCause(e);
					stream.close(ce);
				} finally {
					ContainerThreadMarker.clear();
				}
			}
		} finally {
			// handler.executeQueuedStream();
			stream.released(stream);
		}
	}

	final void addOutputFilter(int id) {
		http2OutputBuffer.addActiveFilter(id);
	}

	// Static so it can be used by Stream to build the MimeHeaders required for
	// an ACK. For that use case coyoteRequest, protocol and stream will be null.
	static void prepareHeaders(ExchangeData exchangeData, boolean noSendfile, Http2Protocol protocol, Stream stream) {
		MimeHeaders headers = exchangeData.getResponseHeaders();
		int statusCode = exchangeData.getStatus();

		// Add the pseudo header for status
		headers.addValue(":status").setString(Integer.toString(statusCode));

		if (noSendfile && stream != null) {
			((StreamProcessor) stream.getCurrentProcessor())
					.addOutputFilter(org.apache.coyote.http11.Constants.FLOWCTRL_FILTER);
			((StreamProcessor) stream.getCurrentProcessor())
					.addOutputFilter(org.apache.coyote.http11.Constants.BUFFEREDOUTPUT_FILTER);
		}
		// Compression can't be used with sendfile
		// Need to check for compression (and set headers appropriately) before
		// adding headers below
		if (noSendfile && protocol != null && protocol.useCompression(exchangeData)) {
			// Enable compression. Headers will have been set. Need to configure
			// output filter at this point.
			if (stream.getCurrentProcessor() != null) {
				((StreamProcessor) stream.getCurrentProcessor())
						.addOutputFilter(org.apache.coyote.http11.Constants.GZIP_FILTER);
			}
		}

		// Check to see if a response body is present
		if (!(statusCode < 200 || statusCode == 204 || statusCode == 205 || statusCode == 304)) {
			String contentType = exchangeData.getResponseContentType();
			if (contentType != null) {
				headers.setValue("content-type").setString(contentType);
			}
			String contentLanguage = exchangeData.getContentLanguage();
			if (contentLanguage != null) {
				headers.setValue("content-language").setString(contentLanguage);
			}
			// Add a content-length header if a content length has been set unless
			// the application has already added one
			if (headers.getValue("content-length") == null) {
				long contentLength = exchangeData.getResponseContentLengthLong();
				if (contentLength != -1) {
					headers.addValue("content-length").setLong(contentLength);
				}
			} else {
				exchangeData.setRequestContentLength(headers.getValue("content-length").getLong());
			}
		} else {
			if (statusCode == 205) {
				// RFC 7231 requires the server to explicitly signal an empty
				// response in this case
				exchangeData.setResponseContentLength(0);
			} else {
				exchangeData.setResponseContentLength(-1);
			}
		}

		// Add date header unless it is an informational response or the
		// application has already set one
		if (statusCode >= 200 && headers.getValue("date") == null) {
			headers.addValue("date").setString(FastHttpDateFormat.getCurrentDate());
		}
	}

//	@Override
//	public void processSocketEvent(SocketEvent event, boolean dispatch) {
//		if (dispatch) {
//			handler.processStreamOnContainerThread(stream, this, event);
//		} else {
//			handler.getProtocol().getHttp11Protocol().getHandler().processSocket(stream, event, dispatch);
//			// this.process(event);
//		}
//	}

//	@Override
//	protected final boolean isReadyForWrite() {
//		return stream.isReadyForWrite();
//	}

	@Override
	protected final void executeDispatches() {
		Iterator<DispatchType> dispatches = getIteratorAndClearDispatches();
		/*
		 * Compare with superclass that uses SocketWrapper A sync is not necessary here
		 * as the window sizes are updated with syncs before the dispatches are executed
		 * and it is the window size updates that need to be complete before the
		 * dispatch executes.
		 */
		while (dispatches != null && dispatches.hasNext()) {
			DispatchType dispatchType = dispatches.next();
			/*
			 * Dispatch on new thread. Firstly, this avoids a deadlock on the SocketWrapper
			 * as Streams being processed by container threads lock the SocketProcessor
			 * before they lock the SocketWrapper which is the opposite order to container
			 * threads processing via Http2UpgrageHandler. Secondly, this code executes
			 * after a Window update has released one or more Streams. By dispatching each
			 * Stream to a dedicated thread, those Streams may progress concurrently.
			 */
			// processSocketEvent(dispatchType.getSocketStatus(), true);
			handler.getProtocol().getHttp11Protocol().getHandler().processSocket(stream, dispatchType.getSocketStatus(),
					true);
		}
	}

	@Override
	protected final boolean isPushSupported() {
		return isPushSupported2();
	}

	final boolean isPushSupported2() {
		return handler.getRemoteSettings().getEnablePush();
	}

	@Override
	protected final void doPush(ExchangeData exchangeData) {
		try {
			push(exchangeData);
		} catch (IOException ioe) {
			setErrorState(ErrorState.CLOSE_CONNECTION_NOW, ioe);
			exchangeData.setErrorException(ioe);
		}
	}

	final void push(ExchangeData exchangeData) throws IOException {
		// Can only push when supported and from a peer initiated stream
		if (!isPushSupported() || stream.getIdAsInt() % 2 == 0) {
			return;
		}
		// Set the special HTTP/2 headers
		exchangeData.getRequestHeaders().addValue(":method").duplicate(exchangeData.getMethod());
		exchangeData.getRequestHeaders().addValue(":scheme").duplicate(exchangeData.getScheme());
		StringBuilder path = new StringBuilder(exchangeData.getRequestURI().toString());
		if (!exchangeData.getQueryString().isNull()) {
			path.append('?');
			path.append(exchangeData.getQueryString().toString());
		}
		exchangeData.getRequestHeaders().addValue(":path").setString(path.toString());

		// Authority needs to include the port only if a non-standard port is
		// being used.
		if (!(exchangeData.getScheme().equals("http") && exchangeData.getServerPort() == 80)
				&& !(exchangeData.getScheme().equals("https") && exchangeData.getServerPort() == 443)) {
			exchangeData.getRequestHeaders().addValue(":authority")
					.setString(exchangeData.getServerName().getString() + ":" + exchangeData.getServerPort());
		} else {
			exchangeData.getRequestHeaders().addValue(":authority").duplicate(exchangeData.getServerName());
		}

		push(handler, exchangeData, stream);
	}

	private static void push(final Http2UpgradeHandler handler, final ExchangeData exchangeData, final Stream stream)
			throws IOException {
		if (org.apache.coyote.Constants.IS_SECURITY_ENABLED) {
			try {
				AccessController.doPrivileged(new PrivilegedPush(handler, exchangeData, stream));
			} catch (PrivilegedActionException ex) {
				Exception e = ex.getException();
				if (e instanceof IOException) {
					throw (IOException) e;
				} else {
					throw new IOException(ex);
				}
			}

		} else {
			handler.getWriter().writePushHeader(exchangeData, stream);
		}
	}

//	@Override
//	protected boolean isTrailerFieldsSupported() {
//		return stream.isTrailerFieldsSupported();
//	}

	@Override
	protected boolean repeat() {
		if (repeat) {
			repeat = false;
			return true;
		} else {
			return false;
		}
	}

	@Override
	public final SocketState serviceInternal() throws IOException {// Channel channel
		RequestInfo rp = exchangeData.getRequestProcessor();
		rp.setStage(org.apache.coyote.Constants.STAGE_PARSE);

		SendfileState sendfileState = SendfileState.DONE;
		// setChannel(channel);

		while (!getErrorState().isError() && repeat() && !asyncStateMachine.isAsync() && !isUpgrade()
				&& sendfileState == SendfileState.DONE && !protocol.isPaused()) {

			if (!parsingHeader()) {
				if (!getErrorState().isError()) {
					if (canReleaseProcessor()) {
						return SocketState.OPEN;
					} else {
						return SocketState.LONG;
					}
				}
			}

			if (getErrorState().isIoAllowed()) {
				// Setting up filters, and parse some request headers
				rp.setStage(org.apache.coyote.Constants.STAGE_PREPARE);
				try {
					http2InputBuffer.prepareRequest();
				} catch (Throwable t) {
					ExceptionUtils.handleThrowable(t);
					if (log.isDebugEnabled()) {
						getLog().debug(sm.getString("ajpprocessor.request.prepare"), t);
					}
					// 500 - Internal Server Error
					exchangeData.setStatus(500);
					setErrorState(ErrorState.CLOSE_CLEAN, t);
				}
			}

			if (getErrorState().isIoAllowed()) {
				try {
					rp.setStage(org.apache.coyote.Constants.STAGE_SERVICE);
					Request request = createRequest();
					Response response = createResponse();
					request.setResponse(response);
					getAdapter().service(request, response);
				} catch (InterruptedIOException e) {
					setErrorState(ErrorState.CLOSE_CONNECTION_NOW, e);
				} catch (Throwable t) {
					ExceptionUtils.handleThrowable(t);
					getLog().error(sm.getString("ajpprocessor.request.process"), t);
					// 500 - Internal Server Error
					exchangeData.setStatus(500);
					setErrorState(ErrorState.CLOSE_CLEAN, t);
					Request request = createRequest();
					Response response = createResponse();
					request.setResponse(response);
					getAdapter().log(request, response, 0);
				}
			}

			rp.setStage(org.apache.coyote.Constants.STAGE_ENDINPUT);
			if (!asyncStateMachine.isAsync()) {
				// If this is an async request then the request ends when it has
				// been completed. The AsyncContext is responsible for calling
				// endRequest() in that case.
				endRequest();
			}
			rp.setStage(org.apache.coyote.Constants.STAGE_ENDOUTPUT);

			if (getErrorState().isError()) {
				exchangeData.setStatus(500);
			}

			if ((!asyncStateMachine.isAsync() && !isUpgrade()) || getErrorState().isError()) {
				exchangeData.updateCounters();
				if (getErrorState().isIoAllowed()) {
					nextRequest();
				}
			}

			rp.setStage(org.apache.coyote.Constants.STAGE_KEEPALIVE);

			sendfileState = processSendfile();
		}

		if (getErrorState().isError()) {
			exchangeData.updateCounters();
			return SocketState.CLOSED;
		} else if (asyncStateMachine.isAsync()) {
			return SocketState.SUSPENDED;
		} else if (sendfileState == SendfileState.PENDING) {
			return SocketState.SENDFILE;
		} else {
//			http2OutputBuffer.close();
			exchangeData.updateCounters();
			return SocketState.CLOSED;
		}
	}

	@Override
	protected boolean parsingHeader() {
		return true;
	}

	@Override
	protected boolean canReleaseProcessor() {
		return false;
	}

	@Override
	protected SendfileState processSendfile() throws IOException {
//		openSocket = inputBuffer.keepAlive;
		// Done is equivalent to sendfile not being used
		SendfileState result = SendfileState.DONE;
		SendfileData sendfileData = http2OutputBuffer.getSendfileData();
		http2OutputBuffer.setSendfileData(null);
		// Do sendfile as needed: add socket to sendfile and end
		if (sendfileData != null && !getErrorState().isError()) {
			result = stream.getHandler().processSendfile(sendfileData);
			switch (result) {
			case ERROR:
				// Write failed
				if (log.isDebugEnabled()) {
					log.debug(sm.getString("http11processor.sendfile.error"));
				}
				setErrorState(ErrorState.CLOSE_CONNECTION_NOW, null);
				//$FALL-THROUGH$
			default:

			}
		}
		return result;
//		return http2OutputBuffer.getSendfileState();
	}

	@Override
	protected final boolean flushBufferedWrite() throws IOException {
		if (log.isDebugEnabled()) {
			log.debug(sm.getString("streamProcessor.flushBufferedWrite.entry", stream.getConnectionId(),
					stream.getIdentifier()));
		}
		// TODO check
		if (http2OutputBuffer.flush(false)) {//
			// The buffer wasn't fully flushed so re-register the
			// stream for write. Note this does not go via the
			// Response since the write registration state at
			// that level should remain unchanged. Once the buffer
			// has been emptied then the code below will call
			// dispatch() which will enable the
			// Response to respond to this event.
			if (stream.isReadyForWrite()) {
				// Unexpected
				throw new IllegalStateException();
			}
			return true;
		}
		return false;
	}

	@Override
	protected final SocketState dispatchEndRequest() throws IOException {
		endRequest();
		return SocketState.CLOSED;
	}

	@Override
	protected void endRequest() throws IOException {
		if (!stream.isInputFinished() && getErrorState().isIoAllowed()) {
			if (handler.hasAsyncIO() && !stream.isContentLengthInconsistent()) {
				// Need an additional checks for asyncIO as the end of stream
				// might have been set on the header frame but not processed
				// yet. Checking for this here so the extra processing only
				// occurs on the potential error condition rather than on every
				// request.
				return;
			}
			// The request has been processed but the request body has not been
			// fully read. This typically occurs when Tomcat rejects an upload
			// of some form (e.g. PUT or POST). Need to tell the client not to
			// send any more data but only if a reset has not already been
			// triggered.
			StreamException se = new StreamException(
					sm.getString("streamProcessor.cancel", stream.getConnectionId(), stream.getIdentifier()),
					Http2Error.CANCEL, stream.getIdAsInt());
			handler.getWriter().writeStreamReset(se);
		}
		http2OutputBuffer.close();
	}

	@Override
	public Exception collectCloseException() {
		if (!getErrorState().isConnectionIoAllowed()) {
			ConnectionException ce = new ConnectionException(
					sm.getString("streamProcessor.error.connection", stream.getConnectionId(), stream.getIdentifier()),
					Http2Error.INTERNAL_ERROR);
			return ce;
		} else if (!getErrorState().isIoAllowed()) {
			StreamException se = stream.getResetException();
			if (se == null) {
				se = new StreamException(
						sm.getString("streamProcessor.error.stream", stream.getConnectionId(), stream.getIdentifier()),
						Http2Error.INTERNAL_ERROR, stream.getIdAsInt());
			}
			return se;
		}
		return null;
	}

	@Override
	protected void nextRequestInternal() {
		// TODO Auto-generated method stub
		http2InputBuffer.recycle();
		http2OutputBuffer.recycle();
	}

	@Override
	protected void recycleInternal() {
		// StreamProcessor instances are not re-used.
		// Clear fields that can be cleared to aid GC and trigger NPEs if this
		// is reused
		// super.recycle();
		channel = null;
		http2InputBuffer.recycle();
		http2OutputBuffer.recycle();
	}

	@Override
	protected final Log getLog() {
		return log;
	}

	@Override
	public final void pause() {
		// NO-OP. Handled by the Http2UpgradeHandler
	}

	private static class PrivilegedPush implements PrivilegedExceptionAction<Void> {

		private final Http2UpgradeHandler handler;
		private final ExchangeData exchangeData;
		private final Stream stream;

		public PrivilegedPush(Http2UpgradeHandler handler, ExchangeData exchangeData, Stream stream) {
			this.handler = handler;
			this.exchangeData = exchangeData;
			this.stream = stream;
		}

		@Override
		public Void run() throws IOException {
			handler.getWriter().writePushHeader(exchangeData, stream);
			return null;
		}
	}

}
