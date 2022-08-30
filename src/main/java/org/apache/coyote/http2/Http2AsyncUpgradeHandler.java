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
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.servlet.http.WebConnection;

import org.apache.coyote.Adapter;
import org.apache.coyote.ExchangeData;
import org.apache.coyote.ProtocolException;
import org.apache.tomcat.util.http.MimeHeaders;
import org.apache.tomcat.util.net.SendfileState;
import org.apache.tomcat.util.net.SocketChannel;
import org.apache.tomcat.util.net.SocketChannel.BlockingMode;
import org.apache.tomcat.util.net.SocketChannel.CompletionState;

public class Http2AsyncUpgradeHandler extends Http2UpgradeHandler {

	private static final ByteBuffer[] BYTEBUFFER_ARRAY = new ByteBuffer[0];
	// Ensures headers are generated and then written for one thread at a time.
	// Because of the compression used, headers need to be written to the
	// network in the same order they are generated.
	private final Object headerWriteLock = new Object();
	private Throwable error = null;
	private IOException applicationIOE = null;
	private final InputHandlerImpl inputHandler;
	private final OutputHandlerAsyncImpl outputHandler;

	public Http2AsyncUpgradeHandler(Http2Protocol protocol, Adapter adapter, ExchangeData exchangeData) {
		super(protocol, adapter, exchangeData);
		inputHandler = new InputHandlerImpl();
		outputHandler = new OutputHandlerAsyncImpl();
	}

	private CompletionHandler<Long, Void> errorCompletion = new CompletionHandler<Long, Void>() {
		@Override
		public void completed(Long result, Void attachment) {
		}

		@Override
		public void failed(Throwable t, Void attachment) {
			error = t;
		}
	};
	private CompletionHandler<Long, Void> applicationErrorCompletion = new CompletionHandler<Long, Void>() {
		@Override
		public void completed(Long result, Void attachment) {
		}

		@Override
		public void failed(Throwable t, Void attachment) {
			if (t instanceof IOException) {
				t.printStackTrace();
				applicationIOE = (IOException) t;
			}
			error = t;
		}
	};

	@Override
	protected Http2Parser createParser(String connectionId) {
		return new Http2AsyncParser(connectionId, inputHandler, outputHandler, getChannel(), this);
	}

	@Override
	protected ChannelWriter createChannelWriter() {
		return new ChannelWriterAsync();
	}

	@Override
	protected PingManager createPingManager() {
		return new AsyncPingManager();
	}

	@Override
	public boolean hasAsyncIO() {
		return true;
	}

	@Override
	protected void processConnection(WebConnection webConnection, StreamChannel stream) {
		// The end of the processing will instead be an async callback
	}

	void processConnectionCallback(WebConnection webConnection, StreamChannel stream) {
		super.processConnection(webConnection, stream);
	}

	@Override
	protected HeaderFrameBuffers getHeaderFrameBuffers(int initialPayloadSize) {
		return new AsyncHeaderFrameBuffers(initialPayloadSize);
	}

	private void handleAsyncException() throws IOException {
		if (applicationIOE != null) {
			IOException ioe = applicationIOE;
			applicationIOE = null;
			handleAppInitiatedIOException(ioe);
		} else if (error != null) {
			Throwable error = this.error;
			this.error = null;
			if (error instanceof IOException) {
				throw (IOException) error;
			} else {
				throw new IOException(error);
			}
		}
	}

	@Override
	protected SendfileState processSendfile(SendfileData sendfile) {
		if (sendfile != null) {
			try {
				try (FileChannel channel = FileChannel.open(sendfile.getPath(), StandardOpenOption.READ)) {
					sendfile.setMappedBuffer(
							channel.map(MapMode.READ_ONLY, sendfile.getPos(), sendfile.getEnd() - sendfile.getPos()));
				}
				// Reserve as much as possible right away
				int reservation = (sendfile.getEnd() - sendfile.getPos() > Integer.MAX_VALUE) ? Integer.MAX_VALUE
						: (int) (sendfile.getEnd() - sendfile.getPos());
				sendfile.setStreamReservation(sendfile.getStream().reserveWindowSize(reservation, true));
				sendfile.setConnectionReservation(
						zero.reserveWindowSize(sendfile.getStream(), (int) sendfile.getStreamReservation(), true));
			} catch (IOException e) {
				return SendfileState.ERROR;
			}
			// Actually perform the write
			int frameSize = Integer.min(localSettings.getMaxFrameSize(), (int) sendfile.getConnectionReservation());
			boolean finished = (frameSize == sendfile.getLeft())
					&& sendfile.getStream().getExchangeData().getTrailerFieldsSupplier() == null;

			// Need to check this now since sending end of stream will change this.
			boolean writeable = sendfile.getStream().canWrite();
			byte[] header = new byte[9];
			ByteUtil.setThreeBytes(header, 0, frameSize);
			header[3] = FrameType.DATA.getIdByte();
			if (finished) {
				header[4] = FLAG_END_OF_STREAM;
				sendfile.getStream().sentEndOfStream();
				if (!sendfile.getStream().isActive()) {
					setConnectionTimeoutForStreamCount(activeRemoteStreamCount.decrementAndGet());
				}
			}
			if (writeable) {
				ByteUtil.set31Bits(header, 5, sendfile.getStream().getIdAsInt());
				sendfile.getMappedBuffer().limit(sendfile.getMappedBuffer().position() + frameSize);
				getChannel().write(BlockingMode.SEMI_BLOCK, protocol.getWriteTimeout(), TimeUnit.MILLISECONDS, sendfile,
						SocketChannel.COMPLETE_WRITE_WITH_COMPLETION, new SendfileCompletionHandler(),
						ByteBuffer.wrap(header), sendfile.getMappedBuffer());
				try {
					handleAsyncException();
				} catch (IOException e) {
					return SendfileState.ERROR;
				}
			}
			return SendfileState.PENDING;
		} else {
			return SendfileState.DONE;
		}
	}

	protected class ChannelWriterAsync extends ChannelWriter {

		@Override
		protected void writeSettings(ConnectionSettingsLocal localSettings) {
			getChannel().write(BlockingMode.SEMI_BLOCK, protocol.getWriteTimeout(), TimeUnit.MILLISECONDS, null,
					SocketChannel.COMPLETE_WRITE, errorCompletion,
					ByteBuffer.wrap(localSettings.getSettingsFrameForPending()),
					ByteBuffer.wrap(createWindowUpdateForSettings()));
			if (error != null) {
				String msg = sm.getString("upgradeHandler.sendPrefaceFail", zero.getConnectionID());
				if (log.isDebugEnabled()) {
					log.debug(msg);
				}
				throw new ProtocolException(msg, error);
			}
		}

		@Override
		void writeHeaders(Stream stream, int pushedStreamId, MimeHeaders mimeHeaders, boolean endOfStream,
				int payloadSize) throws IOException {
			synchronized (headerWriteLock) {
				AsyncHeaderFrameBuffers headerFrameBuffers = (AsyncHeaderFrameBuffers) doWriteHeaders(stream,
						pushedStreamId, mimeHeaders, endOfStream, payloadSize);
				if (headerFrameBuffers != null) {
					getChannel().write(BlockingMode.SEMI_BLOCK, protocol.getWriteTimeout(), TimeUnit.MILLISECONDS, null,
							SocketChannel.COMPLETE_WRITE, applicationErrorCompletion,
							headerFrameBuffers.bufs.toArray(BYTEBUFFER_ARRAY));
					handleAsyncException();
				}
			}
			if (endOfStream) {
				stream.sentEndOfStream();
			}
		}

		@Override
		void writeBody(Stream stream, ByteBuffer data, int len, boolean finished) throws IOException {
			if (log.isDebugEnabled()) {
				log.debug(sm.getString("upgradeHandler.writeBody", zero.getConnectionID(), stream.getIdentifier(),
						Integer.toString(len)));
			}
			// Need to check this now since sending end of stream will change this.
			boolean writeable = stream.canWrite();
			byte[] header = new byte[9];
			ByteUtil.setThreeBytes(header, 0, len);
			header[3] = FrameType.DATA.getIdByte();
			if (finished) {
				header[4] = FLAG_END_OF_STREAM;
				stream.sentEndOfStream();
				if (!stream.isActive()) {
					setConnectionTimeoutForStreamCount(activeRemoteStreamCount.decrementAndGet());
				}
			}
			if (writeable) {
				ByteUtil.set31Bits(header, 5, stream.getIdAsInt());
				int orgLimit = data.limit();
				data.limit(data.position() + len);
				getChannel().write(BlockingMode.BLOCK, protocol.getWriteTimeout(), TimeUnit.MILLISECONDS, null,
						SocketChannel.COMPLETE_WRITE, applicationErrorCompletion, ByteBuffer.wrap(header), data);
				data.limit(orgLimit);
				handleAsyncException();
			}
		}

		@Override
		void writeWindowUpdate(Stream stream, int increment, boolean applicationInitiated) throws IOException {
			if (!stream.canWrite()) {
				return;
			}
			// Build window update frame for stream 0
			byte[] frame = new byte[13];
			ByteUtil.setThreeBytes(frame, 0, 4);
			frame[3] = FrameType.WINDOW_UPDATE.getIdByte();
			ByteUtil.set31Bits(frame, 9, increment);
			// Change stream Id
			byte[] frame2 = new byte[13];
			ByteUtil.setThreeBytes(frame2, 0, 4);
			frame2[3] = FrameType.WINDOW_UPDATE.getIdByte();
			ByteUtil.set31Bits(frame2, 9, increment);
			ByteUtil.set31Bits(frame2, 5, stream.getIdAsInt());
			getChannel().write(BlockingMode.SEMI_BLOCK, protocol.getWriteTimeout(), TimeUnit.MILLISECONDS, null,
					SocketChannel.COMPLETE_WRITE, errorCompletion, ByteBuffer.wrap(frame), ByteBuffer.wrap(frame2));
			handleAsyncException();
		}

		@Override
		void writeStreamReset(StreamException se) throws IOException {
			if (log.isDebugEnabled()) {
				log.debug(sm.getString("upgradeHandler.rst.debug", zero.getConnectionID(),
						Integer.toString(se.getStreamId()), se.getError(), se.getMessage()));
			}
			// Write a RST frame
			byte[] rstFrame = new byte[13];
			// Length
			ByteUtil.setThreeBytes(rstFrame, 0, 4);
			// Type
			rstFrame[3] = FrameType.RST.getIdByte();
			// No flags
			// Stream ID
			ByteUtil.set31Bits(rstFrame, 5, se.getStreamId());
			// Payload
			ByteUtil.setFourBytes(rstFrame, 9, se.getError().getCode());
			getChannel().write(BlockingMode.SEMI_BLOCK, protocol.getWriteTimeout(), TimeUnit.MILLISECONDS, null,
					SocketChannel.COMPLETE_WRITE, errorCompletion, ByteBuffer.wrap(rstFrame));
			handleAsyncException();
		}

		@Override
		protected void writeGoAwayFrame(int maxStreamId, long errorCode, byte[] debugMsg) throws IOException {
			byte[] fixedPayload = new byte[8];
			ByteUtil.set31Bits(fixedPayload, 0, maxStreamId);
			ByteUtil.setFourBytes(fixedPayload, 4, errorCode);
			int len = 8;
			if (debugMsg != null) {
				len += debugMsg.length;
			}
			byte[] payloadLength = new byte[3];
			ByteUtil.setThreeBytes(payloadLength, 0, len);
			if (debugMsg != null) {
				getChannel().write(BlockingMode.SEMI_BLOCK, protocol.getWriteTimeout(), TimeUnit.MILLISECONDS, null,
						SocketChannel.COMPLETE_WRITE, errorCompletion, ByteBuffer.wrap(payloadLength),
						ByteBuffer.wrap(GOAWAY), ByteBuffer.wrap(fixedPayload), ByteBuffer.wrap(debugMsg));
			} else {
				getChannel().write(BlockingMode.SEMI_BLOCK, protocol.getWriteTimeout(), TimeUnit.MILLISECONDS, null,
						SocketChannel.COMPLETE_WRITE, errorCompletion, ByteBuffer.wrap(payloadLength),
						ByteBuffer.wrap(GOAWAY), ByteBuffer.wrap(fixedPayload));
			}
			handleAsyncException();
		}

	}

	protected class OutputHandlerAsyncImpl extends OutputHandlerImpl {

		@Override
		public void receiveSettingsEnd(boolean ack) throws IOException {
			if (ack) {
				if (!localSettings.ack()) {
					// Ack was unexpected
					log.warn(
							sm.getString("upgradeHandler.unexpectedAck", zero.getConnectionID(), zero.getIdentifier()));
				}
			} else {
				getChannel().write(BlockingMode.SEMI_BLOCK, protocol.getWriteTimeout(), TimeUnit.MILLISECONDS, null,
						SocketChannel.COMPLETE_WRITE, errorCompletion, ByteBuffer.wrap(SETTINGS_ACK));
			}
			handleAsyncException();
		}

	}

	protected class SendfileCompletionHandler implements CompletionHandler<Long, SendfileData> {
		@Override
		public void completed(Long nBytes, SendfileData sendfile) {
			CompletionState completionState = null;
			long bytesWritten = nBytes.longValue() - 9;

			/*
			 * Loop for in-line writes only. Avoids a possible stack-overflow of chained
			 * completion handlers with a long series of in-line writes.
			 */
			do {
				sendfile.setLeft(sendfile.getLeft() - bytesWritten);
				if (sendfile.getLeft() == 0) {
					try {
						sendfile.getStream().doWriteBody(ByteBuffer.allocate(0), true);
					} catch (IOException e) {
						e.printStackTrace();
					}
					return;
				}
				sendfile.setStreamReservation(sendfile.getStreamReservation() - bytesWritten);
				sendfile.setConnectionReservation(sendfile.getConnectionReservation() - bytesWritten);
				sendfile.setPos(sendfile.getPos() + bytesWritten);
				try {
					if (sendfile.getConnectionReservation() == 0) {
						if (sendfile.getStreamReservation() == 0) {
							int reservation = (sendfile.getEnd() - sendfile.getPos() > Integer.MAX_VALUE)
									? Integer.MAX_VALUE
									: (int) (sendfile.getEnd() - sendfile.getPos());
							sendfile.setStreamReservation(sendfile.getStream().reserveWindowSize(reservation, true));
						}
						sendfile.setConnectionReservation(zero.reserveWindowSize(sendfile.getStream(),
								(int) sendfile.getStreamReservation(), true));
					}
				} catch (IOException e) {
					failed(e, sendfile);
					return;
				}

				if (log.isDebugEnabled()) {
					log.debug(sm.getString("upgradeHandler.sendfile.reservation",
							sendfile.getStream().getConnectionId(), sendfile.getStream().getIdAsString(),
							Long.valueOf(sendfile.getConnectionReservation()),
							Long.valueOf(sendfile.getStreamReservation())));
				}

				// connectionReservation will always be smaller than or the same as
				// streamReservation
				int frameSize = Integer.min(localSettings.getMaxFrameSize(), (int) sendfile.getConnectionReservation());
				boolean finished = (frameSize == sendfile.getLeft())
						&& sendfile.getStream().getExchangeData().getTrailerFields() == null;

				// Need to check this now since sending end of stream will change this.
				boolean writable = sendfile.getStream().canWrite();
				byte[] header = new byte[9];
				ByteUtil.setThreeBytes(header, 0, frameSize);
				header[3] = FrameType.DATA.getIdByte();
				if (finished) {
					header[4] = FLAG_END_OF_STREAM;
					sendfile.getStream().sentEndOfStream();
					if (!sendfile.getStream().isActive()) {
						setConnectionTimeoutForStreamCount(activeRemoteStreamCount.decrementAndGet());
					}
				}
				if (writable) {
					if (log.isDebugEnabled()) {
						log.debug(sm.getString("upgradeHandler.writeBody", sendfile.getStream().getConnectionId(),
								sendfile.getStream().getIdAsString(), Integer.toString(frameSize),
								Boolean.valueOf(finished)));
					}
					ByteUtil.set31Bits(header, 5, sendfile.getStream().getIdAsInt());
					sendfile.getMappedBuffer().limit(sendfile.getMappedBuffer().position() + frameSize);
					// Note: Completion handler not called in the write
					// completes in-line. The wrote will continue via the
					// surrounding loop.
					completionState = getChannel().write(BlockingMode.SEMI_BLOCK, protocol.getWriteTimeout(),
							TimeUnit.MILLISECONDS, sendfile, SocketChannel.COMPLETE_WRITE, this,
							ByteBuffer.wrap(header), sendfile.getMappedBuffer());
					try {
						handleAsyncException();
					} catch (IOException e) {
						failed(e, sendfile);
						return;
					}
				}
				// Update bytesWritten for start of next loop iteration
				bytesWritten = frameSize;
			} while (completionState == CompletionState.INLINE);
		}

		@Override
		public void failed(Throwable t, SendfileData sendfile) {
			applicationErrorCompletion.failed(t, null);
		}
	}

	protected class AsyncPingManager extends PingManager {
		@Override
		public void sendPing(boolean force) throws IOException {
			if (initiateDisabled) {
				return;
			}
			long now = System.nanoTime();
			if (force || now - lastPingNanoTime > pingIntervalNano) {
				lastPingNanoTime = now;
				byte[] payload = new byte[8];
				int sentSequence = ++sequence;
				PingRecord pingRecord = new PingRecord(sentSequence, now);
				inflightPings.add(pingRecord);
				ByteUtil.set31Bits(payload, 4, sentSequence);
				getChannel().write(BlockingMode.SEMI_BLOCK, protocol.getWriteTimeout(), TimeUnit.MILLISECONDS, null,
						SocketChannel.COMPLETE_WRITE, errorCompletion, ByteBuffer.wrap(PING), ByteBuffer.wrap(payload));
				handleAsyncException();
			}
		}

		@Override
		public void receivePing(byte[] payload, boolean ack) throws IOException {
			if (ack) {
				super.receivePing(payload, ack);
			} else {
				// Client originated ping. Echo it back.
				getChannel().write(BlockingMode.SEMI_BLOCK, protocol.getWriteTimeout(), TimeUnit.MILLISECONDS, null,
						SocketChannel.COMPLETE_WRITE, errorCompletion, ByteBuffer.wrap(PING_ACK),
						ByteBuffer.wrap(payload));
				handleAsyncException();
			}
		}

	}

	private static class AsyncHeaderFrameBuffers implements HeaderFrameBuffers {

		int payloadSize;

		private byte[] header;
		private ByteBuffer payload;

		private final List<ByteBuffer> bufs = new ArrayList<>();

		public AsyncHeaderFrameBuffers(int initialPayloadSize) {
			this.payloadSize = initialPayloadSize;
		}

		@Override
		public void startFrame() {
			header = new byte[9];
			payload = ByteBuffer.allocate(payloadSize);
		}

		@Override
		public void endFrame() throws IOException {
			bufs.add(ByteBuffer.wrap(header));
			bufs.add(payload);
		}

		@Override
		public void endHeaders() throws IOException {
		}

		@Override
		public byte[] getHeader() {
			return header;
		}

		@Override
		public ByteBuffer getPayload() {
			return payload;
		}

		@Override
		public void expandPayload() {
			payloadSize = payloadSize * 2;
			payload = ByteBuffer.allocate(payloadSize);
		}
	}
}
