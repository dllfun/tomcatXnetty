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
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Map;
import java.util.function.Supplier;

import org.apache.coyote.AbstractProcessor;
import org.apache.coyote.CloseNowException;
import org.apache.coyote.ErrorState;
import org.apache.coyote.ExchangeData;
import org.apache.coyote.ResponseAction;
import org.apache.coyote.http11.Constants;
import org.apache.coyote.http11.filters.GzipOutputFilter;
import org.apache.coyote.http2.filters.BufferedOutputFilter;
import org.apache.coyote.http2.filters.FlowCtrlOutputFilter;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.buf.MessageBytes;
import org.apache.tomcat.util.http.MimeHeaders;
import org.apache.tomcat.util.res.StringManager;

public class Http2OutputBuffer extends ResponseAction {

	protected static final Log log = LogFactory.getLog(Stream.class);
	protected static final StringManager sm = StringManager.getManager(Stream.class);

	private static final MimeHeaders ACK_HEADERS;

	static {
		ExchangeData exchangeData = new ExchangeData();
		exchangeData.setStatus(100);
		StreamProcessor.prepareHeaders(exchangeData, true, null, null);
		ACK_HEADERS = exchangeData.getResponseHeaders();
	}

	private final ExchangeData exchangeData;
	private final AbstractProcessor processor;
	private SendfileData sendfileData = null;
	// Flag that indicates that data was left over on a previous
	// non-blocking write. Once set, this flag stays set until all the data
	// has been written.
	private volatile long written = 0;

	public Http2OutputBuffer(AbstractProcessor processor) {
		super(processor);
		this.processor = processor;
//		this.stream = stream;
		this.exchangeData = processor.getExchangeData();
//		this.streamOutputBuffer = stream.getOutputBuffer();
		addFilter(new GzipOutputFilter(processor));
		addFilter(new FlowCtrlOutputFilter(processor));
		addFilter(new BufferedOutputFilter(processor));
	}

	public SendfileData getSendfileData() {
		return sendfileData;
	}

	public void setSendfileData(SendfileData sendfileData) {
		this.sendfileData = sendfileData;
	}

	@Override
	public boolean isTrailerFieldsSupported() {
		return !((StreamChannel) processor.getChannel()).isOutputClosed();
	}

	@Override
	public final boolean isReadyForWrite() {
		BufferedOutputFilter bufferedOutputFilter = (BufferedOutputFilter) getActiveFilter(
				Constants.BUFFEREDOUTPUT_FILTER);
		if (bufferedOutputFilter != null) {
			if (bufferedOutputFilter.isDataLeft()) {
				return false;
			}
		}
		return ((StreamChannel) processor.getChannel()).isReadyForWrite();
	}

	@Override
	public final void prepareResponse(boolean finished) throws IOException {
		if (((StreamChannel) processor.getChannel()).getHandler().hasAsyncIO()
				&& ((StreamChannel) processor.getChannel()).getHandler().getProtocol().getUseSendfile()) {
			prepareSendfile();
		}
		StreamProcessor.prepareHeaders(exchangeData, sendfileData == null,
				((StreamChannel) processor.getChannel()).getHandler().getProtocol(),
				((StreamChannel) processor.getChannel()));
		writeHeaders(finished);

	}

	private void writeHeaders(boolean finished) throws IOException {
		if (finished) {
			System.err.println("writeHeaders and finished");
		}
		boolean endOfStream = hasNoBody() && exchangeData.getTrailerFieldsSupplier() == null;
		if (endOfStream) {
			System.err.println("has bug, never happen");
		}
		((StreamChannel) processor.getChannel()).doWriteHeader(exchangeData.getResponseHeaders(),
				finished && sendfileData == null);
	}

	@Override
	public final void finishResponse() throws IOException {
//		sendfileState = stream.getHandler().processSendfile(sendfileData);
		if (sendfileData == null) {// !(sendfileState == SendfileState.PENDING)
			this.end();
		}
	}

	// @Override
	protected final void ack() {
		if (!exchangeData.isCommitted() && exchangeData.hasExpectation()) {
			try {
				writeAck();
			} catch (IOException ioe) {
				processor.setErrorState(ErrorState.CLOSE_CONNECTION_NOW, ioe);
			}
		}
	}

	final void writeAck() throws IOException {
		((StreamChannel) processor.getChannel()).doWriteHeader(ACK_HEADERS, false);
	}

	protected void prepareSendfile() {
		String fileName = (String) processor.getExchangeData()
				.getAttribute(org.apache.coyote.Constants.SENDFILE_FILENAME_ATTR);
		if (fileName != null) {
			sendfileData = new SendfileData();
			sendfileData.setPath(new File(fileName).toPath());
			sendfileData.setPos(((Long) processor.getExchangeData()
					.getAttribute(org.apache.coyote.Constants.SENDFILE_FILE_START_ATTR)).longValue());
			sendfileData.setEnd(((Long) processor.getExchangeData()
					.getAttribute(org.apache.coyote.Constants.SENDFILE_FILE_END_ATTR)).longValue());
			sendfileData.setLeft(sendfileData.getEnd() - sendfileData.getPos());
			sendfileData.setStream(((StreamChannel) processor.getChannel()));
			// sendfileData.outputBuffer = this;
		}
	}

	@Override
	public final void setSwallowResponse() {
		// NO-OP
	}

	@Override
	protected int doWriteToChannel(ByteBuffer chunk) throws IOException {
		long contentLength = exchangeData.getResponseContentLengthLong();
		int result = chunk.remaining();
		written += result;
		boolean finished = contentLength != -1 && contentLength == written
				&& exchangeData.getTrailerFieldsSupplier() == null && sendfileData == null;
		((StreamChannel) processor.getChannel()).doWriteBody(chunk, finished);
		return result;
	}

	@Override
	protected long getBytesWrittenToChannel() {
		return written;
	}

	@Override
	protected void flushToChannel() throws IOException {
		// do nothing
	}

	@Override
	protected boolean flushToChannel(boolean block) throws IOException {
		return false;
	}

	@Override
	public final boolean flushBufferedWrite() throws IOException {
		if (log.isDebugEnabled()) {
			log.debug(sm.getString("streamProcessor.flushBufferedWrite.entry",
					((StreamChannel) processor.getChannel()).getConnectionId(),
					((StreamChannel) processor.getChannel()).getIdentifier()));
		}
		// TODO check
		if (flush(false)) {//
			// The buffer wasn't fully flushed so re-register the
			// stream for write. Note this does not go via the
			// Response since the write registration state at
			// that level should remain unchanged. Once the buffer
			// has been emptied then the code below will call
			// dispatch() which will enable the
			// Response to respond to this event.
			if (((StreamChannel) processor.getChannel()).isReadyForWrite()) {
				// Unexpected
				throw new IllegalStateException();
			}
			return true;
		}
		return false;
	}

	@Override
	protected void endToChannel() throws IOException {
		if (((StreamChannel) processor.getChannel()).getResetException() != null) {
			throw new CloseNowException(((StreamChannel) processor.getChannel()).getResetException());
		}
		if (!((StreamChannel) processor.getChannel()).isOutputClosed()) {
			// ((StreamChannel) processor.getChannel()).setOutputClosed(true);
			// flush(true);
			if (!((StreamChannel) processor.getChannel()).isOutputClosed()) {
				// Handling this special case here is simpler than trying
				// to modify the following code to handle it.
				System.err.println("write empty body for finish");
				((StreamChannel) processor.getChannel()).doWriteBody(ByteBuffer.allocate(0),
						exchangeData.getTrailerFieldsSupplier() == null);
			}
			writeTrailers();
		}
	}

	private final void writeTrailers() throws IOException {
		Supplier<Map<String, String>> supplier = exchangeData.getTrailerFieldsSupplier();
		if (supplier == null) {
			// No supplier was set, end of stream will already have been sent
			return;
		}

		// We can re-use the MimeHeaders from the response since they have
		// already been processed by the encoder at this point
		MimeHeaders mimeHeaders = exchangeData.getResponseHeaders();
		mimeHeaders.recycle();

		Map<String, String> headerMap = supplier.get();
		if (headerMap == null) {
			headerMap = Collections.emptyMap();
		}

		// Copy the contents of the Map to the MimeHeaders
		// TODO: Is there benefit in refactoring this? Is MimeHeaders too
		// heavyweight? Can we reduce the copy/conversions?
		for (Map.Entry<String, String> headerEntry : headerMap.entrySet()) {
			MessageBytes mb = mimeHeaders.addValue(headerEntry.getKey());
			mb.setString(headerEntry.getValue());
		}

		((StreamChannel) processor.getChannel()).doWriteHeader(mimeHeaders, true);
	}

	/**
	 * @return <code>true</code> if it is certain that the associated response has
	 *         no body.
	 */
	final boolean hasNoBody() {
		return ((written == 0) && ((StreamChannel) processor.getChannel()).isOutputClosed());
	}

	@Override
	public void recycle() {

	}

}
