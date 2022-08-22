package org.apache.coyote;

import org.apache.tomcat.util.buf.ByteChunk;

public abstract class RequestAction implements InputReader {

	public abstract int getAvailable(Object param);

	public abstract boolean isReadyForRead();

	public abstract boolean isRequestBodyFullyRead();

	public abstract void registerReadInterest();

	public abstract boolean isTrailerFieldsReady();

	public abstract void setRequestBody(ByteChunk body);

	public abstract void disableSwallowRequest();

	public abstract void actionREQ_HOST_ADDR_ATTRIBUTE();

	public abstract void actionREQ_HOST_ATTRIBUTE();

	public abstract void actionREQ_REMOTEPORT_ATTRIBUTE();

	public abstract void actionREQ_LOCAL_NAME_ATTRIBUTE();

	public abstract void actionREQ_LOCAL_ADDR_ATTRIBUTE();

	public abstract void actionREQ_LOCALPORT_ATTRIBUTE();

	public abstract void actionREQ_SSL_ATTRIBUTE();

	public abstract void actionREQ_SSL_CERTIFICATE();

	/**
	 * Processors that populate request attributes directly (e.g. AJP) should
	 * over-ride this method and return {@code false}.
	 *
	 * @return {@code true} if the SocketWrapper should be used to populate the
	 *         request attributes, otherwise {@code false}.
	 */
	protected boolean getPopulateRequestAttributesFromSocket() {
		return true;
	}

}
