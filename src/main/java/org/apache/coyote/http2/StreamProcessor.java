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
import java.nio.charset.StandardCharsets;
import java.security.AccessController;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.Iterator;
import java.util.Locale;

import org.apache.coyote.AbstractProcessor;
import org.apache.coyote.Adapter;
import org.apache.coyote.ContainerThreadMarker;
import org.apache.coyote.ErrorState;
import org.apache.coyote.ExchangeData;
import org.apache.coyote.RequestAction;
import org.apache.coyote.ResponseAction;
import org.apache.coyote.http2.HpackDecoder.HeaderEmitter;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.buf.ByteChunk;
import org.apache.tomcat.util.buf.MessageBytes;
import org.apache.tomcat.util.http.FastHttpDateFormat;
import org.apache.tomcat.util.http.MimeHeaders;
import org.apache.tomcat.util.http.parser.Host;
import org.apache.tomcat.util.net.Channel;
import org.apache.tomcat.util.net.DispatchType;
import org.apache.tomcat.util.net.Endpoint.Handler.SocketState;
import org.apache.tomcat.util.net.SendfileState;
import org.apache.tomcat.util.net.SocketEvent;
import org.apache.tomcat.util.res.StringManager;

class StreamProcessor extends AbstractProcessor implements HeaderEmitter {

	private static final int HEADER_STATE_START = 0;
	private static final int HEADER_STATE_PSEUDO = 1;
	private static final int HEADER_STATE_REGULAR = 2;
	private static final int HEADER_STATE_TRAILER = 3;

	private static final Log log = LogFactory.getLog(StreamProcessor.class);
	private static final StringManager sm = StringManager.getManager(StreamProcessor.class);

	private final Http2UpgradeHandler handler;
	private final StreamChannel stream;
	private int headerState = HEADER_STATE_START;
	private StreamException headerException = null;
	private StringBuilder cookieHeader = null;
	private boolean repeat = true;
	private volatile long contentLengthReceived = 0;

	StreamProcessor(Http2UpgradeHandler handler, StreamChannel stream, Adapter adapter) {
		this(handler, stream, adapter, new ExchangeData());
	}

	StreamProcessor(Http2UpgradeHandler handler, StreamChannel stream, Adapter adapter, ExchangeData exchangeData) {
		super(handler.getProtocol().getHttp11Protocol(), adapter, exchangeData);
		this.handler = handler;
		this.stream = stream;
		this.stream.setCurrentProcessor(this);
		this.exchangeData.setSendfile(handler.hasAsyncIO() && handler.getProtocol().getUseSendfile());
		// this.coyoteResponse.setOutputBuffer(http2OutputBuffer);
//		this.exchangeData.setResponseData(responseData);
		this.exchangeData.getProtocol().setString("HTTP/2.0");
		if (this.exchangeData.getStartTime() < 0) {
			this.exchangeData.setStartTime(System.currentTimeMillis());
		}
	}

	@Override
	protected RequestAction createRequestAction() {
		return new Http2InputBuffer(this);
	}

	@Override
	protected ResponseAction createResponseAction() {
		return new Http2OutputBuffer(this);
	}

	@Override
	protected void onChannelReady(Channel channel) {

	}

	@Override
	public final void emitHeader(String name, String value) throws HpackException {
		if (log.isDebugEnabled()) {
			log.debug(
					sm.getString("stream.header.debug", stream.getConnectionId(), stream.getIdentifier(), name, value));
		}

		// Header names must be lower case
		if (!name.toLowerCase(Locale.US).equals(name)) {
			throw new HpackException(
					sm.getString("stream.header.case", stream.getConnectionId(), stream.getIdentifier(), name));
		}

		if ("connection".equals(name)) {
			throw new HpackException(
					sm.getString("stream.header.connection", stream.getConnectionId(), stream.getIdentifier()));
		}

		if ("te".equals(name)) {
			if (!"trailers".equals(value)) {
				throw new HpackException(
						sm.getString("stream.header.te", stream.getConnectionId(), stream.getIdentifier(), value));
			}
		}

		if (headerException != null) {
			// Don't bother processing the header since the stream is going to
			// be reset anyway
			return;
		}

		if (name.length() == 0) {
			throw new HpackException(
					sm.getString("stream.header.empty", stream.getConnectionId(), stream.getIdentifier()));
		}

		boolean pseudoHeader = name.charAt(0) == ':';

		if (pseudoHeader && headerState != HEADER_STATE_PSEUDO) {
			headerException = new StreamException(sm.getString("stream.header.unexpectedPseudoHeader",
					stream.getConnectionId(), stream.getIdentifier(), name), Http2Error.PROTOCOL_ERROR,
					stream.getIdAsInt());
			// No need for further processing. The stream will be reset.
			return;
		}

		if (headerState == HEADER_STATE_PSEUDO && !pseudoHeader) {
			headerState = HEADER_STATE_REGULAR;
		}

		switch (name) {
		case ":method": {
			if (exchangeData.getMethod().isNull()) {
				exchangeData.getMethod().setString(value);
			} else {
				throw new HpackException(sm.getString("stream.header.duplicate", stream.getConnectionId(),
						stream.getIdentifier(), ":method"));
			}
			break;
		}
		case ":scheme": {
			if (exchangeData.getScheme().isNull()) {
				exchangeData.getScheme().setString(value);
			} else {
				throw new HpackException(sm.getString("stream.header.duplicate", stream.getConnectionId(),
						stream.getIdentifier(), ":scheme"));
			}
			break;
		}
		case ":path": {
			if (!exchangeData.getRequestURI().isNull()) {
				throw new HpackException(sm.getString("stream.header.duplicate", stream.getConnectionId(),
						stream.getIdentifier(), ":path"));
			}
			if (value.length() == 0) {
				throw new HpackException(
						sm.getString("stream.header.noPath", stream.getConnectionId(), stream.getIdentifier()));
			}
			int queryStart = value.indexOf('?');
			String uri;
			if (queryStart == -1) {
				uri = value;
			} else {
				uri = value.substring(0, queryStart);
				String query = value.substring(queryStart + 1);
				exchangeData.getQueryString().setString(query);
			}
			// Bug 61120. Set the URI as bytes rather than String so:
			// - any path parameters are correctly processed
			// - the normalization security checks are performed that prevent
			// directory traversal attacks
			byte[] uriBytes = uri.getBytes(StandardCharsets.ISO_8859_1);
			exchangeData.getRequestURI().setBytes(uriBytes, 0, uriBytes.length);
			break;
		}
		case ":authority": {
			if (exchangeData.getServerName().isNull()) {
				int i;
				try {
					i = Host.parse(value);
				} catch (IllegalArgumentException iae) {
					// Host value invalid
					throw new HpackException(sm.getString("stream.header.invalid", stream.getConnectionId(),
							stream.getIdentifier(), ":authority", value));
				}
				if (i > -1) {
					exchangeData.getServerName().setString(value.substring(0, i));
					exchangeData.setServerPort(Integer.parseInt(value.substring(i + 1)));
				} else {
					exchangeData.getServerName().setString(value);
				}
			} else {
				throw new HpackException(sm.getString("stream.header.duplicate", stream.getConnectionId(),
						stream.getIdentifier(), ":authority"));
			}
			break;
		}
		case "cookie": {
			// Cookie headers need to be concatenated into a single header
			// See RFC 7540 8.1.2.5
			if (cookieHeader == null) {
				cookieHeader = new StringBuilder();
			} else {
				cookieHeader.append("; ");
			}
			cookieHeader.append(value);
			break;
		}
		default: {
			if (headerState == HEADER_STATE_TRAILER && !handler.getProtocol().isTrailerHeaderAllowed(name)) {
				break;
			}
			if ("expect".equals(name) && "100-continue".equals(value)) {
				exchangeData.setExpectation(true);
			}
			if (pseudoHeader) {
				headerException = new StreamException(sm.getString("stream.header.unknownPseudoHeader",
						stream.getConnectionId(), stream.getIdentifier(), name), Http2Error.PROTOCOL_ERROR,
						stream.getIdAsInt());
			}

			if (headerState == HEADER_STATE_TRAILER) {
				// HTTP/2 headers are already always lower case
				exchangeData.getTrailerFields().put(name, value);
			} else {
				exchangeData.getRequestHeaders().addValue(name).setString(value);
			}
		}
		}
	}

	// @Override
	public void receivedStartOfHeadersInternal(boolean headersEndStream) throws ConnectionException {
		if (headerState == HEADER_STATE_START) {
			headerState = HEADER_STATE_PSEUDO;
			handler.getHpackDecoder().setMaxHeaderCount(handler.getProtocol().getMaxHeaderCount());
			handler.getHpackDecoder().setMaxHeaderSize(handler.getProtocol().getMaxHeaderSize());
		} else if (headerState == HEADER_STATE_PSEUDO || headerState == HEADER_STATE_REGULAR) {
			// Trailer headers MUST include the end of stream flag
			if (headersEndStream) {
				headerState = HEADER_STATE_TRAILER;
				handler.getHpackDecoder().setMaxHeaderCount(handler.getProtocol().getMaxTrailerCount());
				handler.getHpackDecoder().setMaxHeaderSize(handler.getProtocol().getMaxTrailerSize());
			} else {
				throw new ConnectionException(sm.getString("stream.trailerHeader.noEndOfStream",
						stream.getConnectionId(), stream.getIdentifier()), Http2Error.PROTOCOL_ERROR);
			}
		}
	}

	// @Override
	public boolean receivedEndOfHeadersInternal() throws ConnectionException {
		if (exchangeData.getMethod().isNull() || exchangeData.getScheme().isNull()
				|| exchangeData.getRequestURI().isNull()) {
			throw new ConnectionException(
					sm.getString("stream.header.required", stream.getConnectionId(), stream.getIdentifier()),
					Http2Error.PROTOCOL_ERROR);
		}
		// Cookie headers need to be concatenated into a single header
		// See RFC 7540 8.1.2.5
		// Can only do this once the headers are fully received
		if (cookieHeader != null) {
			exchangeData.getRequestHeaders().addValue("cookie").setString(cookieHeader.toString());
		}
		return headerState == HEADER_STATE_REGULAR || headerState == HEADER_STATE_PSEUDO;
	}

	@Override
	public void setHeaderException(StreamException streamException) {
		if (headerException == null) {
			headerException = streamException;
		}
	}

	@Override
	public void validateHeaders() throws StreamException {
		if (headerException == null) {
			return;
		}

		throw headerException;
	}

//	@Override
	protected void receivedDataInternal(int payloadSize) throws ConnectionException {
		contentLengthReceived += payloadSize;
		long contentLengthHeader = exchangeData.getRequestContentLengthLong();
		if (contentLengthHeader > -1 && contentLengthReceived > contentLengthHeader) {
			throw new ConnectionException(
					sm.getString("stream.header.contentLength", stream.getConnectionId(), stream.getIdentifier(),
							Long.valueOf(contentLengthHeader), Long.valueOf(contentLengthReceived)),
					Http2Error.PROTOCOL_ERROR);
		}
	}

//	@Override
	protected void receivedEndOfStreamInternal() throws ConnectionException {
//		System.out.println(
//		"conn(" + getConnectionId() + ") " + "stream(" + getIdentifier() + ")" + " receivedEndOfStream");
		if (isContentLengthInconsistent()) {
			throw new ConnectionException(sm.getString("stream.header.contentLength", stream.getConnectionId(),
					stream.getIdentifier(), Long.valueOf(exchangeData.getRequestContentLengthLong()),
					Long.valueOf(contentLengthReceived)), Http2Error.PROTOCOL_ERROR);
		}
	}

	final boolean isContentLengthInconsistent() {
		long contentLengthHeader = exchangeData.getRequestContentLengthLong();
		if (contentLengthHeader > -1 && contentLengthReceived != contentLengthHeader) {
			return true;
		}
		return false;
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
		responseAction.addActiveFilter(id);
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
	protected boolean isHttp2Preface() {
		return false;
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
	public void prepareRequest() {
		if (exchangeData.getServerName().isNull()) {
			MessageBytes hostValueMB = exchangeData.getRequestHeaders().getUniqueValue("host");
			if (hostValueMB == null) {
				throw new IllegalArgumentException();
			}
			// This processing expects bytes. Server push will have used a String
			// to trigger a conversion if required.
			hostValueMB.toBytes();
			ByteChunk valueBC = hostValueMB.getByteChunk();
			byte[] valueB = valueBC.getBytes();
			int valueL = valueBC.getLength();
			int valueS = valueBC.getStart();

			int colonPos = Host.parse(hostValueMB);
			if (colonPos != -1) {
				int port = 0;
				for (int i = colonPos + 1; i < valueL; i++) {
					char c = (char) valueB[i + valueS];
					if (c < '0' || c > '9') {
						throw new IllegalArgumentException();
					}
					port = port * 10 + c - '0';
				}
				exchangeData.setServerPort(port);

				// Only need to copy the host name up to the :
				valueL = colonPos;
			}

			// Extract the host name
			char[] hostNameC = new char[valueL];
			for (int i = 0; i < valueL; i++) {
				hostNameC[i] = (char) valueB[i + valueS];
			}
			exchangeData.getServerName().setChars(hostNameC, 0, valueL);
		}
	}

	@Override
	protected void resetSocketReadTimeout() {

	}

	@Override
	protected SendfileState processSendfile() throws IOException {
//		openSocket = inputBuffer.keepAlive;
		// Done is equivalent to sendfile not being used
		SendfileState result = SendfileState.DONE;
		SendfileData sendfileData = ((Http2OutputBuffer) responseAction).getSendfileData();
		((Http2OutputBuffer) responseAction).setSendfileData(null);
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
	protected final SocketState dispatchFinishActions() throws IOException {
		finishActions();
		return SocketState.CLOSED;
	}

	@Override
	protected void finishActions() throws IOException {
		if (!stream.isInputFinished() && getErrorState().isIoAllowed()) {
			if (handler.hasAsyncIO() && !isContentLengthInconsistent()) {
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
		responseAction.finish();
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
		requestAction.recycle();
		responseAction.recycle();
	}

	@Override
	protected void recycleInternal() {
		// StreamProcessor instances are not re-used.
		// Clear fields that can be cleared to aid GC and trigger NPEs if this
		// is reused
		// super.recycle();
		requestAction.recycle();
		responseAction.recycle();
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
