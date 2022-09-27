package org.apache.coyote;

import java.io.IOException;
import java.util.Arrays;
import java.util.NoSuchElementException;

import org.apache.coyote.http11.Constants;
import org.apache.coyote.http11.HttpOutputBuffer;
import org.apache.coyote.http11.OutputFilter;
import org.apache.tomcat.util.net.BufWrapper;

public abstract class ResponseAction implements HttpOutputBuffer {

	private final AbstractProcessor processor;

	private final ExchangeData exchangeData;
	/**
	 * Filter library for processing the response body.
	 */
	private OutputFilter[] filterLibrary;

	/**
	 * Active filters for the current request.
	 */
	private OutputFilter[] activeFilters;

	/**
	 * Index of the last active filter.
	 */
	private int lastActiveFilter;

	/**
	 * Finished flag.
	 */
	protected boolean responseFinished;

	private long writeTime = 0;

	private boolean firstWrite = true;

	private HttpOutputBuffer channelOutputBuffer = new HttpOutputBuffer() {

		@Override
		public long getBytesWritten() {
			return getBytesWrittenToChannel();
		}

		@Override
		public int doWrite(BufWrapper chunk) throws IOException {
			return doWriteToChannel(chunk);
		}

		@Override
		public boolean flush(boolean block) throws IOException {
			return flushToChannel(block);
		}

		@Override
		public void flush() throws IOException {
			flushToChannel();
		}

		@Override
		public void end() throws IOException {
			endToChannel();
		}
	};

	public ResponseAction(AbstractProcessor processor) {
		this.processor = processor;
		this.exchangeData = processor.exchangeData;
		filterLibrary = new OutputFilter[0];
		activeFilters = new OutputFilter[0];
		lastActiveFilter = -1;
		responseFinished = false;
	}

	/**
	 * Add an output filter to the filter library. Note that calling this method
	 * resets the currently active filters to none.
	 *
	 * @param filter The filter to add
	 */
	public final void addFilter(OutputFilter filter) {

		for (int i = 0; i < filterLibrary.length; i++) {
			if (filterLibrary[i].getId() == filter.getId()) {
				throw new IllegalArgumentException("id=" + filter.getId() + " already exist");
			}
		}
		OutputFilter[] newFilterLibrary = Arrays.copyOf(filterLibrary, filterLibrary.length + 1);
		newFilterLibrary[filterLibrary.length] = filter;
		filterLibrary = newFilterLibrary;

		activeFilters = new OutputFilter[filterLibrary.length];
	}

	/**
	 * Get filters.
	 *
	 * @return The current filter library containing all possible filters
	 */
	private final OutputFilter[] getFilters() {
		return filterLibrary;
	}

//	protected abstract HttpOutputBuffer getBaseOutputBuffer();

	/**
	 * Add an output filter to the active filters for the current response.
	 * <p>
	 * The filter does not have to be present in {@link #getFilters()}.
	 * <p>
	 * A filter can only be added to a response once. If the filter has already been
	 * added to this response then this method will be a NO-OP.
	 *
	 * @param filter The filter to add
	 */
	public final void addActiveFilter(int id) {

		OutputFilter filter = null;
		for (int i = 0; i < filterLibrary.length; i++) {
			if (filterLibrary[i].getId() == id) {
				filter = filterLibrary[i];
			}
		}
		if (filter == null) {
			throw new NoSuchElementException("id=" + String.valueOf(id) + " filter not found!");
		}

		if (lastActiveFilter == -1) {
			filter.setNext(channelOutputBuffer);
		} else {
			for (int i = 0; i <= lastActiveFilter; i++) {
				if (activeFilters[i] == filter)
					return;
			}
			filter.setNext(activeFilters[lastActiveFilter]);
		}

		activeFilters[++lastActiveFilter] = filter;

		filter.actived(); // filter.setResponse(processor.exchangeData);
	}

	public OutputFilter getActiveFilter(int id) {
		OutputFilter filter = null;
		for (int i = 0; i <= lastActiveFilter; i++) {
			if (activeFilters[i].getId() == id) {
				filter = activeFilters[i];
			}
		}
		return filter;
	}

	@Override
	public final int doWrite(BufWrapper chunk) throws IOException {
		long startTime = System.currentTimeMillis();
		if (firstWrite) {
			firstWrite = false;
//			System.out.println(exchangeData.getRequestURI().toString() + "第一次写出用时："
//					+ (System.currentTimeMillis() - exchangeData.getStartTime()));
		}
		// if (!responseData.isCommitted()) {
		// Send the connector a request for commit. The connector should
		// then validate the headers, send them (using sendHeaders) and
		// set the filters accordingly.
		// processor.actionCOMMIT();
		// }
		int written = -1;
		if (lastActiveFilter == -1) {
			written = channelOutputBuffer.doWrite(chunk);
		} else {
			written = activeFilters[lastActiveFilter].doWrite(chunk);
		}
		long useTime = System.currentTimeMillis() - startTime;
		writeTime += useTime;
//		System.out.println("responseWrite 用时：" + useTime + "总用时：" + writeTime);
		return written;
	}

	protected abstract int doWriteToChannel(BufWrapper chunk) throws IOException;

	@Override
	public final long getBytesWritten() {
		if (lastActiveFilter == -1) {
			return channelOutputBuffer.getBytesWritten();
		} else {
			return activeFilters[lastActiveFilter].getBytesWritten();
		}
	}

	protected abstract long getBytesWrittenToChannel();

	public abstract boolean isTrailerFieldsSupported();

	public abstract boolean isReadyForWrite();

	/**
	 * Flush any pending writes. Used during non-blocking writes to flush any
	 * remaining data from a previous incomplete write.
	 *
	 * @return <code>true</code> if data remains to be flushed at the end of method
	 *
	 * @throws IOException If an I/O error occurs while attempting to flush the data
	 */
	public abstract boolean flushBufferedWrite() throws IOException;

	// @Override
	public void commit(boolean finished) {
		if (!processor.exchangeData.isCommitted()) {
			processor.exchangeData.setCommitted(true);
			try {
				// Validate and write response headers
				prepareResponse(finished);
			} catch (IOException e) {
				processor.handleIOException(e);
			}
		}
	}

	/**
	 * Flush the response.
	 *
	 * @throws IOException an underlying I/O error occurred
	 */
	@Override
	public final void flush() throws IOException {
		if (lastActiveFilter == -1) {
			channelOutputBuffer.flush();
		} else {
			activeFilters[lastActiveFilter].flush();
		}
	}

	protected abstract void flushToChannel() throws IOException;

	@Override
	public final boolean flush(boolean block) throws IOException {
		if (lastActiveFilter == -1) {
			return channelOutputBuffer.flush(block);
		} else {
			return activeFilters[lastActiveFilter].flush(block);
		}
	}

	protected abstract boolean flushToChannel(boolean block) throws IOException;

	@Override
	public final void end() throws IOException {
		if (responseFinished) {
			return;
		}

		if (lastActiveFilter == -1) {
			channelOutputBuffer.end();
		} else {
			activeFilters[lastActiveFilter].end();
		}

		responseFinished = true;
	}

	protected abstract void endToChannel() throws IOException;

	// @Override
	public void setSwallowResponse() {
		this.responseFinished = true;
	}

	protected final boolean isChunking() {
		for (int i = 0; i < lastActiveFilter; i++) {
			if (activeFilters[i] == filterLibrary[Constants.CHUNKED_FILTER]) {
				return true;
			}
		}
		return false;
	}

	// @Override
	public void finish() {
		commit(true);
		try {
			finishResponse();
		} catch (IOException e) {
			processor.handleIOException(e);
		}
	}

	// @Override
	public void sendAck() {
		ack();
	}

	protected abstract void ack();

	// @Override
	public void clientFlush() {
		commit(false);
		try {
			flush();
		} catch (IOException e) {
			processor.handleIOException(e);
			processor.exchangeData.setErrorException(e);
		}
	}

	public boolean isResponseFinished() {
		return responseFinished;
	}

	protected abstract void prepareResponse(boolean finished) throws IOException;

	protected abstract void finishResponse() throws IOException;

	// @Override
	public void closeNow(Object param) {
		// Prevent further writes to the response
		setSwallowResponse();
		if (param instanceof Throwable) {
			processor.setErrorState(ErrorState.CLOSE_NOW, (Throwable) param);
		} else {
			processor.setErrorState(ErrorState.CLOSE_NOW, null);
		}
	}

	public final void resetFilter() {
		// Recycle filters
		for (int i = 0; i <= lastActiveFilter; i++) {
			activeFilters[i].recycle();
		}
		lastActiveFilter = -1;
		responseFinished = false;
		writeTime = 0;
		firstWrite = true;
	}

	public abstract void recycle();

}
