package org.apache.tomcat.util.net;

import java.io.IOException;

import org.apache.coyote.Processor;

public abstract class AbstractChannel implements Channel {

	/**
	 * The org.apache.coyote.Processor instance currently associated with the
	 * wrapper.
	 */
	protected volatile Object currentProcessor = null;

	/**
	 * Used to record the first IOException that occurs during non-blocking
	 * read/writes that can't be usefully propagated up the stack since there is no
	 * user code or appropriate container code in the stack to handle it.
	 */
	private volatile IOException error = null;

	private volatile Throwable closeException = null;

	// private volatile boolean processing = false;

	protected volatile SSLSupport sslSupport;

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

	@Override
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
		if (currentProcessor == null) {
			throw new NullPointerException();
		}
		if (currentProcessor instanceof Processor) {
			((Processor) currentProcessor).setChannel(this);
		}
		this.currentProcessor = currentProcessor;
	}

	@Override
	public void clearCurrentProcessor() {
		this.currentProcessor = null;
	}

	@Override
	public void setCloseException(Throwable e) {
		this.closeException = e;
	}

	@Override
	public Throwable getCloseException() {
		return closeException;
	}

	// public boolean isProcessing() {
	// return processing;
	// }

	// public void setProcessing(boolean processing) {
	// this.processing = processing;
	// }

	@Override
	public void setSslSupport(SSLSupport sslSupport) {
		this.sslSupport = sslSupport;
	}

	@Override
	public SSLSupport getSslSupport() {
		return sslSupport;
	}

	@Override
	public SSLSupport initSslSupport(String clientCertProvider) {
		return null;
	}

	@Override
	public Object getConnectionID() {
		return null;
	}

	@Override
	public Object getStreamID() {
		return null;
	}

	@Override
	public Object getLock() {
		return null;
	}

}
