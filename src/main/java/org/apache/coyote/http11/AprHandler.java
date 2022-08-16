package org.apache.coyote.http11;

import org.apache.coyote.AbstractProtocol;
import org.apache.tomcat.util.net.Channel;
import org.apache.tomcat.util.net.SocketEvent;
import org.apache.tomcat.util.net.Endpoint.Handler;
import org.apache.tomcat.util.net.Endpoint.Handler.SocketState;

public class AprHandler implements Handler {

	private Handler next;

	public AprHandler(Handler next) {
		super();
		this.next = next;
	}

	@Override
	public AbstractProtocol getProtocol() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void processSocket(Channel channel, SocketEvent event, boolean dispatch) {
		try {
			// Process the request from this socket
			next.processSocket(channel, event, dispatch);// SocketState state =
			// if (state == Handler.SocketState.CLOSED) {
			// Close socket and pool
			// channel.close();
			// }
		} finally {
			channel = null;
			event = null;
			// return to cache
			// if (isRunning() && !isPaused() && processorCache != null) {
			// processorCache.push(this);
			// }
		}
	}

}
