package org.apache.coyote;

import java.util.concurrent.atomic.AtomicBoolean;

import javax.servlet.ReadListener;
import javax.servlet.WriteListener;

import org.apache.tomcat.util.res.StringManager;

public class AsyncState extends AsyncStateMachine {

	private static final StringManager sm = StringManager.getManager(AbstractProcessor.class);

	private volatile long asyncTimeout = -1;

	volatile ReadListener readListener;
	/*
	 * State for non-blocking output is maintained here as it is the one point
	 * easily reachable from the CoyoteOutputStream and the Processor which both
	 * need access to state.
	 */
	volatile WriteListener writerListener;

	AsyncState() {
		super();
	}

	public void setAsyncTimeout(long timeout) {
		asyncTimeout = timeout;
	}

	public long getAsyncTimeout() {
		return asyncTimeout;
	}

	// @Override
	public ReadListener getReadListener() {
		return readListener;
	}

	// @Override
	public void setReadListener(ReadListener listener) {
		if (listener == null) {
			throw new NullPointerException(sm.getString("request.nullReadListener"));
		}
		if (getReadListener() != null) {
			throw new IllegalStateException(sm.getString("request.readListenerSet"));
		}
		// Note: This class is not used for HTTP upgrade so only need to test
		// for async
		AtomicBoolean result = new AtomicBoolean(false);
		actionASYNC_IS_ASYNC(result);
		if (!result.get()) {
			throw new IllegalStateException(sm.getString("request.notAsync"));
		}

		this.readListener = listener;
	}

	// @Override
	public WriteListener getWriteListener() {
		return writerListener;
	}

	// @Override
	public void setWriteListener(WriteListener listener) {
		if (listener == null) {
			throw new NullPointerException(sm.getString("response.nullWriteListener"));
		}
		if (getWriteListener() != null) {
			throw new IllegalStateException(sm.getString("response.writeListenerSet"));
		}
		// Note: This class is not used for HTTP upgrade so only need to test
		// for async
		AtomicBoolean result = new AtomicBoolean(false);
		actionASYNC_IS_ASYNC(result);
		if (!result.get()) {
			throw new IllegalStateException(sm.getString("response.notAsync"));
		}

		this.writerListener = listener;

	}

	// @Override
	public void actionASYNC_IS_ASYNC(AtomicBoolean param) {
		param.set(isAsync());
	}

	@Override
	synchronized void recycle() {
		super.recycle();
		readListener = null;
		writerListener = null;
	}

	protected void clearNonBlockingListeners() {
		readListener = null;
		writerListener = null;
	}
}
