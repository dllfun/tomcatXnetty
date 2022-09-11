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
import java.util.Iterator;

import org.apache.coyote.CloseNowException;
import org.apache.coyote.DispatchHandler.ConcurrencyControlled;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.net.AbstractLogicChannel;
import org.apache.tomcat.util.net.Channel;
import org.apache.tomcat.util.net.SSLSupport;
import org.apache.tomcat.util.net.SocketChannel;
import org.apache.tomcat.util.net.SocketEvent;
import org.apache.tomcat.util.res.StringManager;

public abstract class Stream extends AbstractStream implements AbstractLogicChannel, ConcurrencyControlled {

	protected static final Log log = LogFactory.getLog(Stream.class);
	protected static final StringManager sm = StringManager.getManager(Stream.class);

	private volatile int weight = Constants.DEFAULT_WEIGHT;

	protected final Http2UpgradeHandler handler;
	protected final StreamStateMachine state;
	protected final WindowAllocationManager allocationManager = new WindowAllocationManager(this);

//	private final ResponseData responseData = new ResponseData();
//	private final StreamInputBuffer streamInputBuffer;
//	protected final StreamOutputBuffer streamOutputBuffer = new StreamOutputBuffer();

//	Stream(Integer identifier, Http2UpgradeHandler handler) {
//		this(identifier, handler, null);
//	}

	Stream(Integer identifier, Http2UpgradeHandler handler) {
		super(identifier);
		// this.channel = channel;
		this.handler = handler;
		handler.getZero().addChild(this);
		setWindowSize(handler.getRemoteSettings().getInitialWindowSize());
		state = new StreamStateMachine(this);

		System.out.println("conn(" + getConnectionId() + ") " + "stream(" + getIdentifier() + ")" + " created");
	}

	@Override
	public Object getLock() {
		return getCurrentProcessor();
	}

	@Override
	public SSLSupport initSslSupport(String clientCertProvider) {
		return handler.getChannel().getSslSupport();
	}

	public Http2UpgradeHandler getHandler() {
		return handler;
	}

	final void rePrioritise(AbstractStream parent, boolean exclusive, int weight) {
		if (log.isDebugEnabled()) {
			log.debug(sm.getString("stream.reprioritisation.debug", getConnectionId(), getIdentifier(),
					Boolean.toString(exclusive), parent.getIdentifier(), Integer.toString(weight)));
		}

		// Check if new parent is a descendant of this stream
		if (isDescendant(parent)) {
			parent.detachFromParent();
			// Cast is always safe since any descendant of this stream must be
			// an instance of Stream
			getParentStream().addChild((Stream) parent);
		}

		if (exclusive) {
			// Need to move children of the new parent to be children of this
			// stream. Slightly convoluted to avoid concurrent modification.
			Iterator<Stream> parentsChildren = parent.getChildStreams().iterator();
			while (parentsChildren.hasNext()) {
				Stream parentsChild = parentsChildren.next();
				parentsChildren.remove();
				this.addChild(parentsChild);
			}
		}
		detachFromParent();
		parent.addChild(this);
		this.weight = weight;
	}

	/*
	 * Used when removing closed streams from the tree and we know there is no need
	 * to check for circular references.
	 */
	final void rePrioritise(AbstractStream parent, int weight) {
		if (log.isDebugEnabled()) {
			log.debug(sm.getString("stream.reprioritisation.debug", getConnectionId(), getIdentifier(), Boolean.FALSE,
					parent.getIdentifier(), Integer.toString(weight)));
		}

		parent.addChild(this);
		this.weight = weight;
	}

	final void receiveReset(long errorCode) {
		if (log.isDebugEnabled()) {
			log.debug(
					sm.getString("stream.reset.receive", getConnectionId(), getIdentifier(), Long.toString(errorCode)));
		}
		// Set the new state first since read and write both check this
		state.receivedReset();
		// Reads wait internally so need to call a method to break the wait()
		receiveResetInternal(errorCode);
		cancelAllocationRequests();
	}

	protected abstract void receiveResetInternal(long errorCode);

	final void cancelAllocationRequests() {
		allocationManager.notifyAny();
	}

	final void checkState(FrameType frameType) throws Http2Exception {
		state.checkFrameType(frameType);
	}

	@Override
	final synchronized void incrementWindowSize(int windowSizeIncrement) throws Http2Exception {
		// If this is zero then any thread that has been trying to write for
		// this stream will be waiting. Notify that thread it can continue. Use
		// notify all even though only one thread is waiting to be on the safe
		// side.
		boolean notify = getWindowSize() < 1;
		super.incrementWindowSize(windowSizeIncrement);
		if (notify && getWindowSize() > 0) {
			allocationManager.notifyStream();
		}
	}

	public final synchronized int reserveWindowSize(int reservation, boolean block) throws IOException {
		long windowSize = getWindowSize();
		while (windowSize < 1) {
			if (!canWrite()) {
				throw new CloseNowException(sm.getString("stream.notWritable", getConnectionId(), getIdentifier()));
			}
			if (block) {
				try {
					long writeTimeout = handler.getProtocol().getStreamWriteTimeout();
					allocationManager.waitForStream(writeTimeout);
					windowSize = getWindowSize();
					if (windowSize == 0) {
						doStreamCancel(sm.getString("stream.writeTimeout"), Http2Error.ENHANCE_YOUR_CALM);
					}
				} catch (InterruptedException e) {
					// Possible shutdown / rst or similar. Use an IOException to
					// signal to the client that further I/O isn't possible for this
					// Stream.
					throw new IOException(e);
				}
			} else {
				allocationManager.waitForStreamNonBlocking();
				return 0;
			}
		}
		int allocation;
		if (windowSize < reservation) {
			allocation = (int) windowSize;
		} else {
			allocation = reservation;
		}
		decrementWindowSize(allocation);
		return allocation;
	}

	void doStreamCancel(String msg, Http2Error error) throws CloseNowException {
		StreamException se = new StreamException(msg, error, getIdAsInt());
		// Prevent the application making further writes
		cancelStreamInternal(se);
		// Trigger a reset once control returns to Tomcat
		throw new CloseNowException(msg, se);
	}

	protected abstract void cancelStreamInternal(StreamException se);

//	void doWriteTimeout() throws CloseNowException {
//		String msg = sm.getString("stream.writeTimeout");
//		StreamException se = new StreamException(msg, Http2Error.ENHANCE_YOUR_CALM, getIdAsInt());
	// Prevent the application making further writes
//		streamOutputBuffer.closed = true;
	// Prevent Tomcat's error handling trying to write
//		exchangeData.setError();
//		exchangeData.setErrorReported();
	// Trigger a reset once control returns to Tomcat
//		streamOutputBuffer.reset = se;
//		throw new CloseNowException(msg, se);
//	}

	void waitForConnectionAllocation(long timeout) throws InterruptedException {
		allocationManager.waitForConnection(timeout);
	}

	void waitForConnectionAllocationNonBlocking() {
		allocationManager.waitForConnectionNonBlocking();
	}

	void notifyConnection() {
		allocationManager.notifyConnection();
	}

	@Override
	public final String getConnectionId() {
		return handler.getZero().getConnectionId();
	}

	@Override
	public SocketChannel getSocketChannel() {
		return handler.getChannel();
	}

	@Override
	final int getWeight() {
		return weight;
	}

//	final ResponseData getResponseData() {
//		return responseData;
//	}

	final void receivedStartOfHeaders(boolean headersEndStream) throws Http2Exception {
		receivedStartOfHeadersInternal(headersEndStream);
		// Parser will catch attempt to send a headers frame after the stream
		// has closed.
		state.receivedStartOfHeaders();
	}

	protected abstract void receivedStartOfHeadersInternal(boolean headersEndStream) throws ConnectionException;

	final boolean receivedEndOfHeaders() throws ConnectionException {
		return receivedEndOfHeadersInternal();
	}

	protected abstract boolean receivedEndOfHeadersInternal() throws ConnectionException;

	final void receivedData(int payloadSize) throws ConnectionException {
		receivedDataInternal(payloadSize);
	}

	protected abstract void receivedDataInternal(int payloadSize) throws ConnectionException;

	final void receivedEndOfStream() throws ConnectionException {
		receivedEndOfStreamInternal();
		state.receivedEndOfStream();
		notifyEof();
	}

	protected abstract void receivedEndOfStreamInternal() throws ConnectionException;

	protected abstract void notifyEof();

	final void sentHeaders() {
		state.sentHeaders();
	}

	final void sentEndOfStream() {
		endOfStreamSent();
		state.sentEndOfStream();
	}

	protected abstract void endOfStreamSent();

	final void sentPushPromise() {
		state.sentPushPromise();
	}

	final boolean isActive() {
		return state.isActive();
	}

	public final boolean canWrite() {
		return state.canWrite();
	}

	@Override
	public boolean isClosed() {
		return !state.isActive();
	}

	final boolean isClosedFinal() {
		return state.isClosedFinal();
	}

	final void closeIfIdle() {
		state.closeIfIdle();
	}

	final boolean isInputFinished() {
		return !state.isFrameTypePermitted(FrameType.DATA);
	}

	@Override
	public void close() {
		Throwable t = getCloseException();
		if (t == null) {
			t = new StreamException("force close", Http2Error.INTERNAL_ERROR, this.getIdentifier());
		}
		close(t);
	}

	@Override
	public void close(Throwable e) {
		this.close(new StreamException(e.getMessage(), Http2Error.INTERNAL_ERROR, this.getIdentifier()));
	}

	final void close(Http2Exception http2Exception) {
		System.out.println("conn(" + getConnectionId() + ") " + "stream(" + getIdentifier() + ") closed");
		if (http2Exception instanceof StreamException) {
			try {
				StreamException se = (StreamException) http2Exception;
				if (log.isDebugEnabled()) {
					log.debug(sm.getString("stream.reset.send", getConnectionId(), getIdentifier(), se.getError()));
				}
				state.sendReset();
				handler.getWriter().writeStreamReset(se);
				cancelAllocationRequests();
				closeInternal();
			} catch (IOException ioe) {
				ConnectionException ce = new ConnectionException(sm.getString("stream.reset.fail"),
						Http2Error.PROTOCOL_ERROR);
				ce.initCause(ioe);
				handler.closeConnection(ce);
			}
		} else {
			handler.closeConnection(http2Exception);
		}
	}

	protected abstract void closeInternal() throws IOException;

	boolean isTrailerFieldsReady() {
		// Once EndOfStream has been received, canRead will be false
		return !state.canRead();
	}

	@Override
	public boolean checkPassOrFail(Channel channel, SocketEvent event) {
		return handler.controlled.checkPassOrFail(channel, event);
	}

	@Override
	public void released(Channel channel) {
		handler.controlled.released(channel);
	}

	@Override
	public Object getConnectionID() {
		return this.getConnectionId();
	}

	@Override
	public Object getStreamID() {
		return this.getIdentifier().toString();
	}

//	class StreamOutputBuffer implements HttpOutputBuffer {

//		private StreamOutputBuffer() {

//		}

//	}

//	class StreamInputBuffer implements InputReader, ByteBufferHandler {

	// @Override
	/*
	 * public final int doRead(PreInputBuffer applicationBufferHandler) throws
	 * IOException {
	 * 
	 * ensureBuffersExist();
	 * 
	 * int written = -1;
	 * 
	 * // Ensure that only one thread accesses inBuffer at a time synchronized
	 * (inBuffer) { boolean canRead = false; while (inBuffer.position() == 0 &&
	 * (canRead = isActive() && !isInputFinished())) { // Need to block until some
	 * data is written try { if (log.isDebugEnabled()) {
	 * log.debug(sm.getString("stream.inputBuffer.empty")); }
	 * 
	 * long readTimeout = handler.getProtocol().getStreamReadTimeout(); if
	 * (readTimeout < 0) { inBuffer.wait(); } else { inBuffer.wait(readTimeout); }
	 * 
	 * if (resetReceived) { throw new
	 * IOException(sm.getString("stream.inputBuffer.reset")); }
	 * 
	 * if (inBuffer.position() == 0 && isActive() && !isInputFinished()) { String
	 * msg = sm.getString("stream.inputBuffer.readTimeout"); StreamException se =
	 * new StreamException(msg, Http2Error.ENHANCE_YOUR_CALM, getIdAsInt()); //
	 * Trigger a reset once control returns to Tomcat responseData.setError();
	 * streamOutputBuffer.reset = se; throw new CloseNowException(msg, se); } }
	 * catch (InterruptedException e) { // Possible shutdown / rst or similar. Use
	 * an // IOException to signal to the client that further I/O // isn't possible
	 * for this Stream. throw new IOException(e); } }
	 * 
	 * if (inBuffer.position() > 0) { // Data is available in the inBuffer. Copy it
	 * to the // outBuffer. inBuffer.flip(); written = inBuffer.remaining(); if
	 * (log.isDebugEnabled()) { log.debug(sm.getString("stream.inputBuffer.copy",
	 * Integer.toString(written))); } inBuffer.get(outBuffer, 0, written);
	 * inBuffer.clear(); } else if (!canRead) { return -1; } else { // Should never
	 * happen throw new IllegalStateException(); } }
	 * 
	 * applicationBufferHandler.setBufWrapper(ByteBufferWrapper.wrapper(ByteBuffer.
	 * wrap(outBuffer, 0, written)));
	 * 
	 * // Increment client-side flow control windows by the number of bytes // read
	 * handler.writeWindowUpdate(Stream.this, written, true);
	 * 
	 * return written; }
	 */

//	}

}
