/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.coyote;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javax.management.InstanceNotFoundException;
import javax.management.MBeanRegistration;
import javax.management.MBeanRegistrationException;
import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import javax.servlet.http.HttpUpgradeHandler;
import javax.servlet.http.WebConnection;

import org.apache.coyote.http11.Http11Processor;
import org.apache.coyote.http11.upgrade.InternalHttpUpgradeHandler;
import org.apache.coyote.http11.upgrade.UpgradeProcessorInternal;
import org.apache.juli.logging.Log;
import org.apache.tomcat.InstanceManager;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.collections.SynchronizedStack;
import org.apache.tomcat.util.modeler.Registry;
import org.apache.tomcat.util.net.Channel;
import org.apache.tomcat.util.net.Endpoint;
import org.apache.tomcat.util.net.Endpoint.Handler;
import org.apache.tomcat.util.net.SocketChannel;
import org.apache.tomcat.util.net.SocketEvent;
import org.apache.tomcat.util.res.StringManager;
import org.apache.tomcat.util.threads.ResizableExecutor;
import org.apache.tomcat.util.threads.TaskQueue;
import org.apache.tomcat.util.threads.TaskThreadFactory;
import org.apache.tomcat.util.threads.ThreadPoolExecutor;

public abstract class AbstractProtocol<S> implements ProtocolHandler, MBeanRegistration {

	/**
	 * The string manager for this package.
	 */
	private static final StringManager sm = StringManager.getManager(AbstractProtocol.class);

	/**
	 * Counter used to generate unique JMX names for connectors using automatic port
	 * binding.
	 */
	private static final AtomicInteger nameCounter = new AtomicInteger(0);

	/**
	 * Name of MBean for the Global Request Processor.
	 */
	protected ObjectName rgOname = null;

	/**
	 * Unique ID for this connector. Only used if the connector is configured to use
	 * a random port as the port will change if stop(), start() is called.
	 */
	private int nameIndex = 0;

	/**
	 * Endpoint that provides low-level network I/O - must be matched to the
	 * ProtocolHandler implementation (ProtocolHandler using NIO, requires NIO
	 * Endpoint etc.).
	 */
	protected final Endpoint<S> endpoint;

	// private Handler<S> handler;

	private final Set<Processor> waitingProcessors = Collections
			.newSetFromMap(new ConcurrentHashMap<Processor, Boolean>());

	/**
	 * Controller for the timeout scheduling.
	 */
	private ScheduledFuture<?> timeoutFuture = null;
	private ScheduledFuture<?> monitorFuture;

	/**
	 * External Executor based thread pool.
	 */
	private Executor executor = null;

	/**
	 * Are we using an internal executor
	 */
	protected volatile boolean internalExecutor = true;

	/**
	 * External Executor based thread pool for utility tasks.
	 */
	private ScheduledExecutorService utilityExecutor = null;

	private final RequestGroupInfo global = new RequestGroupInfo();
	private final AtomicLong registerCount = new AtomicLong(0);
	private final RecycledProcessors recycledProcessors = new RecycledProcessors();

	private Handler<S> handler;

	public AbstractProtocol(Endpoint<S> endpoint) {
		this.endpoint = endpoint;
		setConnectionLinger(Constants.DEFAULT_CONNECTION_LINGER);
		setTcpNoDelay(Constants.DEFAULT_TCP_NO_DELAY);
	}

	// ----------------------------------------------- Generic property handling

	protected void setHandler(Handler<S> handler) {
		this.handler = handler;
	}

	public Handler<S> getHandler() {
		return handler;
	}

	/**
	 * Generic property setter used by the digester. Other code should not need to
	 * use this. The digester will only use this method if it can't find a more
	 * specific setter. That means the property belongs to the Endpoint, the
	 * ServerSocketFactory or some other lower level component. This method ensures
	 * that it is visible to both.
	 *
	 * @param name  The name of the property to set
	 * @param value The value, in string form, to set for the property
	 *
	 * @return <code>true</code> if the property was set successfully, otherwise
	 *         <code>false</code>
	 */
	public boolean setProperty(String name, String value) {
		return endpoint.setProperty(name, value);
	}

	/**
	 * Generic property getter used by the digester. Other code should not need to
	 * use this.
	 *
	 * @param name The name of the property to get
	 *
	 * @return The value of the property converted to a string
	 */
	public String getProperty(String name) {
		return endpoint.getProperty(name);
	}

	// ------------------------------- Properties managed by the ProtocolHandler

	/**
	 * The adapter provides the link between the ProtocolHandler and the connector.
	 */
	private Adapter adapter;

	@Override
	public void setAdapter(Adapter adapter) {
		this.adapter = adapter;
	}

	@Override
	public Adapter getAdapter() {
		return adapter;
	}

	/**
	 * The maximum number of idle processors that will be retained in the cache and
	 * re-used with a subsequent request. The default is 200. A value of -1 means
	 * unlimited. In the unlimited case, the theoretical maximum number of cached
	 * Processor objects is {@link #getMaxConnections()} although it will usually be
	 * closer to {@link #getMaxThreads()}.
	 */
	protected int processorCache = 200;

	public int getProcessorCache() {
		return this.processorCache;
	}

	public void setProcessorCache(int processorCache) {
		this.processorCache = processorCache;
	}

	private String clientCertProvider = null;

	/**
	 * When client certificate information is presented in a form other than
	 * instances of {@link java.security.cert.X509Certificate} it needs to be
	 * converted before it can be used and this property controls which JSSE
	 * provider is used to perform the conversion. For example it is used with the
	 * AJP connectors, the HTTP APR connector and with the
	 * {@link org.apache.catalina.valves.SSLValve}. If not specified, the default
	 * provider will be used.
	 *
	 * @return The name of the JSSE provider to use
	 */
	public String getClientCertProvider() {
		return clientCertProvider;
	}

	public void setClientCertProvider(String s) {
		this.clientCertProvider = s;
	}

	private int maxHeaderCount = 100;

	public int getMaxHeaderCount() {
		return maxHeaderCount;
	}

	public void setMaxHeaderCount(int maxHeaderCount) {
		this.maxHeaderCount = maxHeaderCount;
	}

	@Override
	public boolean isAprRequired() {
		return false;
	}

	@Override
	public boolean isSendfileSupported() {
		return endpoint.getUseSendfile();
	}

	// ---------------------- Properties that are passed through to the EndPoint

	@Override
	public final void setExecutor(Executor executor) {
		this.executor = executor;
		this.internalExecutor = (executor == null);
	}

	@Override
	public final Executor getExecutor() {
		return executor;
	}

	public boolean isInternalExecutor() {
		return internalExecutor;
	}

	@Override
	public final void setUtilityExecutor(ScheduledExecutorService utilityExecutor) {
		this.utilityExecutor = utilityExecutor;
	}

	@Override
	public final ScheduledExecutorService getUtilityExecutor() {
		if (utilityExecutor == null) {
			getLog().warn(sm.getString("endpoint.warn.noUtilityExecutor"));
			utilityExecutor = new ScheduledThreadPoolExecutor(1);
		}
		return utilityExecutor;
	}

	/**
	 * Maximum amount of worker threads.
	 */
	private int maxThreads = 200;

	@Override
	public final void setMaxThreads(int maxThreads) {
		this.maxThreads = maxThreads;
		Executor executor = this.executor;
		if (internalExecutor && executor instanceof java.util.concurrent.ThreadPoolExecutor) {
			// The internal executor should always be an instance of
			// j.u.c.ThreadPoolExecutor but it may be null if the endpoint is
			// not running.
			// This check also avoids various threading issues.
			((java.util.concurrent.ThreadPoolExecutor) executor).setMaximumPoolSize(maxThreads);
		}
	}

	@Override
	public final int getMaxThreads() {
		if (internalExecutor) {
			return maxThreads;
		} else {
			return -1;
		}
	}

	public int getMaxConnections() {
		return endpoint.getMaxConnections();
	}

	public void setMaxConnections(int maxConnections) {
		endpoint.setMaxConnections(maxConnections);
	}

	private int minSpareThreads = 10;

	@Override
	public final void setMinSpareThreads(int minSpareThreads) {
		this.minSpareThreads = minSpareThreads;
		Executor executor = this.executor;
		if (internalExecutor && executor instanceof java.util.concurrent.ThreadPoolExecutor) {
			// The internal executor should always be an instance of
			// j.u.c.ThreadPoolExecutor but it may be null if the endpoint is
			// not running.
			// This check also avoids various threading issues.
			((java.util.concurrent.ThreadPoolExecutor) executor).setCorePoolSize(minSpareThreads);
		}
	}

	@Override
	public final int getMinSpareThreads() {
		return Math.min(getMinSpareThreadsInternal(), getMaxThreads());
	}

	private int getMinSpareThreadsInternal() {
		if (internalExecutor) {
			return minSpareThreads;
		} else {
			return -1;
		}
	}

	/**
	 * Priority of the worker threads.
	 */
	protected int threadPriority = Thread.NORM_PRIORITY;

	@Override
	public final void setThreadPriority(int threadPriority) {
		// Can't change this once the executor has started
		this.threadPriority = threadPriority;
	}

	@Override
	public final int getThreadPriority() {
		if (internalExecutor) {
			return threadPriority;
		} else {
			return -1;
		}
	}

	/**
	 * Time to wait for the internal executor (if used) to terminate when the
	 * endpoint is stopped in milliseconds. Defaults to 5000 (5 seconds).
	 */
	private long executorTerminationTimeoutMillis = 5000;

	public final long getExecutorTerminationTimeoutMillis() {
		return executorTerminationTimeoutMillis;
	}

	public final void setExecutorTerminationTimeoutMillis(long executorTerminationTimeoutMillis) {
		this.executorTerminationTimeoutMillis = executorTerminationTimeoutMillis;
	}

	/**
	 * The default is true - the created threads will be in daemon mode. If set to
	 * false, the control thread will not be daemon - and will keep the process
	 * alive.
	 */
	private boolean daemon = true;

	public final void setDaemon(boolean b) {
		daemon = b;
	}

	public final boolean getDaemon() {
		return daemon;
	}

	public final void createExecutor() {
		internalExecutor = true;
		TaskQueue taskqueue = new TaskQueue();
		TaskThreadFactory tf = new TaskThreadFactory(getName() + "-exec-", daemon, getThreadPriority());
		executor = new ThreadPoolExecutor(getMinSpareThreads(), getMaxThreads(), 60, TimeUnit.SECONDS, taskqueue, tf);
		taskqueue.setParent((ThreadPoolExecutor) executor);
	}

	public void shutdownExecutor() {// final
		Executor executor = this.executor;
		if (executor != null && internalExecutor) {
			this.executor = null;
			if (executor instanceof ThreadPoolExecutor) {
				// this is our internal one, so we need to shut it down
				ThreadPoolExecutor tpe = (ThreadPoolExecutor) executor;
				tpe.shutdownNow();
				long timeout = getExecutorTerminationTimeoutMillis();
				if (timeout > 0) {
					try {
						tpe.awaitTermination(timeout, TimeUnit.MILLISECONDS);
					} catch (InterruptedException e) {
						// Ignore
					}
					if (tpe.isTerminating()) {
						getLog().warn(sm.getString("endpoint.warn.executorShutdown", getName()));
					}
				}
				TaskQueue queue = (TaskQueue) tpe.getQueue();
				queue.setParent(null);
			}
		}
	}

	/**
	 * Return the amount of threads that are managed by the pool.
	 *
	 * @return the amount of threads that are managed by the pool
	 */
	public final int getCurrentThreadCount() {
		Executor executor = this.executor;
		if (executor != null) {
			if (executor instanceof ThreadPoolExecutor) {
				return ((ThreadPoolExecutor) executor).getPoolSize();
			} else if (executor instanceof ResizableExecutor) {
				return ((ResizableExecutor) executor).getPoolSize();
			} else {
				return -1;
			}
		} else {
			return -2;
		}
	}

	/**
	 * Return the amount of threads that are in use
	 *
	 * @return the amount of threads that are in use
	 */
	public final int getCurrentThreadsBusy() {
		Executor executor = this.executor;
		if (executor != null) {
			if (executor instanceof ThreadPoolExecutor) {
				return ((ThreadPoolExecutor) executor).getActiveCount();
			} else if (executor instanceof ResizableExecutor) {
				return ((ResizableExecutor) executor).getActiveCount();
			} else {
				return -1;
			}
		} else {
			return -2;
		}
	}

	public int getAcceptCount() {
		return endpoint.getAcceptCount();
	}

	public void setAcceptCount(int acceptCount) {
		endpoint.setAcceptCount(acceptCount);
	}

	public boolean getTcpNoDelay() {
		return endpoint.getTcpNoDelay();
	}

	public void setTcpNoDelay(boolean tcpNoDelay) {
		endpoint.setTcpNoDelay(tcpNoDelay);
	}

	public int getConnectionLinger() {
		return endpoint.getConnectionLinger();
	}

	public void setConnectionLinger(int connectionLinger) {
		endpoint.setConnectionLinger(connectionLinger);
	}

	/**
	 * The time Tomcat will wait for a subsequent request before closing the
	 * connection. The default is {@link #getConnectionTimeout()}.
	 *
	 * @return The timeout in milliseconds
	 */
	public int getKeepAliveTimeout() {
		return endpoint.getKeepAliveTimeout();
	}

	public void setKeepAliveTimeout(int keepAliveTimeout) {
		endpoint.setKeepAliveTimeout(keepAliveTimeout);
	}

	public InetAddress getAddress() {
		return endpoint.getAddress();
	}

	public void setAddress(InetAddress ia) {
		endpoint.setAddress(ia);
	}

	public int getPort() {
		return endpoint.getPort();
	}

	public void setPort(int port) {
		endpoint.setPort(port);
	}

	public int getPortOffset() {
		return endpoint.getPortOffset();
	}

	public void setPortOffset(int portOffset) {
		endpoint.setPortOffset(portOffset);
	}

	public int getPortWithOffset() {
		return endpoint.getPortWithOffset();
	}

	public int getLocalPort() {
		return endpoint.getLocalPort();
	}

	/*
	 * When Tomcat expects data from the client, this is the time Tomcat will wait
	 * for that data to arrive before closing the connection.
	 */
	public int getConnectionTimeout() {
		return endpoint.getConnectionTimeout();
	}

	public void setConnectionTimeout(int timeout) {
		endpoint.setConnectionTimeout(timeout);
	}

	public long getConnectionCount() {
		return endpoint.getConnectionCount();
	}

	/**
	 * NO-OP.
	 *
	 * @param threadCount Unused
	 *
	 * @deprecated Will be removed in Tomcat 10.
	 */
	@Deprecated
	public void setAcceptorThreadCount(int threadCount) {
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

	public void setAcceptorThreadPriority(int threadPriority) {
		endpoint.setAcceptorThreadPriority(threadPriority);
	}

	public int getAcceptorThreadPriority() {
		return endpoint.getAcceptorThreadPriority();
	}

	// ---------------------------------------------------------- Public methods

	public synchronized int getNameIndex() {
		if (nameIndex == 0) {
			nameIndex = nameCounter.incrementAndGet();
		}

		return nameIndex;
	}

	/**
	 * The name will be prefix-address-port if address is non-null and prefix-port
	 * if the address is null.
	 *
	 * @return A name for this protocol instance that is appropriately quoted for
	 *         use in an ObjectName.
	 */
	public String getName() {
		return ObjectName.quote(getNameInternal());
	}

	private String getNameInternal() {
		StringBuilder name = new StringBuilder(getNamePrefix());
		name.append('-');
		if (getAddress() != null) {
			name.append(getAddress().getHostAddress());
			name.append('-');
		}
		int port = getPortWithOffset();
		if (port == 0) {
			// Auto binding is in use. Check if port is known
			name.append("auto-");
			name.append(getNameIndex());
			port = getLocalPort();
			if (port != -1) {
				name.append('-');
				name.append(port);
			}
		} else {
			name.append(port);
		}
		return name.toString();
	}

	public void addWaitingProcessor(Processor processor) {
		if (getLog().isDebugEnabled()) {
			getLog().debug(sm.getString("abstractProtocol.waitingProcessor.add", processor));
		}
		waitingProcessors.add(processor);
	}

	public void removeWaitingProcessor(Processor processor) {
		if (getLog().isDebugEnabled()) {
			getLog().debug(sm.getString("abstractProtocol.waitingProcessor.remove", processor));
		}
		waitingProcessors.remove(processor);
	}

	// ----------------------------------------------- Accessors for sub-classes

	protected final Endpoint<S> getEndpoint() {
		return endpoint;
	}

	// protected final Handler<S> getHandler() {
	// return handler;
	// }

	// protected final void setHandler(Handler<S> handler) {
	// this.handler = handler;
	// }

	// -------------------------------------------------------- Abstract methods

	/**
	 * Concrete implementations need to provide access to their logger to be used by
	 * the abstract classes.
	 * 
	 * @return the logger
	 */
	protected abstract Log getLog();

	/**
	 * Obtain the prefix to be used when construction a name for this protocol
	 * handler. The name will be prefix-address-port.
	 * 
	 * @return the prefix
	 */
	protected abstract String getNamePrefix();

	/**
	 * Obtain the name of the protocol, (Http, Ajp, etc.). Used with JMX.
	 * 
	 * @return the protocol name
	 */
	protected abstract String getProtocolName();

	/**
	 * Find a suitable handler for the protocol negotiated at the network layer.
	 * 
	 * @param name The name of the requested negotiated protocol.
	 * @return The instance where {@link UpgradeProtocol#getAlpnName()} matches the
	 *         requested protocol
	 */
	protected abstract UpgradeProtocol getNegotiatedProtocol(String name);

	/**
	 * Find a suitable handler for the protocol upgraded name specified. This is
	 * used for direct connection protocol selection.
	 * 
	 * @param name The name of the requested negotiated protocol.
	 * @return The instance where {@link UpgradeProtocol#getAlpnName()} matches the
	 *         requested protocol
	 */
	protected abstract UpgradeProtocol getUpgradeProtocol(String name);

	protected Processor popRecycledProcessors() {
		return recycledProcessors.pop();
	}

	/**
	 * Create and configure a new Processor instance for the current protocol
	 * implementation.
	 *
	 * @return A fully configured Processor instance that is ready to use
	 */
	protected abstract Processor createProcessor();

	protected abstract Processor createUpgradeProcessor(SocketChannel channel, UpgradeToken upgradeToken);

	// ----------------------------------------------------- JMX related methods

	protected String domain;
	protected ObjectName oname;
	protected MBeanServer mserver;

	public ObjectName getObjectName() {
		return oname;
	}

	public String getDomain() {
		return domain;
	}

	@Override
	public ObjectName preRegister(MBeanServer server, ObjectName name) throws Exception {
		oname = name;
		mserver = server;
		domain = name.getDomain();
		return name;
	}

	@Override
	public void postRegister(Boolean registrationDone) {
		// NOOP
	}

	@Override
	public void preDeregister() throws Exception {
		// NOOP
	}

	@Override
	public void postDeregister() {
		// NOOP
	}

	private ObjectName createObjectName() throws MalformedObjectNameException {
		// Use the same domain as the connector
		domain = getAdapter().getDomain();

		if (domain == null) {
			return null;
		}

		StringBuilder name = new StringBuilder(getDomain());
		name.append(":type=ProtocolHandler,port=");
		int port = getPortWithOffset();
		if (port > 0) {
			name.append(port);
		} else {
			name.append("auto-");
			name.append(getNameIndex());
		}
		InetAddress address = getAddress();
		if (address != null) {
			name.append(",address=");
			name.append(ObjectName.quote(address.getHostAddress()));
		}
		return new ObjectName(name.toString());
	}

	// ------------------------------------------------------- Lifecycle methods

	/*
	 * NOTE: There is no maintenance of state or checking for valid transitions
	 * within this class. It is expected that the connector will maintain state and
	 * prevent invalid state transitions.
	 */

	@Override
	public void init() throws Exception {
		if (getLog().isInfoEnabled()) {
			getLog().info(sm.getString("abstractProtocolHandler.init", getName()));
			logPortOffset();
		}

		if (oname == null) {
			// Component not pre-registered so register it
			oname = createObjectName();
			if (oname != null) {
				Registry.getRegistry(null, null).registerComponent(this, oname, null);
			}
		}

		if (this.domain != null) {
			rgOname = new ObjectName(domain + ":type=GlobalRequestProcessor,name=" + getName());
			Registry.getRegistry(null, null).registerComponent(getGlobal(), rgOname, null);
		}

		String endpointName = getName();
		endpoint.setName(endpointName.substring(1, endpointName.length() - 1));
		endpoint.setDomain(domain);

		endpoint.init();
	}

	@Override
	public void start() throws Exception {

		if (getLog().isInfoEnabled()) {
			getLog().info(sm.getString("abstractProtocolHandler.start", getName()));
			logPortOffset();
		}
		// Create worker collection
		if (getExecutor() == null) {
			createExecutor();
		}
		endpoint.start();
		monitorFuture = getUtilityExecutor().scheduleWithFixedDelay(new Runnable() {
			@Override
			public void run() {
				if (!isPaused()) {
					startAsyncTimeout();
				}
			}
		}, 0, 60, TimeUnit.SECONDS);
	}

	/**
	 * Note: The name of this method originated with the Servlet 3.0 asynchronous
	 * processing but evolved over time to represent a timeout that is triggered
	 * independently of the socket read/write timeouts.
	 */
	protected void startAsyncTimeout() {
		if (timeoutFuture == null || (timeoutFuture != null && timeoutFuture.isDone())) {
			if (timeoutFuture != null && timeoutFuture.isDone()) {
				// There was an error executing the scheduled task, get it and log it
				try {
					timeoutFuture.get();
				} catch (InterruptedException | ExecutionException e) {
					getLog().error(sm.getString("abstractProtocolHandler.asyncTimeoutError"), e);
				}
			}
			timeoutFuture = getUtilityExecutor().scheduleAtFixedRate(new Runnable() {
				@Override
				public void run() {
					long now = System.currentTimeMillis();
					for (Processor processor : waitingProcessors) {
						processor.timeoutAsync(now);
					}
				}
			}, 1, 1, TimeUnit.SECONDS);
		}
	}

	protected void stopAsyncTimeout() {
		if (timeoutFuture != null) {
			timeoutFuture.cancel(false);
			timeoutFuture = null;
		}
	}

	@Override
	public void pause() throws Exception {
		if (getLog().isInfoEnabled()) {
			getLog().info(sm.getString("abstractProtocolHandler.pause", getName()));
		}

		stopAsyncTimeout();
		endpoint.pause();

		/*
		 * Inform all the processors associated with current connections that the
		 * endpoint is being paused. Most won't care. Those processing multiplexed
		 * streams may wish to take action. For example, HTTP/2 may wish to stop
		 * accepting new streams.
		 *
		 * Note that even if the endpoint is resumed, there is (currently) no API to
		 * inform the Processors of this.
		 */
		for (SocketChannel channel : endpoint.getConnections()) {
			Processor processor = (Processor) channel.getCurrentProcessor();
			if (processor != null) {
				processor.pause();
			}
		}
	}

	public boolean isPaused() {
		return endpoint.isPaused();
	}

	@Override
	public void resume() throws Exception {
		if (getLog().isInfoEnabled()) {
			getLog().info(sm.getString("abstractProtocolHandler.resume", getName()));
		}

		endpoint.resume();
		startAsyncTimeout();
	}

	@Override
	public void stop() throws Exception {
		if (getLog().isInfoEnabled()) {
			getLog().info(sm.getString("abstractProtocolHandler.stop", getName()));
			logPortOffset();
		}

		if (monitorFuture != null) {
			monitorFuture.cancel(true);
			monitorFuture = null;
		}
		stopAsyncTimeout();
		// Timeout any waiting processor
		for (Processor processor : waitingProcessors) {
			processor.timeoutAsync(-1);
		}

		endpoint.stop();

		shutdownExecutor();

		recycle();
	}

	@Override
	public void destroy() throws Exception {
		if (getLog().isInfoEnabled()) {
			getLog().info(sm.getString("abstractProtocolHandler.destroy", getName()));
			logPortOffset();
		}

		try {
			endpoint.destroy();
		} finally {
			if (oname != null) {
				if (mserver == null) {
					Registry.getRegistry(null, null).unregisterComponent(oname);
				} else {
					// Possibly registered with a different MBeanServer
					try {
						mserver.unregisterMBean(oname);
					} catch (MBeanRegistrationException | InstanceNotFoundException e) {
						getLog().info(sm.getString("abstractProtocol.mbeanDeregistrationFailed", oname, mserver));
					}
				}
			}

			if (rgOname != null) {
				Registry.getRegistry(null, null).unregisterComponent(rgOname);
			}
		}

		recycle();
	}

	@Override
	public void closeServerSocketGraceful() {
		endpoint.closeServerSocketGraceful();
	}

	private void logPortOffset() {
		if (getPort() != getPortWithOffset()) {
			getLog().info(sm.getString("abstractProtocolHandler.portOffset", getName(), String.valueOf(getPort()),
					String.valueOf(getPortOffset())));
		}
	}

	// ---------------------------------------------- Request processing methods

	// protected abstract void processSocket(Channel channel, SocketEvent event);

	// @Override
	// public AbstractProtocol<S> getProtocol() {
	// return this;
	// }

	// @Override
	public Object getGlobal() {
		return global;
	}

	// @Override
	protected void recycle() {
		recycledProcessors.clear();
	}

	/**
	 * Transfers processing to a container thread.
	 *
	 * @param runnable The actions to process on a container thread
	 *
	 * @throws RejectedExecutionException If the runnable cannot be executed
	 */
//	@Override
//	public void execute(Runnable runnable) {
//		Executor executor = getExecutor();
//		if (!endpoint.isRunning() || executor == null) {
//			throw new RejectedExecutionException();
//		}
//		executor.execute(runnable);
//	}

	protected void longPoll(Channel channel, Processor processor) {
		if (!processor.isAsync()) {
			// This is currently only used with HTTP
			// Either:
			// - this is an upgraded connection
			// - the request line/headers have not been completely
			// read
			channel.registerReadInterest();
		}
	}

//	@Override
//	public Set<S> getOpenSockets() {
//		Set<Channel> set = endpoint.getConnections();
//		Set<S> result = new HashSet<>();
//		for (Channel socketWrapper : set) {
//			S socket = socketWrapper.getSocket();
//			if (socket != null) {
//				result.add(socket);
//			}
//		}
//		return result;
//	}

	/**
	 * Expected to be used by the handler once the processor is no longer required.
	 *
	 * @param processor Processor being released (that was associated with the
	 *                  socket)
	 */
	protected void release(Processor processor) {
		if (processor != null) {
			processor.recycle();
			if (processor.isUpgrade()) {
				// UpgradeProcessorInternal instances can utilise AsyncIO.
				// If they do, the processor will not pass through the
				// process() method and be removed from waitingProcessors
				// so do that here.
				if (processor instanceof UpgradeProcessorInternal) {
					if (((UpgradeProcessorInternal) processor).hasAsyncIO()) {
						removeWaitingProcessor(processor);
					}
				}
			} else {
				// After recycling, only instances of UpgradeProcessorBase
				// will return true for isUpgrade().
				// Instances of UpgradeProcessorBase should not be added to
				// recycledProcessors since that pool is only for AJP or
				// HTTP processors
				if (shouldRecycle(processor)) {
					recycledProcessors.push(processor);
					if (getLog().isDebugEnabled()) {
						getLog().debug("Pushed Processor [" + processor + "]");
					}
				}
			}
		}
	}

	/**
	 * Expected to be used by the Endpoint to release resources on socket close,
	 * errors etc.
	 */
	// @Override
	public void release(SocketChannel channel) {
		Processor processor = (Processor) channel.getCurrentProcessor();
		channel.setCurrentProcessor(null);
		release(processor);
	}

	public boolean shouldRecycle(Processor processor) {
		return processor instanceof Http11Processor;
	}

	protected void register(Processor processor) {
		if (getDomain() != null) {
			synchronized (this) {
				try {
					long count = registerCount.incrementAndGet();
					RequestInfo rp = processor.getRequestData().getRequestProcessor();
					rp.setGlobalProcessor(global);
					ObjectName rpName = new ObjectName(getDomain() + ":type=RequestProcessor,worker=" + getName()
							+ ",name=" + getProtocolName() + "Request" + count);
					if (getLog().isDebugEnabled()) {
						getLog().debug("Register [" + processor + "] as [" + rpName + "]");
					}
					Registry.getRegistry(null, null).registerComponent(rp, rpName, null);
					rp.setRpName(rpName);
				} catch (Exception e) {
					getLog().warn(sm.getString("abstractProtocol.processorRegisterError"), e);
				}
			}
		}
	}

	protected void unregister(Processor processor) {
		if (getDomain() != null) {
			synchronized (this) {
				try {
					RequestData r = processor.getRequestData();
					if (r == null) {
						// Probably an UpgradeProcessor
						return;
					}
					RequestInfo rp = r.getRequestProcessor();
					rp.setGlobalProcessor(null);
					ObjectName rpName = rp.getRpName();
					if (getLog().isDebugEnabled()) {
						getLog().debug("Unregister [" + rpName + "]");
					}
					Registry.getRegistry(null, null).unregisterComponent(rpName);
					rp.setRpName(null);
				} catch (Exception e) {
					getLog().warn(sm.getString("abstractProtocol.processorUnregisterError"), e);
				}
			}
		}
	}

	protected class RecycledProcessors extends SynchronizedStack<Processor> {

		// private final transient ConnectionHandler<?> handler;
		protected final AtomicInteger size = new AtomicInteger(0);

		public RecycledProcessors() {
			// this.handler = handler;
		}

		@SuppressWarnings("sync-override") // Size may exceed cache size a bit
		@Override
		public boolean push(Processor processor) {
			int cacheSize = getProcessorCache();
			boolean offer = cacheSize == -1 ? true : size.get() < cacheSize;
			// avoid over growing our cache or add after we have stopped
			boolean result = false;
			if (offer) {
				result = super.push(processor);
				if (result) {
					size.incrementAndGet();
				}
			}
			if (!result)
				unregister(processor);
			return result;
		}

		@SuppressWarnings("sync-override") // OK if size is too big briefly
		@Override
		public Processor pop() {
			Processor result = super.pop();
			if (result != null) {
				size.decrementAndGet();
			}
			return result;
		}

		@Override
		public synchronized void clear() {
			Processor next = pop();
			while (next != null) {
				unregister(next);
				next = pop();
			}
			super.clear();
			size.set(0);
		}
	}

	public class ParseInIoHandler implements Handler<S> {

		private Handler next;

		public ParseInIoHandler(Handler next) {
			super();
			this.next = next;
		}

		@Override
		public AbstractProtocol<S> getProtocol() {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public void processSocket(Channel channel, SocketEvent event, boolean dispatch) {
			if (channel == null) {
				// Nothing to do. Socket has been closed.
				return;
			}
			if (channel instanceof SocketChannel) {
				SocketChannel socketChannel = (SocketChannel) channel;

				if (getLog().isDebugEnabled()) {
					getLog().debug(sm.getString("abstractConnectionHandler.process", channel, event));// .getSocket()
				}

				// S socket = channel.getSocket();

				Processor processor = (Processor) socketChannel.getCurrentProcessor();
				if (getLog().isDebugEnabled()) {
					getLog().debug(sm.getString("abstractConnectionHandler.connectionsGet", processor, channel));// socket
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
						processor = recycledProcessors.pop();
						if (getLog().isDebugEnabled()) {
							getLog().debug(sm.getString("abstractConnectionHandler.processorPop", processor));
						}
					}
					if (processor == null) {
						processor = createProcessor();
						register(processor);
						if (getLog().isDebugEnabled()) {
							getLog().debug(sm.getString("abstractConnectionHandler.processorCreate", processor));
						}
					}

					processor.setSslSupport(socketChannel.getSslSupport(getClientCertProvider()));

					// Associate the processor with the connection
					socketChannel.setCurrentProcessor(processor);

					if (processor.processInIoThread(socketChannel, event)) {
						dispatched = true;
						next.processSocket(channel, event, dispatch);
					}

				} catch (java.net.SocketException e) {
					// SocketExceptions are normal
					getLog().debug(sm.getString("abstractConnectionHandler.socketexception.debug"), e);
				} catch (java.io.IOException e) {
					// IOExceptions are normal
					getLog().debug(sm.getString("abstractConnectionHandler.ioexception.debug"), e);
				} catch (ProtocolException e) {
					// Protocol exceptions normally mean the client sent invalid or
					// incomplete data.
					getLog().debug(sm.getString("abstractConnectionHandler.protocolexception.debug"), e);
				}
				// Future developers: if you discover any other
				// rare-but-nonfatal exceptions, catch them here, and log as
				// above.
				catch (OutOfMemoryError oome) {
					// Try and handle this here to give Tomcat a chance to close the
					// connection and prevent clients waiting until they time out.
					// Worst case, it isn't recoverable and the attempt at logging
					// will trigger another OOME.
					getLog().error(sm.getString("abstractConnectionHandler.oome"), oome);
				} catch (Throwable e) {
					ExceptionUtils.handleThrowable(e);
					// any other exception or error is odd. Here we log it
					// with "ERROR" level, so it will show up even on
					// less-than-verbose logs.
					getLog().error(sm.getString("abstractConnectionHandler.error"), e);
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

}
