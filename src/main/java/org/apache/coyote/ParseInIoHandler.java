package org.apache.coyote;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.net.Channel;
import org.apache.tomcat.util.net.SocketChannel;
import org.apache.tomcat.util.net.SocketEvent;
import org.apache.tomcat.util.net.Endpoint.Handler;
import org.apache.tomcat.util.res.StringManager;

public class ParseInIoHandler implements Handler {

	private static final Log log = LogFactory.getLog(ProcessorHandler.class);

	protected static final StringManager sm = StringManager.getManager(AbstractProtocol.class);

	private Handler next;

	private AbstractProtocol protocol;

	public ParseInIoHandler(Handler next, AbstractProtocol protocol) {
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
		if (channel == null) {
			// Nothing to do. Socket has been closed.
			return;
		}
		if (channel instanceof SocketChannel) {
			SocketChannel socketChannel = (SocketChannel) channel;

			if (log.isDebugEnabled()) {
				log.debug(sm.getString("abstractConnectionHandler.process", channel, event));// .getSocket()
			}

			// S socket = channel.getSocket();

			Processor processor = (Processor) socketChannel.getCurrentProcessor();
			if (log.isDebugEnabled()) {
				log.debug(sm.getString("abstractConnectionHandler.connectionsGet", processor, channel));// socket
			}

			// Timeouts are calculated on a dedicated thread and then
			// dispatched. Because of delays in the dispatch process, the
			// timeout may no longer be required. Check here and avoid
			// unnecessary processing.
			if (SocketEvent.TIMEOUT == event && (processor == null || !processor.isAsync() && !processor.isUpgrade()
					|| processor.isAsync() && !processor.checkAsyncTimeoutGeneration())) {
				// This is effectively a NO-OP
				next.processSocket(channel, event, dispatch);
				return;
			}

			if (processor != null) {
				// Make sure an async timeout doesn't fire
				// removeWaitingProcessor(processor);
			} else if (event == SocketEvent.DISCONNECT || event == SocketEvent.ERROR) {
				// Nothing to do. Endpoint requested a close and there is no
				// longer a processor associated with this socket.
				next.processSocket(channel, event, dispatch);
				return;
			}

			boolean dispatched = false;
			try {
				if (processor == null) {
					String negotiatedProtocol = socketChannel.getNegotiatedProtocol();
					// OpenSSL typically returns null whereas JSSE typically
					// returns "" when no protocol is negotiated
					if (negotiatedProtocol != null && negotiatedProtocol.length() > 0) {
						dispatched = true;
						next.processSocket(channel, event, dispatch);
						return;
					}
				}
				if (processor == null) {
					processor = protocol.popRecycledProcessors();
					if (log.isDebugEnabled()) {
						log.debug(sm.getString("abstractConnectionHandler.processorPop", processor));
					}
				}
				if (processor == null) {
					processor = protocol.createProcessor();
					protocol.register(processor);
					if (log.isDebugEnabled()) {
						log.debug(sm.getString("abstractConnectionHandler.processorCreate", processor));
					}
				}

				if (channel.getSslSupport() == null) {
					channel.setSslSupport(socketChannel.initSslSupport(protocol.getClientCertProvider()));
				}

				// Associate the processor with the connection
				socketChannel.setCurrentProcessor(processor);

				if (processor.processInIoThread(socketChannel, event)) {
					dispatched = true;
					next.processSocket(channel, event, dispatch);
				}

			} catch (java.net.SocketException e) {
				// SocketExceptions are normal
				log.debug(sm.getString("abstractConnectionHandler.socketexception.debug"), e);
			} catch (java.io.IOException e) {
				// IOExceptions are normal
				log.debug(sm.getString("abstractConnectionHandler.ioexception.debug"), e);
			} catch (ProtocolException e) {
				// Protocol exceptions normally mean the client sent invalid or
				// incomplete data.
				log.debug(sm.getString("abstractConnectionHandler.protocolexception.debug"), e);
			}
			// Future developers: if you discover any other
			// rare-but-nonfatal exceptions, catch them here, and log as
			// above.
			catch (OutOfMemoryError oome) {
				// Try and handle this here to give Tomcat a chance to close the
				// connection and prevent clients waiting until they time out.
				// Worst case, it isn't recoverable and the attempt at logging
				// will trigger another OOME.
				log.error(sm.getString("abstractConnectionHandler.oome"), oome);
			} catch (Throwable e) {
				ExceptionUtils.handleThrowable(e);
				// any other exception or error is odd. Here we log it
				// with "ERROR" level, so it will show up even on
				// less-than-verbose logs.
				log.error(sm.getString("abstractConnectionHandler.error"), e);
			}

			// Make sure socket/processor is removed from the list of current
			// connections
			if (!dispatched) {
				next.processSocket(channel, event, dispatch);
			}
		} else {
			next.processSocket(channel, event, dispatch);
		}
	}

}
