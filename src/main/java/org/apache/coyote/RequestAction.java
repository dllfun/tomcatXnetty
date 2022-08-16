package org.apache.coyote;

import org.apache.tomcat.util.buf.ByteChunk;

public interface RequestAction extends InputReader {

	public int getAvailable(Object param);

	public boolean isReadyForRead();

	public boolean isRequestBodyFullyRead();

	public void registerReadInterest();

	public boolean isTrailerFieldsReady();

	public void setRequestBody(ByteChunk body);

	public void disableSwallowRequest();

	public void actionREQ_HOST_ADDR_ATTRIBUTE();

	public void actionREQ_HOST_ATTRIBUTE();

	public void actionREQ_REMOTEPORT_ATTRIBUTE();

	public void actionREQ_LOCAL_NAME_ATTRIBUTE();

	public void actionREQ_LOCAL_ADDR_ATTRIBUTE();

	public void actionREQ_LOCALPORT_ATTRIBUTE();

}
