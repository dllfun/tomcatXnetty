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
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.channels.NetworkChannel;
import java.util.Enumeration;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.collections.SynchronizedStack;
import org.apache.tomcat.util.net.Acceptor.AcceptorState;
import org.apache.tomcat.util.res.StringManager;

/**
 * @param <S> The type used by the socket wrapper associated with this endpoint.
 *            May be the same as U.
 * @param <U> The type of the underlying socket used by this endpoint. May be
 *            the same as S.
 *
 * @author Mladen Turk
 * @author Remy Maucherat
 */
public abstract class SocketWrapperBaseEndpoint<S, U> extends AbstractJsseEndpoint<S, U> {

	// -------------------------------------------------------------- Constants

	protected static final StringManager sm = StringManager.getManager(AbstractEndpoint.class);

	/**
	 * Thread used to accept new connections and pass them to worker threads.
	 */
	protected Acceptor<U> acceptor;

	private static Object object = new Object();

	public SocketWrapperBaseEndpoint() {

	}

	protected void startAcceptorThread() {
		acceptor = new Acceptor<>(this);
		String threadName = getName() + "-Acceptor";
		acceptor.setThreadName(threadName);
		Thread t = new Thread(acceptor, threadName);
		t.setPriority(getAcceptorThreadPriority());
		if (getHandler().getProtocol() != null) {
			t.setDaemon(getHandler().getProtocol().getDaemon());
		}
		t.start();
	}

	/**
	 * Pause the endpoint, which will stop it accepting new connections and unlock
	 * the acceptor.
	 */
	public void pauseInternal() {
		unlockAccept();
	}

	/**
	 * Unlock the server socket acceptor threads using bogus connections.
	 */
	private void unlockAccept() {
		// Only try to unlock the acceptor if it is necessary
		if (acceptor == null || acceptor.getState() != AcceptorState.RUNNING) {
			return;
		}

		InetSocketAddress unlockAddress = null;
		InetSocketAddress localAddress = null;
		try {
			localAddress = getLocalAddress();
		} catch (IOException ioe) {
			getLog().debug(sm.getString("endpoint.debug.unlock.localFail", getName()), ioe);
		}
		if (localAddress == null) {
			getLog().warn(sm.getString("endpoint.debug.unlock.localNone", getName()));
			return;
		}

		try {
			unlockAddress = getUnlockAddress(localAddress);

			try (java.net.Socket s = new java.net.Socket()) {
				int stmo = 2 * 1000;
				int utmo = 2 * 1000;
				if (getSocketProperties().getSoTimeout() > stmo)
					stmo = getSocketProperties().getSoTimeout();
				if (getSocketProperties().getUnlockTimeout() > utmo)
					utmo = getSocketProperties().getUnlockTimeout();
				s.setSoTimeout(stmo);
				s.setSoLinger(getSocketProperties().getSoLingerOn(), getSocketProperties().getSoLingerTime());
				if (getLog().isDebugEnabled()) {
					getLog().debug("About to unlock socket for:" + unlockAddress);
				}
				s.connect(unlockAddress, utmo);
				if (getDeferAccept()) {
					/*
					 * In the case of a deferred accept / accept filters we need to send data to
					 * wake up the accept. Send OPTIONS * to bypass even BSD accept filters. The
					 * Acceptor will discard it.
					 */
					OutputStreamWriter sw;

					sw = new OutputStreamWriter(s.getOutputStream(), "ISO-8859-1");
					sw.write("OPTIONS * HTTP/1.0\r\n" + "User-Agent: Tomcat wakeup connection\r\n\r\n");
					sw.flush();
				}
				if (getLog().isDebugEnabled()) {
					getLog().debug("Socket unlock completed for:" + unlockAddress);
				}
			}
			// Wait for upto 1000ms acceptor threads to unlock
			long waitLeft = 1000;
			while (waitLeft > 0 && acceptor.getState() == AcceptorState.RUNNING) {
				Thread.sleep(5);
				waitLeft -= 5;
			}
		} catch (Throwable t) {
			ExceptionUtils.handleThrowable(t);
			if (getLog().isDebugEnabled()) {
				getLog().debug(sm.getString("endpoint.debug.unlock.fail", String.valueOf(getPortWithOffset())), t);
			}
		}
	}

	private static InetSocketAddress getUnlockAddress(InetSocketAddress localAddress) throws SocketException {
		if (localAddress.getAddress().isAnyLocalAddress()) {
			// Need a local address of the same type (IPv4 or IPV6) as the
			// configured bind address since the connector may be configured
			// to not map between types.
			InetAddress loopbackUnlockAddress = null;
			InetAddress linkLocalUnlockAddress = null;

			Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
			while (networkInterfaces.hasMoreElements()) {
				NetworkInterface networkInterface = networkInterfaces.nextElement();
				Enumeration<InetAddress> inetAddresses = networkInterface.getInetAddresses();
				while (inetAddresses.hasMoreElements()) {
					InetAddress inetAddress = inetAddresses.nextElement();
					if (localAddress.getAddress().getClass().isAssignableFrom(inetAddress.getClass())) {
						if (inetAddress.isLoopbackAddress()) {
							if (loopbackUnlockAddress == null) {
								loopbackUnlockAddress = inetAddress;
							}
						} else if (inetAddress.isLinkLocalAddress()) {
							if (linkLocalUnlockAddress == null) {
								linkLocalUnlockAddress = inetAddress;
							}
						} else {
							// Use a non-link local, non-loop back address by default
							return new InetSocketAddress(inetAddress, localAddress.getPort());
						}
					}
				}
			}
			// Prefer loop back over link local since on some platforms (e.g.
			// OSX) some link local addresses are not included when listening on
			// all local addresses.
			if (loopbackUnlockAddress != null) {
				return new InetSocketAddress(loopbackUnlockAddress, localAddress.getPort());
			}
			if (linkLocalUnlockAddress != null) {
				return new InetSocketAddress(linkLocalUnlockAddress, localAddress.getPort());
			}
			// Fallback
			return new InetSocketAddress("localhost", localAddress.getPort());
		} else {
			return localAddress;
		}
	}

	protected abstract U serverSocketAccept() throws Exception;

	protected abstract boolean setSocketOptions(U socket);

	/**
	 * Close the socket when the connection has to be immediately closed when an
	 * error occurs while configuring the accepted socket or trying to dispatch it
	 * for processing. The wrapper associated with the socket will be used for the
	 * close.
	 * 
	 * @param socket The newly accepted socket
	 */
	protected void closeSocket(U socket) {
		SocketChannel channel = connections.get(socket);
		if (channel != null) {
			channel.close();
		}
	}

	/**
	 * Close the socket. This is used when the connector is not in a state which
	 * allows processing the socket, or if there was an error which prevented the
	 * allocation of the socket wrapper.
	 * 
	 * @param socket The newly accepted socket
	 */
	protected abstract void destroySocket(U socket);

	protected abstract NetworkChannel getServerSocket();

	@Override
	protected InetSocketAddress getLocalAddress() throws IOException {
		NetworkChannel serverSock = getServerSocket();
		if (serverSock == null) {
			return null;
		}
		SocketAddress sa = serverSock.getLocalAddress();
		if (sa instanceof InetSocketAddress) {
			return (InetSocketAddress) sa;
		}
		return null;
	}

}
