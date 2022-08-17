package org.apache.coyote;

import java.io.IOException;

import org.apache.coyote.http11.OutputFilter;

public interface ResponseAction extends OutputWriter {

	public boolean isTrailerFieldsSupported();

	public boolean isReadyForWrite();

	public void commit();

	public void close();

	public void sendAck();

	public void clientFlush();

	public void prepareResponse() throws IOException;

	public void finishResponse() throws IOException;

}
