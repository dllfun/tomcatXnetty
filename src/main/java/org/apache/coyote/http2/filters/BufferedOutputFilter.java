package org.apache.coyote.http2.filters;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.apache.coyote.AbstractProcessor;
import org.apache.coyote.ExchangeData;
import org.apache.coyote.ProcessorComponent;
import org.apache.coyote.http11.Constants;
import org.apache.coyote.http11.HttpOutputBuffer;
import org.apache.coyote.http11.OutputFilter;
import org.apache.coyote.http2.Stream;
import org.apache.coyote.http2.StreamChannel;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.net.WriteBuffer;
import org.apache.tomcat.util.res.StringManager;

public class BufferedOutputFilter extends ProcessorComponent implements OutputFilter, WriteBuffer.Sink {

	private static final Log log = LogFactory.getLog(BufferedOutputFilter.class);
	private static final StringManager sm = StringManager.getManager(Stream.class);

	private final ByteBuffer buffer = ByteBuffer.allocate(8 * 1024);
	private final WriteBuffer writeBuffer = new WriteBuffer(32 * 1024);
	// Flag that indicates that data was left over on a previous
	// non-blocking write. Once set, this flag stays set until all the data
	// has been written.
	private boolean dataLeft;

	private HttpOutputBuffer next;

	private volatile boolean closed = false;
//	private Stream stream;

	public BufferedOutputFilter(AbstractProcessor processor) {
		super(processor);
//		this.stream = stream;
	}

	@Override
	public int getId() {
		return Constants.BUFFEREDOUTPUT_FILTER;
	}

	@Override
	public void actived() {

	}

	public boolean isDataLeft() {
		return dataLeft;
	}

	@Override
	public void end() throws IOException {
		flush();
		next.end();
	}

	@Override
	public void flush() throws IOException {
		boolean block = processor.isBlockingWrite();
		/*
		 * Need to ensure that there is exactly one call to flush even when there is no
		 * data to write. Too few calls (i.e. zero) and the end of stream message is not
		 * sent for a completed asynchronous write. Too many calls and the end of stream
		 * message is sent too soon and trailer headers are not sent.
		 */
		boolean dataInBuffer = buffer.position() > 0;
		boolean flushed = false;

		if (dataInBuffer) {
			dataInBuffer = flush(false, block);
			flushed = true;
		}

		if (dataInBuffer) {
			dataLeft = true;
		} else {
			if (writeBuffer.isEmpty()) {
				// Both buffer and writeBuffer are empty.
				if (flushed) {
					dataLeft = false;
				} else {
					dataLeft = flush(false, block);
				}
			} else {
				dataLeft = writeBuffer.write(this, block);
			}
		}

		next.flush();
	}

	@Override
	public boolean flush(boolean block) throws IOException {
		return flush(false, block);
	}

	private boolean flush(boolean writeInProgress, boolean block) throws IOException {
		if (log.isDebugEnabled()) {
			log.debug(sm.getString("stream.outputBuffer.flush.debug",
					((StreamChannel) processor.getChannel()).getConnectionId(),
					((StreamChannel) processor.getChannel()).getIdentifier(), Integer.toString(buffer.position()),
					Boolean.toString(writeInProgress), Boolean.toString(closed)));
		}
		if (buffer.position() == 0) {
			// Buffer is empty. Nothing to do.
			return false;
		}
		buffer.flip();
		while (buffer.hasRemaining()) {
			int len = next.doWrite(buffer);
			if (len == 0) {
				buffer.compact();
				return true;
			}
		}
		buffer.clear();
		return false;
	}

	@Override
	public synchronized boolean writeFromBuffer(ByteBuffer src, boolean blocking) throws IOException {
		int chunkLimit = src.limit();
		while (src.remaining() > 0) {
			int thisTime = Math.min(buffer.remaining(), src.remaining());
			src.limit(src.position() + thisTime);
			buffer.put(src);
			src.limit(chunkLimit);
			if (flush(false, blocking)) {
				return true;
			}
		}
		return false;
	}

	@Override
	public int doWrite(ByteBuffer chunk) throws IOException {
		boolean block = processor.isBlockingWrite();
		// chunk is always fully written
		int result = chunk.remaining();
		if (writeBuffer.isEmpty()) {
			int chunkLimit = chunk.limit();
			while (chunk.remaining() > 0) {
				int thisTime = Math.min(buffer.remaining(), chunk.remaining());
				chunk.limit(chunk.position() + thisTime);
				buffer.put(chunk);
				chunk.limit(chunkLimit);
				if (chunk.remaining() > 0 && !buffer.hasRemaining()) {
					// Only flush if we have more data to write and the buffer
					// is full
					if (flush(true, block)) {
						writeBuffer.add(chunk);
						dataLeft = true;
						break;
					}
				}
			}
		} else {
			writeBuffer.add(chunk);
		}
		return result;
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
		buffer.clear();
	}

	@Override
	public void setNext(HttpOutputBuffer next) {
		this.next = next;
	}

}
