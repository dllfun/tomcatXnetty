package org.apache.tomcat.util.net;

import java.io.IOException;

public abstract class AbstractChannel implements Channel {

	/**
	 * The org.apache.coyote.Processor instance currently associated with the
	 * wrapper.
	 */
	protected Object currentProcessor = null;

	/**
	 * Used to record the first IOException that occurs during non-blocking
	 * read/writes that can't be usefully propagated up the stack since there is no
	 * user code or appropriate container code in the stack to handle it.
	 */
	private volatile IOException error = null;

	private volatile Throwable closeException = null;

	@Override
	public IOException getError() {
		return error;
	}

	@Override
	public void setError(IOException error) {
		// Not perfectly thread-safe but good enough. Just needs to ensure that
		// once this.error is non-null, it can never be null.
		if (this.error != null) {
			return;
		}
		this.error = error;
	}

	public void checkError() throws IOException {
		if (error != null) {
			throw error;
		}
	}

	@Override
	public Object getCurrentProcessor() {
		return currentProcessor;
	}

	@Override
	public void setCurrentProcessor(Object currentProcessor) {
		this.currentProcessor = currentProcessor;
	}

	@Override
	public void registerReadInterest() {
		throw new UnsupportedOperationException();
	}

	@Override
	public void registerWriteInterest() {
		throw new UnsupportedOperationException();
	}

	@Override
	public void setCloseException(Throwable e) {
		this.closeException = e;
	}

	@Override
	public Throwable getCloseException() {
		return closeException;
	}

}
