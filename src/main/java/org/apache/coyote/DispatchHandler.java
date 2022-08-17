package org.apache.coyote;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import org.apache.coyote.http11.Http11NioProtocol;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.net.Channel;
import org.apache.tomcat.util.net.SocketChannel;
import org.apache.tomcat.util.net.SocketEvent;
import org.apache.tomcat.util.net.Endpoint.Handler;
import org.apache.tomcat.util.res.StringManager;

public class DispatchHandler implements Handler {

	/**
	 * The string manager for this package.
	 */
	private static final StringManager sm = StringManager.getManager(AbstractProtocol.class);

	private static final Log log = LogFactory.getLog(DispatchHandler.class);

	private Handler next;

	private AbstractProtocol protocol;

	public DispatchHandler(Handler next, AbstractProtocol protocol) {
		super();
		this.next = next;
		this.protocol = protocol;
	}

	@Override
	public AbstractProtocol getProtocol() {
		return protocol;
	}

	@Override
	public void processSocket(Channel channel, SocketEvent event, boolean dispatch) {

		try {
			if (channel == null) {
				return;
			}

			if (event == SocketEvent.TIMEOUT) {
				System.out.println();
			}

			// SocketProcessorBase<S> sc = endpoint.popSocketProcessor();
			// if (sc == null) {
			// sc = endpoint.createSocketProcessor(channel, event);
			// } else {
			// sc.reset(channel, event);
			// }
			Executor executor = protocol.getExecutor();
			if (dispatch && executor != null) {
				Runnable runnable = new Runnable() {

					@Override
					public void run() {
						synchronized (channel.getLock()) {
							// It is possible that processing may be triggered for read and
							// write at the same time. The sync above makes sure that processing
							// does not occur in parallel. The test below ensures that if the
							// first event to be processed results in the socket being closed,
							// the subsequent events are not processed.
							if (channel.isClosed()) {
								return;
							}
							try {
								next.processSocket(channel, event, false);
							} catch (Throwable t) {
								ExceptionUtils.handleThrowable(t);
								// This means we got an OOM or similar creating a thread, or that
								// the pool and its queue are full
								log.error(sm.getString("endpoint.process.fail"), t);
								channel.close(t);
							}
						}
					}
				};
				executor.execute(runnable);
			} else {
				synchronized (channel.getLock()) {
					// It is possible that processing may be triggered for read and
					// write at the same time. The sync above makes sure that processing
					// does not occur in parallel. The test below ensures that if the
					// first event to be processed results in the socket being closed,
					// the subsequent events are not processed.
					if (channel.isClosed()) {
						return;
					}
					next.processSocket(channel, event, dispatch);
				}
			}
		} catch (RejectedExecutionException ree) {
			log.warn(sm.getString("endpoint.executor.fail", channel), ree);
			channel.close(ree);
		} catch (Throwable t) {
			ExceptionUtils.handleThrowable(t);
			// This means we got an OOM or similar creating a thread, or that
			// the pool and its queue are full
			log.error(sm.getString("endpoint.process.fail"), t);
			channel.close(t);
		}

	}

}
