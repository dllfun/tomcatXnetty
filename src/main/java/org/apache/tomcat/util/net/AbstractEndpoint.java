/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.tomcat.util.net;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;

import org.apache.coyote.AbstractProtocol;
import org.apache.juli.logging.Log;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.IntrospectionUtils;
import org.apache.tomcat.util.modeler.Registry;
import org.apache.tomcat.util.res.StringManager;
import org.apache.tomcat.util.threads.LimitLatch;

/**
 * @param <S> The type used by the socket wrapper associated with this endpoint.
 *            May be the same as U.
 * @param <U> The type of the underlying socket used by this endpoint. May be
 *            the same as S.
 *
 * @author Mladen Turk
 * @author Remy Maucherat
 */
public abstract class AbstractEndpoint<S, U> implements Endpoint<S> {

	// -------------------------------------------------------------- Constants

	protected static final StringManager sm = StringManager.getManager(AbstractEndpoint.class);

	protected enum BindState {
		UNBOUND, BOUND_ON_INIT, BOUND_ON_START, SOCKET_CLOSED_ON_STOP
	}

	// ----------------------------------------------------------------- Fields

	/**
	 * Running state of the endpoint.
	 */
	private volatile boolean running = false;

	/**
	 * Will be set to true whenever the endpoint is paused.
	 */
	private volatile boolean paused = false;

	/**
	 * counter for nr of connections handled by an endpoint
	 */
	private volatile LimitLatch connectionLimitLatch = null;

	/**
	 * Socket properties
	 */
	protected final SocketProperties socketProperties = new SocketProperties();

	@Override
	public final SocketProperties getSocketProperties() {
		return socketProperties;
	}

	private ObjectName oname = null;

	/**
	 * Map holding all current connections keyed with the sockets.
	 */
	protected final Map<U, SocketChannel> connections = new ConcurrentHashMap<>();

	public AbstractEndpoint() {

	}

	// ----------------------------------------------------------------- Properties

	/**
	 * Handling of accepted sockets.
	 */
	private Handler handler = new Handler() {

		@Override
		public void processSocket(Channel channel, SocketEvent event, boolean dispatch) {
			if (event == SocketEvent.OPEN_READ) {
				if (channel instanceof SocketChannel) {
					SocketChannel socketChannel = (SocketChannel) channel;
					try {

						StringBuffer sb = new StringBuffer();
						sb.append("HTTP/1.1 200 OK\r\n");
						sb.append("Server: nginx\r\n");
						sb.append("Content-Length: 59\r\n");
						sb.append("ContentType: text/html;charset=utf-8\r\n");
						sb.append("Connection: close\r\n");
						sb.append("\r\n");
						sb.append("<!DOCTYPE html><html><head></head><body>error</body></html>");// 40

						byte[] b = sb.toString().getBytes("utf-8");
						socketChannel.write(true, b, 0, b.length);
						socketChannel.flush(true);
						socketChannel.registerReadInterest();
						// socketChannel.close();
					} catch (IOException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				}
			} else {
				channel.close();
			}
		}

		@Override
		public AbstractProtocol<?> getProtocol() {
			return null;
		}
	};

	@Override
	public final void setHandler(Handler handler) {
		if (handler != null) {
			this.handler = handler;
		}
	}

	protected final Handler getHandler() {
		return handler;
	}

	/**
	 * Has the user requested that send file be used where possible?
	 */
	private boolean useSendfile = true;

	@Override
	public final boolean getUseSendfile() {
		return useSendfile;
	}

	@Override
	public void setUseSendfile(boolean useSendfile) {
		this.useSendfile = useSendfile;
	}

	/**
	 * Acceptor thread count.
	 */
	protected int acceptorThreadCount = 1;

	/**
	 * NO-OP.
	 *
	 * @param acceptorThreadCount Unused
	 *
	 * @deprecated Will be removed in Tomcat 10.
	 */
	@Deprecated
	public void setAcceptorThreadCount(int acceptorThreadCount) {
	}

	/**
	 * Always returns 1.
	 *
	 * @return Always 1.
	 *
	 * @deprecated Will be removed in Tomcat 10.
	 */
	@Deprecated
	public int getAcceptorThreadCount() {
		return 1;
	}

	/**
	 * Priority of the acceptor threads.
	 */
	protected int acceptorThreadPriority = Thread.NORM_PRIORITY;

	@Override
	public final void setAcceptorThreadPriority(int acceptorThreadPriority) {
		this.acceptorThreadPriority = acceptorThreadPriority;
	}

	@Override
	public final int getAcceptorThreadPriority() {
		return acceptorThreadPriority;
	}

	private int maxConnections = 8 * 1024;

	@Override
	public void setMaxConnections(int maxCon) {// final
		this.maxConnections = maxCon;
		LimitLatch latch = this.connectionLimitLatch;
		if (latch != null) {
			// Update the latch that enforces this
			if (maxCon == -1) {
				releaseConnectionLatch();
			} else {
				latch.setLimit(maxCon);
			}
		} else if (maxCon > 0) {
			initializeConnectionLatch();
		}
	}

	@Override
	public final int getMaxConnections() {
		return this.maxConnections;
	}

	/**
	 * Return the current count of connections handled by this endpoint, if the
	 * connections are counted (which happens when the maximum count of connections
	 * is limited), or <code>-1</code> if they are not. This property is added here
	 * so that this value can be inspected through JMX. It is visible on
	 * "ThreadPool" MBean.
	 *
	 * <p>
	 * The count is incremented by the Acceptor before it tries to accept a new
	 * connection. Until the limit is reached and thus the count cannot be
	 * incremented, this value is more by 1 (the count of acceptors) than the actual
	 * count of connections that are being served.
	 *
	 * @return The count
	 */
	@Override
	public final long getConnectionCount() {
		LimitLatch latch = connectionLimitLatch;
		if (latch != null) {
			return latch.getCount();
		}
		return -1;
	}

	/**
	 * Server socket port.
	 */
	private int port = -1;

	@Override
	public final int getPort() {
		return port;
	}

	@Override
	public final void setPort(int port) {
		this.port = port;
	}

	private int portOffset = 0;

	@Override
	public final int getPortOffset() {
		return portOffset;
	}

	@Override
	public final void setPortOffset(int portOffset) {
		if (portOffset < 0) {
			throw new IllegalArgumentException(
					sm.getString("endpoint.portOffset.invalid", Integer.valueOf(portOffset)));
		}
		this.portOffset = portOffset;
	}

	@Override
	public final int getPortWithOffset() {
		// Zero is a special case and negative values are invalid
		int port = getPort();
		if (port > 0) {
			return port + getPortOffset();
		}
		return port;
	}

	@Override
	public final int getLocalPort() {
		try {
			InetSocketAddress localAddress = getLocalAddress();
			if (localAddress == null) {
				return -1;
			}
			return localAddress.getPort();
		} catch (IOException ioe) {
			return -1;
		}
	}

	/**
	 * Address for the server socket.
	 */
	private InetAddress address;

	@Override
	public final InetAddress getAddress() {
		return address;
	}

	@Override
	public final void setAddress(InetAddress address) {
		this.address = address;
	}

	/**
	 * Obtain the network address the server socket is bound to. This primarily
	 * exists to enable the correct address to be used when unlocking the server
	 * socket since it removes the guess-work involved if no address is specifically
	 * set.
	 *
	 * @return The network address that the server socket is listening on or null if
	 *         the server socket is not currently bound.
	 *
	 * @throws IOException If there is a problem determining the currently bound
	 *                     socket
	 */
	protected abstract InetSocketAddress getLocalAddress() throws IOException;

	/**
	 * Priority of the worker threads.
	 */
	private int threadPriority = Thread.NORM_PRIORITY;

	@Override
	public final void setThreadPriority(int threadPriority) {
		// Can't change this once the executor has started
		this.threadPriority = threadPriority;
	}

	public final int getThreadPriority() {
		return threadPriority;
	}

	/**
	 * The default is true - the created threads will be in daemon mode. If set to
	 * false, the control thread will not be daemon - and will keep the process
	 * alive.
	 */
	private boolean daemon = true;

	@Override
	public final void setDaemon(boolean b) {
		daemon = b;
	}

	public final boolean getDaemon() {
		return daemon;
	}

	/**
	 * Allows the server developer to specify the acceptCount (backlog) that should
	 * be used for server sockets. By default, this value is 100.
	 */
	private int acceptCount = 100;

	@Override
	public final void setAcceptCount(int acceptCount) {
		if (acceptCount > 0)
			this.acceptCount = acceptCount;
	}

	@Override
	public final int getAcceptCount() {
		return acceptCount;
	}

	/**
	 * Controls when the Endpoint binds the port. <code>true</code>, the default
	 * binds the port on {@link #init()} and unbinds it on {@link #destroy()}. If
	 * set to <code>false</code> the port is bound on {@link #start()} and unbound
	 * on {@link #stop()}.
	 */
	private boolean bindOnInit = true;

	public final boolean getBindOnInit() {
		return bindOnInit;
	}

	public final void setBindOnInit(boolean b) {
		this.bindOnInit = b;
	}

	private volatile BindState bindState = BindState.UNBOUND;

	/**
	 * Keepalive timeout, if not set the soTimeout is used.
	 */
	private Integer keepAliveTimeout = null;

	@Override
	public final int getKeepAliveTimeout() {
		if (keepAliveTimeout == null) {
			return getConnectionTimeout();
		} else {
			return keepAliveTimeout.intValue();
		}
	}

	@Override
	public final void setKeepAliveTimeout(int keepAliveTimeout) {
		this.keepAliveTimeout = Integer.valueOf(keepAliveTimeout);
	}

	/**
	 * Socket TCP no delay.
	 *
	 * @return The current TCP no delay setting for sockets created by this endpoint
	 */
	@Override
	public final boolean getTcpNoDelay() {
		return socketProperties.getTcpNoDelay();
	}

	@Override
	public final void setTcpNoDelay(boolean tcpNoDelay) {
		socketProperties.setTcpNoDelay(tcpNoDelay);
	}

	/**
	 * Socket linger.
	 *
	 * @return The current socket linger time for sockets created by this endpoint
	 */
	@Override
	public final int getConnectionLinger() {
		return socketProperties.getSoLingerTime();
	}

	@Override
	public final void setConnectionLinger(int connectionLinger) {
		socketProperties.setSoLingerTime(connectionLinger);
		socketProperties.setSoLingerOn(connectionLinger >= 0);
	}

	/**
	 * Socket timeout.
	 *
	 * @return The current socket timeout for sockets created by this endpoint
	 */
	@Override
	public final int getConnectionTimeout() {
		return socketProperties.getSoTimeout();
	}

	@Override
	public final void setConnectionTimeout(int soTimeout) {
		socketProperties.setSoTimeout(soTimeout);
	}

	/**
	 * SSL engine.
	 */
	private boolean SSLEnabled = false;

	@Override
	public final boolean isSSLEnabled() {
		return SSLEnabled;
	}

	@Override
	public final void setSSLEnabled(boolean SSLEnabled) {
		this.SSLEnabled = SSLEnabled;
	}

	/**
	 * Max keep alive requests
	 */
	private int maxKeepAliveRequests = 100; // as in Apache HTTPD server

	@Override
	public final int getMaxKeepAliveRequests() {
		return maxKeepAliveRequests;
	}

	@Override
	public final void setMaxKeepAliveRequests(int maxKeepAliveRequests) {
		this.maxKeepAliveRequests = maxKeepAliveRequests;
	}

	/**
	 * Name of the thread pool, which will be used for naming child threads.
	 */
	private String name = "TP";

	@Override
	public final void setName(String name) {
		this.name = name;
	}

	public final String getName() {
		return name;
	}

	/**
	 * Name of domain to use for JMX registration.
	 */
	private String domain;

	@Override
	public final void setDomain(String domain) {
		this.domain = domain;
	}

	public final String getDomain() {
		return domain;
	}

	/**
	 * Expose asynchronous IO capability.
	 */
	private boolean useAsyncIO = true;

	public final void setUseAsyncIO(boolean useAsyncIO) {
		this.useAsyncIO = useAsyncIO;
	}

	public final boolean getUseAsyncIO() {
		return useAsyncIO;
	}

	protected abstract boolean getDeferAccept();

	protected final List<String> negotiableProtocols = new ArrayList<>();

	@Override
	public final void addNegotiatedProtocol(String negotiableProtocol) {
		negotiableProtocols.add(negotiableProtocol);
	}

	public final boolean hasNegotiableProtocols() {
		return (negotiableProtocols.size() > 0);
	}

	/**
	 * Attributes provide a way for configuration to be passed to sub-components
	 * without the {@link org.apache.coyote.ProtocolHandler} being aware of the
	 * properties available on those sub-components.
	 */
	protected final HashMap<String, Object> attributes = new HashMap<>();

	/**
	 * Generic property setter called when a property for which a specific setter
	 * already exists within the {@link org.apache.coyote.ProtocolHandler} needs to
	 * be made available to sub-components. The specific setter will call this
	 * method to populate the attributes.
	 *
	 * @param name  Name of property to set
	 * @param value The value to set the property to
	 */
	public final void setAttribute(String name, Object value) {
		if (getLog().isTraceEnabled()) {
			getLog().trace(sm.getString("endpoint.setAttribute", name, value));
		}
		attributes.put(name, value);
	}

	/**
	 * Used by sub-components to retrieve configuration information.
	 *
	 * @param key The name of the property for which the value should be retrieved
	 *
	 * @return The value of the specified property
	 */
	public final Object getAttribute(String key) {
		Object value = attributes.get(key);
		if (getLog().isTraceEnabled()) {
			getLog().trace(sm.getString("endpoint.getAttribute", key, value));
		}
		return value;
	}

	@Override
	public boolean setProperty(String name, String value) {// final
		setAttribute(name, value);
		final String socketName = "socket.";
		try {
			if (name.startsWith(socketName)) {
				return IntrospectionUtils.setProperty(socketProperties, name.substring(socketName.length()), value);
			} else {
				return IntrospectionUtils.setProperty(this, name, value, false);
			}
		} catch (Exception x) {
			getLog().error(sm.getString("endpoint.setAttributeError", name, value), x);
			return false;
		}
	}

	@Override
	public final String getProperty(String name) {
		String value = (String) getAttribute(name);
		final String socketName = "socket.";
		if (value == null && name.startsWith(socketName)) {
			Object result = IntrospectionUtils.getProperty(socketProperties, name.substring(socketName.length()));
			if (result != null) {
				value = result.toString();
			}
		}
		return value;
	}

	@Override
	public final boolean isRunning() {
		return running;
	}

	@Override
	public final boolean isPaused() {
		return paused;
	}

	/**
	 * Get a set with the current open connections.
	 * 
	 * @return A set with the open socket wrappers
	 */
	@Override
	public final Set<SocketChannel> getConnections() {
		return new HashSet<>(connections.values());
	}

	// ------------------------------------------------------- Lifecycle methods

	/*
	 * NOTE: There is no maintenance of state or checking for valid transitions
	 * within this class other than ensuring that bind/unbind are called in the
	 * right place. It is expected that the calling code will maintain state and
	 * prevent invalid state transitions.
	 */

	public abstract void bind() throws Exception;

	public abstract void unbind() throws Exception;

	public abstract void startInternal() throws Exception;

	public abstract void stopInternal() throws Exception;

	private void bindWithCleanup() throws Exception {
		try {
			bind();
		} catch (Throwable t) {
			// Ensure open sockets etc. are cleaned up if something goes
			// wrong during bind
			ExceptionUtils.handleThrowable(t);
			unbind();
			throw t;
		}
	}

	@Override
	public final void init() throws Exception {
		if (bindOnInit) {
			bindWithCleanup();
			bindState = BindState.BOUND_ON_INIT;
		}
		if (this.domain != null) {
			// Register endpoint (as ThreadPool - historical name)
			oname = new ObjectName(domain + ":type=ThreadPool,name=\"" + getName() + "\"");
			Registry.getRegistry(null, null).registerComponent(this, oname, null);

			ObjectName socketPropertiesOname = new ObjectName(
					domain + ":type=SocketProperties,name=\"" + getName() + "\"");
			socketProperties.setObjectName(socketPropertiesOname);
			Registry.getRegistry(null, null).registerComponent(socketProperties, socketPropertiesOname, null);

			for (SSLHostConfig sslHostConfig : findSslHostConfigs()) {
				registerJmx(sslHostConfig);
			}
		}
	}

	private void registerJmx(SSLHostConfig sslHostConfig) {
		if (domain == null) {
			// Before init the domain is null
			return;
		}
		ObjectName sslOname = null;
		try {
			sslOname = new ObjectName(domain + ":type=SSLHostConfig,ThreadPool=\"" + getName() + "\",name="
					+ ObjectName.quote(sslHostConfig.getHostName()));
			sslHostConfig.setObjectName(sslOname);
			try {
				Registry.getRegistry(null, null).registerComponent(sslHostConfig, sslOname, null);
			} catch (Exception e) {
				getLog().warn(sm.getString("endpoint.jmxRegistrationFailed", sslOname), e);
			}
		} catch (MalformedObjectNameException e) {
			getLog().warn(sm.getString("endpoint.invalidJmxNameSslHost", sslHostConfig.getHostName()), e);
		}

		for (SSLHostConfigCertificate sslHostConfigCert : sslHostConfig.getCertificates()) {
			ObjectName sslCertOname = null;
			try {
				sslCertOname = new ObjectName(domain + ":type=SSLHostConfigCertificate,ThreadPool=\"" + getName()
						+ "\",Host=" + ObjectName.quote(sslHostConfig.getHostName()) + ",name="
						+ sslHostConfigCert.getType());
				sslHostConfigCert.setObjectName(sslCertOname);
				try {
					Registry.getRegistry(null, null).registerComponent(sslHostConfigCert, sslCertOname, null);
				} catch (Exception e) {
					getLog().warn(sm.getString("endpoint.jmxRegistrationFailed", sslCertOname), e);
				}
			} catch (MalformedObjectNameException e) {
				getLog().warn(sm.getString("endpoint.invalidJmxNameSslHostCert", sslHostConfig.getHostName(),
						sslHostConfigCert.getType()), e);
			}
		}
	}

	private void unregisterJmx(SSLHostConfig sslHostConfig) {
		Registry registry = Registry.getRegistry(null, null);
		registry.unregisterComponent(sslHostConfig.getObjectName());
		for (SSLHostConfigCertificate sslHostConfigCert : sslHostConfig.getCertificates()) {
			registry.unregisterComponent(sslHostConfigCert.getObjectName());
		}
	}

	@Override
	public final void start() throws Exception {
		if (bindState == BindState.UNBOUND) {
			bindWithCleanup();
			bindState = BindState.BOUND_ON_START;
		}
		if (!running) {
			running = true;
			paused = false;
			startInternal();
		}
	}

	/**
	 * Pause the endpoint, which will stop it accepting new connections and unlock
	 * the acceptor.
	 */
	@Override
	public final void pause() {
		if (running && !paused) {
			paused = true;
			releaseConnectionLatch();
			pauseInternal();
			// getHandler().pause();
		}
	}

	protected abstract void pauseInternal();

	/**
	 * Resume the endpoint, which will make it start accepting new connections
	 * again.
	 */
	@Override
	public void resume() {// final
		if (running) {
			paused = false;
		}
	}

	@Override
	public final void stop() throws Exception {
		if (!paused) {
			pause();
		}
		if (running) {
			running = false;
			stopInternal();
		}
		if (bindState == BindState.BOUND_ON_START || bindState == BindState.SOCKET_CLOSED_ON_STOP) {
			unbind();
			bindState = BindState.UNBOUND;
		}
	}

	@Override
	public final void destroy() throws Exception {
		if (bindState == BindState.BOUND_ON_INIT) {
			unbind();
			bindState = BindState.UNBOUND;
		}
		Registry registry = Registry.getRegistry(null, null);
		registry.unregisterComponent(oname);
		registry.unregisterComponent(socketProperties.getObjectName());
		for (SSLHostConfig sslHostConfig : findSslHostConfigs()) {
			unregisterJmx(sslHostConfig);
		}
	}

	protected abstract Log getLog();

	protected final LimitLatch initializeConnectionLatch() {
		if (maxConnections == -1)
			return null;
		if (connectionLimitLatch == null) {
			connectionLimitLatch = new LimitLatch(getMaxConnections());
		}
		return connectionLimitLatch;
	}

	private void releaseConnectionLatch() {
		LimitLatch latch = connectionLimitLatch;
		if (latch != null)
			latch.releaseAll();
		connectionLimitLatch = null;
	}

	protected final void countUpOrAwaitConnection() throws InterruptedException {
		if (maxConnections == -1)
			return;
		LimitLatch latch = connectionLimitLatch;
		if (latch != null)
			latch.countUpOrAwait();
	}

	protected final long countDownConnection() {
		if (maxConnections == -1)
			return -1;
		LimitLatch latch = connectionLimitLatch;
		if (latch != null) {
			long result = latch.countDown();
			if (result < 0) {
				getLog().warn(sm.getString("endpoint.warn.incorrectConnectionCount"));
			}
			return result;
		} else
			return -1;
	}

	/**
	 * Close the server socket (to prevent further connections) if the server socket
	 * was originally bound on {@link #start()} (rather than on {@link #init()}).
	 *
	 * @see #getBindOnInit()
	 */
	@Override
	public final void closeServerSocketGraceful() {
		if (bindState == BindState.BOUND_ON_START) {
			bindState = BindState.SOCKET_CLOSED_ON_STOP;
			try {
				doCloseServerSocket();
			} catch (IOException ioe) {
				getLog().warn(sm.getString("endpoint.serverSocket.closeFailed", getName()), ioe);
			}
		}
	}

	/**
	 * Actually close the server socket but don't perform any other clean-up.
	 *
	 * @throws IOException If an error occurs closing the socket
	 */
	protected abstract void doCloseServerSocket() throws IOException;

	private String defaultSSLHostConfigName = SSLHostConfig.DEFAULT_SSL_HOST_NAME;

	@Override
	public final String getDefaultSSLHostConfigName() {
		return defaultSSLHostConfigName;
	}

	@Override
	public final void setDefaultSSLHostConfigName(String defaultSSLHostConfigName) {
		this.defaultSSLHostConfigName = defaultSSLHostConfigName;
	}

	protected final ConcurrentMap<String, SSLHostConfig> sslHostConfigs = new ConcurrentHashMap<>();

	/**
	 * Add the given SSL Host configuration.
	 *
	 * @param sslHostConfig The configuration to add
	 *
	 * @throws IllegalArgumentException If the host name is not valid or if a
	 *                                  configuration has already been provided for
	 *                                  that host
	 */
	@Override
	public final void addSslHostConfig(SSLHostConfig sslHostConfig) throws IllegalArgumentException {
		addSslHostConfig(sslHostConfig, false);
	}

	/**
	 * Add the given SSL Host configuration, optionally replacing the existing
	 * configuration for the given host.
	 *
	 * @param sslHostConfig The configuration to add
	 * @param replace       If {@code true} replacement of an existing configuration
	 *                      is permitted, otherwise any such attempted replacement
	 *                      will trigger an exception
	 *
	 * @throws IllegalArgumentException If the host name is not valid or if a
	 *                                  configuration has already been provided for
	 *                                  that host and replacement is not allowed
	 */
	public final void addSslHostConfig(SSLHostConfig sslHostConfig, boolean replace) throws IllegalArgumentException {
		String key = sslHostConfig.getHostName();
		if (key == null || key.length() == 0) {
			throw new IllegalArgumentException(sm.getString("endpoint.noSslHostName"));
		}
		if (bindState != BindState.UNBOUND && bindState != BindState.SOCKET_CLOSED_ON_STOP && isSSLEnabled()) {
			try {
				createSSLContext(sslHostConfig);
			} catch (Exception e) {
				throw new IllegalArgumentException(e);
			}
		}
		if (replace) {
			SSLHostConfig previous = sslHostConfigs.put(key, sslHostConfig);
			if (previous != null) {
				unregisterJmx(sslHostConfig);
			}
			registerJmx(sslHostConfig);

			// Do not release any SSLContexts associated with a replaced
			// SSLHostConfig. They may still be in used by existing connections
			// and releasing them would break the connection at best. Let GC
			// handle the clean up.
		} else {
			SSLHostConfig duplicate = sslHostConfigs.putIfAbsent(key, sslHostConfig);
			if (duplicate != null) {
				releaseSSLContext(sslHostConfig);
				throw new IllegalArgumentException(sm.getString("endpoint.duplicateSslHostName", key));
			}
			registerJmx(sslHostConfig);
		}
	}

	/**
	 * Removes the SSL host configuration for the given host name, if such a
	 * configuration exists.
	 *
	 * @param hostName The host name associated with the SSL host configuration to
	 *                 remove
	 *
	 * @return The SSL host configuration that was removed, if any
	 */
	public final SSLHostConfig removeSslHostConfig(String hostName) {
		if (hostName == null) {
			return null;
		}
		// Host names are case insensitive
		if (hostName.equalsIgnoreCase(getDefaultSSLHostConfigName())) {
			throw new IllegalArgumentException(sm.getString("endpoint.removeDefaultSslHostConfig", hostName));
		}
		SSLHostConfig sslHostConfig = sslHostConfigs.remove(hostName);
		unregisterJmx(sslHostConfig);
		return sslHostConfig;
	}

	/**
	 * Re-read the configuration files for the SSL host and replace the existing SSL
	 * configuration with the updated settings. Note this replacement will happen
	 * even if the settings remain unchanged.
	 *
	 * @param hostName The SSL host for which the configuration should be reloaded.
	 *                 This must match a current SSL host
	 */
	@Override
	public final void reloadSslHostConfig(String hostName) {
		SSLHostConfig sslHostConfig = sslHostConfigs.get(hostName);
		if (sslHostConfig == null) {
			throw new IllegalArgumentException(sm.getString("endpoint.unknownSslHostName", hostName));
		}
		addSslHostConfig(sslHostConfig, true);
	}

	/**
	 * Re-read the configuration files for all SSL hosts and replace the existing
	 * SSL configuration with the updated settings. Note this replacement will
	 * happen even if the settings remain unchanged.
	 */
	@Override
	public final void reloadSslHostConfigs() {
		for (String hostName : sslHostConfigs.keySet()) {
			reloadSslHostConfig(hostName);
		}
	}

	@Override
	public final SSLHostConfig[] findSslHostConfigs() {
		return sslHostConfigs.values().toArray(new SSLHostConfig[0]);
	}

	/**
	 * Create the SSLContextfor the the given SSLHostConfig.
	 *
	 * @param sslHostConfig The SSLHostConfig for which the SSLContext should be
	 *                      created
	 * @throws Exception If the SSLContext cannot be created for the given
	 *                   SSLHostConfig
	 */
	protected abstract void createSSLContext(SSLHostConfig sslHostConfig) throws Exception;

	protected final void destroySsl() throws Exception {
		if (isSSLEnabled()) {
			for (SSLHostConfig sslHostConfig : sslHostConfigs.values()) {
				releaseSSLContext(sslHostConfig);
			}
		}
	}

	/**
	 * Release the SSLContext, if any, associated with the SSLHostConfig.
	 *
	 * @param sslHostConfig The SSLHostConfig for which the SSLContext should be
	 *                      released
	 */
	protected final void releaseSSLContext(SSLHostConfig sslHostConfig) {
		for (SSLHostConfigCertificate certificate : sslHostConfig.getCertificates(true)) {
			if (certificate.getSslContext() != null) {
				SSLContext sslContext = certificate.getSslContext();
				if (sslContext != null) {
					sslContext.destroy();
				}
			}
		}
	}

	protected final SSLHostConfig getSSLHostConfig(String sniHostName) {
		SSLHostConfig result = null;

		if (sniHostName != null) {
			// First choice - direct match
			result = sslHostConfigs.get(sniHostName);
			if (result != null) {
				return result;
			}
			// Second choice, wildcard match
			int indexOfDot = sniHostName.indexOf('.');
			if (indexOfDot > -1) {
				result = sslHostConfigs.get("*" + sniHostName.substring(indexOfDot));
			}
		}

		// Fall-back. Use the default
		if (result == null) {
			result = sslHostConfigs.get(getDefaultSSLHostConfigName());
		}
		if (result == null) {
			// Should never happen.
			throw new IllegalStateException();
		}
		return result;
	}

}
