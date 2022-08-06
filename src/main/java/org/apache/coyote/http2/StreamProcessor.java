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

import java.io.File;
import java.io.IOException;
import java.util.Iterator;

import org.apache.coyote.AbstractProcessor;
import org.apache.coyote.Adapter;
import org.apache.coyote.ContainerThreadMarker;
import org.apache.coyote.ErrorState;
import org.apache.coyote.Request;
import org.apache.coyote.RequestData;
import org.apache.coyote.Response;
import org.apache.coyote.ResponseData;
import org.apache.coyote.http11.filters.GzipOutputFilter;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
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
	private final Stream stream;
	private SendfileData sendfileData = null;
	private SendfileState sendfileState = null;

	StreamProcessor(Http2UpgradeHandler handler, Stream stream, Adapter adapter, Channel<?> channel) {
		super(adapter, stream.getCoyoteRequest(), stream.getCoyoteResponse());
		this.handler = handler;
		this.stream = stream;
		this.stream.setProcessor(this);
		this.stream.setChannel(channel);
		// inputHandler = this.stream.getInputBuffer();
		setChannel(channel);
	}

	@Override
	protected Request createRequest() {
		return new Request(this.requestData, this, asyncStateMachine, stream.getInputBuffer());
	}

	@Override
	protected Response createResponse() {
		return new Response(this.responseData, this, asyncStateMachine, stream.getOutputBuffer());
	}

	final void process(SocketEvent event) {
		try {
			// FIXME: the regular processor syncs on socketWrapper, but here this deadlocks
			synchronized (this) {
				// HTTP/2 equivalent of AbstractConnectionHandler#process() without the
				// socket <-> processor mapping
				ContainerThreadMarker.set();
				SocketState state = SocketState.CLOSED;
				try {
					state = process(channel, event);

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
			handler.executeQueuedStream();
		}
	}

	@Override
	protected final void prepareResponse() throws IOException {
		responseData.setCommitted(true);
		if (handler.hasAsyncIO() && handler.getProtocol().getUseSendfile()) {
			prepareSendfile();
		}
		prepareHeaders(requestData, responseData, sendfileData == null, handler.getProtocol(), stream);
		stream.writeHeaders();
	}

	private void prepareSendfile() {
		String fileName = (String) stream.getCoyoteRequest()
				.getAttribute(org.apache.coyote.Constants.SENDFILE_FILENAME_ATTR);
		if (fileName != null) {
			sendfileData = new SendfileData();
			sendfileData.path = new File(fileName).toPath();
			sendfileData.pos = ((Long) stream.getCoyoteRequest()
					.getAttribute(org.apache.coyote.Constants.SENDFILE_FILE_START_ATTR)).longValue();
			sendfileData.end = ((Long) stream.getCoyoteRequest()
					.getAttribute(org.apache.coyote.Constants.SENDFILE_FILE_END_ATTR)).longValue();
			sendfileData.left = sendfileData.end - sendfileData.pos;
			sendfileData.stream = stream;
		}
	}

	// Static so it can be used by Stream to build the MimeHeaders required for
	// an ACK. For that use case coyoteRequest, protocol and stream will be null.
	static void prepareHeaders(RequestData coyoteRequest, ResponseData coyoteResponse, boolean noSendfile,
			Http2Protocol protocol, Stream stream) {
		MimeHeaders headers = coyoteResponse.getMimeHeaders();
		int statusCode = coyoteResponse.getStatus();

		// Add the pseudo header for status
		headers.addValue(":status").setString(Integer.toString(statusCode));

		// Compression can't be used with sendfile
		// Need to check for compression (and set headers appropriately) before
		// adding headers below
		if (noSendfile && protocol != null && protocol.useCompression(coyoteRequest, coyoteResponse)) {
			// Enable compression. Headers will have been set. Need to configure
			// output filter at this point.
			stream.addOutputFilter(new GzipOutputFilter());
		}

		// Check to see if a response body is present
		if (!(statusCode < 200 || statusCode == 204 || statusCode == 205 || statusCode == 304)) {
			String contentType = coyoteResponse.getContentType();
			if (contentType != null) {
				headers.setValue("content-type").setString(contentType);
			}
			String contentLanguage = coyoteResponse.getContentLanguage();
			if (contentLanguage != null) {
				headers.setValue("content-language").setString(contentLanguage);
			}
			// Add a content-length header if a content length has been set unless
			// the application has already added one
			long contentLength = coyoteResponse.getContentLengthLong();
			if (contentLength != -1 && headers.getValue("content-length") == null) {
				headers.addValue("content-length").setLong(contentLength);
			}
		} else {
			if (statusCode == 205) {
				// RFC 7231 requires the server to explicitly signal an empty
				// response in this case
				coyoteResponse.setContentLength(0);
			} else {
				coyoteResponse.setContentLength(-1);
			}
		}

		// Add date header unless it is an informational response or the
		// application has already set one
		if (statusCode >= 200 && headers.getValue("date") == null) {
			headers.addValue("date").setString(FastHttpDateFormat.getCurrentDate());
		}
	}

	@Override
	protected final void finishResponse() throws IOException {
		sendfileState = handler.processSendfile(sendfileData);
		if (!(sendfileState == SendfileState.PENDING)) {
			stream.getOutputBuffer().end();
		}
	}

	@Override
	protected final void ack() {
		if (!responseData.isCommitted() && requestData.hasExpectation()) {
			try {
				stream.writeAck();
			} catch (IOException ioe) {
				setErrorState(ErrorState.CLOSE_CONNECTION_NOW, ioe);
			}
		}
	}

	@Override
	protected final void flush() throws IOException {
		stream.getOutputBuffer().flush();
	}

	@Override
	protected final void setSwallowResponse() {
		// NO-OP
	}

	@Override
	public void processSocketEvent(SocketEvent event, boolean dispatch) {
		if (dispatch) {
			handler.processStreamOnContainerThread(this, event);
		} else {
			this.process(event);
		}
	}

	@Override
	protected final boolean isReadyForWrite() {
		return stream.isReadyForWrite();
	}

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
			processSocketEvent(dispatchType.getSocketStatus(), true);
		}
	}

	@Override
	protected final boolean isPushSupported() {
		return stream.isPushSupported();
	}

	@Override
	protected final void doPush(RequestData pushTarget) {
		try {
			stream.push(pushTarget);
		} catch (IOException ioe) {
			setErrorState(ErrorState.CLOSE_CONNECTION_NOW, ioe);
			responseData.setErrorException(ioe);
		}
	}

	@Override
	protected boolean isTrailerFieldsReady() {
		return stream.isTrailerFieldsReady();
	}

	@Override
	protected boolean isTrailerFieldsSupported() {
		return stream.isTrailerFieldsSupported();
	}

	@Override
	protected Object getConnectionID() {
		return stream.getConnectionId();
	}

	@Override
	protected Object getStreamID() {
		return stream.getIdentifier().toString();
	}

	@Override
	public final void recycle() {
		// StreamProcessor instances are not re-used.
		// Clear fields that can be cleared to aid GC and trigger NPEs if this
		// is reused
		setChannel(null);
	}

	@Override
	protected final Log getLog() {
		return log;
	}

	@Override
	public final void pause() {
		// NO-OP. Handled by the Http2UpgradeHandler
	}

	@Override
	public final SocketState service(Channel<?> socket) throws IOException {
		try {
			Request request = createRequest();
			Response response = createResponse();
			request.setResponse(response);
			getAdapter().service(request, response);
		} catch (Exception e) {
			if (log.isDebugEnabled()) {
				log.debug(sm.getString("streamProcessor.service.error"), e);
			}
			responseData.setStatus(500);
			setErrorState(ErrorState.CLOSE_NOW, e);
		}

		if (!isAsync()) {
			// If this is an async request then the request ends when it has
			// been completed. The AsyncContext is responsible for calling
			// endRequest() in that case.
			endRequest();
		}

		if (sendfileState == SendfileState.PENDING) {
			return SocketState.SENDFILE;
		} else if (getErrorState().isError()) {
			close();
			requestData.updateCounters();
			return SocketState.CLOSED;
		} else if (isAsync()) {
			return SocketState.LONG;
		} else {
			close();
			requestData.updateCounters();
			return SocketState.CLOSED;
		}
	}

	@Override
	protected final boolean flushBufferedWrite() throws IOException {
		if (log.isDebugEnabled()) {
			log.debug(sm.getString("streamProcessor.flushBufferedWrite.entry", stream.getConnectionId(),
					stream.getIdentifier()));
		}
		if (stream.flush(false)) {
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

	private void endRequest() throws IOException {
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
			handler.sendStreamReset(se);
		}
	}
}