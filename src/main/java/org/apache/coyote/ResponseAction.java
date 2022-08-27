package org.apache.coyote;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.NoSuchElementException;

import org.apache.coyote.http11.Constants;
import org.apache.coyote.http11.HttpOutputBuffer;
import org.apache.coyote.http11.OutputFilter;

public abstract class ResponseAction implements HttpOutputBuffer {

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
	private boolean responseFinished;

	private AbstractProcessor processor;

	public ResponseAction(AbstractProcessor processor) {
		this.processor = processor;
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

	protected abstract HttpOutputBuffer getBaseOutputBuffer();

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
			filter.setBuffer(getBaseOutputBuffer());
		} else {
			for (int i = 0; i <= lastActiveFilter; i++) {
				if (activeFilters[i] == filter)
					return;
			}
			filter.setBuffer(activeFilters[lastActiveFilter]);
		}

		activeFilters[++lastActiveFilter] = filter;

		filter.setResponse(processor.responseData);
	}

	@Override
	public int doWrite(ByteBuffer chunk) throws IOException {

		// if (!responseData.isCommitted()) {
		// Send the connector a request for commit. The connector should
		// then validate the headers, send them (using sendHeaders) and
		// set the filters accordingly.
		// processor.actionCOMMIT();
		// }

		if (lastActiveFilter == -1) {
			return getBaseOutputBuffer().doWrite(chunk);
		} else {
			return activeFilters[lastActiveFilter].doWrite(chunk);
		}
	}

	@Override
	public long getBytesWritten() {
		if (lastActiveFilter == -1) {
			return getBaseOutputBuffer().getBytesWritten();
		} else {
			return activeFilters[lastActiveFilter].getBytesWritten();
		}
	}

	public abstract boolean isTrailerFieldsSupported();

	public abstract boolean isReadyForWrite();

	// @Override
	public void commit(boolean finished) {
		if (!processor.responseData.isCommitted()) {
			processor.responseData.setCommitted(true);
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
	public void flush() throws IOException {
		if (lastActiveFilter == -1) {
			getBaseOutputBuffer().flush();
		} else {
			activeFilters[lastActiveFilter].flush();
		}
	}

	@Override
	public void end() throws IOException {
		if (responseFinished) {
			return;
		}

		if (lastActiveFilter == -1) {
			getBaseOutputBuffer().end();
		} else {
			activeFilters[lastActiveFilter].end();
		}

		responseFinished = true;
	}

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
	public void close() {
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
			processor.responseData.setErrorException(e);
		}
	}

	public abstract void prepareResponse(boolean finished) throws IOException;

	public abstract void finishResponse() throws IOException;

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
	}

}
