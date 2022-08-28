package org.apache.coyote.http2;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.apache.coyote.ResponseData;
import org.apache.coyote.http11.Constants;
import org.apache.coyote.http11.HttpOutputBuffer;
import org.apache.coyote.http11.OutputFilter;

public class FlowCtrlOutputFilter implements OutputFilter {

	private int streamReservation = 0;

	private Stream stream;

	private Http2UpgradeHandler handler;

	private ResponseData responseData;

	/**
	 * Next buffer in the pipeline.
	 */
	private HttpOutputBuffer buffer;

	public FlowCtrlOutputFilter(Stream stream, Http2UpgradeHandler handler) {
		super();
		this.stream = stream;
		this.handler = handler;
	}

	public void setStream(Stream stream) {
		this.stream = stream;
	}

	public void setHandler(Http2UpgradeHandler handler) {
		this.handler = handler;
	}

	@Override
	public void end() throws IOException {
		buffer.end();
	}

	@Override
	public void flush() throws IOException {
		buffer.flush();
	}

	@Override
	public boolean flush(boolean block) throws IOException {
		return buffer.flush(block);
	}

	@Override
	public int doWrite(ByteBuffer chunk) throws IOException {

		boolean block = responseData.getRequestData().getAsyncStateMachine().getWriteListener() == null;
		int written = 0;
		int left = chunk.remaining();
		while (left > 0) {
			if (streamReservation == 0) {
				streamReservation = stream.reserveWindowSize(left, block);
				if (streamReservation == 0) {
					// Must be non-blocking.
					// Note: Can't add to the writeBuffer here as the write
					// may originate from the writeBuffer.
					// chunk.compact();
					return written;
				}
			}
			while (streamReservation > 0) {
				int connectionReservation = handler.reserveWindowSize(stream, streamReservation, block);
				if (connectionReservation == 0) {
					// Must be non-blocking.
					// Note: Can't add to the writeBuffer here as the write
					// may originate from the writeBuffer.
					return written;
				}

				int orgLimit = chunk.limit();
				chunk.limit(chunk.position() + connectionReservation);
				// Do the write
				int len = buffer.doWrite(chunk);
				written += len;
				chunk.limit(orgLimit);
				streamReservation -= connectionReservation;
				left -= connectionReservation;
			}
		}
		return written;
	}

	@Override
	public long getBytesWritten() {
		return buffer.getBytesWritten();
	}

	@Override
	public int getId() {
		return Constants.FLOWCTRL_FILTER;
	}

	@Override
	public void setResponse(ResponseData response) {
		this.responseData = response;
	}

	@Override
	public void recycle() {
		streamReservation = 0;
	}

	@Override
	public void setBuffer(HttpOutputBuffer buffer) {
		this.buffer = buffer;
	}

}
