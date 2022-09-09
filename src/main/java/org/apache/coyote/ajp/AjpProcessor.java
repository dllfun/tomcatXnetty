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
package org.apache.coyote.ajp;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.coyote.AbstractProcessor;
import org.apache.coyote.Adapter;
import org.apache.coyote.ErrorState;
import org.apache.coyote.Request;
import org.apache.coyote.RequestInfo;
import org.apache.coyote.Response;
import org.apache.coyote.http11.HeadersTooLargeException;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.buf.MessageBytes;
import org.apache.tomcat.util.net.Channel;
import org.apache.tomcat.util.net.Endpoint.Handler.SocketState;
import org.apache.tomcat.util.net.SocketChannel.BufWrapper;
import org.apache.tomcat.util.net.SendfileState;
import org.apache.tomcat.util.net.SocketChannel;
import org.apache.tomcat.util.res.StringManager;

/**
 * AJP Processor implementation.
 */
public class AjpProcessor extends AbstractProcessor {

	private static final Log log = LogFactory.getLog(AjpProcessor.class);
	/**
	 * The string manager for this package.
	 */
	private static final StringManager sm = StringManager.getManager(AjpProcessor.class);

	/**
	 * Input.
	 */
	private final SocketInputReader inputReader;

	/**
	 * Associated output buffer.
	 */
	private final SocketOutputBuffer outputBuffer;

	private SocketChannel channel;

	// ----------------------------------------------------- Instance Variables

	private final AbstractAjpProtocol<?> protocol;

	/**
	 * Byte chunk for certs.
	 */
	private final MessageBytes certificates = MessageBytes.newInstance();

	// Expected to block on the first read as there should be at least one
	// AJP message to read.
	boolean firstRead = true;

	// ------------------------------------------------------------ Constructor

	public AjpProcessor(AbstractAjpProtocol<?> protocol, Adapter adapter) {
		super(protocol, adapter);
		this.protocol = protocol;

		this.inputReader = new SocketInputReader(this);

		// inputHandler = inputReader;

		this.outputBuffer = new SocketOutputBuffer(this);
	}

	public AbstractAjpProtocol<?> getProtocol() {
		return protocol;
	}

	public MessageBytes getCertificates() {
		return certificates;
	}

	public SocketInputReader getInputReader() {
		return inputReader;
	}

	public SocketOutputBuffer getOutputBuffer() {
		return outputBuffer;
	}

	@Override
	protected Request createRequest() {
		return new Request(this.exchangeData, this, inputReader);
	}

	@Override
	protected Response createResponse() {
		return new Response(this.exchangeData, this, outputBuffer);
	}

	// --------------------------------------------------------- Public Methods

	@Override
	protected boolean flushBufferedWrite() throws IOException {
		if (hasDataToWrite()) {
			channel.flush(false);
			if (hasDataToWrite()) {
				// There is data to write but go via Response to
				// maintain a consistent view of non-blocking state
				// response.checkRegisterForWrite();
				AtomicBoolean ready = new AtomicBoolean(false);
				synchronized (getAsyncStateMachine().getNonBlockingStateLock()) {
					if (!getAsyncStateMachine().isRegisteredForWrite()) {
						// actionNB_WRITE_INTEREST(ready);
						ready.set(outputBuffer.isReadyForWrite());
						getAsyncStateMachine().setRegisteredForWrite(!ready.get());
					}
				}
				return true;
			}
		}
		return false;
	}

	@Override
	protected void dispatchNonBlockingRead() {
		if (inputReader.available(true) > 0) {
			super.dispatchNonBlockingRead();
		}
	}

	@Override
	protected SocketState dispatchEndRequest() {
		// Set keep alive timeout for next request
		channel.setReadTimeout(protocol.getKeepAliveTimeout());
		nextRequest();
		if (protocol.isPaused()) {
			return SocketState.CLOSED;
		} else {
			return SocketState.OPEN;
		}
	}

	@Override
	protected void initChannel(Channel channel) {
		this.channel = (SocketChannel) channel;
	}

	@Override
	protected boolean repeat() {
		return true;
	}

	@Override
	public SocketState serviceInternal() throws IOException {// Channel channel
//		SocketChannel socketChannel = (SocketChannel) channel;
		// this.channel = socketChannel;
		RequestInfo rp = exchangeData.getRequestProcessor();
		rp.setStage(org.apache.coyote.Constants.STAGE_PARSE);

		// Setting up the socket
		// setChannel(channel);

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

			if (getErrorState().isIoAllowed()) {
				// Setting up filters, and parse some request headers
				rp.setStage(org.apache.coyote.Constants.STAGE_PREPARE);
				try {
					inputReader.prepareRequest();
				} catch (Throwable t) {
					ExceptionUtils.handleThrowable(t);
					if (log.isDebugEnabled()) {
						getLog().debug(sm.getString("ajpprocessor.request.prepare"), t);
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
//			cping = false;

			// Process the request in the adapter
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
					log.error(sm.getString("ajpprocessor.request.process"), e);
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
					log.error(sm.getString("ajpprocessor.request.process"), t);
					// 500 - Internal Server Error
					exchangeData.setStatus(500);
					setErrorState(ErrorState.CLOSE_CLEAN, t);
					Request request = createRequest();
					Response response = createResponse();
					request.setResponse(response);
					getAdapter().log(request, response, 0);
				}
			}

			rp.setStage(org.apache.coyote.Constants.STAGE_ENDINPUT);
			if (!asyncStateMachine.isAsync()) {
				endRequest();
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
	}

	@Override
	protected boolean isHttp2Preface() {
		return false;
	}

	@Override
	protected boolean parsingHeader() {
		// Parsing the request header
//		boolean cping = false;

		try {
			// Get first message of the request
			if (!inputReader.fillHeaderMessage(firstRead)) {
				return false;
			}
			firstRead = false;

			// Processing the request so make sure the connection rather
			// than keep-alive timeout is used
			channel.setReadTimeout(protocol.getConnectionTimeout());
			exchangeData.setStartTime(System.currentTimeMillis());

		} catch (IOException e) {
			setErrorState(ErrorState.CLOSE_CONNECTION_NOW, e);
		} catch (Throwable t) {
			ExceptionUtils.handleThrowable(t);
			getLog().debug(sm.getString("ajpprocessor.header.error"), t);
			// 400 - Bad Request
			exchangeData.setStatus(400);
			setErrorState(ErrorState.CLOSE_CLEAN, t);
		}
		return true;

	}

	@Override
	protected boolean canReleaseProcessor() {
		BufWrapper byteBuffer = ((SocketChannel) getChannel()).getAppReadBuffer();
		if (byteBuffer.getPosition() == 0 && byteBuffer.getRemaining() == 0) {
			return true;
		} else {
			return false;
		}
	}

	@Override
	protected void resetSocketReadTimeout() {
		// Set keep alive timeout for next request
		channel.setReadTimeout(protocol.getKeepAliveTimeout());
	}

	@Override
	protected void endRequest() {
		// Finish the response if not done yet
		if (!outputBuffer.isResponseFinished() && getErrorState().isIoAllowed()) {
//						try {
			outputBuffer.close();
			// outputBuffer.commit(true);
			// outputBuffer.finishResponse();
//						} catch (IOException ioe) {
//							setErrorState(ErrorState.CLOSE_CONNECTION_NOW, ioe);
//						} catch (Throwable t) {
//							ExceptionUtils.handleThrowable(t);
//							setErrorState(ErrorState.CLOSE_NOW, t);
//						}
		}

	}

	@Override
	protected SendfileState processSendfile() throws IOException {
		return SendfileState.DONE;
	}

	@Override
	protected void nextRequestInternal() {
		Request request = createRequest();
		Response response = createResponse();
		request.setResponse(response);
		getAdapter().checkRecycled(request, response);
		// super.recycle();
		inputReader.recycle();
		outputBuffer.recycle();
		certificates.recycle();
	}

	@Override
	protected void recycleInternal() {
		Request request = createRequest();
		Response response = createResponse();
		request.setResponse(response);
		getAdapter().checkRecycled(request, response);
		// super.recycle();
		channel = null;
//		first = true;
		inputReader.recycle();
		outputBuffer.recycle();
		certificates.recycle();
//		waitingForBodyMessage = false;
//		empty = true;
//		replay = false;
//		responseFinished = false;
//		swallowResponse = false;
//		bytesWritten = 0;
	}

	@Override
	public void pause() {
		// NOOP for AJP
	}

	// ------------------------------------------------------ Protected Methods

	private boolean hasDataToWrite() {
		return outputBuffer.getResponseMsgPos() != -1 || channel.hasDataToWrite();
	}

	@Override
	protected Log getLog() {
		return log;
	}

	// ------------------------------------- InputStreamInputBuffer Inner Class

	// ----------------------------------- OutputStreamOutputBuffer Inner Class

}
