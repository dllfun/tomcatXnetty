package org.apache.coyote;

import java.io.IOException;

public abstract class ResponseAction implements OutputWriter {

	private AbstractProcessor processor;

	public ResponseAction(AbstractProcessor processor) {
		this.processor = processor;
	}

	public abstract boolean isTrailerFieldsSupported();

	public abstract boolean isReadyForWrite();

	public abstract void commit();

	public abstract void close();

	public abstract void sendAck();

	public abstract void clientFlush();

	public abstract void prepareResponse() throws IOException;

	public abstract void finishResponse() throws IOException;

	public abstract void setSwallowResponse();

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

}
