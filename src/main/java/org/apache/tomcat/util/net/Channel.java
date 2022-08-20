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

	public SSLSupport getSslSupport(String clientCertProvider);

	// public boolean isProcessing();

	// public void setProcessing(boolean processing);

}
