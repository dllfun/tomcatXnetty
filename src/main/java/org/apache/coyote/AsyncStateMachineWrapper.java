package org.apache.coyote;

import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Stack;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

import javax.servlet.ReadListener;
import javax.servlet.RequestDispatcher;
import javax.servlet.WriteListener;

import org.apache.catalina.core.AsyncContextImpl;
import org.apache.tomcat.util.res.StringManager;
import org.apache.tomcat.util.security.PrivilegedGetTccl;
import org.apache.tomcat.util.security.PrivilegedSetTccl;

public class AsyncStateMachineWrapper {

	private static final StringManager sm = StringManager.getManager(AbstractProcessor.class);

	private Stack<AsyncStateMachine> stack = new Stack<>();

	private volatile AsyncStateMachine currentStateMachine = new AsyncStateMachine();

	private volatile ReadListener readListener;
	/*
	 * State for non-blocking output is maintained here as it is the one point
	 * easily reachable from the CoyoteOutputStream and the Processor which both
	 * need access to state.
	 */
	private volatile WriteListener writerListener;

	private boolean fireListener = false;
	private boolean registeredForWrite = false;
	private final Object nonBlockingStateLock = new Object();

	private volatile Request request = null;

	private volatile Runnable dispatcher = null;

	AsyncStateMachineWrapper() {

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
		if (!isAsync()) {
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
		if (!isAsync()) {
			throw new IllegalStateException(sm.getString("response.notAsync"));
		}

		this.writerListener = listener;

	}

	public Request getRequest() {
		return request;
	}

	// @Override
	// public void actionASYNC_IS_ASYNC(AtomicBoolean param) {
	// param.set(currentStateMachine.isAsync());
	// }

	public boolean isAsync() {
		return currentStateMachine.isAsync();
	}

	public final boolean isBlockingRead() {
		return readListener == null;
	}

	public final boolean isBlockingWrite() {
		return writerListener == null;
	}

	boolean isAsyncDispatching() {
		return currentStateMachine.isAsyncDispatching();
	}

	boolean isAsyncStarted() {
		return currentStateMachine.isAsyncStarted();
	}

	boolean isAsyncTimingOut() {
		return currentStateMachine.isAsyncTimingOut();
	}

	boolean isAsyncError() {
		return currentStateMachine.isAsyncError();
	}

	boolean isCompleting() {
		return currentStateMachine.isCompleting();
	}

	public AsyncContextImpl getAsyncCtxt() {
		return currentStateMachine.getAsyncCtxt();
	}

	/**
	 * Obtain the time that this connection last transitioned to async processing.
	 *
	 * @return The time (as returned by {@link System#currentTimeMillis()}) that
	 *         this connection last transitioned to async
	 */
	long getLastAsyncStart() {
		return currentStateMachine.getLastAsyncStart();
	}

	long getCurrentGeneration() {
		return currentStateMachine.getCurrentGeneration();
	}

	void asyncStart(Request request, AsyncContextImpl asyncCtxt) {
		this.request = request;
		currentStateMachine.asyncStart(asyncCtxt);
	}

	void asyncOperation() {
		currentStateMachine.asyncOperation();
	}

	/*
	 * Async has been processed. Whether or not to enter a long poll depends on
	 * current state. For example, as per SRV.2.3.3.3 can now process calls to
	 * complete() or dispatch().
	 */
	boolean asyncPostProcess() {
		return currentStateMachine.asyncPostProcess();
	}

	boolean asyncComplete() {
		return currentStateMachine.asyncComplete();
	}

	boolean asyncTimeout() {
		return currentStateMachine.asyncTimeout();
	}

	boolean asyncDispatch(Runnable dispatcher) {
		boolean triggerDispatch = currentStateMachine.asyncDispatch();
		this.dispatcher = dispatcher;
		return triggerDispatch;
	}

	// void asyncDispatched() {
	// currentStateMachine.asyncDispatched();
	// }

	boolean asyncError() {
		return currentStateMachine.asyncError();
	}

	void asyncRun(Executor executor, Runnable runnable) {
		currentStateMachine.asyncRun(executor, runnable);
	}

	boolean isAvailable() {
		return currentStateMachine.isAvailable();
	}

	public void setAsyncTimeout(long timeout) {
		currentStateMachine.setAsyncTimeout(timeout);
	}

	public long getAsyncTimeout() {
		return currentStateMachine.getAsyncTimeout();
	}

	public Runnable pushDispatchingState() {
		stack.push(currentStateMachine);
		currentStateMachine = new AsyncStateMachine();
		Runnable dispatcher = this.dispatcher;
		this.dispatcher = null;
		return dispatcher;
	}

	public void popDispatchingState() {
		if (stack.isEmpty()) {
			throw new RuntimeException();
		}
		currentStateMachine.recycle();
		currentStateMachine = stack.pop();
	}

	public boolean hasStackedState() {
		return !stack.isEmpty();
	}

	public boolean isFireListener() {
		return fireListener;
	}

	public void setFireListener(boolean fireListener) {
		this.fireListener = fireListener;
	}

	public boolean isRegisteredForWrite() {
		return registeredForWrite;
	}

	public void setRegisteredForWrite(boolean registeredForWrite) {
		this.registeredForWrite = registeredForWrite;
	}

	public Object getNonBlockingStateLock() {
		return nonBlockingStateLock;
	}

	// @Override
	synchronized void recycle() {
		currentStateMachine.recycle();
		readListener = null;
		writerListener = null;
		fireListener = false;
		registeredForWrite = false;
		request = null;
		dispatcher = null;
	}

	protected void clearNonBlockingListeners() {
		readListener = null;
		writerListener = null;
	}

	private enum AsyncState {
		DISPATCHED(false, false, false, false), STARTING(true, true, false, false), STARTED(true, true, false, false),
		MUST_COMPLETE(true, true, true, false), COMPLETE_PENDING(true, true, false, false),
		COMPLETING(true, false, true, false), TIMING_OUT(true, true, false, false),
		MUST_DISPATCH(true, true, false, true), DISPATCH_PENDING(true, true, false, false),
		DISPATCHING(true, false, false, true), READ_WRITE_OP(true, true, false, false),
		MUST_ERROR(true, true, false, false), ERROR(true, true, false, false);

		private final boolean isAsync;
		private final boolean isStarted;
		private final boolean isCompleting;
		private final boolean isDispatching;

		private AsyncState(boolean isAsync, boolean isStarted, boolean isCompleting, boolean isDispatching) {
			this.isAsync = isAsync;
			this.isStarted = isStarted;
			this.isCompleting = isCompleting;
			this.isDispatching = isDispatching;
		}

		boolean isAsync() {
			return isAsync;
		}

		boolean isStarted() {
			return isStarted;
		}

		boolean isDispatching() {
			return isDispatching;
		}

		boolean isCompleting() {
			return isCompleting;
		}
	}

	/**
	 * Manages the state transitions for async requests.
	 *
	 * <pre>
	 * The internal states that are used are:
	 * DISPATCHED       - Standard request. Not in Async mode.
	 * STARTING         - ServletRequest.startAsync() has been called from
	 *                    Servlet.service() but service() has not exited.
	 * STARTED          - ServletRequest.startAsync() has been called from
	 *                    Servlet.service() and service() has exited.
	 * READ_WRITE_OP    - Performing an asynchronous read or write.
	 * MUST_COMPLETE    - ServletRequest.startAsync() followed by complete() have
	 *                    been called during a single Servlet.service() method. The
	 *                    complete() will be processed as soon as Servlet.service()
	 *                    exits.
	 * COMPLETE_PENDING - ServletRequest.startAsync() has been called from
	 *                    Servlet.service() but, before service() exited, complete()
	 *                    was called from another thread. The complete() will
	 *                    be processed as soon as Servlet.service() exits.
	 * COMPLETING       - The call to complete() was made once the request was in
	 *                    the STARTED state.
	 * TIMING_OUT       - The async request has timed out and is waiting for a call
	 *                    to complete() or dispatch(). If that isn't made, the error
	 *                    state will be entered.
	 * MUST_DISPATCH    - ServletRequest.startAsync() followed by dispatch() have
	 *                    been called during a single Servlet.service() method. The
	 *                    dispatch() will be processed as soon as Servlet.service()
	 *                    exits.
	 * DISPATCH_PENDING - ServletRequest.startAsync() has been called from
	 *                    Servlet.service() but, before service() exited, dispatch()
	 *                    was called from another thread. The dispatch() will
	 *                    be processed as soon as Servlet.service() exits.
	 * DISPATCHING      - The dispatch is being processed.
	 * MUST_ERROR       - ServletRequest.startAsync() has been called from
	 *                    Servlet.service() but, before service() exited, an I/O
	 *                    error occured on another thread. The container will
	 *                    perform the necessary error handling when
	 *                    Servlet.service() exits.
	 * ERROR            - Something went wrong.
	 *
	 *
	 * The valid state transitions are:
	 *
	 *                  post()                                        dispatched()
	 *    |-------»------------------»---------|    |-------«-----------------------«-----|
	 *    |                                    |    |                                     |
	 *    |                                    |    |        post()                       |
	 *    |               post()              \|/  \|/       dispatched()                 |
	 *    |           |-----»----------------»DISPATCHED«-------------«-------------|     |
	 *    |           |                          | /|\ |                            |     |
	 *    |           |              startAsync()|  |--|timeout()                   |     |
	 *    ^           |                          |                                  |     |
	 *    |           |        complete()        |                  dispatch()      ^     |
	 *    |           |   |--«---------------«-- | ---«--MUST_ERROR--»-----|        |     |
	 *    |           |   |                      |         /|\             |        |     |
	 *    |           ^   |                      |          |              |        |     |
	 *    |           |   |                      |    /-----|error()       |        |     |
	 *    |           |   |                      |   /                     |        ^     |
	 *    |           |  \|/  ST-complete()     \|/ /   ST-dispatch()     \|/       |     |
	 *    |    MUST_COMPLETE«--------«--------STARTING--------»---------»MUST_DISPATCH    |
	 *    |                                    / | \                                      |
	 *    |                                   /  |  \                                     |
	 *    |                    OT-complete() /   |   \    OT-dispatch()                   |
	 *    |   COMPLETE_PENDING«------«------/    |    \-------»---------»DISPATCH_PENDING |
	 *    |          |                           |                           |            |
	 *    |    post()|   timeout()         post()|   post()            post()|  timeout() |
	 *    |          |   |--|                    |  |--|                     |    |--|    |
	 *    |         \|/ \|/ |   complete()      \|/\|/ |   dispatch()       \|/  \|/ |    |
	 *    |--«-----COMPLETING«--------«----------STARTED--------»---------»DISPATCHING----|
	 *            /|\  /|\ /|\                   | /|\ \                   /|\ /|\ /|\
	 *             |    |   |                    |  \   \asyncOperation()   |   |   |
	 *             |    |   |           timeout()|   \   \                  |   |   |
	 *             |    |   |                    |    \   \                 |   |   |
	 *             |    |   |                    |     \   \                |   |   |
	 *             |    |   |                    |      \   \               |   |   |
	 *             |    |   |                    |       \   \              |   |   |
	 *             |    |   |                    |  post()\   \   dispatch()|   |   |
	 *             |    |   |   complete()       |         \ \|/            |   |   |
	 *             |    |   |---«------------«-- | --«---READ_WRITE----»----|   |   |
	 *             |    |                        |                              |   |
	 *             |    |       complete()      \|/         dispatch()          |   |
	 *             |    |------------«-------TIMING_OUT--------»----------------|   |
	 *             |                                                                |
	 *             |            complete()                     dispatch()           |
	 *             |---------------«-----------ERROR--------------»-----------------|
	 *
	 *
	 * Notes: * For clarity, the transitions to ERROR which are valid from every state apart from
	 *          STARTING are not shown.
	 *        * All transitions may happen on either the Servlet.service() thread (ST) or on any
	 *          other thread (OT) unless explicitly marked.
	 * </pre>
	 */
	private class AsyncStateMachine {

		/**
		 * The string manager for this package.
		 */
		private final StringManager sm = StringManager.getManager(AsyncStateMachine.class);

		private volatile AsyncState state = AsyncState.DISPATCHED;
		private volatile long lastAsyncStart = 0;
		/*
		 * Tracks the current generation of async processing for this state machine. The
		 * generation is incremented every time async processing is started. The primary
		 * purpose of this is to enable Tomcat to detect and prevent attempts to process
		 * an event for a previous generation with the current generation as processing
		 * such an event usually ends badly: e.g. CVE-2018-8037.
		 */
		private final AtomicLong generation = new AtomicLong(0);
		// Need this to fire listener on complete
		private AsyncContextImpl asyncCtxt = null;
		// private final AbstractProcessor processor;
		private volatile long asyncTimeout = -1;

		AsyncStateMachine() {
			// this.processor = processor;
		}

		boolean isAsync() {
			return state.isAsync();
		}

		boolean isAsyncDispatching() {
			return state.isDispatching();
		}

		boolean isAsyncStarted() {
			return state.isStarted();
		}

		boolean isAsyncTimingOut() {
			return state == AsyncState.TIMING_OUT;
		}

		boolean isAsyncError() {
			return state == AsyncState.ERROR;
		}

		boolean isCompleting() {
			return state.isCompleting();
		}

		public AsyncContextImpl getAsyncCtxt() {
			return asyncCtxt;
		}

		/**
		 * Obtain the time that this connection last transitioned to async processing.
		 *
		 * @return The time (as returned by {@link System#currentTimeMillis()}) that
		 *         this connection last transitioned to async
		 */
		long getLastAsyncStart() {
			return lastAsyncStart;
		}

		long getCurrentGeneration() {
			return generation.get();
		}

		synchronized void asyncStart(AsyncContextImpl asyncCtxt) {
			if (state == AsyncState.DISPATCHED) {
				generation.incrementAndGet();
				state = AsyncState.STARTING;
				this.asyncCtxt = asyncCtxt;
				lastAsyncStart = System.currentTimeMillis();
			} else {
				throw new IllegalStateException(
						sm.getString("asyncStateMachine.invalidAsyncState", "asyncStart()", state));
			}
		}

		synchronized void asyncOperation() {
			if (state == AsyncState.STARTED) {
				state = AsyncState.READ_WRITE_OP;
			} else {
				throw new IllegalStateException(
						sm.getString("asyncStateMachine.invalidAsyncState", "asyncOperation()", state));
			}
		}

		/*
		 * Async has been processed. Whether or not to enter a long poll depends on
		 * current state. For example, as per SRV.2.3.3.3 can now process calls to
		 * complete() or dispatch().
		 */
		synchronized boolean asyncPostProcess() {
			if (state == AsyncState.COMPLETE_PENDING) {
				clearNonBlockingListeners();
				state = AsyncState.COMPLETING;
				return true;
			} else if (state == AsyncState.DISPATCH_PENDING) {
				clearNonBlockingListeners();
				state = AsyncState.DISPATCHING;
				return true;
			} else if (state == AsyncState.STARTING || state == AsyncState.READ_WRITE_OP) {
				state = AsyncState.STARTED;
				return false;
			} else if (state == AsyncState.MUST_COMPLETE || state == AsyncState.COMPLETING) {
				asyncCtxt.fireOnComplete();
				state = AsyncState.DISPATCHED;
				return true;
			} else if (state == AsyncState.MUST_DISPATCH) {
				state = AsyncState.DISPATCHING;
				return true;
			} else if (state == AsyncState.DISPATCHING) {
				// asyncCtxt.fireOnComplete();
				state = AsyncState.COMPLETING;
				return true;
			} else if (state == AsyncState.STARTED) {
				// This can occur if an async listener does a dispatch to an async
				// servlet during onTimeout
				return false;
			} else {
				throw new IllegalStateException(
						sm.getString("asyncStateMachine.invalidAsyncState", "asyncPostProcess()", state));
			}
		}

		synchronized boolean asyncComplete() {
			if (!ContainerThreadMarker.isContainerThread() && state == AsyncState.STARTING) {
				state = AsyncState.COMPLETE_PENDING;
				return false;
			}

			clearNonBlockingListeners();
			boolean triggerDispatch = false;
			if (state == AsyncState.STARTING || state == AsyncState.MUST_ERROR) {
				// Processing is on a container thread so no need to transfer
				// processing to a new container thread
				state = AsyncState.MUST_COMPLETE;
			} else if (state == AsyncState.STARTED) {
				state = AsyncState.COMPLETING;
				// A dispatch to a container thread is always required.
				// If on a non-container thread, need to get back onto a container
				// thread to complete the processing.
				// If on a container thread the current request/response are not the
				// request/response associated with the AsyncContext so need a new
				// container thread to process the different request/response.
				triggerDispatch = true;
			} else if (state == AsyncState.READ_WRITE_OP || state == AsyncState.TIMING_OUT
					|| state == AsyncState.ERROR) {
				// Read/write operations can happen on or off a container thread but
				// while in this state the call to listener that triggers the
				// read/write will be in progress on a container thread.
				// Processing of timeouts and errors can happen on or off a
				// container thread (on is much more likely) but while in this state
				// the call that triggers the timeout will be in progress on a
				// container thread.
				// The socket will be added to the poller when the container thread
				// exits the AbstractConnectionHandler.process() method so don't do
				// a dispatch here which would add it to the poller a second time.
				state = AsyncState.COMPLETING;
			} else {
				throw new IllegalStateException(
						sm.getString("asyncStateMachine.invalidAsyncState", "asyncComplete()", state));
			}
			return triggerDispatch;
		}

		synchronized boolean asyncTimeout() {
			if (state == AsyncState.STARTED) {
				state = AsyncState.TIMING_OUT;
				return true;
			} else if (state == AsyncState.COMPLETING || state == AsyncState.DISPATCHING
					|| state == AsyncState.DISPATCHED) {
				// NOOP - App called complete() or dispatch() between the the
				// timeout firing and execution reaching this point
				return false;
			} else {
				throw new IllegalStateException(
						sm.getString("asyncStateMachine.invalidAsyncState", "asyncTimeout()", state));
			}
		}

		synchronized boolean asyncDispatch() {
			if (!ContainerThreadMarker.isContainerThread() && state == AsyncState.STARTING) {
				state = AsyncState.DISPATCH_PENDING;
				return false;
			}

			clearNonBlockingListeners();
			boolean triggerDispatch = false;
			if (state == AsyncState.STARTING || state == AsyncState.MUST_ERROR) {
				// Processing is on a container thread so no need to transfer
				// processing to a new container thread
				state = AsyncState.MUST_DISPATCH;
			} else if (state == AsyncState.STARTED) {
				state = AsyncState.DISPATCHING;
				// A dispatch to a container thread is always required.
				// If on a non-container thread, need to get back onto a container
				// thread to complete the processing.
				// If on a container thread the current request/response are not the
				// request/response associated with the AsyncContext so need a new
				// container thread to process the different request/response.
				triggerDispatch = true;
			} else if (state == AsyncState.READ_WRITE_OP || state == AsyncState.TIMING_OUT
					|| state == AsyncState.ERROR) {
				// Read/write operations can happen on or off a container thread but
				// while in this state the call to listener that triggers the
				// read/write will be in progress on a container thread.
				// Processing of timeouts and errors can happen on or off a
				// container thread (on is much more likely) but while in this state
				// the call that triggers the timeout will be in progress on a
				// container thread.
				// The socket will be added to the poller when the container thread
				// exits the AbstractConnectionHandler.process() method so don't do
				// a dispatch here which would add it to the poller a second time.
				state = AsyncState.DISPATCHING;
			} else {
				throw new IllegalStateException(
						sm.getString("asyncStateMachine.invalidAsyncState", "asyncDispatch()", state));
			}
			return triggerDispatch;
		}

//		synchronized void asyncDispatched() {
//			if (state == AsyncState.DISPATCHING || state == AsyncState.MUST_DISPATCH) {
//				state = AsyncState.DISPATCHED;
//			} else {
//				throw new IllegalStateException(
//						sm.getString("asyncStateMachine.invalidAsyncState", "asyncDispatched()", state));
//			}
//		}

		synchronized boolean asyncError() {
			clearNonBlockingListeners();
			if (state == AsyncState.STARTING) {
				state = AsyncState.MUST_ERROR;
			} else {
				state = AsyncState.ERROR;
			}
			return !ContainerThreadMarker.isContainerThread();
		}

		synchronized void asyncRun(Executor executor, Runnable runnable) {
			if (state == AsyncState.STARTING || state == AsyncState.STARTED || state == AsyncState.READ_WRITE_OP) {
				// Execute the runnable using a container thread from the
				// Connector's thread pool. Use a wrapper to prevent a memory leak
				ClassLoader oldCL;
				if (Constants.IS_SECURITY_ENABLED) {
					PrivilegedAction<ClassLoader> pa = new PrivilegedGetTccl();
					oldCL = AccessController.doPrivileged(pa);
				} else {
					oldCL = Thread.currentThread().getContextClassLoader();
				}
				try {
					if (Constants.IS_SECURITY_ENABLED) {
						PrivilegedAction<Void> pa = new PrivilegedSetTccl(this.getClass().getClassLoader());
						AccessController.doPrivileged(pa);
					} else {
						Thread.currentThread().setContextClassLoader(this.getClass().getClassLoader());
					}

					executor.execute(runnable);
				} finally {
					if (Constants.IS_SECURITY_ENABLED) {
						PrivilegedAction<Void> pa = new PrivilegedSetTccl(oldCL);
						AccessController.doPrivileged(pa);
					} else {
						Thread.currentThread().setContextClassLoader(oldCL);
					}
				}
			} else {
				throw new IllegalStateException(
						sm.getString("asyncStateMachine.invalidAsyncState", "asyncRun()", state));
			}

		}

		synchronized boolean isAvailable() {
			if (asyncCtxt == null) {
				// Async processing has probably been completed in another thread.
				// Trigger a timeout to make sure the Processor is cleaned up.
				return false;
			}
			return asyncCtxt.isAvailable();
		}

		public void setAsyncTimeout(long timeout) {
			asyncTimeout = timeout;
		}

		public long getAsyncTimeout() {
			return asyncTimeout;
		}

		synchronized void recycle() {
			// Use lastAsyncStart to determine if this instance has been used since
			// it was last recycled. If it hasn't there is no need to recycle again
			// which saves the relatively expensive call to notifyAll()
			if (lastAsyncStart == 0) {
				return;
			}
			// Ensure in case of error that any non-container threads that have been
			// paused are unpaused.
			notifyAll();
			asyncCtxt = null;
			state = AsyncState.DISPATCHED;
			lastAsyncStart = 0;
		}

	}
}
