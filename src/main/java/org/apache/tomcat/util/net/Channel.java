package org.apache.tomcat.util.net;

import java.io.IOException;

public interface Channel {

	public Object getCurrentProcessor();

	public void setCurrentProcessor(Object currentProcessor);

	public IOException getError();

	public void setError(IOException error);

	public void checkError() throws IOException;

	public boolean isClosed();

	public void close();

	public void setCloseException(Throwable e);

	public Throwable getCloseException();

	public void close(Throwable e);

	public Object getLock();

	public SSLSupport initSslSupport(String clientCertProvider);

	public void setSslSupport(SSLSupport sslSupport);

	public SSLSupport getSslSupport();

	// public boolean isProcessing();

	// public void setProcessing(boolean processing);

	/**
	 * Protocols that support multiplexing (e.g. HTTP/2) should override this method
	 * and return the appropriate ID.
	 *
	 * @return The stream ID associated with this request or {@code null} if a
	 *         multiplexing protocol is not being used
	 */
	public Object getConnectionID();

	/**
	 * Protocols that support multiplexing (e.g. HTTP/2) should override this method
	 * and return the appropriate ID.
	 *
	 * @return The stream ID associated with this request or {@code null} if a
	 *         multiplexing protocol is not being used
	 */
	public Object getStreamID();

}
