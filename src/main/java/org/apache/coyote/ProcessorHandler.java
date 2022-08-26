package org.apache.coyote;

import java.nio.ByteBuffer;

import javax.servlet.http.HttpUpgradeHandler;
import javax.servlet.http.WebConnection;

import org.apache.coyote.http11.upgrade.InternalHttpUpgradeHandler;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.InstanceManager;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.net.Channel;
import org.apache.tomcat.util.net.SocketChannel;
import org.apache.tomcat.util.net.SocketEvent;
import org.apache.tomcat.util.net.Endpoint.Handler;
import org.apache.tomcat.util.res.StringManager;

public class ProcessorHandler implements Handler {

	private static final Log log = LogFactory.getLog(ProcessorHandler.class);

	protected static final StringManager sm = StringManager.getManager(AbstractProtocol.class);

	private AbstractProtocol protocol;

	public ProcessorHandler(AbstractProtocol protocol) {
		this.protocol = protocol;
	}

	@Override
	public AbstractProtocol getProtocol() {
		return protocol;
	}

	@Override
	public void processSocket(Channel channel, SocketEvent event, boolean dispatch) {
		SocketState state = process(channel, event);
		if (state == SocketState.LONG) {

		} else if (state == SocketState.CLOSED) {
			// poller.cancelledKey(socket.getIOChannel().keyFor(poller.getSelector()),
			// socketWrapper);
			if (channel != null && !channel.isClosed()) {
				channel.setCurrentProcessor(null);
				channel.close();
			}
		}
	}

	// @Override
	private SocketState process(Channel channel, SocketEvent event) {
		if (log.isDebugEnabled()) {
			log.debug(sm.getString("abstractConnectionHandler.process", channel, event));// .getSocket()
		}
		if (channel == null) {
			// Nothing to do. Socket has been closed.
			return SocketState.CLOSED;
		}

		if (channel instanceof SocketChannel) {
			System.out.println(((SocketChannel) channel).getRemotePort() + " process " + event);
		}

		// S socket = channel.getSocket();

		Processor processor = (Processor) channel.getCurrentProcessor();
		if (log.isDebugEnabled()) {
			log.debug(sm.getString("abstractConnectionHandler.connectionsGet", processor, channel));// socket
		}

		if (processor == null) {

			// Timeouts are calculated on a dedicated thread and then
			// dispatched. Because of delays in the dispatch process, the
			// timeout may no longer be required. Check here and avoid
			// unnecessary processing.
			if (SocketEvent.TIMEOUT == event) {
				// This is effectively a NO-OP
				return SocketState.OPEN;
			}

			if (event == SocketEvent.DISCONNECT || event == SocketEvent.ERROR) {
				// Nothing to do. Endpoint requested a close and there is no
				// longer a processor associated with this socket.
				channel.close();
				return SocketState.CLOSED;
			}
		} else {

			// Timeouts are calculated on a dedicated thread and then
			// dispatched. Because of delays in the dispatch process, the
			// timeout may no longer be required. Check here and avoid
			// unnecessary processing.
			if (SocketEvent.TIMEOUT == event && (!processor.isAsync() && !processor.isUpgrade()
					|| processor.isAsync() && !processor.checkAsyncTimeoutGeneration())) {
				// This is effectively a NO-OP
				return SocketState.OPEN;
			}

			// Make sure an async timeout doesn't fire
			protocol.removeWaitingProcessor(processor);

		}

		ContainerThreadMarker.set();

		try {
			if (processor == null) {
				if (channel instanceof SocketChannel) {
					SocketChannel socketChannel = (SocketChannel) channel;
					String negotiatedProtocol = socketChannel.getNegotiatedProtocol();
					// OpenSSL typically returns null whereas JSSE typically
					// returns "" when no protocol is negotiated
					if (negotiatedProtocol != null && negotiatedProtocol.length() > 0) {
						System.out.println(socketChannel.getRemotePort() + " negotiatedProtocol " + negotiatedProtocol);
						UpgradeProtocol upgradeProtocol = protocol.getNegotiatedProtocol(negotiatedProtocol);
						if (upgradeProtocol != null) {
							processor = upgradeProtocol.getProcessor(socketChannel, protocol.getAdapter());
							if (log.isDebugEnabled()) {
								log.debug(sm.getString("abstractConnectionHandler.processorCreate", processor));
							}
						} else if (negotiatedProtocol.equals("http/1.1")) {
							// Explicitly negotiated the default protocol.
							// Obtain a processor below.
						} else {
							// TODO:
							// OpenSSL 1.0.2's ALPN callback doesn't support
							// failing the handshake with an error if no
							// protocol can be negotiated. Therefore, we need to
							// fail the connection here. Once this is fixed,
							// replace the code below with the commented out
							// block.
							if (log.isDebugEnabled()) {
								log.debug(sm.getString("abstractConnectionHandler.negotiatedProcessor.fail",
										negotiatedProtocol));
							}
							channel.setCurrentProcessor(null);
							channel.close();
							return SocketState.CLOSED;
							/*
							 * To replace the code above once OpenSSL 1.1.0 is used. // Failed to create
							 * processor. This is a bug. throw new IllegalStateException(sm.getString(
							 * "abstractConnectionHandler.negotiatedProcessor.fail", negotiatedProtocol));
							 */
						}
					}
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
				channel.setSslSupport(channel.initSslSupport(protocol.getClientCertProvider()));
			}

			// Associate the processor with the connection
			channel.setCurrentProcessor(processor);

			SocketState state = SocketState.CLOSED;

			do {

				state = processor.process(channel, event);

				if (state == SocketState.UPGRADING) {
					SocketChannel socketChannel = (SocketChannel) channel;
					// Get the HTTP upgrade handler
					UpgradeToken upgradeToken = processor.getUpgradeToken();
					// Retrieve leftover input
					ByteBuffer leftOverInput = processor.getLeftoverInput();
					if (upgradeToken == null) {
						// Assume direct HTTP/2 connection
						UpgradeProtocol upgradeProtocol = protocol.getUpgradeProtocol("h2c");
						if (upgradeProtocol != null) {
							protocol.release(processor);
							processor = upgradeProtocol.getProcessor(socketChannel, protocol.getAdapter());
							socketChannel.unRead(leftOverInput);
							// Associate with the processor with the connection
							socketChannel.setCurrentProcessor(processor);
						} else {
							if (log.isDebugEnabled()) {
								log.debug(sm.getString("abstractConnectionHandler.negotiatedProcessor.fail", "h2c"));
							}
							channel.setCurrentProcessor(null);
							channel.close();
							return SocketState.CLOSED;
						}
					} else {
						System.out.println(socketChannel.getRemotePort() + " upgrade");
						HttpUpgradeHandler httpUpgradeHandler = upgradeToken.getHttpUpgradeHandler();
						// Release the Http11 processor to be re-used
						protocol.release(processor);
						// Create the upgrade processor
						processor = protocol.createUpgradeProcessor(socketChannel, upgradeToken);
						if (log.isDebugEnabled()) {
							log.debug(sm.getString("abstractConnectionHandler.upgradeCreate", processor, channel));
						}
						socketChannel.unRead(leftOverInput);
						// Mark the connection as upgraded
						socketChannel.setUpgraded(true);
						// Associate with the processor with the connection
						socketChannel.setCurrentProcessor(processor);
						// Initialise the upgrade handler (which may trigger
						// some IO using the new protocol which is why the lines
						// above are necessary)
						// This cast should be safe. If it fails the error
						// handling for the surrounding try/catch will deal with
						// it.
						if (upgradeToken.getInstanceManager() == null) {
							httpUpgradeHandler.init((WebConnection) processor);
							if (socketChannel.isClosed()) {
								state = SocketState.CLOSED;
							}
						} else {
							ClassLoader oldCL = upgradeToken.getContextBind().bind(false, null);
							try {
								httpUpgradeHandler.init((WebConnection) processor);
								if (socketChannel.isClosed()) {
									state = SocketState.CLOSED;
								}
							} finally {
								upgradeToken.getContextBind().unbind(false, oldCL);
							}
						}
						if (state != SocketState.CLOSED) {
							if (httpUpgradeHandler instanceof InternalHttpUpgradeHandler) {
								if (((InternalHttpUpgradeHandler) httpUpgradeHandler).hasAsyncIO()) {
									// The handler will initiate all further I/O
									state = SocketState.LONG;
								}
							}
						}
					}
				}
			} while (state == SocketState.UPGRADING);

			if (state == SocketState.ASYNC_END) {
				channel.setCurrentProcessor(null);
				channel.close();
				state = SocketState.CLOSED;
			} else if (state == SocketState.LONG) {
				// In the middle of processing a request/response. Keep the
				// socket associated with the processor. Exact requirements
				// depend on type of long poll
				protocol.longPoll(channel, processor);
				if (processor.isAsync()) {
					protocol.addWaitingProcessor(processor);
				}
			} else if (state == SocketState.OPEN) {
				// In keep-alive but between requests. OK to recycle
				// processor. Continue to poll for the next request.
				channel.setCurrentProcessor(null);
				protocol.release(processor);
				if (channel instanceof SocketChannel) {
					((SocketChannel) channel).registerReadInterest();
				}
			} else if (state == SocketState.SENDFILE) {
				// Sendfile in progress. If it fails, the socket will be
				// closed. If it works, the socket either be added to the
				// poller (or equivalent) to await more data or processed
				// if there are any pipe-lined requests remaining.
			} else if (state == SocketState.UPGRADED) {
				// Don't add sockets back to the poller if this was a
				// non-blocking write otherwise the poller may trigger
				// multiple read events which may lead to thread starvation
				// in the connector. The write() method will add this socket
				// to the poller if necessary.
				if (event != SocketEvent.OPEN_WRITE) {
					protocol.longPoll(channel, processor);
					// protocol.addWaitingProcessor(processor);
				}
			} else if (state == SocketState.SUSPENDED) {
				// Don't add sockets back to the poller.
				// The resumeProcessing() method will add this socket
				// to the poller.
			} else if (state == SocketState.CLOSED) {
				// Connection closed. OK to recycle the processor.
				// Processors handling upgrades require additional clean-up
				// before release.
				Exception closeException = processor.collectCloseException();
				if (closeException != null) {
					channel.setCloseException(closeException);
				}
				channel.setCurrentProcessor(null);
				channel.close();
				if (processor.isUpgrade()) {
					UpgradeToken upgradeToken = processor.getUpgradeToken();
					HttpUpgradeHandler httpUpgradeHandler = upgradeToken.getHttpUpgradeHandler();
					InstanceManager instanceManager = upgradeToken.getInstanceManager();
					if (instanceManager == null) {
						httpUpgradeHandler.destroy();
					} else {
						ClassLoader oldCL = upgradeToken.getContextBind().bind(false, null);
						try {
							httpUpgradeHandler.destroy();
						} finally {
							try {
								instanceManager.destroyInstance(httpUpgradeHandler);
							} catch (Throwable e) {
								ExceptionUtils.handleThrowable(e);
								log.error(sm.getString("abstractConnectionHandler.error"), e);
							}
							upgradeToken.getContextBind().unbind(false, oldCL);
						}
					}
				}
				protocol.release(processor);
			}
			return state;
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
			channel.setCloseException(e);
		} finally {
			ContainerThreadMarker.clear();
		}

		// Make sure socket/processor is removed from the list of current
		// connections
		channel.setCurrentProcessor(null);
		channel.close();
		protocol.release(processor);
		return SocketState.CLOSED;
	}
}
