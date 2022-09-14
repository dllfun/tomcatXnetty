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
package org.apache.coyote.http2;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.CompletionHandler;
import java.util.concurrent.TimeUnit;

import javax.servlet.http.WebConnection;

import org.apache.coyote.ContainerThreadMarker;
import org.apache.coyote.ProtocolException;
import org.apache.tomcat.util.net.SocketChannel;
import org.apache.tomcat.util.net.SocketChannel.BlockingMode;
import org.apache.tomcat.util.net.SocketChannel.CompletionCheck;
import org.apache.tomcat.util.net.SocketChannel.CompletionHandlerCall;
import org.apache.tomcat.util.net.SocketChannel.CompletionState;
import org.apache.tomcat.util.net.SocketWrapperBase.ByteBufferWrapper;
import org.apache.tomcat.util.net.SocketEvent;
import org.apache.tomcat.util.net.SocketWrapperBase;

class Http2AsyncParser extends Http2Parser {

	private final Http2AsyncUpgradeHandler upgradeHandler;
	private SocketChannel channel;
	private Throwable error = null;
	private ByteBufferWrapper header = ByteBufferWrapper.wrapper(ByteBuffer.allocate(9), false);
	private ByteBufferWrapper framePayload = ByteBufferWrapper.wrapper(ByteBuffer.allocate(input.getMaxFrameSize()),
			false);

	Http2AsyncParser(String connectionId, Input input, Output output, Http2AsyncUpgradeHandler upgradeHandler) {
		super(connectionId, input, output);
		this.upgradeHandler = upgradeHandler;
	}

	@Override
	void onChannelReady(SocketChannel channel) {
		super.onChannelReady(channel);
		this.channel = channel;
		if (channel instanceof SocketWrapperBase<?>) {
			((SocketWrapperBase<?>) channel).expandSocketBuffer(input.getMaxFrameSize());
		}
	}

	@Override
	void readConnectionPreface(WebConnection webConnection, StreamChannel stream) throws Http2Exception {
		byte[] prefaceData = new byte[CLIENT_PREFACE_START.length];
		ByteBufferWrapper preface = ByteBufferWrapper.wrapper(ByteBuffer.wrap(prefaceData), false);
		if (framePayload.getCapacity() < input.getMaxFrameSize()) {
			framePayload = ByteBufferWrapper.wrapper(ByteBuffer.allocate(input.getMaxFrameSize()), false);
		}
		PrefaceCompletionHandler handler = new PrefaceCompletionHandler(webConnection, stream, prefaceData, preface,
				header, framePayload);
		channel.read(BlockingMode.NON_BLOCK, channel.getReadTimeout(), TimeUnit.MILLISECONDS, null, handler, handler,
				preface, header, framePayload);
	}

	private class PrefaceCompletionHandler extends FrameCompletionHandler {

		private boolean prefaceValidated = false;

		private final WebConnection webConnection;
		private final StreamChannel stream;
		private final byte[] prefaceData;

		private PrefaceCompletionHandler(WebConnection webConnection, StreamChannel stream, byte[] prefaceData,
				ByteBufferWrapper... buffers) {
			super(FrameType.SETTINGS, buffers);
			this.webConnection = webConnection;
			this.stream = stream;
			this.prefaceData = prefaceData;
		}

		@Override
		public CompletionHandlerCall callHandler(CompletionState state, ByteBufferWrapper[] buffers, int offset,
				int length) {
			if (offset != 0 || length != 3) {
				try {
					throw new IllegalArgumentException(sm.getString("http2Parser.invalidBuffers"));
				} catch (IllegalArgumentException e) {
					error = e;
					return CompletionHandlerCall.DONE;
				}
			}
			if (!prefaceValidated) {
				if (buffers[0].hasRemaining()) {
					// The preface must be fully read before being validated
					return CompletionHandlerCall.CONTINUE;
				}
				// Validate preface content
				for (int i = 0; i < CLIENT_PREFACE_START.length; i++) {
					if (CLIENT_PREFACE_START[i] != prefaceData[i]) {
						error = new ProtocolException(sm.getString("http2Parser.preface.invalid"));
						return CompletionHandlerCall.DONE;
					}
				}
				prefaceValidated = true;
			}
			return validate(state, buffers[1], buffers[2]);
		}

		@Override
		public void completed(Long result, Void attachment) {
			if (streamException || error == null) {
				ByteBufferWrapper payload = buffers[2];
//				payload.flip();
				payload.switchToReadMode();
				try {
					if (streamException) {
						swallowPayload(streamId, frameTypeId, payloadSize, false, payload);
					} else {
						readSettingsFrame(flags, payloadSize, payload);
					}
				} catch (RuntimeException | IOException | Http2Exception e) {
					error = e;
				}
				// Any extra frame is not processed yet, so put back any leftover data
				if (payload.hasRemaining()) {
					channel.unRead(payload);
				}
//				header.compact();
				header.switchToReadMode();
				while (header.hasRemaining()) {
					header.getByte();
				}
				header.switchToWriteMode();
//				framePayload.compact();
				while (framePayload.hasRemaining()) {
					framePayload.getByte();
				}
				framePayload.switchToWriteMode();
			}
			// Finish processing the connection
			upgradeHandler.processConnectionCallback(webConnection, stream);
			// Continue reading frames
			if (ContainerThreadMarker.isContainerThread()) {
				upgradeHandler.upgradeDispatch(SocketEvent.OPEN_READ);
			} else {
				upgradeHandler.getProtocol().getHttp11Protocol().getHandler().processSocket(channel,
						SocketEvent.OPEN_READ, false);
			}
		}

	}

	@Override
	protected boolean readFrame(boolean block, FrameType expected) throws IOException, Http2Exception {
		handleAsyncException();
		if (framePayload.getCapacity() < input.getMaxFrameSize()) {
			framePayload = ByteBufferWrapper.wrapper(ByteBuffer.allocate(input.getMaxFrameSize()), false);
		}
		header.switchToWriteMode();
		framePayload.switchToWriteMode();
		FrameCompletionHandler handler = new FrameCompletionHandler(expected, header, framePayload);
		CompletionState state = channel.read(block ? BlockingMode.BLOCK : BlockingMode.NON_BLOCK,
				channel.getReadTimeout(), TimeUnit.MILLISECONDS, null, handler, handler, header, framePayload);
		if (state == CompletionState.ERROR || state == CompletionState.INLINE) {
			handleAsyncException();
			return true;
		} else {
			return false;
		}
	}

	private void handleAsyncException() throws IOException, Http2Exception {
		if (error != null) {
			Throwable error = this.error;
			this.error = null;
			if (error instanceof Http2Exception) {
				throw (Http2Exception) error;
			} else if (error instanceof IOException) {
				throw (IOException) error;
			} else if (error instanceof RuntimeException) {
				throw (RuntimeException) error;
			} else {
				throw new RuntimeException(error);
			}
		}
	}

	private class FrameCompletionHandler implements CompletionCheck, CompletionHandler<Long, Void> {

		private final FrameType expected;
		protected final ByteBufferWrapper[] buffers;

		private volatile boolean parsedFrameHeader = false;
		private volatile boolean validated = false;
		private volatile CompletionState state = null;
		protected volatile int payloadSize;
		protected volatile int frameTypeId;
		protected volatile FrameType frameType;
		protected volatile int flags;
		protected volatile int streamId;
		protected volatile boolean streamException = false;

		private FrameCompletionHandler(FrameType expected, ByteBufferWrapper... buffers) {
			this.expected = expected;
			this.buffers = buffers;
		}

		@Override
		public CompletionHandlerCall callHandler(CompletionState state, ByteBufferWrapper[] buffers, int offset,
				int length) {
			if (offset != 0 || length != 2) {
				try {
					throw new IllegalArgumentException(sm.getString("http2Parser.invalidBuffers"));
				} catch (IllegalArgumentException e) {
					error = e;
					return CompletionHandlerCall.DONE;
				}
			}
			return validate(state, buffers[0], buffers[1]);
		}

		protected CompletionHandlerCall validate(CompletionState state, ByteBufferWrapper frameHeaderBuffer,
				ByteBufferWrapper payload) {
			if (!parsedFrameHeader) {
				// The first buffer should be 9 bytes long
				if (frameHeaderBuffer.getPosition() < 9) {
					return CompletionHandlerCall.CONTINUE;
				}
				parsedFrameHeader = true;
				frameHeaderBuffer.switchToReadMode();
				payloadSize = ByteUtil.getThreeBytes(frameHeaderBuffer, 0);
				frameTypeId = ByteUtil.getOneByte(frameHeaderBuffer, 3);
				frameType = FrameType.valueOf(frameTypeId);
				flags = ByteUtil.getOneByte(frameHeaderBuffer, 4);
				streamId = ByteUtil.get31Bits(frameHeaderBuffer, 5);
			}
			this.state = state;

			if (!validated) {
				validated = true;
				try {
					validateFrame(expected, frameType, streamId, flags, payloadSize);
				} catch (StreamException e) {
					error = e;
					streamException = true;
				} catch (Http2Exception e) {
					error = e;
					// The problem will be handled later, consider the frame read is done
					return CompletionHandlerCall.DONE;
				}
			}

			if (payload.getPosition() < payloadSize) {
				frameHeaderBuffer.switchToWriteMode();
				return CompletionHandlerCall.CONTINUE;
			}

			return CompletionHandlerCall.DONE;
		}

		@Override
		public void completed(Long result, Void attachment) {
			if (streamException || error == null) {
				ByteBufferWrapper payload = buffers[1];
//				payload.flip();
				payload.switchToReadMode();
				try {
					boolean continueParsing;
					do {
						continueParsing = false;
						if (streamException) {
							swallowPayload(streamId, frameTypeId, payloadSize, false, payload);
						} else {
							switch (frameType) {
							case DATA:
								readDataFrame(streamId, flags, payloadSize, payload);
								break;
							case HEADERS:
								readHeadersFrame(streamId, flags, payloadSize, payload);
								break;
							case PRIORITY:
								readPriorityFrame(streamId, payload);
								break;
							case RST:
								readRstFrame(streamId, payload);
								break;
							case SETTINGS:
								readSettingsFrame(flags, payloadSize, payload);
								break;
							case PUSH_PROMISE:
								readPushPromiseFrame(streamId, payload);
								break;
							case PING:
								readPingFrame(flags, payload);
								break;
							case GOAWAY:
								readGoawayFrame(payloadSize, payload);
								break;
							case WINDOW_UPDATE:
								readWindowUpdateFrame(streamId, payload);
								break;
							case CONTINUATION:
								readContinuationFrame(streamId, flags, payloadSize, payload);
								break;
							case UNKNOWN:
								readUnknownFrame(streamId, frameTypeId, flags, payloadSize, payload);
							}
						}
						// See if there is a new 9 byte header and continue parsing if possible
						if (payload.getRemaining() >= 9) {
							int position = payload.getPosition();
							payloadSize = ByteUtil.getThreeBytes(payload, position);
							frameType = FrameType.valueOf(ByteUtil.getOneByte(payload, position + 3));
							flags = ByteUtil.getOneByte(payload, position + 4);
							streamId = ByteUtil.get31Bits(payload, position + 5);
							streamException = false;
							if (payload.getRemaining() - 9 >= payloadSize) {
								continueParsing = true;
								// Now go over frame header
								payload.setPosition(payload.getPosition() + 9);
								try {
									validateFrame(null, frameType, streamId, flags, payloadSize);
								} catch (StreamException e) {
									error = e;
									streamException = true;
								} catch (Http2Exception e) {
									error = e;
									continueParsing = false;
								}
							}
						}
					} while (continueParsing);
				} catch (RuntimeException | IOException | Http2Exception e) {
					error = e;
				}
				if (payload.hasRemaining()) {
					channel.unRead(payload);
				}
//				header.compact();
				// TODO check
				header.switchToReadMode();
				while (header.hasRemaining()) {
					header.getByte();
				}
				header.switchToWriteMode();
//				framePayload.compact();
				while (framePayload.hasRemaining()) {
					framePayload.getByte();
				}
				framePayload.switchToWriteMode();
			}
			if (state == CompletionState.DONE) {
				// The call was not completed inline, so must start reading new frames
				// or process the stream exception
				if (ContainerThreadMarker.isContainerThread()) {
					upgradeHandler.upgradeDispatch(SocketEvent.OPEN_READ);
				} else {
					upgradeHandler.getProtocol().getHttp11Protocol().getHandler().processSocket(channel,
							SocketEvent.OPEN_READ, false);
				}
			}
		}

		@Override
		public void failed(Throwable e, Void attachment) {
			// Always a fatal IO error
			error = e;
			if (log.isDebugEnabled()) {
				log.debug(sm.getString("http2Parser.error", connectionId, Integer.valueOf(streamId), frameType), e);
			}
			if (state == null || state == CompletionState.DONE) {
				if (ContainerThreadMarker.isContainerThread()) {
					upgradeHandler.upgradeDispatch(SocketEvent.ERROR);
				} else {
					upgradeHandler.getProtocol().getHttp11Protocol().getHandler().processSocket(channel,
							SocketEvent.ERROR, false);
				}
			}
		}

	}

}
