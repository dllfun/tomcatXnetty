package org.apache.tomcat.util.net;

import java.net.InetAddress;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;

public interface Endpoint<S> {

	public static interface Handler<S> {

		/**
		 * Different types of socket states to react upon.
		 */
		public enum SocketState {
			// TODO Add a new state to the AsyncStateMachine and remove
			// ASYNC_END (if possible)
			OPEN, CLOSED, LONG, ASYNC_END, SENDFILE, UPGRADING, UPGRADED, SUSPENDED
		}

		/**
		 * Process the provided socket with the given current status.
		 *
		 * @param socket The socket to process
		 * @param status The current socket status
		 *
		 * @return The state of the socket after processing
		 */
		public SocketState process(Channel<S> channel, SocketEvent status);

		/**
		 * Obtain the GlobalRequestProcessor associated with the handler.
		 *
		 * @return the GlobalRequestProcessor
		 */
		public Object getGlobal();

		/**
		 * Obtain the currently open sockets.
		 *
		 * @return The sockets for which the handler is tracking a currently open
		 *         connection
		 * @deprecated Unused, will be removed in Tomcat 10, replaced by
		 *             AbstractEndpoint.getConnections
		 */
		@Deprecated
		public Set<S> getOpenSockets();

		/**
		 * Release any resources associated with the given SocketWrapper.
		 *
		 * @param socketWrapper The socketWrapper to release resources for
		 */
		public void release(Channel<S> channel);

		/**
		 * Inform the handler that the endpoint has stopped accepting any new
		 * connections. Typically, the endpoint will be stopped shortly afterwards but
		 * it is possible that the endpoint will be resumed so the handler should not
		 * assume that a stop will follow.
		 */
		public void pause();

		/**
		 * Recycle resources associated with the handler.
		 */
		public void recycle();
	}

	public void setHandler(Handler<S> handler);

	public boolean setProperty(String name, String value);

	public String getProperty(String name);

	public void setUseSendfile(boolean useSendfile);

	public boolean getUseSendfile();

	public Executor getExecutor();

	public void setExecutor(Executor executor);

	public ScheduledExecutorService getUtilityExecutor();

	public void setUtilityExecutor(ScheduledExecutorService utilityExecutor);

	public int getMaxThreads();

	public void setMaxThreads(int maxThreads);

	public int getMaxConnections();

	public void setMaxConnections(int maxCon);

	public int getMinSpareThreads();

	public void setMinSpareThreads(int minSpareThreads);

	public int getThreadPriority();

	public void setThreadPriority(int threadPriority);

	public int getAcceptCount();

	public void setAcceptCount(int acceptCount);

	public boolean getTcpNoDelay();

	public void setTcpNoDelay(boolean tcpNoDelay);

	public int getConnectionLinger();

	public void setConnectionLinger(int connectionLinger);

	public int getMaxKeepAliveRequests();

	public void setMaxKeepAliveRequests(int maxKeepAliveRequests);

	public int getKeepAliveTimeout();

	public void setKeepAliveTimeout(int keepAliveTimeout);

	public InetAddress getAddress();

	public void setAddress(InetAddress address);

	public int getPort();

	public void setPort(int port);

	public int getPortOffset();

	public void setPortOffset(int portOffset);

	public int getPortWithOffset();

	public int getLocalPort();

	public int getConnectionTimeout();

	public void setConnectionTimeout(int soTimeout);

	public long getConnectionCount();

	public void setAcceptorThreadPriority(int acceptorThreadPriority);

	public int getAcceptorThreadPriority();

	public void setName(String name);

	public void setDomain(String domain);

	/**
	 * Identifies if the endpoint supports ALPN. Note that a return value of
	 * <code>true</code> implies that {@link #isSSLEnabled()} will also return
	 * <code>true</code>.
	 *
	 * @return <code>true</code> if the endpoint supports ALPN in its current
	 *         configuration, otherwise <code>false</code>.
	 */
	public boolean isAlpnSupported();

	public void addNegotiatedProtocol(String negotiableProtocol);

	public void init() throws Exception;

	public void start() throws Exception;

	public Set<Channel<S>> getConnections();

	public void pause();

	public boolean isPaused();

	public void resume();

	public void stop() throws Exception;

	public void destroy() throws Exception;

	public void closeServerSocketGraceful();

	public boolean isSSLEnabled();

	public void setSSLEnabled(boolean SSLEnabled);

	public String getDefaultSSLHostConfigName();

	public void setDefaultSSLHostConfigName(String defaultSSLHostConfigName);

	public void addSslHostConfig(SSLHostConfig sslHostConfig) throws IllegalArgumentException;

	public SSLHostConfig[] findSslHostConfigs();

	public void reloadSslHostConfigs();

	public void reloadSslHostConfig(String hostName);

	public static long toTimeout(long timeout) {
		// Many calls can't do infinite timeout so use Long.MAX_VALUE if timeout is <= 0
		return (timeout > 0) ? timeout : Long.MAX_VALUE;
	}

}
