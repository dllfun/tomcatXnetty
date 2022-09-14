package org.apache.coyote.http2.filters;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.apache.coyote.AbstractProcessor;
import org.apache.coyote.ProcessorComponent;
import org.apache.coyote.http11.Constants;
import org.apache.coyote.http11.HttpOutputBuffer;
import org.apache.coyote.http11.OutputFilter;
import org.apache.coyote.http2.Http2UpgradeHandler;
import org.apache.coyote.http2.Stream;
import org.apache.coyote.http2.StreamChannel;

public class FlowCtrlOutputFilter extends ProcessorComponent implements OutputFilter {

	private int streamReservation = 0;

//	private Stream stream;

//	private Http2UpgradeHandler handler;

//	private ExchangeData exchangeData;

	/**
	 * Next buffer in the pipeline.
	 */
	private HttpOutputBuffer next;

	public FlowCtrlOutputFilter(AbstractProcessor processor) {
		super(processor);
//		this.stream = stream;
//		this.handler = handler;
	}

	@Override
	public int getId() {
		return Constants.FLOWCTRL_FILTER;
	}

	@Override
	public void actived() {

	}

	@Override
	public void end() throws IOException {
		next.end();
	}

	@Override
	public void flush() throws IOException {
		next.flush();
	}

	@Override
	public boolean flush(boolean block) throws IOException {
		return next.flush(block);
	}

	@Override
	public int doWrite(ByteBuffer chunk) throws IOException {

		boolean block = processor.isBlockingWrite();
		int written = 0;
		int left = chunk.remaining();
		while (left > 0) {
			if (streamReservation == 0) {
				streamReservation = ((StreamChannel) processor.getChannel()).reserveWindowSize(left, block);
				System.out.println("streamReservation:" + streamReservation);
				if (streamReservation == 0) {
					// Must be non-blocking.
					// Note: Can't add to the writeBuffer here as the write
					// may originate from the writeBuffer.
					// chunk.compact();
					return written;
				}
			}
			while (streamReservation > 0) {
				int connectionReservation = ((StreamChannel) processor.getChannel()).getHandler().getZero()
						.reserveWindowSize(((StreamChannel) processor.getChannel()), streamReservation, block);
				System.out.println("connectionReservation:" + connectionReservation);
				if (connectionReservation == 0) {
					// Must be non-blocking.
					// Note: Can't add to the writeBuffer here as the write
					// may originate from the writeBuffer.
					return written;
				}

				int orgLimit = chunk.limit();
				chunk.limit(chunk.position() + connectionReservation);
				// Do the write
				int len = next.doWrite(chunk);
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
		return next.getBytesWritten();
	}

//	@Override
//	public void setResponse(ExchangeData exchangeData) {
//		this.exchangeData = exchangeData;
//	}

	@Override
	public void recycle() {
		streamReservation = 0;
	}

	@Override
	public void setNext(HttpOutputBuffer next) {
		this.next = next;
	}

}
