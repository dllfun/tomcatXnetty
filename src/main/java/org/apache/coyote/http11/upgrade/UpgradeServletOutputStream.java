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
package org.apache.coyote.http11.upgrade;

import java.io.IOException;

import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;

import org.apache.coyote.ContainerThreadMarker;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.net.DispatchType;
import org.apache.tomcat.util.net.SocketChannel;
import org.apache.tomcat.util.res.StringManager;

public class UpgradeServletOutputStream extends ServletOutputStream {

	private static final Log log = LogFactory.getLog(UpgradeServletOutputStream.class);
	private static final StringManager sm = StringManager.getManager(UpgradeServletOutputStream.class);

	private final UpgradeProcessorBase processor;
	private final SocketChannel channel;

	// Used to ensure that isReady() and onWritePossible() have a consistent
	// view of buffer and registered.
	private final Object registeredLock = new Object();

	// Used to ensure that only one thread writes to the socket at a time and
	// that buffer is consistently updated with any unwritten data after the
	// write. Note it is not necessary to hold this lock when checking if buffer
	// contains data but, depending on how the result is used, some form of
	// synchronization may be required (see fireListenerLock for an example).
	private final Object writeLock = new Object();

	private volatile boolean flushing = false;

	private volatile boolean closed = false;

	// Start in blocking-mode
	private volatile WriteListener listener = null;

	// Guarded by registeredLock
	private boolean registered = false;

	public UpgradeServletOutputStream(UpgradeProcessorBase processor, SocketChannel channel) {
		this.processor = processor;
		this.channel = channel;
	}

	@Override
	public final boolean isReady() {
		if (listener == null) {
			throw new IllegalStateException(sm.getString("upgrade.sos.canWrite.ise"));
		}
		if (closed) {
			return false;
		}

		// Make sure isReady() and onWritePossible() have a consistent view of
		// fireListener when determining if the listener should fire
		synchronized (registeredLock) {
			if (flushing) {
				// Since flushing is true the socket must already be registered
				// for write and multiple registrations will cause problems.
				registered = true;
				return false;
			} else if (registered) {
				// The socket is already registered for write and multiple
				// registrations will cause problems.
				return false;
			} else {
				boolean result = channel.isReadyForWrite();
				registered = !result;
				return result;
			}
		}
	}

	@Override
	public final void setWriteListener(WriteListener listener) {
		if (listener == null) {
			throw new IllegalArgumentException(sm.getString("upgrade.sos.writeListener.null"));
		}
		if (this.listener != null) {
			throw new IllegalArgumentException(sm.getString("upgrade.sos.writeListener.set"));
		}
		if (closed) {
			throw new IllegalStateException(sm.getString("upgrade.sos.write.closed"));
		}
		this.listener = listener;
		// Container is responsible for first call to onWritePossible().
		synchronized (registeredLock) {
			registered = true;
			// Container is responsible for first call to onDataAvailable().
			if (ContainerThreadMarker.isContainerThread()) {
				processor.addDispatch(DispatchType.NON_BLOCKING_WRITE);
			} else {
				channel.registerWriteInterest();
			}
		}

	}

	final boolean isClosed() {
		return closed;
	}

	@Override
	public void write(int b) throws IOException {
		synchronized (writeLock) {
			preWriteChecks();
			writeInternal(new byte[] { (byte) b }, 0, 1);
		}
	}

	@Override
	public void write(byte[] b, int off, int len) throws IOException {
		synchronized (writeLock) {
			preWriteChecks();
			writeInternal(b, off, len);
		}
	}

	@Override
	public void flush() throws IOException {
		preWriteChecks();
		flushInternal(listener == null, true);
	}

	private void flushInternal(boolean block, boolean updateFlushing) throws IOException {
		try {
			synchronized (writeLock) {
				if (updateFlushing) {
					flushing = channel.flush(block);
					if (flushing) {
						channel.registerWriteInterest();
					}
				} else {
					channel.flush(block);
				}
			}
		} catch (Throwable t) {
			ExceptionUtils.handleThrowable(t);
			onError(t);
			if (t instanceof IOException) {
				throw (IOException) t;
			} else {
				throw new IOException(t);
			}
		}
	}

	@Override
	public void close() throws IOException {
		if (closed) {
			return;
		}
		closed = true;
		flushInternal((listener == null), false);
	}

	private void preWriteChecks() {
		if (listener != null && !channel.canWrite()) {
			throw new IllegalStateException(sm.getString("upgrade.sos.write.ise"));
		}
		if (closed) {
			throw new IllegalStateException(sm.getString("upgrade.sos.write.closed"));
		}
	}

	/**
	 * Must hold writeLock to call this method.
	 */
	private void writeInternal(byte[] b, int off, int len) throws IOException {
		if (listener == null) {
			// Simple case - blocking IO
			channel.write(true, b, off, len);
		} else {
			channel.write(false, b, off, len);
		}
	}

	final void onWritePossible() {
		try {
			if (flushing) {
				flushInternal(false, true);
				if (flushing) {
					return;
				}
			} else {
				// This may fill the write buffer in which case the
				// isReadyForWrite() call below will re-register the socket for
				// write
				flushInternal(false, false);
			}
		} catch (IOException ioe) {
			onError(ioe);
			return;
		}

		// Make sure isReady() and onWritePossible() have a consistent view
		// of buffer and fireListener when determining if the listener
		// should fire
		boolean fire = false;
		synchronized (registeredLock) {
			if (channel.isReadyForWrite()) {
				registered = false;
				fire = true;
			} else {
				registered = true;
			}
		}

		if (fire) {
			ClassLoader oldCL = processor.getUpgradeToken().getContextBind().bind(false, null);
			try {
				listener.onWritePossible();
			} catch (Throwable t) {
				ExceptionUtils.handleThrowable(t);
				onError(t);
			} finally {
				processor.getUpgradeToken().getContextBind().unbind(false, oldCL);
			}
		}
	}

	private final void onError(Throwable t) {
		if (listener == null) {
			return;
		}
		ClassLoader oldCL = processor.getUpgradeToken().getContextBind().bind(false, null);
		try {
			listener.onError(t);
		} catch (Throwable t2) {
			ExceptionUtils.handleThrowable(t2);
			log.warn(sm.getString("upgrade.sos.onErrorFail"), t2);
		} finally {
			processor.getUpgradeToken().getContextBind().unbind(false, oldCL);
		}
		try {
			close();
		} catch (IOException ioe) {
			if (log.isDebugEnabled()) {
				log.debug(sm.getString("upgrade.sos.errorCloseFail"), ioe);
			}
		}
	}
}
