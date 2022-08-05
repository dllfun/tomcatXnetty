package org.apache.coyote;

import org.apache.tomcat.util.buf.ByteChunk;

public interface ChannelHandler extends InputReader {

	public int getAvailable(Object param);

	public boolean isReadyForRead();

	public boolean isRequestBodyFullyRead();

	public void registerReadInterest();

	public void setRequestBody(ByteChunk body);

	public void disableSwallowRequest();

}
