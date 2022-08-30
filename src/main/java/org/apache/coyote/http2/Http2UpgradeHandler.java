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

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javax.servlet.http.WebConnection;

import org.apache.coyote.Adapter;
import org.apache.coyote.DispatchHandler.ConcurrencyControlled;
import org.apache.coyote.ExchangeData;
import org.apache.coyote.ProtocolException;
import org.apache.coyote.http11.upgrade.InternalHttpUpgradeHandler;
import org.apache.coyote.http2.HpackDecoder.HeaderEmitter;
import org.apache.coyote.http2.HpackEncoder.State;
import org.apache.coyote.http2.Http2Parser.ByteBufferHandler;
import org.apache.coyote.http2.Http2Parser.Input;
import org.apache.coyote.http2.Http2Parser.Output;
import org.apache.coyote.http2.StreamZero.ConnectionState;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.codec.binary.Base64;
import org.apache.tomcat.util.http.MimeHeaders;
import org.apache.tomcat.util.net.Channel;
import org.apache.tomcat.util.net.Endpoint.Handler.SocketState;
import org.apache.tomcat.util.net.SendfileState;
import org.apache.tomcat.util.net.SocketChannel;
import org.apache.tomcat.util.net.SocketEvent;
import org.apache.tomcat.util.res.StringManager;

/**
 * This represents an HTTP/2 connection from a client to Tomcat. It is designed
 * on the basis that there will never be more than one thread performing I/O at
 * a time. <br>
 * For reading, this implementation is blocking within frames and non-blocking
 * between frames. <br>
 * Note:
 * <ul>
 * <li>You will need to nest an &lt;UpgradeProtocol
 * className="org.apache.coyote.http2.Http2Protocol" /&gt; element inside a TLS
 * enabled Connector element in server.xml to enable HTTP/2 support.</li>
 * </ul>
 */
public class Http2UpgradeHandler implements InternalHttpUpgradeHandler {

	protected static final Log log = LogFactory.getLog(Http2UpgradeHandler.class);
	protected static final StringManager sm = StringManager.getManager(Http2UpgradeHandler.class);

	protected static final int FLAG_END_OF_STREAM = 1;
	protected static final int FLAG_END_OF_HEADERS = 4;

	protected static final byte[] PING = { 0x00, 0x00, 0x08, 0x06, 0x00, 0x00, 0x00, 0x00, 0x00 };
	protected static final byte[] PING_ACK = { 0x00, 0x00, 0x08, 0x06, 0x01, 0x00, 0x00, 0x00, 0x00 };

	protected static final byte[] SETTINGS_ACK = { 0x00, 0x00, 0x00, 0x04, 0x01, 0x00, 0x00, 0x00, 0x00 };

	protected static final byte[] GOAWAY = { 0x07, 0x00, 0x00, 0x00, 0x00, 0x00 };

	private static final String HTTP2_SETTINGS_HEADER = "HTTP2-Settings";

	private static final HeaderSink HEADER_SINK = new HeaderSink();

	protected final Http2Protocol protocol;
	private final Adapter adapter;
	private volatile SocketChannel channel;
	// private volatile SSLSupport sslSupport;
	protected final StreamZero zero;

	private volatile Http2Parser parser;

	// Simple state machine (sequence of states)
	private volatile long pausedNanoTime = Long.MAX_VALUE;

	/**
	 * Remote settings are settings defined by the client and sent to Tomcat that
	 * Tomcat must use when communicating with the client.
	 */
	private final ConnectionSettingsRemote remoteSettings;
	/**
	 * Local settings are settings defined by Tomcat and sent to the client that the
	 * client must use when communicating with Tomcat.
	 */
	protected final ConnectionSettingsLocal localSettings;

	private HpackDecoder hpackDecoder;
	private HpackEncoder hpackEncoder;

	protected final AtomicInteger activeRemoteStreamCount = new AtomicInteger(0);
	// Start at -1 so the 'add 2' logic in closeIdleStreams() works
	private volatile int maxProcessedStreamId;
	private final PingManager pingManager = createPingManager();
	// The time at which the connection will timeout unless data arrives before
	// then. -1 means no timeout.
	// private volatile long connectionTimeout = -1;

	// Stream concurrency control
	private AtomicInteger streamConcurrency = null;
	private Queue<StreamRunnable> queuedRunnable = null;

	// Track 'overhead' frames vs 'request/response' frames
	private final AtomicLong overheadCount = new AtomicLong(-10);
	private final InputHandlerImpl inputHandler;
	private final OutputHandlerImpl outputHandler;
	protected final ConcurrencyControlled controlled = new ConcurrencyControlled() {

		@Override
		public boolean checkPassOrFail(Channel channel, SocketEvent event) {
			if (streamConcurrency == null) {
				return true;
			} else {
				synchronized (streamConcurrency) {
					if (streamConcurrency.get() < protocol.getMaxConcurrentStreamExecution()) {
						streamConcurrency.incrementAndGet();
						return true;
					} else {
						StreamRunnable streamRunnable = new StreamRunnable((Stream) channel,
								(StreamProcessor) channel.getCurrentProcessor(), event);
						queuedRunnable.offer(streamRunnable);
						return false;
					}
				}
			}
		}

		@Override
		public void released(Channel channel) {
			if (streamConcurrency == null) {
				return;
			}
			StreamRunnable streamRunnable = null;
			synchronized (streamConcurrency) {
				if (streamConcurrency.decrementAndGet() < protocol.getMaxConcurrentStreamExecution()) {
					streamRunnable = queuedRunnable.poll();
				}
			}
			if (streamRunnable != null) {
				// increaseStreamConcurrency();
				protocol.getHttp11Protocol().getHandler().processSocket(streamRunnable.getStream(),
						streamRunnable.getEvent(), true);// .getExecutor().execute(streamRunnable)
			}
		}

	};
	private final ChannelWriter channelWriter = createChannelWriter();

	Http2UpgradeHandler(Http2Protocol protocol, Adapter adapter, ExchangeData exchangeData) {
		// super(STREAM_ID_ZERO);
		this.protocol = protocol;
		this.adapter = adapter;
		this.zero = new StreamZero(this);

		remoteSettings = new ConnectionSettingsRemote(zero.getConnectionId());
		localSettings = new ConnectionSettingsLocal(zero.getConnectionId());

		localSettings.set(Setting.MAX_CONCURRENT_STREAMS, protocol.getMaxConcurrentStreams());
		localSettings.set(Setting.INITIAL_WINDOW_SIZE, protocol.getInitialWindowSize());

		pingManager.initiateDisabled = protocol.getInitiatePingDisabled();

		inputHandler = new InputHandlerImpl();
		outputHandler = new OutputHandlerImpl();
		// Initial HTTP request becomes stream 1.
		if (exchangeData != null) {
			if (log.isDebugEnabled()) {
				log.debug(sm.getString("upgradeHandler.upgrade", zero.getConnectionId()));
			}
			Integer key = Integer.valueOf(1);
			StreamChannel stream = new StreamChannel(key, this, exchangeData);
			zero.getStreams().put(key, stream);
			outputHandler.maxActiveRemoteStreamId = 1;
			activeRemoteStreamCount.set(1);
			maxProcessedStreamId = 1;
		}

	}

	protected PingManager createPingManager() {
		return new PingManager();
	}

	protected Http2Parser createParser(String connectionId) {
		return new Http2Parser(connectionId, inputHandler, outputHandler);
	}

	protected ChannelWriter createChannelWriter() {
		return new ChannelWriter();
	}

	@Override
	public void init(WebConnection webConnection) {
		if (log.isDebugEnabled()) {
			log.debug(sm.getString("upgradeHandler.init", zero.getConnectionId(), zero.getConnectionState().get()));
		}

		if (!zero.getConnectionState().compareAndSet(ConnectionState.NEW, ConnectionState.CONNECTED)) {
			return;
		}

		// Init concurrency control if needed
		if (protocol.getMaxConcurrentStreamExecution() < localSettings.getMaxConcurrentStreams()) {
			streamConcurrency = new AtomicInteger(0);
			queuedRunnable = new ConcurrentLinkedQueue<>();
		}

		parser = createParser(zero.getConnectionId());

		StreamChannel stream = null;

		channel.setReadTimeout(protocol.getReadTimeout());
		channel.setWriteTimeout(protocol.getWriteTimeout());

		if (webConnection != null) {
			// HTTP/2 started via HTTP upgrade.
			// The initial HTTP/1.1 request is available as Stream 1.

			try {
				// Process the initial settings frame
				stream = zero.getStream(1, true);
				String base64Settings = stream.getExchangeData().getRequestHeader(HTTP2_SETTINGS_HEADER);
				byte[] settings = Base64.decodeBase64URLSafe(base64Settings);

				// Settings are only valid on stream 0
				FrameType.SETTINGS.check(0, settings.length);

				for (int i = 0; i < settings.length % 6; i++) {
					int id = ByteUtil.getTwoBytes(settings, i * 6);
					long value = ByteUtil.getFourBytes(settings, (i * 6) + 2);
					remoteSettings.set(Setting.valueOf(id), value);
				}
			} catch (Http2Exception e) {
				throw new ProtocolException(sm.getString("upgradeHandler.upgrade.fail", zero.getConnectionId()));
			}
		}

		// Send the initial settings frame
		channelWriter.writeSettings(localSettings);

		// Make sure the client has sent a valid connection preface before we
		// send the response to the original request over HTTP/2.
		try {
			parser.readConnectionPreface(webConnection, stream);
		} catch (Http2Exception e) {
			String msg = sm.getString("upgradeHandler.invalidPreface", zero.getConnectionId());
			if (log.isDebugEnabled()) {
				log.debug(msg, e);
			}
			throw new ProtocolException(msg);
		}
		if (log.isDebugEnabled()) {
			log.debug(sm.getString("upgradeHandler.prefaceReceived", zero.getConnectionId()));
		}

		processConnection(webConnection, stream);
	}

	protected void processConnection(WebConnection webConnection, StreamChannel stream) {
		// Send a ping to get an idea of round trip time as early as possible
		try {
			pingManager.sendPing(true);
		} catch (IOException ioe) {
			throw new ProtocolException(sm.getString("upgradeHandler.pingFailed", zero.getConnectionId()), ioe);
		}

		if (webConnection != null) {
			processStreamOnContainerThread(stream);
		}
	}

	protected void processStreamOnContainerThread(StreamChannel stream) {
		StreamProcessor streamProcessor = new StreamProcessor(this, stream, adapter);
		// streamProcessor.setSslSupport(sslSupport);
		stream.setCurrentProcessor(streamProcessor);
		protocol.getHttp11Protocol().getHandler().processSocket(stream, SocketEvent.OPEN_READ, true);
		// processStreamOnContainerThread(stream, streamProcessor,
		// SocketEvent.OPEN_READ);
	}

//	void processStreamOnContainerThread(Stream stream, StreamProcessor streamProcessor, SocketEvent event) {
//		if (streamConcurrency == null) {
//			protocol.getHttp11Protocol().getHandler().processSocket(stream, event, true);// .execute(streamRunnable);
//		} else {
//			if (getStreamConcurrency() < protocol.getMaxConcurrentStreamExecution()) {
//				increaseStreamConcurrency();
//				protocol.getHttp11Protocol().getHandler().processSocket(stream, event, true);// getExecutor().execute(streamRunnable)
//			} else {
//				StreamRunnable streamRunnable = new StreamRunnable(stream, streamProcessor, event);
//				queuedRunnable.offer(streamRunnable);
//			}
//		}
//	}

	@Override
	public void setChannel(SocketChannel channel) {
		this.channel = channel;
	}

	protected SocketChannel getChannel() {
		return channel;
	}

//	@Override
//	public SSLSupport initSslSupport(String clientCertProvider) {
//		return this.channel.getSslSupport();
//	}

	public StreamZero getZero() {
		return zero;
	}

	protected ChannelWriter getWriter() {
		return channelWriter;
	}

	@Override
	public SocketState upgradeDispatch(SocketEvent status) {
		if (log.isDebugEnabled()) {
			log.debug(sm.getString("upgradeHandler.upgradeDispatch.entry", zero.getConnectionId(), status));
		}

		// WebConnection is not used so passing null here is fine
		// Might not be necessary. init() will handle that.
		init(null);

		SocketState result = SocketState.CLOSED;

		try {
			pingManager.sendPing(false);

			switch (status) {
			case OPEN_READ:
				try {
					// There is data to read so use the read timeout while
					// reading frames ...
					channel.setReadTimeout(protocol.getReadTimeout());
					// ... and disable the connection timeout
					// setConnectionTimeout(-1);
					while (true) {
						try {
							if (!parser.readFrame(false)) {
								break;
							}
						} catch (StreamException se) {
							// Stream errors are not fatal to the connection so
							// continue reading frames
							Stream stream = zero.getStream(se.getStreamId(), false);
							if (stream == null) {
								channelWriter.writeStreamReset(se);
							} else {
								stream.close(se);
							}
						}
						if (overheadCount.get() > 0) {
							System.err.println(channel.getRemotePort() + " tooMuchOverhead");
							throw new ConnectionException(
									sm.getString("upgradeHandler.tooMuchOverhead", zero.getConnectionId()),
									Http2Error.ENHANCE_YOUR_CALM);
						}
					}

					// Need to know the correct timeout before starting the read
					// but that may not be known at this time if one or more
					// requests are currently being processed so don't set a
					// timeout for the socket...
					channel.setReadTimeout(-1);

					// ...set a timeout on the connection
					setConnectionTimeoutForStreamCount(activeRemoteStreamCount.get());

				} catch (Http2Exception ce) {
					ce.printStackTrace();
					// Really ConnectionException
					if (log.isDebugEnabled()) {
						log.debug(sm.getString("upgradeHandler.connectionError"), ce);
					}
					closeConnection(ce);
					break;
				}

				if (zero.getConnectionState().get() != ConnectionState.CLOSED) {
					result = SocketState.UPGRADED;
				}
				break;

			case OPEN_WRITE:
				channelWriter.processWrites();

				result = SocketState.UPGRADED;
				break;

			case TIMEOUT:
				closeConnection(null);
				break;

			case DISCONNECT:
			case ERROR:
			case STOP:
			case CONNECT_FAIL:
				zero.close();
				break;
			}
		} catch (IOException ioe) {
			ioe.printStackTrace();
			if (log.isDebugEnabled()) {
				log.debug(sm.getString("upgradeHandler.ioerror", zero.getConnectionId()), ioe);
			}
			zero.close();
		}

		if (log.isDebugEnabled()) {
			log.debug(sm.getString("upgradeHandler.upgradeDispatch.exit", zero.getConnectionId(), result));
		}
		return result;
	}

	/*
	 * Sets the connection timeout based on the current number of active streams.
	 */
	protected void setConnectionTimeoutForStreamCount(int streamCount) {
		if (streamCount == 0) {
			// No streams currently active. Use the keep-alive
			// timeout for the connection.
			long keepAliveTimeout = protocol.getKeepAliveTimeout();
			if (keepAliveTimeout == -1) {
				channel.setReadTimeout(-1);
			} else {
				channel.setReadTimeout(keepAliveTimeout);// System.currentTimeMillis() +
			}
		} else {
			// Streams currently active. Individual streams have
			// timeouts so keep the connection open.
			channel.setReadTimeout(-1);
		}
	}

	// private void setConnectionTimeout(long connectionTimeout) {
	// this.connectionTimeout = connectionTimeout;
	// }

	@Override
	public void timeoutAsync(long now) {
		// long connectionTimeout = this.connectionTimeout;
		// if (now == -1 || connectionTimeout > -1 && now > connectionTimeout) {
		// Have to dispatch as this will be executed from a non-container
		// thread.
		// protocol.getHttp11Protocol().getHandler().processSocket(channel,
		// SocketEvent.TIMEOUT, true);
		// }
	}

	ConnectionSettingsRemote getRemoteSettings() {
		return remoteSettings;
	}

	ConnectionSettingsLocal getLocalSettings() {
		return localSettings;
	}

	Http2Protocol getProtocol() {
		return protocol;
	}

//	@Override
//	public Object getLock() {
//		return this;
//	}

	@Override
	public void pause() {
		if (log.isDebugEnabled()) {
			log.debug(sm.getString("upgradeHandler.pause.entry", zero.getConnectionId()));
		}

		if (zero.getConnectionState().compareAndSet(ConnectionState.CONNECTED, ConnectionState.PAUSING)) {
			pausedNanoTime = System.nanoTime();

			try {
				channelWriter.writeGoAwayFrame((1 << 31) - 1, Http2Error.NO_ERROR.getCode(), null);
			} catch (IOException ioe) {
				// This is fatal for the connection. Ignore it here. There will be
				// further attempts at I/O in upgradeDispatch() and it can better
				// handle the IO errors.
			}
		}
	}

	@Override
	public void destroy() {
		// NO-OP
	}

	void checkPauseState() throws IOException {
		if (zero.getConnectionState().get() == ConnectionState.PAUSING) {
			if (pausedNanoTime + pingManager.getRoundTripTimeNano() < System.nanoTime()) {
				zero.getConnectionState().compareAndSet(ConnectionState.PAUSING, ConnectionState.PAUSED);
				channelWriter.writeGoAwayFrame(maxProcessedStreamId, Http2Error.NO_ERROR.getCode(), null);
			}
		}
	}

	void closeConnection(Http2Exception ce) {
		long code;
		byte[] msg;
		if (ce == null) {
			code = Http2Error.NO_ERROR.getCode();
			msg = null;
		} else {
			code = ce.getError().getCode();
			msg = ce.getMessage().getBytes(StandardCharsets.UTF_8);
		}
		try {
			channelWriter.writeGoAwayFrame(maxProcessedStreamId, code, msg);
		} catch (IOException ioe) {
			// Ignore. GOAWAY is sent on a best efforts basis and the original
			// error has already been logged.
		}
		zero.close();
	}

	protected HeaderFrameBuffers getHeaderFrameBuffers(int initialPayloadSize) {
		return new DefaultHeaderFrameBuffers(initialPayloadSize);
	}

	protected HpackDecoder getHpackDecoder() {
		if (hpackDecoder == null) {
			hpackDecoder = new HpackDecoder(localSettings.getHeaderTableSize());
		}
		return hpackDecoder;
	}

	protected HpackEncoder getHpackEncoder() {
		if (hpackEncoder == null) {
			hpackEncoder = new HpackEncoder();
		}
		// Ensure latest agreed table size is used
		hpackEncoder.setMaxTableSize(remoteSettings.getHeaderTableSize());
		return hpackEncoder;
	}

	/*
	 * Handles an I/O error on the socket underlying the HTTP/2 connection when it
	 * is triggered by application code (usually reading the request or writing the
	 * response). Such I/O errors are fatal so the connection is closed. The
	 * exception is re-thrown to make the client code aware of the problem.
	 *
	 * Note: We can not rely on this exception reaching the socket processor since
	 * the application code may swallow it.
	 */
	protected void handleAppInitiatedIOException(IOException ioe) throws IOException {
		zero.close();
		throw ioe;
	}

	/**
	 * Process send file (if supported) for the given stream. The appropriate
	 * request attributes should be set before calling this method.
	 *
	 * @param sendfileData The stream and associated data to process
	 *
	 * @return The result of the send file processing
	 */
	protected SendfileState processSendfile(SendfileData sendfileData) {
		return SendfileState.DONE;
	}

	private void reduceOverheadCount() {
		overheadCount.decrementAndGet();
	}

	private void increaseOverheadCount() {
		overheadCount.addAndGet(getProtocol().getOverheadCountFactor());
	}

	protected class ChannelWriter {

		/**
		 * Write the initial settings frame and any necessary supporting frames. If the
		 * initial settings increase the initial window size, it will also be necessary
		 * to send a WINDOW_UPDATE frame to increase the size of the flow control window
		 * for the connection (stream 0).
		 */
		protected void writeSettings(ConnectionSettingsLocal localSettings) {
			// Send the initial settings frame
			try {
				channel.getWriteLock().lock();
				byte[] settings = localSettings.getSettingsFrameForPending();
				channel.write(true, settings, 0, settings.length);
				byte[] windowUpdateFrame = createWindowUpdateForSettings();
				if (windowUpdateFrame.length > 0) {
					channel.write(true, windowUpdateFrame, 0, windowUpdateFrame.length);
				}
				channel.flush(true);
			} catch (IOException ioe) {
				String msg = sm.getString("upgradeHandler.sendPrefaceFail", zero.getConnectionId());
				if (log.isDebugEnabled()) {
					log.debug(msg);
				}
				throw new ProtocolException(msg, ioe);
			} finally {
				channel.getWriteLock().unlock();
			}
		}

		/**
		 * @return The WINDOW_UPDATE frame if one is required or an empty array if no
		 *         WINDOW_UPDATE is required.
		 */
		protected byte[] createWindowUpdateForSettings() {
			// Build a WINDOW_UPDATE frame if one is required. If not, create an
			// empty byte array.
			byte[] windowUpdateFrame;
			int increment = protocol.getInitialWindowSize() - ConnectionSettingsBase.DEFAULT_INITIAL_WINDOW_SIZE;
			if (increment > 0) {
				// Build window update frame for stream 0
				windowUpdateFrame = new byte[13];
				ByteUtil.setThreeBytes(windowUpdateFrame, 0, 4);
				windowUpdateFrame[3] = FrameType.WINDOW_UPDATE.getIdByte();
				ByteUtil.set31Bits(windowUpdateFrame, 9, increment);
			} else {
				windowUpdateFrame = new byte[0];
			}

			return windowUpdateFrame;
		}

		void writeHeaders(Stream stream, int pushedStreamId, MimeHeaders mimeHeaders, boolean endOfStream,
				int payloadSize) throws IOException {
			// This ensures the Stream processing thread has control of the socket.
			try {
				channel.getWriteLock().lock();
				System.out.println("==========================print h2 header start==========================");
				int size = mimeHeaders.size();
				for (int i = 0; i < size; i++) {
					System.out.println(
							" " + mimeHeaders.getName(i).toString() + " : " + mimeHeaders.getValue(i).toString());
				}
				doWriteHeaders(stream, pushedStreamId, mimeHeaders, endOfStream, payloadSize);
				System.out.println("==========================print h2 header end============================");
			} finally {
				channel.getWriteLock().unlock();
			}
			stream.sentHeaders();
			if (endOfStream) {
				stream.sentEndOfStream();
			}
		}

		/*
		 * Separate method to allow Http2AsyncUpgradeHandler to call this code without
		 * synchronizing on channel since it doesn't need to.
		 */
		protected HeaderFrameBuffers doWriteHeaders(Stream stream, int pushedStreamId, MimeHeaders mimeHeaders,
				boolean endOfStream, int payloadSize) throws IOException {

			if (log.isDebugEnabled()) {
				if (pushedStreamId == 0) {
					log.debug(sm.getString("upgradeHandler.writeHeaders", zero.getConnectionId(),
							stream.getIdentifier()));
				} else {
					log.debug(sm.getString("upgradeHandler.writePushHeaders", zero.getConnectionId(),
							stream.getIdentifier(), Integer.valueOf(pushedStreamId), Boolean.valueOf(endOfStream)));
				}
			}

			if (!stream.canWrite()) {
				return null;
			}

			HeaderFrameBuffers headerFrameBuffers = getHeaderFrameBuffers(payloadSize);

			byte[] pushedStreamIdBytes = null;
			if (pushedStreamId > 0) {
				pushedStreamIdBytes = new byte[4];
				ByteUtil.set31Bits(pushedStreamIdBytes, 0, pushedStreamId);
			}

			boolean first = true;
			State state = null;

			while (state != State.COMPLETE) {
				headerFrameBuffers.startFrame();
				if (first && pushedStreamIdBytes != null) {
					headerFrameBuffers.getPayload().put(pushedStreamIdBytes);
				}
				state = getHpackEncoder().encode(mimeHeaders, headerFrameBuffers.getPayload());
				headerFrameBuffers.getPayload().flip();
				if (state == State.COMPLETE || headerFrameBuffers.getPayload().limit() > 0) {
					ByteUtil.setThreeBytes(headerFrameBuffers.getHeader(), 0, headerFrameBuffers.getPayload().limit());
					if (first) {
						first = false;
						if (pushedStreamIdBytes == null) {
							headerFrameBuffers.getHeader()[3] = FrameType.HEADERS.getIdByte();
						} else {
							headerFrameBuffers.getHeader()[3] = FrameType.PUSH_PROMISE.getIdByte();
						}
						if (endOfStream) {
							headerFrameBuffers.getHeader()[4] = FLAG_END_OF_STREAM;
						}
					} else {
						headerFrameBuffers.getHeader()[3] = FrameType.CONTINUATION.getIdByte();
					}
					if (state == State.COMPLETE) {
						headerFrameBuffers.getHeader()[4] += FLAG_END_OF_HEADERS;
					}
					if (log.isDebugEnabled()) {
						log.debug(headerFrameBuffers.getPayload().limit() + " bytes");
					}
					ByteUtil.set31Bits(headerFrameBuffers.getHeader(), 5, stream.getIdAsInt());
					headerFrameBuffers.endFrame();
				} else if (state == State.UNDERFLOW) {
					headerFrameBuffers.expandPayload();
				}
			}
			headerFrameBuffers.endHeaders();
			return headerFrameBuffers;
		}

		void writePushHeader(ExchangeData exchangeData, Stream associatedStream) throws IOException {
			if (localSettings.getMaxConcurrentStreams() < activeRemoteStreamCount.incrementAndGet()) {
				// If there are too many open streams, simply ignore the push
				// request.
				setConnectionTimeoutForStreamCount(activeRemoteStreamCount.decrementAndGet());
				return;
			}

			StreamChannel pushStream;

			// Synchronized since PUSH_PROMISE frames have to be sent in order. Once
			// the stream has been created we need to ensure that the PUSH_PROMISE
			// is sent before the next stream is created for a PUSH_PROMISE.
			try {
				channel.getWriteLock().lock();
				pushStream = zero.createLocalStream(exchangeData);
				writeHeaders(associatedStream, pushStream.getIdAsInt(), exchangeData.getRequestHeaders(), false,
						Constants.DEFAULT_HEADERS_FRAME_SIZE);
			} finally {
				channel.getWriteLock().unlock();
			}

			pushStream.sentPushPromise();

			processStreamOnContainerThread(pushStream);
		}

		void writeBody(Stream stream, ByteBuffer data, int len, boolean finished) throws IOException {
			if (log.isDebugEnabled()) {
				log.debug(sm.getString("upgradeHandler.writeBody", zero.getConnectionId(), stream.getIdentifier(),
						Integer.toString(len)));
			}

			reduceOverheadCount();

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
				try {
					channel.getWriteLock().lock();
					try {
						channel.write(true, header, 0, header.length);
						int orgLimit = data.limit();
						data.limit(data.position() + len);
						channel.write(true, data);
						data.limit(orgLimit);
						channel.flush(true);
					} catch (IOException ioe) {
						handleAppInitiatedIOException(ioe);
					}
				} finally {
					channel.getWriteLock().unlock();
				}
			}
		}

		/*
		 * Needs to know if this was application initiated since that affects the error
		 * handling.
		 */
		void writeWindowUpdate(Stream stream, int increment, boolean applicationInitiated) throws IOException {
			if (!stream.canWrite()) {
				return;
			}
			try {
				channel.getWriteLock().lock();
				// Build window update frame for stream 0
				byte[] frame = new byte[13];
				ByteUtil.setThreeBytes(frame, 0, 4);
				frame[3] = FrameType.WINDOW_UPDATE.getIdByte();
				ByteUtil.set31Bits(frame, 9, increment);
				channel.write(true, frame, 0, frame.length);
				// Change stream Id and re-use
				ByteUtil.set31Bits(frame, 5, stream.getIdAsInt());
				try {
					channel.write(true, frame, 0, frame.length);
					channel.flush(true);
				} catch (IOException ioe) {
					if (applicationInitiated) {
						handleAppInitiatedIOException(ioe);
					} else {
						throw ioe;
					}
				}
			} finally {
				channel.getWriteLock().unlock();
			}
		}

		protected void processWrites() throws IOException {
			try {
				channel.getWriteLock().lock();
				if (channel.flush(false)) {
					channel.registerWriteInterest();
				}
			} finally {
				channel.getWriteLock().unlock();
			}
		}

		void writeSettingAck() throws IOException {
			try {
				channel.getWriteLock().lock();
				channel.write(true, SETTINGS_ACK, 0, SETTINGS_ACK.length);
				channel.flush(true);
			} finally {
				channel.getWriteLock().unlock();
			}
		}

		void writePing(byte[] payload) throws IOException {
			try {
				channel.getWriteLock().lock();
				channel.write(true, PING, 0, PING.length);
				channel.write(true, payload, 0, payload.length);
				channel.flush(true);
			} finally {
				channel.getWriteLock().unlock();
			}
		}

		void writePingAck(byte[] payload) throws IOException {
			try {
				channel.getWriteLock().lock();
				channel.write(true, PING_ACK, 0, PING_ACK.length);
				channel.write(true, payload, 0, payload.length);
				channel.flush(true);
			} finally {
				channel.getWriteLock().unlock();
			}
		}

		void writeStreamReset(StreamException se) throws IOException {

			if (log.isDebugEnabled()) {
				log.debug(sm.getString("upgradeHandler.rst.debug", zero.getConnectionId(),
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

			try {
				channel.getWriteLock().lock();
				channel.write(true, rstFrame, 0, rstFrame.length);
				channel.flush(true);
			} finally {
				channel.getWriteLock().unlock();
			}
		}

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

			try {
				channel.getWriteLock().lock();
				channel.write(true, payloadLength, 0, payloadLength.length);
				channel.write(true, GOAWAY, 0, GOAWAY.length);
				channel.write(true, fixedPayload, 0, 8);
				if (debugMsg != null) {
					channel.write(true, debugMsg, 0, debugMsg.length);
				}
				channel.flush(true);
			} finally {
				channel.getWriteLock().unlock();
			}
		}

	}

	// ----------------------------------------------- Http2Parser.Input methods

	protected class InputHandlerImpl implements Input {

		protected InputHandlerImpl() {

		}

		@Override
		public boolean fill(boolean block, byte[] data, int offset, int length) throws IOException {
			int len = length;
			int pos = offset;
			boolean nextReadBlock = block;
			int thisRead = 0;

			while (len > 0) {
				thisRead = channel.read(nextReadBlock, data, pos, len);
				if (thisRead == 0) {
					if (nextReadBlock) {
						// Should never happen
						throw new IllegalStateException();
					} else {
						return false;
					}
				} else if (thisRead == -1) {
					if (zero.getConnectionState().get().isNewStreamAllowed()) {
						throw new EOFException();
					} else {
						return false;
					}
				} else {
					pos += thisRead;
					len -= thisRead;
					nextReadBlock = true;
				}
			}

			return true;
		}

		@Override
		public int getMaxFrameSize() {
			return localSettings.getMaxFrameSize();
		}

	}

	protected class OutputHandlerImpl implements Output {

		private volatile int lastNonFinalDataPayload;
		private volatile int maxActiveRemoteStreamId = -1;
		private volatile int lastWindowUpdate;

		protected OutputHandlerImpl() {
			lastNonFinalDataPayload = protocol.getOverheadDataThreshold() * 2;
			lastWindowUpdate = protocol.getOverheadWindowUpdateThreshold() * 2;
		}
		// ---------------------------------------------- Http2Parser.Output methods

		@Override
		public HpackDecoder getHpackDecoder() {
			return Http2UpgradeHandler.this.getHpackDecoder();
		}

		@Override
		public ByteBufferHandler startRequestBodyFrame(int streamId, int payloadSize, boolean endOfStream)
				throws Http2Exception {
			// DATA frames reduce the overhead count ...
			reduceOverheadCount();

			// .. but lots of small payloads are inefficient so that will increase
			// the overhead count unless it is the final DATA frame where small
			// payloads are expected.

			// See also https://bz.apache.org/bugzilla/show_bug.cgi?id=63690
			// The buffering behaviour of some clients means that small data frames
			// are much more frequent (roughly 1 in 20) than expected. Use an
			// average over two frames to avoid false positives.
			if (!endOfStream) {
				int overheadThreshold = protocol.getOverheadDataThreshold();
				int average = (lastNonFinalDataPayload >> 1) + (payloadSize >> 1);
				lastNonFinalDataPayload = payloadSize;
				// Avoid division by zero
				if (average == 0) {
					average = 1;
				}
				if (average < overheadThreshold) {
					overheadCount.addAndGet(overheadThreshold / average);
				}
			}

			StreamChannel stream = zero.getStream(streamId, true);
			stream.checkState(FrameType.DATA);
			stream.receivedData(payloadSize);
			return stream;
		}

		@Override
		public void endRequestBodyFrame(int streamId) throws Http2Exception {
			StreamChannel stream = zero.getStream(streamId, true);
			stream.onDataAvailable();
		}

		@Override
		public void receivedEndOfStream(int streamId) throws ConnectionException {
			Stream stream = zero.getStream(streamId, zero.getConnectionState().get().isNewStreamAllowed());
			if (stream != null) {
				stream.receivedEndOfStream();
				if (!stream.isActive()) {
					setConnectionTimeoutForStreamCount(activeRemoteStreamCount.decrementAndGet());
				}
			}
		}

		@Override
		public void swallowedPadding(int streamId, int paddingLength) throws ConnectionException, IOException {
			Stream stream = zero.getStream(streamId, true);
			// +1 is for the payload byte used to define the padding length
			channelWriter.writeWindowUpdate(stream, paddingLength + 1, false);
		}

		@Override
		public HeaderEmitter headersStart(int streamId, boolean headersEndStream) throws Http2Exception, IOException {

			// Check the pause state before processing headers since the pause state
			// determines if a new stream is created or if this stream is ignored.
			checkPauseState();

			if (zero.getConnectionState().get().isNewStreamAllowed()) {
				StreamChannel stream = zero.getStream(streamId, false);
				if (stream == null) {
					stream = zero.createRemoteStream(streamId);
				}
				if (streamId < maxActiveRemoteStreamId) {
					throw new ConnectionException(sm.getString("upgradeHandler.stream.old", Integer.valueOf(streamId),
							Integer.valueOf(maxActiveRemoteStreamId)), Http2Error.PROTOCOL_ERROR);
				}
				stream.checkState(FrameType.HEADERS);
				stream.receivedStartOfHeaders(headersEndStream);
				closeIdleStreams(streamId);
				if (localSettings.getMaxConcurrentStreams() < activeRemoteStreamCount.incrementAndGet()) {
					setConnectionTimeoutForStreamCount(activeRemoteStreamCount.decrementAndGet());
					// Ignoring maxConcurrentStreams increases the overhead count
					increaseOverheadCount();
					throw new StreamException(
							sm.getString("upgradeHandler.tooManyRemoteStreams",
									Long.toString(localSettings.getMaxConcurrentStreams())),
							Http2Error.REFUSED_STREAM, streamId);
				}
				// Valid new stream reduces the overhead count
				reduceOverheadCount();
				return stream.getHeaderEmitter();
			} else {
				if (log.isDebugEnabled()) {
					log.debug(sm.getString("upgradeHandler.noNewStreams", zero.getConnectionId(),
							Integer.toString(streamId)));
				}
				reduceOverheadCount();
				// Stateless so a static can be used to save on GC
				return HEADER_SINK;
			}
		}

		private void closeIdleStreams(int newMaxActiveRemoteStreamId) {
			for (Entry<Integer, StreamChannel> entry : zero.getStreams().entrySet()) {
				if (entry.getKey().intValue() > maxActiveRemoteStreamId
						&& entry.getKey().intValue() < newMaxActiveRemoteStreamId) {
					System.err.println("has bug, never happen");
					entry.getValue().closeIfIdle();
				}
			}
			maxActiveRemoteStreamId = newMaxActiveRemoteStreamId;
		}

		@Override
		public void reprioritise(int streamId, int parentStreamId, boolean exclusive, int weight)
				throws Http2Exception {
			if (streamId == parentStreamId) {
				throw new ConnectionException(sm.getString("upgradeHandler.dependency.invalid", zero.getConnectionId(),
						Integer.valueOf(streamId)), Http2Error.PROTOCOL_ERROR);
			}

			increaseOverheadCount();

			Stream stream = zero.getStream(streamId, false);
			if (stream == null) {
				stream = zero.createRemoteStream(streamId);
			}
			stream.checkState(FrameType.PRIORITY);
			AbstractStream parentStream = zero.getStream(parentStreamId, false);
			if (parentStream == null) {
				parentStream = zero;
			}
			stream.rePrioritise(parentStream, exclusive, weight);
		}

		@Override
		public void headersContinue(int payloadSize, boolean endOfHeaders) {
			// Generally, continuation frames don't impact the overhead count but if
			// they are small and the frame isn't the final header frame then that
			// is indicative of an abusive client
			if (!endOfHeaders) {
				int overheadThreshold = getProtocol().getOverheadContinuationThreshold();
				if (payloadSize < overheadThreshold) {
					if (payloadSize == 0) {
						// Avoid division by zero
						overheadCount.addAndGet(overheadThreshold);
					} else {
						overheadCount.addAndGet(overheadThreshold / payloadSize);
					}
				}
			}
		}

		@Override
		public void headersEnd(int streamId) throws ConnectionException {
			StreamChannel stream = zero.getStream(streamId, zero.getConnectionState().get().isNewStreamAllowed());
			if (stream != null) {
				setMaxProcessedStream(streamId);
				if (stream.isActive()) {
					if (stream.receivedEndOfHeaders()) {
						processStreamOnContainerThread(stream);
					}
				}
			}
		}

		private void setMaxProcessedStream(int streamId) {
			if (maxProcessedStreamId < streamId) {
				maxProcessedStreamId = streamId;
			}
		}

		@Override
		public void receiveReset(int streamId, long errorCode) throws Http2Exception {
			Stream stream = zero.getStream(streamId, true);
			stream.checkState(FrameType.RST);
			stream.receiveReset(errorCode);
		}

		@Override
		public void receiveSetting(Setting setting, long value) throws ConnectionException {

			increaseOverheadCount();

			// Possible with empty settings frame
			if (setting == null) {
				return;
			}

			// Special handling required
			if (setting == Setting.INITIAL_WINDOW_SIZE) {
				long oldValue = remoteSettings.getInitialWindowSize();
				// Do this first in case new value is invalid
				remoteSettings.set(setting, value);
				int diff = (int) (value - oldValue);
				for (Stream stream : zero.getStreams().values()) {
					try {
						stream.incrementWindowSize(diff);
					} catch (Http2Exception h2e) {
						stream.close(new StreamException(sm.getString("upgradeHandler.windowSizeTooBig",
								zero.getConnectionId(), stream.getIdentifier()), h2e.getError(), stream.getIdAsInt()));
					}
				}
			} else {
				remoteSettings.set(setting, value);
			}
		}

		@Override
		public void receiveSettingsEnd(boolean ack) throws IOException {
			if (ack) {
				if (!localSettings.ack()) {
					// Ack was unexpected
					log.warn(
							sm.getString("upgradeHandler.unexpectedAck", zero.getConnectionId(), zero.getIdentifier()));
				}
			} else {
				channelWriter.writeSettingAck();
			}
		}

		@Override
		public void receivePing(byte[] payload, boolean ack) throws IOException {
			if (!ack) {
				increaseOverheadCount();
			}
			pingManager.receivePing(payload, ack);
		}

		@Override
		public void receiveGoaway(int lastStreamId, long errorCode, String debugData) {
			if (log.isDebugEnabled()) {
				log.debug(sm.getString("upgradeHandler.goaway.debug", zero.getConnectionId(),
						Integer.toString(lastStreamId), Long.toHexString(errorCode), debugData));
			}
			zero.close();
		}

		@Override
		public void receiveIncWindows(int streamId, int increment) throws Http2Exception {
			// See also https://bz.apache.org/bugzilla/show_bug.cgi?id=63690
			// The buffering behaviour of some clients means that small data frames
			// are much more frequent (roughly 1 in 20) than expected. Some clients
			// issue a Window update for every DATA frame so a similar pattern may
			// be observed. Use an average over two frames to avoid false positives.

			int average = (lastWindowUpdate >> 1) + (increment >> 1);
			int overheadThreshold = protocol.getOverheadWindowUpdateThreshold();
			lastWindowUpdate = increment;
			// Avoid division by zero
			if (average == 0) {
				average = 1;
			}

			if (streamId == 0) {
				// Check for small increments which are inefficient
				if (average < overheadThreshold) {
					// The smaller the increment, the larger the overhead
					overheadCount.addAndGet(overheadThreshold / average);
				}

				zero.incrementWindowSize(increment);
			} else {
				Stream stream = zero.getStream(streamId, true);

				// Check for small increments which are inefficient
				if (average < overheadThreshold) {
					// For Streams, client might only release the minimum so check
					// against current demand
//					BacklogTracker tracker = backlogManager.backLogStreams.get(stream);
					if (increment < stream.getConnectionAllocationRequested()) {
						// The smaller the increment, the larger the overhead
						overheadCount.addAndGet(overheadThreshold / average);
					}
				}

				stream.checkState(FrameType.WINDOW_UPDATE);
				stream.incrementWindowSize(increment);
			}
		}

		@Override
		public void swallowed(int streamId, FrameType frameType, int flags, int size) throws IOException {
			// NO-OP.
		}

	}

	protected class PingManager {

		protected boolean initiateDisabled = false;

		// 10 seconds
		protected final long pingIntervalNano = 10000000000L;

		protected int sequence = 0;
		protected long lastPingNanoTime = Long.MIN_VALUE;

		protected Queue<PingRecord> inflightPings = new ConcurrentLinkedQueue<>();
		protected Queue<Long> roundTripTimes = new ConcurrentLinkedQueue<>();

		/**
		 * Check to see if a ping was sent recently and, if not, send one.
		 *
		 * @param force Send a ping, even if one was sent recently
		 *
		 * @throws IOException If an I/O issue prevents the ping from being sent
		 */
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
				channelWriter.writePing(payload);
			}
		}

		public void receivePing(byte[] payload, boolean ack) throws IOException {
			if (ack) {
				// Extract the sequence from the payload
				int receivedSequence = ByteUtil.get31Bits(payload, 4);
				PingRecord pingRecord = inflightPings.poll();
				while (pingRecord != null && pingRecord.getSequence() < receivedSequence) {
					pingRecord = inflightPings.poll();
				}
				if (pingRecord == null) {
					// Unexpected ACK. Log it.
				} else {
					long roundTripTime = System.nanoTime() - pingRecord.getSentNanoTime();
					roundTripTimes.add(Long.valueOf(roundTripTime));
					while (roundTripTimes.size() > 3) {
						// Ignore the returned value as we just want to reduce
						// the queue to 3 entries to use for the rolling average.
						roundTripTimes.poll();
					}
					if (log.isDebugEnabled()) {
						log.debug(sm.getString("pingManager.roundTripTime", zero.getConnectionId(),
								Long.valueOf(roundTripTime)));
					}
				}

			} else {
				// Client originated ping. Echo it back.
				channelWriter.writePingAck(payload);
			}
		}

		public long getRoundTripTimeNano() {
			return (long) roundTripTimes.stream().mapToLong(x -> x.longValue()).average().orElse(0);
		}
	}

	protected static class PingRecord {

		private final int sequence;
		private final long sentNanoTime;

		public PingRecord(int sequence, long sentNanoTime) {
			this.sequence = sequence;
			this.sentNanoTime = sentNanoTime;
		}

		public int getSequence() {
			return sequence;
		}

		public long getSentNanoTime() {
			return sentNanoTime;
		}
	}

	protected static interface HeaderFrameBuffers {
		public void startFrame();

		public void endFrame() throws IOException;

		public void endHeaders() throws IOException;

		public byte[] getHeader();

		public ByteBuffer getPayload();

		public void expandPayload();
	}

	private class DefaultHeaderFrameBuffers implements HeaderFrameBuffers {

		private final byte[] header;
		private ByteBuffer payload;

		public DefaultHeaderFrameBuffers(int initialPayloadSize) {
			header = new byte[9];
			payload = ByteBuffer.allocate(initialPayloadSize);
		}

		@Override
		public void startFrame() {
			// NO-OP
		}

		@Override
		public void endFrame() throws IOException {
			try {
				channel.write(true, header, 0, header.length);
				channel.write(true, payload);
				channel.flush(true);
			} catch (IOException ioe) {
				handleAppInitiatedIOException(ioe);
			}
			payload.clear();
		}

		@Override
		public void endHeaders() {
			// NO-OP
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
			payload = ByteBuffer.allocate(payload.capacity() * 2);
		}
	}

}
