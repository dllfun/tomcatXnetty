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

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.servlet.RequestDispatcher;
import javax.servlet.http.HttpServletResponse;

import org.apache.coyote.AbstractProtocol.RegistrableProcessor;
import org.apache.coyote.http11.Constants;
import org.apache.coyote.http11.HeadersTooLargeException;
import org.apache.coyote.http11.upgrade.InternalHttpUpgradeHandler;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.buf.MessageBytes;
import org.apache.tomcat.util.http.MimeHeaders;
import org.apache.tomcat.util.http.parser.TokenList;
import org.apache.tomcat.util.log.UserDataHelper;
import org.apache.tomcat.util.net.Channel;
import org.apache.tomcat.util.net.DispatchType;
import org.apache.tomcat.util.net.Endpoint.Handler.SocketState;
import org.apache.tomcat.util.net.SendfileState;
import org.apache.tomcat.util.net.SocketChannel;
import org.apache.tomcat.util.net.SocketEvent;
import org.apache.tomcat.util.res.StringManager;

/**
 * Provides functionality and attributes common to all supported protocols
 * (currently HTTP and AJP) for processing a single request/response.
 */
public abstract class AbstractProcessor extends AbstractProcessorLight implements RegistrableProcessor {

	private static final StringManager sm = StringManager.getManager(AbstractProcessor.class);

	protected final AbstractProtocol<?> protocol;

	private final Adapter adapter;
	// private volatile long asyncTimeout = -1;
	/*
	 * Tracks the current async generation when a timeout is dispatched. In the time
	 * it takes for a container thread to be allocated and the timeout processing to
	 * start, it is possible that the application completes this generation of async
	 * processing and starts a new one. If the timeout is then processed against the
	 * new generation, response mix-up can occur. This field is used to ensure that
	 * any timeout event processed is for the current async generation. This
	 * prevents the response mix-up.
	 */
	private volatile long asyncTimeoutGeneration = 0;
	protected final AsyncStateMachineWrapper asyncStateMachine;
	protected final ExchangeData exchangeData;
	protected final RequestAction requestAction;
	protected final ResponseAction responseAction;
	private final List<ProcessorComponent> components = new ArrayList<>();
	// protected final ResponseData responseData;
	// protected InputHandler inputHandler;
	private volatile Channel channel = null;
	// protected volatile SSLSupport sslSupport;

	/**
	 * Error state for the request/response currently being processed.
	 */
	private ErrorState errorState = ErrorState.NONE;
	protected final UserDataHelper userDataHelper;

	private boolean recycled = false;

	public AbstractProcessor(AbstractProtocol<?> protocol, Adapter adapter) {
		this(protocol, adapter, new ExchangeData());
	}

	protected AbstractProcessor(AbstractProtocol<?> protocol, Adapter adapter, ExchangeData exchangeData) {
		if (exchangeData == null) {
			throw new IllegalArgumentException();
		}
		this.protocol = protocol;
		this.adapter = adapter;
		this.exchangeData = exchangeData;
		this.requestAction = createRequestAction();
		this.responseAction = createResponseAction();
		// this.responseData = responseData;
		// response.setHook(this);
//		this.exchangeData.setResponseData(this.responseData);
		// request.setHook(this);
		this.asyncStateMachine = new AsyncStateMachineWrapper();
		this.userDataHelper = new UserDataHelper(getLog());
	}

	protected abstract RequestAction createRequestAction();

	protected abstract ResponseAction createResponseAction();

	public final RequestAction getRequestAction() {
		return requestAction;
	}

	public final ResponseAction getResponseAction() {
		return responseAction;
	}

	public AbstractProtocol<?> getProtocol() {
		return protocol;
	}

	/**
	 * Get the associated adapter.
	 *
	 * @return the associated adapter
	 */
	public Adapter getAdapter() {
		return adapter;
	}

	@Override
	public final ExchangeData getExchangeData() {
		return exchangeData;
	}

	public final AsyncStateMachineWrapper getAsyncStateMachine() {
		return asyncStateMachine;
	}

	public final void addComponent(ProcessorComponent component) {
		this.components.add(component);
	}

	// @Override
//	public ResponseData getResponseData() {
//		return responseData;
//	}

	/**
	 * Update the current error state to the new error state if the new error state
	 * is more severe than the current error state.
	 * 
	 * @param errorState The error status details
	 * @param t          The error which occurred
	 */
	public final void setErrorState(ErrorState errorState, Throwable t) {
		// Use the return value to avoid processing more than one async error
		// in a single async cycle.
		boolean setError = exchangeData.setError();
		boolean blockIo = this.errorState.isIoAllowed() && !errorState.isIoAllowed();
		this.errorState = this.errorState.getMostSevere(errorState);
		// Don't change the status code for IOException since that is almost
		// certainly a client disconnect in which case it is preferable to keep
		// the original status code http://markmail.org/message/4cxpwmxhtgnrwh7n
		if (exchangeData.getStatus() < 400 && !(t instanceof IOException)) {
			exchangeData.setStatus(500);
		}
		if (t != null) {
			exchangeData.setAttribute(RequestDispatcher.ERROR_EXCEPTION, t);
		}
		if (blockIo && asyncStateMachine.isAsync() && setError) {
			if (asyncStateMachine.asyncError()) {
				// processSocketEvent(SocketEvent.ERROR, true);
				Channel channel = getChannel();
				if (channel != null) {

					// TODO sads
					// channel.processSocket(event, dispatch);
					protocol.getHandler().processSocket(channel, SocketEvent.ERROR, true);
				}
			}
		}
	}

	public final ErrorState getErrorState() {
		return errorState;
	}

	// @Override
	public void isError(AtomicBoolean param) {
		param.set(getErrorState().isError());
	}

	// @Override
	public void isIoAllowed(AtomicBoolean param) {
		param.set(getErrorState().isIoAllowed());
	}

	public void handleIOException(IOException ioe) {
		if (ioe instanceof CloseNowException) {
			// Close the channel but keep the connection open
			setErrorState(ErrorState.CLOSE_NOW, ioe);
		} else {
			// Close the connection and all channels within that connection
			setErrorState(ErrorState.CLOSE_CONNECTION_NOW, ioe);
		}
	}

	/**
	 * Set the socket wrapper being used.
	 * 
	 * @param channel The socket wrapper
	 */
	@Override
	public final void setChannel(Channel channel) {
		this.channel = channel;
		onChannelReady(channel);
		if (components != null && components.size() > 0) {
			for (ProcessorComponent component : components) {
				component.onChannelReady(channel);
			}
		}
		recycled = false;
	}

	protected abstract void onChannelReady(Channel channel);

	/**
	 * @return the socket wrapper being used.
	 */
	@Override
	public final Channel getChannel() {
		return channel;
	}

	// @Override
	// public final void setSslSupport(SSLSupport sslSupport) {
	// this.sslSupport = sslSupport;
	// }

	/**
	 * Provides a mechanism to trigger processing on a container thread.
	 *
	 * @param runnable The task representing the processing that needs to take place
	 *                 on a container thread
	 */
//	protected void execute(Runnable runnable) {
//		Channel channel = this.channel;
//		if (channel == null) {
//			throw new RejectedExecutionException(sm.getString("abstractProcessor.noExecute"));
//		} else {
//			// channel.execute(runnable);
//			protocol.getExecutor().execute(runnable);
//		}
//	}

//	@Override
//	protected boolean isAsync() {
//		return asyncStateMachine.isAsync();
//	}

	public final boolean isBlockingRead() {
		return asyncStateMachine.getReadListener() == null;
	}

	/**
	 * Is standard Servlet blocking IO being used for output?
	 * 
	 * @return <code>true</code> if this is blocking IO
	 */
	public final boolean isBlockingWrite() {
		return asyncStateMachine.getWriteListener() == null;
	}

//	@Override
//	protected boolean shouldDispatch(SocketEvent event) {
//		return asyncStateMachine.isAsync();
//	}

	@Override
	public boolean isIgnoredTimeout() {
		return (!asyncStateMachine.isAsync()
				|| asyncStateMachine.isAsync() && asyncTimeoutGeneration == asyncStateMachine.getCurrentGeneration());
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * Sub-classes of this base class represent a single request/response pair. The
	 * timeout to be processed is, therefore, the Servlet asynchronous processing
	 * timeout.
	 */
	@Override
	public void checkTimeout(long now) {
		if (now < 0) {
			doTimeoutAsync();
		} else {
			long asyncTimeout = asyncStateMachine.getAsyncTimeout();
			if (asyncTimeout > 0) {
				long asyncStart = asyncStateMachine.getLastAsyncStart();
				if ((now - asyncStart) > asyncTimeout) {
					doTimeoutAsync();
				}
			} else if (!asyncStateMachine.isAvailable()) {
				// Timeout the async process if the associated web application
				// is no longer running.
				doTimeoutAsync();
			}
		}
	}

	private void doTimeoutAsync() {
		// Avoid multiple timeouts
		asyncStateMachine.setAsyncTimeout(-1);
		asyncTimeoutGeneration = asyncStateMachine.getCurrentGeneration();
		// processSocketEvent(SocketEvent.TIMEOUT, true);
		Channel channel = getChannel();
		if (channel != null) {

			// TODO sads
			// channel.processSocket(event, dispatch);
			protocol.getHandler().processSocket(channel, SocketEvent.TIMEOUT, true);
		}
	}

	@Override
	public final SocketState dispatch(SocketEvent event) throws IOException {
		SocketState state;
		boolean cont = false;
		do {
			cont = false;
			if (!asyncStateMachine.isAsync()) {
				if (asyncStateMachine.hasStackedState()) {
					asyncStateMachine.popDispatchingState();
					asyncStateMachine.asyncPostProcess();
				}
			}

			if (event == SocketEvent.OPEN_WRITE && asyncStateMachine.getWriteListener() != null) {
				asyncStateMachine.asyncOperation();
				try {
					if (responseAction.flushBufferedWrite()) {
						asyncStateMachine.asyncPostProcess();
						return SocketState.SUSPENDED;
					}
				} catch (IOException ioe) {
					if (getLog().isDebugEnabled()) {
						getLog().debug("Unable to write async data.", ioe);
					}
					event = SocketEvent.ERROR;
					exchangeData.setAttribute(RequestDispatcher.ERROR_EXCEPTION, ioe);
				}
			} else if (event == SocketEvent.OPEN_READ && asyncStateMachine.getReadListener() != null) {
				dispatchNonBlockingRead();
			} else if (event == SocketEvent.ERROR) {
				// An I/O error occurred on a non-container thread. This includes:
				// - read/write timeouts fired by the Poller (NIO & APR)
				// - completion handler failures in NIO2

				if (exchangeData.getAttribute(RequestDispatcher.ERROR_EXCEPTION) == null) {
					// Because the error did not occur on a container thread the
					// request's error attribute has not been set. If an exception
					// is available from the channel, use it to set the
					// request's error attribute here so it is visible to the error
					// handling.
					exchangeData.setAttribute(RequestDispatcher.ERROR_EXCEPTION, channel.getError());
				}

				if (asyncStateMachine.getReadListener() != null || asyncStateMachine.getWriteListener() != null) {
					// The error occurred during non-blocking I/O. Set the correct
					// state else the error handling will trigger an ISE.
					asyncStateMachine.asyncOperation();
				}
			}

			RequestInfo rp = exchangeData.getRequestProcessor();
			try {
				rp.setStage(org.apache.coyote.Constants.STAGE_SERVICE);
				Request request = asyncStateMachine.getRequest(); // createRequest();
				Response response = request.getResponse(); // createResponse();
				// request.setResponse(response);
				if (!getAdapter().asyncDispatch(request, response, event)) {
					setErrorState(ErrorState.CLOSE_NOW, null);
				}
			} catch (InterruptedIOException e) {
				setErrorState(ErrorState.CLOSE_CONNECTION_NOW, e);
			} catch (Throwable t) {
				ExceptionUtils.handleThrowable(t);
				setErrorState(ErrorState.CLOSE_NOW, t);
				getLog().error(sm.getString("http11processor.request.process"), t);
			}

			rp.setStage(org.apache.coyote.Constants.STAGE_ENDED);

			if (getErrorState().isError()) {
				exchangeData.updateCounters();
				state = SocketState.CLOSED;
			} else if (asyncStateMachine.isAsync()) {
				state = SocketState.SUSPENDED;
				cont = asyncStateMachine.asyncPostProcess();
				if (getLog().isDebugEnabled()) {
					getLog().debug("Socket: [" + channel + "], State after async post processing: [" + state + "]");
				}
			} else {
				exchangeData.updateCounters();
				state = dispatchFinishActions();
			}

			if (getLog().isDebugEnabled()) {
				getLog().debug("Socket: [" + channel + "], Status in: [" + event + "], State out: [" + state + "]");
			}
		} while (cont);
		return state;
	}

	/**
	 * Perform any necessary processing for a non-blocking read before dispatching
	 * to the adapter.
	 */
	protected void dispatchNonBlockingRead() {
		asyncStateMachine.asyncOperation();
	}

	/**
	 * Perform any necessary clean-up processing if the dispatch resulted in the
	 * completion of processing for the current request.
	 *
	 * @return The state to return for the socket once the clean-up for the current
	 *         request has completed
	 *
	 * @throws IOException If an I/O error occurs while attempting to end the
	 *                     request
	 */
	protected abstract SocketState dispatchFinishActions() throws IOException;

	@Override
	public boolean processInIoThread(SocketEvent event) throws IOException {
		return true;
	}

	@Override
	protected final SocketState service(SocketEvent event) throws IOException {
		SocketState state = SocketState.OPEN;
		boolean cont = false;
		do {
			if (asyncStateMachine.isAsync() || cont) {
				if (cont) {
					state = dispatch(event);
				} else {
					state = dispatch(event);
				}
			}
			cont = false;
			if (state == SocketState.OPEN) {
				state = serviceInternal();
				if (state != SocketState.CLOSED && asyncStateMachine.isAsync() && !asyncStateMachine.isAsyncError()) {
					cont = asyncStateMachine.asyncPostProcess();
					if (getLog().isDebugEnabled()) {
						getLog().debug("Socket: [" + channel + "], State after async post processing: [" + state + "]");
					}
				}
			}
		} while (cont);
		if (state != SocketState.CLOSED && asyncStateMachine.isAsync()) {
			protocol.addWaitingProcessor(this);
		}
		return state;
	}

	/**
	 * 
	 * @return
	 */
	protected abstract boolean repeat();

	protected SocketState serviceInternal() throws IOException {
		// Channel channel
//		SocketChannel socketChannel = (SocketChannel) channel;
		RequestInfo rp = exchangeData.getRequestProcessor();
		rp.setStage(org.apache.coyote.Constants.STAGE_PARSE);

		// Setting up the I/O
		// setChannel(channel);

		// Flags
//		inputBuffer.keepAlive = true;
//		openSocket = false;
//		readComplete = true;
		SendfileState sendfileState = SendfileState.DONE;

		while (!getErrorState().isError() && repeat() && !asyncStateMachine.isAsync() && !isUpgrade()
				&& sendfileState == SendfileState.DONE && !protocol.isPaused()) {

			// Parsing the request header
			if (!parsingHeader()) {
				if (!getErrorState().isError()) {
					if (isHttp2Preface()) {
						return SocketState.UPGRADING;
					} else {
						if (canReleaseProcessor()) {
							return SocketState.OPEN;
						} else {
							return SocketState.LONG;
						}
					}
				}
			}

			if (!getErrorState().isError() && channel instanceof SocketChannel) {
				SocketChannel socketChannel = (SocketChannel) channel;
				// Has an upgrade been requested?
				if (isConnectionToken(exchangeData.getRequestHeaders(), "upgrade")) {
					// Check the protocol
					String requestedProtocol = exchangeData.getRequestHeader("Upgrade");

					UpgradeProtocol upgradeProtocol = protocol.getUpgradeProtocol(requestedProtocol);
					if (upgradeProtocol != null) {
						if (upgradeProtocol.accept(exchangeData)) {
							exchangeData.setStatus(HttpServletResponse.SC_SWITCHING_PROTOCOLS);
							exchangeData.setResponseHeader("Connection", "Upgrade");
							exchangeData.setResponseHeader("Upgrade", requestedProtocol);
							responseAction.finish();
							Request request = createRequest();
							Response response = createResponse();
							request.setResponse(response);
							getAdapter().log(request, response, 0);

							InternalHttpUpgradeHandler upgradeHandler = upgradeProtocol.getInternalUpgradeHandler(
									socketChannel, getAdapter(), cloneExchangeData(this.exchangeData));
							UpgradeToken upgradeToken = new UpgradeToken(upgradeHandler, null, null);
							upgrade(upgradeToken);
							return SocketState.UPGRADING;
						}
					}
				}
			}

			if (getErrorState().isIoAllowed()) {
				// Setting up filters, and parse some request headers
				rp.setStage(org.apache.coyote.Constants.STAGE_PREPARE);
				try {
					prepareRequest();
				} catch (Throwable t) {
					ExceptionUtils.handleThrowable(t);
					if (getLog().isDebugEnabled()) {
						getLog().debug(sm.getString("http11processor.request.prepare"), t);
					}
					// 500 - Internal Server Error
					exchangeData.setStatus(500);
					setErrorState(ErrorState.CLOSE_CLEAN, t);
				}
			}

			if (!getErrorState().isIoAllowed()) {
				Request request = createRequest();
				Response response = createResponse();
				request.setResponse(response);
				getAdapter().log(request, response, 0);
			} else {

				if (protocol.isPaused()) {// && !cping
					// 503 - Service unavailable
					exchangeData.setStatus(503);
					setErrorState(ErrorState.CLOSE_CLEAN, null);
				}
			}

			// Process the request in the adapter
			if (getErrorState().isIoAllowed()) {
				try {
					rp.setStage(org.apache.coyote.Constants.STAGE_SERVICE);
					Request request = createRequest();
					Response response = createResponse();
					request.setResponse(response);
					getAdapter().service(request, response);
					// Handle when the response was committed before a serious
					// error occurred. Throwing a ServletException should both
					// set the status to 500 and set the errorException.
					// If we fail here, then the response is likely already
					// committed, so we can't try and set headers.
					if (repeat() && !getErrorState().isError() && !asyncStateMachine.isAsync()
							&& statusDropsConnection(this.exchangeData.getStatus())) {
						setErrorState(ErrorState.CLOSE_CLEAN, null);
					}
				} catch (InterruptedIOException e) {
					setErrorState(ErrorState.CLOSE_CONNECTION_NOW, e);
				} catch (HeadersTooLargeException e) {
					getLog().error(sm.getString("http11processor.request.process"), e);
					// The response should not have been committed but check it
					// anyway to be safe
					if (exchangeData.isCommitted()) {
						setErrorState(ErrorState.CLOSE_NOW, e);
					} else {
						exchangeData.reset();
						exchangeData.setStatus(500);
						setErrorState(ErrorState.CLOSE_CLEAN, e);
						exchangeData.setResponseHeader("Connection", "close"); // TODO: Remove
					}
				} catch (Throwable t) {
					ExceptionUtils.handleThrowable(t);
					getLog().error(sm.getString("http11processor.request.process"), t);
					// 500 - Internal Server Error
					exchangeData.setStatus(500);
					setErrorState(ErrorState.CLOSE_CLEAN, t);
					Request request = createRequest();
					Response response = createResponse();
					request.setResponse(response);
					getAdapter().log(request, response, 0);
				}
			}

			// Finish the handling of the request
			rp.setStage(org.apache.coyote.Constants.STAGE_ENDINPUT);
			if (!asyncStateMachine.isAsync()) {
				// If this is an async request then the request ends when it has
				// been completed. The AsyncContext is responsible for calling
				// endRequest() in that case.
				finishActions();
			}
			rp.setStage(org.apache.coyote.Constants.STAGE_ENDOUTPUT);

			// If there was an error, make sure the request is counted as
			// and error, and update the statistics counter
			if (getErrorState().isError()) {
				exchangeData.setStatus(500);
			}

			if ((!asyncStateMachine.isAsync() && !isUpgrade()) || getErrorState().isError()) {
				exchangeData.updateCounters();
				if (getErrorState().isIoAllowed()) {
					nextRequest();
//					exchangeData.recycle();
//					headParser.nextRequest();
//					inputBuffer.nextRequest();
//					outputBuffer.nextRequest();
				}
			}

			if (repeat()) {
				resetSocketReadTimeout();
				rp.setStage(org.apache.coyote.Constants.STAGE_KEEPALIVE);
			}

			sendfileState = processSendfile();
		}

		rp.setStage(org.apache.coyote.Constants.STAGE_ENDED);

		if (getErrorState().isError() || (protocol.isPaused() && !asyncStateMachine.isAsync())) {
			return SocketState.CLOSED;
		} else if (asyncStateMachine.isAsync()) {
			return SocketState.SUSPENDED;
		} else if (isUpgrade()) {
			return SocketState.UPGRADING;
		} else {
			if (sendfileState == SendfileState.PENDING) {
				return SocketState.SENDFILE;
			} else {
				if (repeat()) {
//					if (readComplete) {
					return SocketState.OPEN;
//					} else {
//						return SocketState.LONG;
//					}
				} else {
					return SocketState.CLOSED;
				}
			}
		}
	};

	private ExchangeData cloneExchangeData(ExchangeData source) throws IOException {
		ExchangeData dest = new ExchangeData();

		// Transfer the minimal information required for the copy of the Request
		// that is passed to the HTTP upgrade process

		dest.getDecodedURI().duplicate(source.getDecodedURI());
		dest.getMethod().duplicate(source.getMethod());
		dest.getRequestHeaders().duplicate(source.getRequestHeaders());
		dest.getRequestURI().duplicate(source.getRequestURI());
		dest.getQueryString().duplicate(source.getQueryString());

		return dest;

	}

	/**
	 * 解析报文头部
	 * 
	 * @return
	 * @throws IOException
	 */
	protected abstract boolean parsingHeader() throws IOException;

	/**
	 * 是否符合http2前言
	 * 
	 * @return
	 */
	protected abstract boolean isHttp2Preface();

	/**
	 * 是否可以释放processor
	 * 
	 * @return
	 */
	protected abstract boolean canReleaseProcessor();

	protected abstract void prepareRequest() throws IOException;

	protected abstract void resetSocketReadTimeout();

	protected abstract void finishActions() throws IOException;

	protected abstract SendfileState processSendfile() throws IOException;

	// @Override
	public void dispatchRead() {
		addDispatch(DispatchType.NON_BLOCKING_READ);
	}

	// @Override
	public void dispatchWrite() {
		addDispatch(DispatchType.NON_BLOCKING_WRITE);
	}

	// @Override
	public void dispatchExecute() {
		executeDispatches();
	}

	protected void executeDispatches() {
		Channel channel = getChannel();
		Iterator<DispatchType> dispatches = getIteratorAndClearDispatches();
		if (channel != null) {
			synchronized (channel) {
				/*
				 * This method is called when non-blocking IO is initiated by defining a read
				 * and/or write listener in a non-container thread. It is called once the
				 * non-container thread completes so that the first calls to onWritePossible()
				 * and/or onDataAvailable() as appropriate are made by the container.
				 *
				 * Processing the dispatches requires (for APR/native at least) that the socket
				 * has been added to the waitingRequests queue. This may not have occurred by
				 * the time that the non-container thread completes triggering the call to this
				 * method. Therefore, the coded syncs on the SocketWrapper as the container
				 * thread that initiated this non-container thread holds a lock on the
				 * SocketWrapper. The container thread will add the socket to the
				 * waitingRequests queue before releasing the lock on the channel. Therefore, by
				 * obtaining the lock on channel before processing the dispatches, we can be
				 * sure that the socket has been added to the waitingRequests queue.
				 */
				while (dispatches != null && dispatches.hasNext()) {
					DispatchType dispatchType = dispatches.next();
					protocol.getHandler().processSocket(channel, dispatchType.getSocketStatus(), false);
				}
			}
		}
	}

	// @Override
	public void upgrade(UpgradeToken param) {
		doHttpUpgrade(param);
	}

	/**
	 * Process an HTTP upgrade. Processors that support HTTP upgrade should override
	 * this method and process the provided token.
	 *
	 * @param upgradeToken Contains all the information necessary for the Processor
	 *                     to process the upgrade
	 *
	 * @throws UnsupportedOperationException if the protocol does not support HTTP
	 *                                       upgrade
	 */
	protected void doHttpUpgrade(UpgradeToken upgradeToken) {
		// Should never happen
		throw new UnsupportedOperationException(sm.getString("abstractProcessor.httpupgrade.notsupported"));
	}

	// @Override
	public void isPushSupported(AtomicBoolean param) {
		AtomicBoolean result = param;
		result.set(isPushSupported());
	}

	/**
	 * Protocols that support push should override this method and return {@code
	 * true}.
	 *
	 * @return {@code true} if push is supported by this processor, otherwise
	 *         {@code false}.
	 */
	protected boolean isPushSupported() {
		return false;
	}

	// @Override
	public void pushRequest(ExchangeData param) {
		doPush(param);
	}

	/**
	 * Process a push. Processors that support push should override this method and
	 * process the provided token.
	 *
	 * @param pushTarget Contains all the information necessary for the Processor to
	 *                   process the push request
	 *
	 * @throws UnsupportedOperationException if the protocol does not support push
	 */
	protected void doPush(ExchangeData exchangeData) {
		throw new UnsupportedOperationException(sm.getString("abstractProcessor.pushrequest.notsupported"));
	}

	@Override
	public final void nextRequest() {
		if (recycled) {
			throw new RuntimeException();
		}
//		System.out.println(exchangeData.getRequestURI().toString() + "处理请求总用时："
//				+ (System.currentTimeMillis() - exchangeData.getStartTime()));
		errorState = ErrorState.NONE;
		exchangeData.recycle();
		asyncStateMachine.recycle();
//		responseData.getRequestData().recycle();
		nextRequestInternal();
	}

	protected abstract void nextRequestInternal();

	@Override
	public final void recycle() {
		if (recycled) {
			throw new RuntimeException();
		}
//		responseData.getRequestData().recycle();
		recycleInternal();
		errorState = ErrorState.NONE;
		exchangeData.recycle();
		asyncStateMachine.recycle();
		channel = null;
		recycled = true;
	}

	protected abstract void recycleInternal();

	@Override
	public Exception collectCloseException() {
		return null;
	}

	/**
	 * {@inheritDoc} Processors that implement HTTP upgrade must override this
	 * method and provide the necessary token.
	 */
	@Override
	public UpgradeToken getUpgradeToken() {
		// Should never reach this code but in case we do...
		throw new IllegalStateException(sm.getString("abstractProcessor.httpupgrade.notsupported"));
	}

	/**
	 * {@inheritDoc} Processors that implement HTTP upgrade must override this
	 * method.
	 */
	@Override
	public ByteBuffer getLeftoverInput() {
		// Should never reach this code but in case we do...
		throw new IllegalStateException(sm.getString("abstractProcessor.httpupgrade.notsupported"));
	}

	/**
	 * {@inheritDoc} Processors that implement HTTP upgrade must override this
	 * method.
	 */
	@Override
	public boolean isUpgrade() {
		return false;
	}

	@Override
	protected final void logAccess() throws IOException {
		// Set the socket wrapper so the access log can read the socket related
		// information (e.g. client IP)
		// setChannel(channel);
		// Setup the minimal request information
		exchangeData.setStartTime(System.currentTimeMillis());
		// Setup the minimal response information
		exchangeData.setStatus(400);
		exchangeData.setError();
		if (asyncStateMachine.isAsync()) {
			Request request = asyncStateMachine.getRequest(); // createRequest();
			Response response = request.getResponse(); // createResponse();
//			request.setResponse(response);
			getAdapter().log(request, response, 0);
		} else {
			Request request = createRequest();
			Response response = createResponse();
			request.setResponse(response);
			getAdapter().log(request, response, 0);
		}
	}

	public final Request createRequest() {
		return new Request(exchangeData, this, requestAction);
	}

	public final Response createResponse() {
		return new Response(exchangeData, this, responseAction);
	}

	public static boolean isConnectionToken(MimeHeaders headers, String token) throws IOException {
		MessageBytes connection = headers.getValue(Constants.CONNECTION);
		if (connection == null) {
			return false;
		}

		Set<String> tokens = new HashSet<>();
		TokenList.parseTokenList(headers.values(Constants.CONNECTION), tokens);
		return tokens.contains(token);
	}

	/**
	 * Determine if we must drop the connection because of the HTTP status code. Use
	 * the same list of codes as Apache/httpd.
	 */
	public static boolean statusDropsConnection(int status) {
		return status == 400 /* SC_BAD_REQUEST */ || status == 408 /* SC_REQUEST_TIMEOUT */
				|| status == 411 /* SC_LENGTH_REQUIRED */ || status == 413 /* SC_REQUEST_ENTITY_TOO_LARGE */
				|| status == 414 /* SC_REQUEST_URI_TOO_LONG */ || status == 500 /* SC_INTERNAL_SERVER_ERROR */
				|| status == 503 /* SC_SERVICE_UNAVAILABLE */ || status == 501 /* SC_NOT_IMPLEMENTED */;
	}

}
