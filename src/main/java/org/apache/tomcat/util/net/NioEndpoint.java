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
package org.apache.tomcat.util.net;

import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.Channel;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.CompletionHandler;
import java.nio.channels.FileChannel;
import java.nio.channels.NetworkChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.WritableByteChannel;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.IntrospectionUtils;
import org.apache.tomcat.util.collections.SynchronizedQueue;
import org.apache.tomcat.util.collections.SynchronizedStack;
import org.apache.tomcat.util.net.SocketWrapperBase.ByteBufferWrapper;
import org.apache.tomcat.util.net.jsse.JSSESupport;

/**
 * NIO tailored thread pool, providing the following services:
 * <ul>
 * <li>Socket acceptor thread</li>
 * <li>Socket poller thread</li>
 * <li>Worker threads pool</li>
 * </ul>
 *
 * When switching to Java 5, there's an opportunity to use the virtual machine's
 * thread pool.
 *
 * @author Mladen Turk
 * @author Remy Maucherat
 */
public class NioEndpoint extends SocketWrapperBaseEndpoint<NioChannel, SocketChannel> {

	// -------------------------------------------------------------- Constants

	private static final Log log = LogFactory.getLog(NioEndpoint.class);

	public static final int OP_REGISTER = 0x100; // register interest op

	// ----------------------------------------------------------------- Fields

	private NioSelectorPool selectorPool = new NioSelectorPool();

	/**
	 * Server socket "pointer".
	 */
	private volatile ServerSocketChannel serverSock = null;

	/**
	 * Stop latch used to wait for poller stop
	 */
	private volatile CountDownLatch stopLatch = null;

	/**
	 * Cache for poller events
	 */
	private SynchronizedStack<PollerEvent> eventCache;

	/**
	 * Bytebuffer cache, each channel holds a set of buffers (two, except for SSL
	 * holds four)
	 */
	private SynchronizedStack<NioChannel> nioChannels;

	// ------------------------------------------------------------- Properties

	public NioEndpoint() {
		// TODO remove
		setUseAsyncIO(false);
	}

	/**
	 * Generic properties, introspected
	 */
	@Override
	public boolean setProperty(String name, String value) {
		final String selectorPoolName = "selectorPool.";
		try {
			if (name.startsWith(selectorPoolName)) {
				return IntrospectionUtils.setProperty(selectorPool, name.substring(selectorPoolName.length()), value);
			} else {
				return super.setProperty(name, value);
			}
		} catch (Exception e) {
			log.error(sm.getString("endpoint.setAttributeError", name, value), e);
			return false;
		}
	}

	/**
	 * Use System.inheritableChannel to obtain channel from stdin/stdout.
	 */
	private boolean useInheritedChannel = false;

	public void setUseInheritedChannel(boolean useInheritedChannel) {
		this.useInheritedChannel = useInheritedChannel;
	}

	public boolean getUseInheritedChannel() {
		return useInheritedChannel;
	}

	/**
	 * Priority of the poller threads.
	 */
	private int pollerThreadPriority = Thread.NORM_PRIORITY;

	public void setPollerThreadPriority(int pollerThreadPriority) {
		this.pollerThreadPriority = pollerThreadPriority;
	}

	public int getPollerThreadPriority() {
		return pollerThreadPriority;
	}

	/**
	 * NO-OP.
	 *
	 * @param pollerThreadCount Unused
	 *
	 * @deprecated Will be removed in Tomcat 10.
	 */
	@Deprecated
	public void setPollerThreadCount(int pollerThreadCount) {
	}

	/**
	 * Always returns 1.
	 *
	 * @return Always 1.
	 *
	 * @deprecated Will be removed in Tomcat 10.
	 */
	@Deprecated
	public int getPollerThreadCount() {
		return 1;
	}

	private long selectorTimeout = 1000;

	public void setSelectorTimeout(long timeout) {
		this.selectorTimeout = timeout;
	}

	public long getSelectorTimeout() {
		return this.selectorTimeout;
	}

	/**
	 * The socket poller.
	 */
	private Poller poller = null;

	public void setSelectorPool(NioSelectorPool selectorPool) {
		this.selectorPool = selectorPool;
	}

	/**
	 * Is deferAccept supported?
	 */
	@Override
	public boolean getDeferAccept() {
		// Not supported
		return false;
	}

	// --------------------------------------------------------- Public Methods

	/**
	 * Number of keep-alive sockets.
	 *
	 * @return The number of sockets currently in the keep-alive state waiting for
	 *         the next request to be received on the socket
	 */
	public int getKeepAliveCount() {
		if (poller == null) {
			return 0;
		} else {
			return poller.getKeyCount();
		}
	}

	// ----------------------------------------------- Public Lifecycle Methods

	/**
	 * Initialize the endpoint.
	 */
	@Override
	public void bind() throws Exception {
		initServerSocket();

		setStopLatch(new CountDownLatch(1));

		// Initialize SSL if needed
		initialiseSsl();

		selectorPool.open(getName());
	}

	// Separated out to make it easier for folks that extend NioEndpoint to
	// implement custom [server]sockets
	protected void initServerSocket() throws Exception {
		if (!getUseInheritedChannel()) {
			serverSock = ServerSocketChannel.open();
			socketProperties.setProperties(serverSock.socket());
			InetSocketAddress addr = new InetSocketAddress(getAddress(), getPortWithOffset());
			serverSock.socket().bind(addr, getAcceptCount());
		} else {
			// Retrieve the channel provided by the OS
			Channel ic = System.inheritedChannel();
			if (ic instanceof ServerSocketChannel) {
				serverSock = (ServerSocketChannel) ic;
			}
			if (serverSock == null) {
				throw new IllegalArgumentException(sm.getString("endpoint.init.bind.inherited"));
			}
		}
		serverSock.configureBlocking(true); // mimic APR behavior
	}

	/**
	 * Start the NIO endpoint, creating acceptor, poller threads.
	 */
	@Override
	public void startInternal() throws Exception {

		if (socketProperties.getEventCache() != 0) {
			eventCache = new SynchronizedStack<>(SynchronizedStack.DEFAULT_SIZE, socketProperties.getEventCache());
		}
		if (socketProperties.getBufferPool() != 0) {
			nioChannels = new SynchronizedStack<>(SynchronizedStack.DEFAULT_SIZE, socketProperties.getBufferPool());
		}

		initializeConnectionLatch();

		// Start poller thread
		poller = new Poller();
		Thread pollerThread = new Thread(poller, getName() + "-ClientPoller");
		pollerThread.setPriority(getThreadPriority());
		pollerThread.setDaemon(getDaemon());
		pollerThread.start();

		startAcceptorThread();

	}

	/**
	 * Stop the endpoint. This will cause all processing threads to stop.
	 */
	@Override
	public void stopInternal() {

		if (poller != null) {
			poller.destroy();
			poller = null;
		}
		try {
			if (!getStopLatch().await(selectorTimeout + 100, TimeUnit.MILLISECONDS)) {
				log.warn(sm.getString("endpoint.nio.stopLatchAwaitFail"));
			}
		} catch (InterruptedException e) {
			log.warn(sm.getString("endpoint.nio.stopLatchAwaitInterrupted"), e);
		}
		if (eventCache != null) {
			eventCache.clear();
			eventCache = null;
		}
		if (nioChannels != null) {
			nioChannels.clear();
			nioChannels = null;
		}

	}

	/**
	 * Deallocate NIO memory pools, and close server socket.
	 */
	@Override
	public void unbind() throws Exception {
		if (log.isDebugEnabled()) {
			log.debug("Destroy initiated for " + new InetSocketAddress(getAddress(), getPortWithOffset()));
		}
		if (isRunning()) {
			stop();
		}
		try {
			doCloseServerSocket();
		} catch (IOException ioe) {
			getLog().warn(sm.getString("endpoint.serverSocket.closeFailed", getName()), ioe);
		}
		destroySsl();
		super.unbind();
		// if (getHandler() != null) {
		// getHandler().recycle();
		// }
		selectorPool.close();
		if (log.isDebugEnabled()) {
			log.debug("Destroy completed for " + new InetSocketAddress(getAddress(), getPortWithOffset()));
		}
	}

	@Override
	protected void doCloseServerSocket() throws IOException {
		if (!getUseInheritedChannel() && serverSock != null) {
			// Close server socket
			serverSock.close();
		}
		serverSock = null;
	}

	// ------------------------------------------------------ Protected Methods

	protected NioSelectorPool getSelectorPool() {
		return selectorPool;
	}

	protected SynchronizedStack<NioChannel> getNioChannels() {
		return nioChannels;
	}

	protected Poller getPoller() {
		return poller;
	}

	protected CountDownLatch getStopLatch() {
		return stopLatch;
	}

	protected void setStopLatch(CountDownLatch stopLatch) {
		this.stopLatch = stopLatch;
	}

	/**
	 * Process the specified connection.
	 * 
	 * @param socket The socket channel
	 * @return <code>true</code> if the socket was correctly configured and
	 *         processing may continue, <code>false</code> if the socket needs to be
	 *         close immediately
	 */
	@Override
	protected boolean setSocketOptions(SocketChannel socket) {
		NioSocketWrapper socketWrapper = null;
		try {
			// Allocate channel and wrapper
			NioChannel channel = null;
			if (nioChannels != null) {
				channel = nioChannels.pop();
			}
			if (channel == null) {
				if (isSSLEnabled()) {
					channel = new SecureNioChannel(socketProperties, selectorPool, this);
				} else {
					channel = new NioChannel(socketProperties.getAppReadBufSize(),
							socketProperties.getAppWriteBufSize(), socketProperties.getDirectBuffer());
				}
			}
			NioSocketWrapper newWrapper = new NioSocketWrapper(channel, this);
			channel.reset(socket, newWrapper);
			connections.put(socket, newWrapper);
			socketWrapper = newWrapper;

			// Set socket properties
			// Disable blocking, polling will be used
			socket.configureBlocking(false);
			socketProperties.setProperties(socket.socket());

			socketWrapper.setReadTimeout(getConnectionTimeout());
			socketWrapper.setWriteTimeout(getConnectionTimeout());
			// socketWrapper.setKeepAliveLeft(NioEndpoint.this.getMaxKeepAliveRequests());
			// socketWrapper.setSecure(isSSLEnabled());
			poller.register(socketWrapper);
			return true;
		} catch (Throwable t) {
			ExceptionUtils.handleThrowable(t);
			try {
				log.error(sm.getString("endpoint.socketOptionsError"), t);
			} catch (Throwable tt) {
				ExceptionUtils.handleThrowable(tt);
			}
			if (socketWrapper == null) {
				destroySocket(socket);
			}
		}
		// Tell to close the socket if needed
		return false;
	}

	@Override
	protected void destroySocket(SocketChannel socket) {
		countDownConnection();
		try {
			socket.close();
		} catch (IOException ioe) {
			if (log.isDebugEnabled()) {
				log.debug(sm.getString("endpoint.err.close"), ioe);
			}
		}
	}

	private void closeSocketWrapper(NioSocketWrapper socketWrapper) {
		if (socketWrapper.getSocket().getIOChannel() != null) {
			connections.remove(socketWrapper.getSocket().getIOChannel());
		}
	}

	@Override
	protected NetworkChannel getServerSocket() {
		return serverSock;
	}

	@Override
	protected SocketChannel serverSocketAccept() throws Exception {
		SocketChannel socketChannel = serverSock.accept();
		return socketChannel;
	}

	@Override
	protected Log getLog() {
		return log;
	}

	// ----------------------------------------------------- Poller Inner Classes

	/**
	 * PollerEvent, cacheable object for poller events to avoid GC
	 */
	public static class PollerEvent {

		private NioSocketWrapper socketWrapper;
		private int interestOps;

		public PollerEvent(NioSocketWrapper socketWrapper, int intOps) {
			reset(socketWrapper, intOps);
		}

		public void reset(NioSocketWrapper socketWrapper, int intOps) {
			this.socketWrapper = socketWrapper;
			this.interestOps = intOps;
		}

		public NioSocketWrapper getSocketWrapper() {
			return socketWrapper;
		}

		public int getInterestOps() {
			return interestOps;
		}

		public void reset() {
			reset(null, 0);
		}

		@Override
		public String toString() {
			return "Poller event: socket [" + socketWrapper.getSocket() + "], socketWrapper [" + socketWrapper
					+ "], interestOps [" + interestOps + "]";
		}
	}

	/**
	 * Poller class.
	 */
	public class Poller implements Runnable {

		private Selector selector;
		private final SynchronizedQueue<PollerEvent> events = new SynchronizedQueue<>();

		private volatile boolean close = false;
		// Optimize expiration handling
		private long nextExpiration = 0;

		private AtomicLong wakeupCounter = new AtomicLong(0);

		private volatile int keyCount = 0;

		public Poller() throws IOException {
			this.selector = Selector.open();
		}

		public int getKeyCount() {
			return keyCount;
		}

		public Selector getSelector() {
			return selector;
		}

		/**
		 * Destroy the poller.
		 */
		protected void destroy() {
			// Wait for polltime before doing anything, so that the poller threads
			// exit, otherwise parallel closure of sockets which are still
			// in the poller can cause problems
			close = true;
			selector.wakeup();
		}

		private void addEvent(PollerEvent event) {
			events.offer(event);
			if (wakeupCounter.incrementAndGet() == 0) {
				selector.wakeup();
			}
		}

		/**
		 * Add specified socket and associated pool to the poller. The socket will be
		 * added to a temporary array, and polled first after a maximum amount of time
		 * equal to pollTime (in most cases, latency will be much lower, however).
		 *
		 * @param socketWrapper to add to the poller
		 * @param interestOps   Operations for which to register this socket with the
		 *                      Poller
		 */
		public void add(NioSocketWrapper socketWrapper, int interestOps) {
			PollerEvent r = null;
			if (eventCache != null) {
				r = eventCache.pop();
			}
			if (r == null) {
				r = new PollerEvent(socketWrapper, interestOps);
			} else {
				r.reset(socketWrapper, interestOps);
			}
			addEvent(r);
			if (close) {
				getHandler().processSocket(socketWrapper, SocketEvent.STOP, false);
			}
		}

		/**
		 * Processes events in the event queue of the Poller.
		 *
		 * @return <code>true</code> if some events were processed, <code>false</code>
		 *         if queue was empty
		 */
		public boolean events() {
			boolean result = false;

			PollerEvent pe = null;
			for (int i = 0, size = events.size(); i < size && (pe = events.poll()) != null; i++) {
				result = true;
				NioSocketWrapper socketWrapper = pe.getSocketWrapper();
				NioChannel channel = socketWrapper.getSocket();
				int interestOps = pe.getInterestOps();
				if (interestOps == OP_REGISTER) {
					try {
						channel.getIOChannel().register(getSelector(), SelectionKey.OP_READ, socketWrapper);
					} catch (Exception x) {
						log.error(sm.getString("endpoint.nio.registerFail"), x);
					}
				} else {
					if (channel.getIOChannel() == null) {
						System.out.println(channel + "IOChannel has Closed");
					} else {
						final SelectionKey key = channel.getIOChannel().keyFor(getSelector());
						if (key == null) {
							// The key was cancelled (e.g. due to socket closure)
							// and removed from the selector while it was being
							// processed. Count down the connections at this point
							// since it won't have been counted down when the socket
							// closed.
							socketWrapper.close();
						} else {
							final NioSocketWrapper attachment = (NioSocketWrapper) key.attachment();
							if (attachment != null) {
								// We are registering the key to start with, reset the fairness counter.
								try {
									int ops = key.interestOps() | interestOps;
									// attachment.interestOps(ops);
									key.interestOps(ops);
								} catch (CancelledKeyException ckx) {
									cancelledKey(key, socketWrapper);
								}
							} else {
								cancelledKey(key, attachment);
							}
						}
					}
				}
				if (isRunning() && !isPaused() && eventCache != null) {
					pe.reset();
					eventCache.push(pe);
				}
			}

			return result;
		}

		/**
		 * Registers a newly created socket with the poller.
		 *
		 * @param socket        The newly created socket
		 * @param socketWrapper The socket wrapper
		 */
		public void register(final NioSocketWrapper socketWrapper) {
			// socketWrapper.interestOps(SelectionKey.OP_READ);// this is what OP_REGISTER
			// turns into.
			PollerEvent event = null;
			if (eventCache != null) {
				event = eventCache.pop();
			}
			if (event == null) {
				event = new PollerEvent(socketWrapper, OP_REGISTER);
			} else {
				event.reset(socketWrapper, OP_REGISTER);
			}
			addEvent(event);
		}

		private void closeSocketWrapper(final NioSocketWrapper socketWrapper) {
			SocketChannel socketChannel = socketWrapper.getSocket().getIOChannel();
			if (socketChannel != null) {
				poller.cancelledKey(socketChannel.keyFor(poller.getSelector()), socketWrapper, false);
			}
		}

		private void cancelledKey(SelectionKey sk, SocketWrapperBase<NioChannel> socketWrapper) {
			cancelledKey(sk, socketWrapper, true);
		}

		private void cancelledKey(SelectionKey sk, SocketWrapperBase<NioChannel> socketWrapper, boolean doClose) {
			try {
				// If is important to cancel the key first, otherwise a deadlock may occur
				// between the
				// poller select and the socket channel close which would cancel the key
				if (sk != null) {
					sk.attach(null);
					if (sk.isValid()) {
						sk.cancel();
					}
				}
			} catch (Throwable e) {
				ExceptionUtils.handleThrowable(e);
				if (log.isDebugEnabled()) {
					log.error(sm.getString("endpoint.debug.channelCloseFail"), e);
				}
			} finally {
				if (socketWrapper != null && doClose) {
					socketWrapper.close();
				}
			}
		}

		/**
		 * The background thread that adds sockets to the Poller, checks the poller for
		 * triggered events and hands the associated socket off to an appropriate
		 * processor as events occur.
		 */
		@Override
		public void run() {
			// Loop until destroy() is called
			while (true) {

				boolean hasEvents = false;

				try {
					if (!close) {
						hasEvents = events();
						if (wakeupCounter.getAndSet(-1) > 0) {
							// If we are here, means we have other stuff to do
							// Do a non blocking select
							keyCount = selector.selectNow();
						} else {
							keyCount = selector.select(selectorTimeout);
						}
						wakeupCounter.set(0);
					}
					if (close) {
						events();
						timeout(0, false);
						try {
							selector.close();
						} catch (IOException ioe) {
							log.error(sm.getString("endpoint.nio.selectorCloseFail"), ioe);
						}
						break;
					}
				} catch (Throwable x) {
					ExceptionUtils.handleThrowable(x);
					log.error(sm.getString("endpoint.nio.selectorLoopError"), x);
					continue;
				}
				// Either we timed out or we woke up, process events first
				if (keyCount == 0) {
					hasEvents = (hasEvents | events());
				}

				Iterator<SelectionKey> iterator = keyCount > 0 ? selector.selectedKeys().iterator() : null;
				// Walk through the collection of ready keys and dispatch
				// any active event.
				while (iterator != null && iterator.hasNext()) {
					SelectionKey sk = iterator.next();
					NioSocketWrapper socketWrapper = (NioSocketWrapper) sk.attachment();
					// Attachment may be null if another thread has called
					// cancelledKey()
					if (socketWrapper == null) {
						iterator.remove();
					} else {
						iterator.remove();
						processKey(sk, socketWrapper);
					}
				}

				// Process timeouts
				timeout(keyCount, hasEvents);
			}

			getStopLatch().countDown();
		}

		protected void processKey(SelectionKey sk, NioSocketWrapper socketWrapper) {
			try {
				if (close) {
					cancelledKey(sk, socketWrapper);
				} else if (sk.isValid() && socketWrapper != null) {
					if (sk.isReadable() || sk.isWritable()) {
						if (socketWrapper.getSendfileData() != null) {
							processSendfile(sk, socketWrapper, false);
						} else {
							unreg(sk, socketWrapper, sk.readyOps());
							boolean closeSocket = false;
							// Read goes before write
							if (sk.isReadable()) {
								if (socketWrapper.getReadLatch() != null) {
									if (socketWrapper.registeReadTimeStamp != -1) {
										long useTime = System.currentTimeMillis() - socketWrapper.registeReadTimeStamp;
										socketWrapper.awaitReadTime += useTime;
//										System.out.println(socketWrapper.getRemotePort() + " await read use " + useTime
//												+ " total wait use " + socketWrapper.awaitReadTime + " count "
//												+ socketWrapper.registeReadCount);
									}
									socketWrapper.startProcessTimeStamp = System.currentTimeMillis();
//									System.out.println(socketWrapper.getRemotePort() + " countDownReadLatch");
									socketWrapper.getReadLatch().countDown();
								} else {
									if (socketWrapper.getReadOperation() != null) {
										if (!socketWrapper.getReadOperation().process()) {
											closeSocket = true;
										}
									} else {
										if (socketWrapper.registeReadTimeStamp != -1) {
											long useTime = System.currentTimeMillis()
													- socketWrapper.registeReadTimeStamp;
											socketWrapper.awaitReadTime += useTime;
//											System.out.println(socketWrapper.getRemotePort() + " await read use "
//													+ useTime + " total wait use " + socketWrapper.awaitReadTime
//													+ " count " + socketWrapper.registeReadCount);
										}
										socketWrapper.startProcessTimeStamp = System.currentTimeMillis();
										getHandler().processSocket(socketWrapper, SocketEvent.OPEN_READ, true);
									}
								}
							}
							if (!closeSocket && sk.isWritable()) {
								if (socketWrapper.getWriteLatch() != null) {
//									System.out.println(socketWrapper.getRemotePort() + " countDownWriteLatch");
									socketWrapper.getWriteLatch().countDown();
								} else {
									if (socketWrapper.getWriteOperation() != null) {
										if (!socketWrapper.getWriteOperation().process()) {
											closeSocket = true;
										}
									} else {
										getHandler().processSocket(socketWrapper, SocketEvent.OPEN_WRITE, true);
									}
								}
							}
							if (closeSocket) {
								cancelledKey(sk, socketWrapper);
							}
						}
					}
				} else {
					// Invalid key
					cancelledKey(sk, socketWrapper);
				}
			} catch (CancelledKeyException ckx) {
				cancelledKey(sk, socketWrapper);
			} catch (Throwable t) {
				ExceptionUtils.handleThrowable(t);
				log.error(sm.getString("endpoint.nio.keyProcessingError"), t);
			}
		}

		public SendfileState processSendfile(SelectionKey sk, NioSocketWrapper socketWrapper,
				boolean calledByProcessor) {
			NioChannel sc = null;
			try {
				unreg(sk, socketWrapper, sk.readyOps());
				SendfileData sd = socketWrapper.getSendfileData();

				if (log.isTraceEnabled()) {
					log.trace("Processing send file for: " + sd.fileName);
				}

				if (sd.fchannel == null) {
					// Setup the file channel
					File f = new File(sd.fileName);
					@SuppressWarnings("resource") // Closed when channel is closed
					FileInputStream fis = new FileInputStream(f);
					sd.fchannel = fis.getChannel();
				}

				// Configure output channel
				sc = socketWrapper.getSocket();
				// TLS/SSL channel is slightly different
				WritableByteChannel wc = sc.getWritableByteChannel();// ((sc instanceof SecureNioChannel) ? sc :

				// We still have data in the buffer
				if (sc.getOutboundRemaining() > 0) {
					if (sc.flushOutbound()) {
						socketWrapper.updateLastWrite();
					}
				} else {
					long written = sd.fchannel.transferTo(sd.pos, sd.length, wc);
					if (written > 0) {
						sd.pos += written;
						sd.length -= written;
						socketWrapper.updateLastWrite();
					} else {
						// Unusual not to be able to transfer any bytes
						// Check the length was set correctly
						if (sd.fchannel.size() <= sd.pos) {
							throw new IOException(sm.getString("endpoint.sendfile.tooMuchData"));
						}
					}
				}
				if (sd.length <= 0 && sc.getOutboundRemaining() <= 0) {
					if (log.isDebugEnabled()) {
						log.debug("Send file complete for: " + sd.fileName);
					}
					socketWrapper.setSendfileData(null);
					try {
						sd.fchannel.close();
					} catch (Exception ignore) {
					}
					// For calls from outside the Poller, the caller is
					// responsible for registering the socket for the
					// appropriate event(s) if sendfile completes.
					if (!calledByProcessor) {
						switch (sd.keepAliveState) {
						case NONE: {
							if (log.isDebugEnabled()) {
								log.debug("Send file connection is being closed");
							}
							poller.cancelledKey(sk, socketWrapper);
							break;
						}
						case PIPELINED: {
							if (log.isDebugEnabled()) {
								log.debug("Connection is keep alive, processing pipe-lined data");
							}
							getHandler().processSocket(socketWrapper, SocketEvent.OPEN_READ, true);
							// if () {
							// poller.cancelledKey(sk, socketWrapper);
							// }
							break;
						}
						case OPEN: {
							if (log.isDebugEnabled()) {
								log.debug("Connection is keep alive, registering back for OP_READ");
							}
							reg(sk, socketWrapper, SelectionKey.OP_READ);
							break;
						}
						}
					}
					return SendfileState.DONE;
				} else {
					if (log.isDebugEnabled()) {
						log.debug("OP_WRITE for sendfile: " + sd.fileName);
					}
					if (calledByProcessor) {
						add(socketWrapper, SelectionKey.OP_WRITE);
					} else {
						reg(sk, socketWrapper, SelectionKey.OP_WRITE);
					}
					return SendfileState.PENDING;
				}
			} catch (IOException e) {
				if (log.isDebugEnabled()) {
					log.debug("Unable to complete sendfile request:", e);
				}
				if (!calledByProcessor && sc != null) {
					poller.cancelledKey(sk, socketWrapper);
				}
				return SendfileState.ERROR;
			} catch (Throwable t) {
				log.error(sm.getString("endpoint.sendfile.error"), t);
				if (!calledByProcessor && sc != null) {
					poller.cancelledKey(sk, socketWrapper);
				}
				return SendfileState.ERROR;
			}
		}

		protected void unreg(SelectionKey sk, NioSocketWrapper socketWrapper, int readyOps) {
			// This is a must, so that we don't have multiple threads messing with the
			// socket
			reg(sk, socketWrapper, sk.interestOps() & (~readyOps));
		}

		protected void reg(SelectionKey sk, NioSocketWrapper socketWrapper, int intops) {
			sk.interestOps(intops);
			// socketWrapper.interestOps(intops);
		}

		protected void timeout(int keyCount, boolean hasEvents) {
			long now = System.currentTimeMillis();
			// This method is called on every loop of the Poller. Don't process
			// timeouts on every loop of the Poller since that would create too
			// much load and timeouts can afford to wait a few seconds.
			// However, do process timeouts if any of the following are true:
			// - the selector simply timed out (suggests there isn't much load)
			// - the nextExpiration time has passed
			// - the server socket is being closed
			if (nextExpiration > 0 && (keyCount > 0 || hasEvents) && (now < nextExpiration) && !close) {
				return;
			}
			int keycount = 0;
			try {
				for (SelectionKey key : selector.keys()) {
					keycount++;
					try {
						NioSocketWrapper socketWrapper = (NioSocketWrapper) key.attachment();
						if (socketWrapper == null) {
							// We don't support any keys without attachments
							cancelledKey(key, null);
						} else if (close) {
							key.interestOps(0);
							// Avoid duplicate stop calls
							// socketWrapper.interestOps(0);
							cancelledKey(key, socketWrapper);
						} else if ((key.interestOps() & SelectionKey.OP_READ) == SelectionKey.OP_READ
								|| (key.interestOps() & SelectionKey.OP_WRITE) == SelectionKey.OP_WRITE) {
							boolean readTimeout = false;
							boolean writeTimeout = false;
							// Check for read timeout
							if ((key.interestOps() & SelectionKey.OP_READ) == SelectionKey.OP_READ) {
								long delta = now - socketWrapper.getLastRead();
								long timeout = socketWrapper.getReadTimeout();
								if (timeout > 0 && delta > timeout) {
									readTimeout = true;
								}
							}
							// Check for write timeout
							if (!readTimeout && (key.interestOps() & SelectionKey.OP_WRITE) == SelectionKey.OP_WRITE) {
								long delta = now - socketWrapper.getLastWrite();
								long timeout = socketWrapper.getWriteTimeout();
								if (timeout > 0 && delta > timeout) {
									writeTimeout = true;
								}
							}
							if (readTimeout || writeTimeout) {
								key.interestOps(0);
								// Avoid duplicate timeout calls
								// socketWrapper.interestOps(0);
								socketWrapper.setError(new SocketTimeoutException());
								if (readTimeout && socketWrapper.getReadLatch() != null) {
//									System.out.println(socketWrapper.getRemotePort() + " countDownReadLatch");
									socketWrapper.getReadLatch().countDown();
								} else if (writeTimeout && socketWrapper.getWriteLatch() != null) {
//									System.out.println(socketWrapper.getRemotePort() + " countDownWriteLatch");
									socketWrapper.getWriteLatch().countDown();
								} else if (readTimeout && socketWrapper.getReadOperation() != null) {
									if (!socketWrapper.getReadOperation().process()) {
										cancelledKey(key, socketWrapper);
									}
								} else if (writeTimeout && socketWrapper.getWriteOperation() != null) {
									if (!socketWrapper.getWriteOperation().process()) {
										cancelledKey(key, socketWrapper);
									}
								} else {
									getHandler().processSocket(socketWrapper, SocketEvent.ERROR, true);
									// cancelledKey(key, socketWrapper);
								}
							}
						}
					} catch (CancelledKeyException ckx) {
						cancelledKey(key, (NioSocketWrapper) key.attachment());
					}
				}
			} catch (ConcurrentModificationException cme) {
				// See https://bz.apache.org/bugzilla/show_bug.cgi?id=57943
				log.warn(sm.getString("endpoint.nio.timeoutCme"), cme);
			}
			// For logging purposes only
			long prevExp = nextExpiration;
			nextExpiration = System.currentTimeMillis() + socketProperties.getTimeoutInterval();
			if (log.isTraceEnabled()) {
				log.trace("timeout completed: keys processed=" + keycount + "; now=" + now + "; nextExpiration="
						+ prevExp + "; keyCount=" + keyCount + "; hasEvents=" + hasEvents + "; eval="
						+ ((now < prevExp) && (keyCount > 0 || hasEvents) && (!close)));
			}

		}
	}

	// --------------------------------------------------- Socket Wrapper Class

	public static class NioSocketWrapper extends SocketWrapperBase<NioChannel> implements HandShakeable {

		private final NioSelectorPool pool;
		private final SynchronizedStack<NioChannel> nioChannels;
		private final Poller poller;
		private final NioEndpoint endpoint;

		// private int interestOps = 0;
		private CountDownLatch readLatch = null;
		private CountDownLatch writeLatch = null;
		private volatile SendfileData sendfileData = null;
		private volatile long lastRead = System.currentTimeMillis();
		private volatile long lastWrite = lastRead;
		private boolean usePool = false;

		public NioSocketWrapper(NioChannel channel, NioEndpoint endpoint) {
			super(channel, endpoint);
			this.endpoint = endpoint;
			this.pool = endpoint.getSelectorPool();
			this.nioChannels = endpoint.getNioChannels();
			this.poller = endpoint.getPoller();
			// setSocketBufferHandler(channel);
		}

		public Poller getPoller() {
			return poller;
		}

		// public int interestOps() {
		// return interestOps;
		// }

		// public int interestOps(int ops) {
		// this.interestOps = ops;
		// return ops;
		// }

		public CountDownLatch getReadLatch() {
			return readLatch;
		}

		public CountDownLatch getWriteLatch() {
			return writeLatch;
		}

		protected CountDownLatch resetLatch(CountDownLatch latch) {
			if (latch == null || latch.getCount() == 0) {
				return null;
			} else {
				throw new IllegalStateException(sm.getString("endpoint.nio.latchMustBeZero"));
			}
		}

		public void resetReadLatch() {
			readLatch = resetLatch(readLatch);
		}

		public void resetWriteLatch() {
			writeLatch = resetLatch(writeLatch);
		}

		protected CountDownLatch startLatch(CountDownLatch latch, int cnt) {
			if (latch == null || latch.getCount() == 0) {
				return new CountDownLatch(cnt);
			} else {
				throw new IllegalStateException(sm.getString("endpoint.nio.latchMustBeZero"));
			}
		}

		public void startReadLatch(int cnt) {
			readLatch = startLatch(readLatch, cnt);
		}

		public void startWriteLatch(int cnt) {
			writeLatch = startLatch(writeLatch, cnt);
		}

		protected void awaitLatch(CountDownLatch latch, long timeout, TimeUnit unit) throws InterruptedException {
			if (latch == null) {
				throw new IllegalStateException(sm.getString("endpoint.nio.nullLatch"));
			}
			// Note: While the return value is ignored if the latch does time
			// out, logic further up the call stack will trigger a
			// SocketTimeoutException
			latch.await(timeout, unit);
		}

		public void awaitReadLatch(long timeout, TimeUnit unit) throws InterruptedException {
//			System.out.println(getRemotePort() + " awaitReadLatch");
			awaitLatch(readLatch, timeout, unit);
		}

		public void awaitWriteLatch(long timeout, TimeUnit unit) throws InterruptedException {
//			System.out.println(getRemotePort() + " awaitWriteLatch");
			awaitLatch(writeLatch, timeout, unit);
		}

		@Override
		public boolean isHandshakeComplete() {
			return getSocket().isHandshakeComplete();
		}

		@Override
		public int handshake(boolean read, boolean write) throws IOException {
			return getSocket().handshake(read, write);
		}

		public void setSendfileData(SendfileData sf) {
			this.sendfileData = sf;
		}

		public SendfileData getSendfileData() {
			return this.sendfileData;
		}

		public void updateLastWrite() {
			lastWrite = System.currentTimeMillis();
		}

		public long getLastWrite() {
			return lastWrite;
		}

		public void updateLastRead() {
			lastRead = System.currentTimeMillis();
		}

		public long getLastRead() {
			return lastRead;
		}

//		@Override
//		protected SocketBufferHandler getSocketBufferHandler() {
//			return getSocket();
//		}

		@Override
		protected ByteBufferWrapper getSocketAppReadBuffer() {
			return getSocket().getAppReadBuffer();
		}

		@Override
		protected ByteBufferWrapper getSocketReadBuffer() {
			return getSocket().getReadBuffer();
		}

		@Override
		protected ByteBufferWrapper getSocketWriteBuffer() {
			return getSocket().getWriteBuffer();
		}

		@Override
		public int getAvailable() {
			getSocket().configureReadBufferForRead();
			return getSocket().getReadBuffer().getRemaining();
		}

		@Override
		public boolean isReadyForRead() throws IOException {
			getSocket().configureReadBufferForRead();

			if (getSocket().getReadBuffer().getRemaining() > 0) {
				return true;
			}

			fillReadBuffer(false);

			boolean isReady = getSocket().getReadBuffer().getPosition() > 0;
			return isReady;
		}

		@Override
		public int read(boolean block, byte[] b, int off, int len) throws IOException {
			int nRead = populateReadBuffer(b, off, len);
			if (nRead > 0) {
				return nRead;
				/*
				 * Since more bytes may have arrived since the buffer was last filled, it is an
				 * option at this point to perform a non-blocking read. However correctly
				 * handling the case if that read returns end of stream adds complexity.
				 * Therefore, at the moment, the preference is for simplicity.
				 */
			}

			// Fill the read buffer as best we can.
			nRead = fillReadBuffer(block);
			updateLastRead();

			// Fill as much of the remaining byte array as possible with the
			// data that was just read
			if (nRead > 0) {
				getSocket().configureReadBufferForRead();
				nRead = Math.min(nRead, len);
				getSocket().getReadBuffer().getBytes(b, off, nRead);
			}
			return nRead;
		}

		@Override
		protected int read(boolean block, ByteBufferWrapper to) throws IOException {
			int nRead = populateReadBuffer(to);
			if (nRead > 0) {
				return nRead;
				/*
				 * Since more bytes may have arrived since the buffer was last filled, it is an
				 * option at this point to perform a non-blocking read. However correctly
				 * handling the case if that read returns end of stream adds complexity.
				 * Therefore, at the moment, the preference is for simplicity.
				 */
			}

			// The socket read buffer capacity is socket.appReadBufSize
			int limit = getSocket().getReadBuffer().getCapacity();
			if (to.getRemaining() >= limit) {
				to.setLimit(to.getPosition() + limit);
				nRead = fillReadBuffer(block, to);
				if (log.isDebugEnabled()) {
					log.debug("Socket: [" + this + "], Read direct from socket: [" + nRead + "]");
				}
				updateLastRead();
			} else {
				// Fill the read buffer as best we can.
				nRead = fillReadBuffer(block);
				if (log.isDebugEnabled()) {
					log.debug("Socket: [" + this + "], Read into buffer: [" + nRead + "]");
				}
				updateLastRead();

				// Fill as much of the remaining byte array as possible with the
				// data that was just read
				if (nRead > 0) {
					nRead = populateReadBuffer(to);
				}
			}
			return nRead;
		}

		private int fillReadBuffer(boolean block) throws IOException {
			getSocket().configureReadBufferForWrite();
			return fillReadBuffer(block, getSocket().getReadBuffer());
		}

		private int fillReadBuffer(boolean block, ByteBufferWrapper to) throws IOException {
			int nRead;
			NioChannel socket = getSocket();
			if (socket == NioChannel.CLOSED_NIO_CHANNEL) {
				throw new ClosedChannelException();
			}
			if (block) {
				if (usePool) {
					Selector selector = null;
					try {
						selector = pool.get();
					} catch (IOException x) {
						// Ignore
					}
					try {
						nRead = pool.read(to, this, selector, getReadTimeout());
					} finally {
						if (selector != null) {
							pool.put(selector);
						}
					}
				} else {
					// boolean timedout = false;
					// int keycount = 1; // assume we can read
					long time = System.currentTimeMillis(); // start the timeout timer
					while (true) {
						// if (keycount > 0) { // only read if we were registered for a read
						nRead = socket.read(to);
						if (nRead != 0) {
							break;
						}
						// }
						System.out.println(getRemotePort() + " blockReadFail");
						if (getReadLatch() == null || getReadLatch().getCount() == 0) {
							startReadLatch(1);
						}
						registerReadInterest();
						try {
							awaitReadLatch(Endpoint.toTimeout(getReadTimeout()), TimeUnit.MILLISECONDS);
						} catch (InterruptedException e) {
							e.printStackTrace();
							throw new EOFException();
						}

						if (getReadLatch() != null && getReadLatch().getCount() > 0) {
							// we got interrupted, but we haven't received notification from the poller.
							// keycount = 0;
						} else {
							// latch countdown has happened
							// keycount = 1;
							resetReadLatch();
						}
						nRead = socket.read(to);
						if (nRead != 0) {
							break;
						}
						if (getReadTimeout() >= 0) {// && (keycount == 0)
							boolean timedout = (System.currentTimeMillis() - time) >= getReadTimeout();
							if (timedout) {
								throw new SocketTimeoutException();
							}
						}
					}
				}
			} else {
				nRead = socket.read(to);
				if (nRead == -1) {
					throw new EOFException();
				}
			}
			return nRead;
		}

		@Override
		protected void doWrite(boolean block, ByteBufferWrapper from) throws IOException {
			NioChannel socket = getSocket();
			if (socket == NioChannel.CLOSED_NIO_CHANNEL) {
				throw new ClosedChannelException();
			}
			if (block) {
				if (usePool) {
					long writeTimeout = getWriteTimeout();
					Selector selector = null;
					try {
						selector = pool.get();
					} catch (IOException x) {
						// Ignore
					}
					try {
						if (!from.isReadMode()) {
							throw new RuntimeException();
						}
						pool.write(from, this, selector, writeTimeout);
						// Make sure we are flushed
						do {

						} while (!socket.flush(true, selector, writeTimeout));
					} finally {
						if (selector != null) {
							pool.put(selector);
						}
					}
				} else {
					// int written = 0;
					// boolean timedout = false;
					// int keycount = 1; // assume we can write
					long time = System.currentTimeMillis(); // start the timeout timer
					while (from.hasRemaining()) {// !timedout &&
						// if (keycount > 0) { // only write if we were registered for a write
						int cnt = socket.write(from); // write the data
						if (cnt == -1) {
							throw new EOFException();
						}
						// written += cnt;
						if (cnt > 0) {
							time = System.currentTimeMillis(); // reset our timeout timer
							continue; // we successfully wrote, try again without a selector
						}
//						System.out.println(getRemotePort() + " blockWriteFail");
						// }

						if (getWriteLatch() == null || getWriteLatch().getCount() == 0) {
							startWriteLatch(1);
						}
						registerWriteInterest();
						try {
							awaitWriteLatch(Endpoint.toTimeout(getWriteTimeout()), TimeUnit.MILLISECONDS);
						} catch (InterruptedException e) {
							e.printStackTrace();
							throw new EOFException();
						}
						if (getWriteLatch() != null && getWriteLatch().getCount() > 0) {
							// we got interrupted, but we haven't received notification from the poller.
							// keycount = 0;
						} else {
							// latch countdown has happened
							// keycount = 1;
							resetWriteLatch();
						}
						cnt = socket.write(from); // write the data
						if (cnt == -1) {
							throw new EOFException();
						}
						// written += cnt;
						if (cnt > 0) {
							time = System.currentTimeMillis(); // reset our timeout timer
							continue; // we successfully wrote, try again without a selector
						}
						if (getWriteTimeout() > 0) {// && (keycount == 0)
							boolean timedout = (System.currentTimeMillis() - time) >= getWriteTimeout();
							if (timedout) {
								throw new SocketTimeoutException();
							}
						}
					}
				}
				// If there is data left in the buffer the socket will be registered for
				// write further up the stack. This is to ensure the socket is only
				// registered for write once as both container and user code can trigger
				// write registration.
			} else {
				if (!from.isReadMode()) {
					throw new RuntimeException();
				}
				int n = 0;
				do {
					n = socket.write(from);
					if (n == -1) {
						throw new EOFException();
					}
				} while (n > 0 && from.hasRemaining());
			}
			updateLastWrite();
		}

		@Override
		public boolean registerReadInterest() {
			// if (isProcessing()) {
			// registe will be handled by processor
			// return;
			// }
			if (log.isDebugEnabled()) {
				log.debug(sm.getString("endpoint.debug.registerRead", this));
			}
			registeReadTimeStamp = System.currentTimeMillis();
			registeReadCount++;
//			System.out.println(getRemotePort() + " registerReadInterest ????????????:"
//					+ (System.currentTimeMillis() - startProcessTimeStamp));
			getPoller().add(this, SelectionKey.OP_READ);
			return true;
		}

		@Override
		public boolean registerWriteInterest() {
			if (log.isDebugEnabled()) {
				log.debug(sm.getString("endpoint.debug.registerWrite", this));
			}
//			System.out.println(getRemotePort() + " registerWriteInterest");
			getPoller().add(this, SelectionKey.OP_WRITE);
			return true;
		}

		@Override
		public SendfileDataBase createSendfileData(String filename, long pos, long length) {
			return new SendfileData(filename, pos, length);
		}

		@Override
		public SendfileState processSendfile(SendfileDataBase sendfileData) {
			setSendfileData((SendfileData) sendfileData);
			SelectionKey key = getSocket().getIOChannel().keyFor(getPoller().getSelector());
			// Might as well do the first write on this thread
			return getPoller().processSendfile(key, this, true);
		}

		@Override
		protected void populateRemoteAddr() {
			SocketChannel sc = getSocket().getIOChannel();
			if (sc != null) {
				InetAddress inetAddr = sc.socket().getInetAddress();
				if (inetAddr != null) {
					remoteAddr = inetAddr.getHostAddress();
				}
			}
		}

		@Override
		protected void populateRemoteHost() {
			SocketChannel sc = getSocket().getIOChannel();
			if (sc != null) {
				InetAddress inetAddr = sc.socket().getInetAddress();
				if (inetAddr != null) {
					remoteHost = inetAddr.getHostName();
					if (remoteAddr == null) {
						remoteAddr = inetAddr.getHostAddress();
					}
				}
			}
		}

		@Override
		protected void populateRemotePort() {
			SocketChannel sc = getSocket().getIOChannel();
			if (sc != null) {
				remotePort = sc.socket().getPort();
			}
		}

		@Override
		protected void populateLocalName() {
			SocketChannel sc = getSocket().getIOChannel();
			if (sc != null) {
				InetAddress inetAddr = sc.socket().getLocalAddress();
				if (inetAddr != null) {
					localName = inetAddr.getHostName();
				}
			}
		}

		@Override
		protected void populateLocalAddr() {
			SocketChannel sc = getSocket().getIOChannel();
			if (sc != null) {
				InetAddress inetAddr = sc.socket().getLocalAddress();
				if (inetAddr != null) {
					localAddr = inetAddr.getHostAddress();
				}
			}
		}

		@Override
		protected void populateLocalPort() {
			SocketChannel sc = getSocket().getIOChannel();
			if (sc != null) {
				localPort = sc.socket().getLocalPort();
			}
		}

		/**
		 * {@inheritDoc}
		 * 
		 * @param clientCertProvider Ignored for this implementation
		 */
		@Override
		public SSLSupport initSslSupport(String clientCertProvider) {
			if (getSocket() instanceof SecureNioChannel) {
				SecureNioChannel ch = (SecureNioChannel) getSocket();
				SSLEngine sslEngine = ch.getSslEngine();
				if (sslEngine != null) {
					SSLSession session = sslEngine.getSession();
					return ((NioEndpoint) getEndpoint()).getSslImplementation().getSSLSupport(session);
				}
			}
			return null;
		}

		@Override
		public void doClientAuth(SSLSupport sslSupport) throws IOException {
			SecureNioChannel sslChannel = (SecureNioChannel) getSocket();
			SSLEngine engine = sslChannel.getSslEngine();
			if (!engine.getNeedClientAuth()) {
				// Need to re-negotiate SSL connection
				engine.setNeedClientAuth(true);
				sslChannel.rehandshake(getEndpoint().getConnectionTimeout());
				((JSSESupport) sslSupport).setSession(engine.getSession());
			}
		}

		@Override
		protected void doClose() {
			if (log.isDebugEnabled()) {
				log.debug("Calling [" + getEndpoint() + "].closeSocket([" + this + "])");
			}
			try {
				poller.closeSocketWrapper(this);
				endpoint.closeSocketWrapper(this);
				if (getSocket().isOpen()) {
					getSocket().close(true);
				}
				if (getEndpoint().isRunning() && !getEndpoint().isPaused()) {
					if (nioChannels == null || !nioChannels.push(getSocket())) {
						getSocket().free();
					}
				}
			} catch (Throwable e) {
				ExceptionUtils.handleThrowable(e);
				if (log.isDebugEnabled()) {
					log.error(sm.getString("endpoint.debug.channelCloseFail"), e);
				}
			} finally {
				// setSocketBufferHandler(SocketBufferHandler.EMPTY);
				nonBlockingWriteBuffer.clear();
				reset(NioChannel.CLOSED_NIO_CHANNEL);
			}
			try {
				SendfileData data = getSendfileData();
				if (data != null && data.fchannel != null && data.fchannel.isOpen()) {
					data.fchannel.close();
				}
			} catch (Throwable e) {
				ExceptionUtils.handleThrowable(e);
				if (log.isDebugEnabled()) {
					log.error(sm.getString("endpoint.sendfile.closeError"), e);
				}
			}
		}

		// @Override
		// public void setAppReadBufHandler(ApplicationBufferHandler handler) {
		// getSocket().setAppReadBufHandler(handler);
		// }

		@Override
		protected <A> OperationState<A> newOperationState(boolean read, BufWrapper[] buffers, int offset, int length,
				BlockingMode block, long timeout, TimeUnit unit, A attachment, CompletionCheck check,
				CompletionHandler<Long, ? super A> handler, Semaphore semaphore,
				VectoredIOCompletionHandler<A> completion) {
			return new NioOperationState<>(read, buffers, offset, length, block, timeout, unit, attachment, check,
					handler, semaphore, completion);
		}

		private class NioOperationState<A> extends OperationState<A> {
			private volatile boolean inline = true;

			private NioOperationState(boolean read, BufWrapper[] buffers, int offset, int length, BlockingMode block,
					long timeout, TimeUnit unit, A attachment, CompletionCheck check,
					CompletionHandler<Long, ? super A> handler, Semaphore semaphore,
					VectoredIOCompletionHandler<A> completion) {
				super(read, buffers, offset, length, block, timeout, unit, attachment, check, handler, semaphore,
						completion);
			}

			@Override
			protected boolean isInline() {
				return inline;
			}

			@Override
			public void run() {
				// Perform the IO operation
				// Called from the poller to continue the IO operation
				long nBytes = 0;
				if (getError() == null) {
					try {
						synchronized (this) {
							if (!completionDone) {
								// This filters out same notification until processing
								// of the current one is done
								if (log.isDebugEnabled()) {
									log.debug("Skip concurrent " + (read ? "read" : "write") + " notification");
								}
								return;
							}
							if (read) {
								// Read from main buffer first
								if (!getSocket().isReadBufferEmpty()) {
									// There is still data inside the main read buffer, it needs to be read first
									getSocket().configureReadBufferForRead();
									for (int i = 0; i < length && !getSocket().isReadBufferEmpty(); i++) {
										if (!buffers[offset + i].isWriteMode()) {
											throw new RuntimeException();
										}
										if (buffers[offset + i].hasRemaining()) {
//											nBytes += transfer(getSocket().getReadBuffer(), buffers[offset + i]);
											nBytes += getSocket().getReadBuffer().transferTo(buffers[offset + i]);
										}
									}
								}
								if (nBytes == 0) {
									nBytes = getSocket().read(buffers, offset, length);
									updateLastRead();
								}
							} else {
								boolean doWrite = true;
								// Write from main buffer first
								if (!getSocket().isWriteBufferEmpty()) {
									// There is still data inside the main write buffer, it needs to be written
									// first
									getSocket().configureWriteBufferForRead();
									do {
										nBytes = getSocket().write(getSocket().getWriteBuffer());
									} while (!getSocket().isWriteBufferEmpty() && nBytes > 0);
									if (!getSocket().isWriteBufferEmpty()) {
										doWrite = false;
									}
									// Preserve a negative value since it is an error
									if (nBytes > 0) {
										nBytes = 0;
									}
								}
								if (doWrite) {
									long n = 0;
									do {
										n = getSocket().write(buffers, offset, length);
										if (n == -1) {
											nBytes = n;
										} else {
											nBytes += n;
										}
									} while (n > 0);
									updateLastWrite();
								}
							}
							if (nBytes != 0 || !buffersArrayHasRemaining(buffers, offset, length)) {
								completionDone = false;
							}
						}
					} catch (IOException e) {
						setError(e);
					}
				}
				if (nBytes > 0 || (nBytes == 0 && !buffersArrayHasRemaining(buffers, offset, length))) {
					// The bytes processed are only updated in the completion handler
					completion.completed(Long.valueOf(nBytes), this);
				} else if (nBytes < 0 || getError() != null) {
					IOException error = getError();
					if (error == null) {
						error = new EOFException();
					}
					completion.failed(error, this);
				} else {
					// As soon as the operation uses the poller, it is no longer inline
					inline = false;
					if (read) {
						if (!ContainerThreadMarker.isContainerThread()) {
							registerReadInterest();
						}
					} else {
						registerWriteInterest();
					}
				}
			}

		}

	}

	// ----------------------------------------------- SendfileData Inner Class

	/**
	 * SendfileData class.
	 */
	public static class SendfileData extends SendfileDataBase {

		public SendfileData(String filename, long pos, long length) {
			super(filename, pos, length);
		}

		protected volatile FileChannel fchannel;
	}
}
