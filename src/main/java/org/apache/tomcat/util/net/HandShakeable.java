package org.apache.tomcat.util.net;

import java.io.IOException;

public interface HandShakeable {

	public static int HANDSHAKE_COMPLETE = 1;

	public static int HANDSHAKE_FAIL = -1;

	public static int HANDSHAKE_NEEDREAD = 2;

	public static int HANDSHAKE_NEEDWRITE = 3;

	public static int HANDSHAKE_IGNORE = 4;

	public boolean isHandshakeComplete();

	/**
	 * Performs SSL handshake hence is a no-op for the non-secure implementation.
	 *
	 * @return Always returns zero
	 *
	 * @throws IOException Never for non-secure channel
	 */
	public int handshake(boolean read, boolean write) throws IOException;

}
