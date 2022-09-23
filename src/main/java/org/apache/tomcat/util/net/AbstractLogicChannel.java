package org.apache.tomcat.util.net;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.apache.tomcat.util.buf.ByteChunk;
import org.apache.tomcat.util.http.MimeHeaders;

public interface AbstractLogicChannel extends Channel {

	public SocketChannel getSocketChannel();

	public int available();

	public boolean isReadyForRead();

	public boolean isRequestBodyFullyRead();

	public BufWrapper doRead() throws IOException;

	public void insertReplayedBody(ByteChunk body);

	public boolean isReadyForWrite();

	public void doWriteHeader(MimeHeaders headers, boolean finished) throws IOException;

	public boolean isOutputClosed();

//	public void setOutputClosed(boolean outputClosed);

	public int doWriteBody(BufWrapper chunk, boolean finished) throws IOException;

}
