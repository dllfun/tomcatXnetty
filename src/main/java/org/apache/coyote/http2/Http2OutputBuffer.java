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

import org.apache.coyote.ErrorState;
import org.apache.coyote.ResponseAction;
import org.apache.coyote.ResponseData;
import org.apache.coyote.http11.HttpOutputBuffer;
import org.apache.coyote.http11.OutputFilter;
import org.apache.coyote.http2.Stream.StreamOutputBuffer;
import org.apache.tomcat.util.net.SendfileState;

public class Http2OutputBuffer extends ResponseAction {

	private Stream stream;
	private final ResponseData responseData;
	private HttpOutputBuffer next;
	private StreamProcessor processor;
	private SendfileData sendfileData = null;
	private SendfileState sendfileState = null;

	public Http2OutputBuffer(StreamProcessor processor, Stream stream, ResponseData responseData,
			StreamOutputBuffer streamOutputBuffer) {
		super(processor);
		this.processor = processor;
		this.stream = stream;
		this.responseData = responseData;
		this.next = streamOutputBuffer;
	}

	public SendfileState getSendfileState() {
		return sendfileState;
	}

	/**
	 * Add a filter at the start of the existing processing chain. Subsequent calls
	 * to the {@link HttpOutputBuffer} methods of this object will be passed to the
	 * filter. If appropriate, the filter will then call the same method on the next
	 * HttpOutputBuffer in the chain until the call reaches the StreamOutputBuffer.
	 *
	 * @param filter The filter to add to the start of the processing chain
	 */
	public void addFilter(OutputFilter filter) {
		filter.setBuffer(next);
		next = filter;
	}

	@Override
	public int doWrite(ByteBuffer chunk) throws IOException {
		// if (!coyoteResponse.isCommitted()) {
		// stream.getHook().actionCOMMIT();
		// coyoteResponse.setCommitted(true);
		// }
		return next.doWrite(chunk);
	}

	@Override
	public long getBytesWritten() {
		return next.getBytesWritten();
	}

	@Override
	public boolean isTrailerFieldsSupported() {
		return stream.isTrailerFieldsSupported();
	}

	@Override
	public final boolean isReadyForWrite() {
		return stream.isReadyForWrite();
	}

	@Override
	public final void prepareResponse() throws IOException {
		responseData.setCommitted(true);
		if (processor.getHandler().hasAsyncIO() && processor.getHandler().getProtocol().getUseSendfile()) {
			prepareSendfile();
		}
		StreamProcessor.prepareHeaders(responseData.getRequestData(), responseData, sendfileData == null,
				processor.getHandler().getProtocol(), stream);
		stream.writeHeaders();
	}

	@Override
	public final void finishResponse() throws IOException {
		sendfileState = processor.getHandler().processSendfile(sendfileData);
		if (!(sendfileState == SendfileState.PENDING)) {
			this.end();
		}
	}

	@Override
	public void commit() {
		if (!responseData.isCommitted()) {
			try {
				// Validate and write response headers
				prepareResponse();
			} catch (IOException e) {
				processor.handleIOException(e);
			}
		}
	}

	@Override
	public void close() {
		commit();
		try {
			finishResponse();
		} catch (IOException e) {
			processor.handleIOException(e);
		}
	}

	@Override
	public void sendAck() {
		ack();
	}

	// @Override
	protected final void ack() {
		if (!responseData.isCommitted() && responseData.getRequestData().hasExpectation()) {
			try {
				stream.writeAck();
			} catch (IOException ioe) {
				processor.setErrorState(ErrorState.CLOSE_CONNECTION_NOW, ioe);
			}
		}
	}

	@Override
	public void clientFlush() {
		commit();
		try {
			flush();
		} catch (IOException e) {
			processor.handleIOException(e);
			responseData.setErrorException(e);
		}
	}

	protected void prepareSendfile() {
		String fileName = (String) stream.getRequestData()
				.getAttribute(org.apache.coyote.Constants.SENDFILE_FILENAME_ATTR);
		if (fileName != null) {
			sendfileData = new SendfileData();
			sendfileData.path = new File(fileName).toPath();
			sendfileData.pos = ((Long) stream.getRequestData()
					.getAttribute(org.apache.coyote.Constants.SENDFILE_FILE_START_ATTR)).longValue();
			sendfileData.end = ((Long) stream.getRequestData()
					.getAttribute(org.apache.coyote.Constants.SENDFILE_FILE_END_ATTR)).longValue();
			sendfileData.left = sendfileData.end - sendfileData.pos;
			sendfileData.stream = stream;
			sendfileData.outputBuffer = this;
		}
	}

	@Override
	public final void setSwallowResponse() {
		// NO-OP
	}

	// @Override
	// protected final void flush() throws IOException {
	// stream.getOutputBuffer().flush();
	// }

	// @Override
	public void end() throws IOException {
		next.end();
	}

	// @Override
	public void flush() throws IOException {
		next.flush();
	}
}
