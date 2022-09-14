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
import java.nio.charset.StandardCharsets;

import javax.servlet.http.WebConnection;

import org.apache.coyote.ProtocolException;
import org.apache.coyote.http2.HpackDecoder.HeaderEmitter;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.net.SocketChannel;
import org.apache.tomcat.util.net.SocketWrapperBase.ByteBufferWrapper;
import org.apache.tomcat.util.res.StringManager;

class Http2Parser {

	protected static final Log log = LogFactory.getLog(Http2Parser.class);
	protected static final StringManager sm = StringManager.getManager(Http2Parser.class);

	protected static final int PARSING_HEADER = 1;

	protected static final int PARSING_SWALLOW = 2;

	protected static final int PARSING_BODY = 3;

	static final byte[] CLIENT_PREFACE_START = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);

	protected final String connectionId;
	protected final Input input;
	private final Output output;
//	private final byte[] frameHeaderBuffer = new byte[9];
//	private ByteBufferWrapper frameHeaderBuffer1 = ByteBufferWrapper.wrapper(ByteBuffer.allocate(9), false);
	private ByteBufferWrapper frameBuffer = null;
	private int parsingState = PARSING_HEADER;

	private volatile HpackDecoder hpackDecoder;
	private volatile ByteBufferWrapper headerReadBuffer = ByteBufferWrapper
			.wrapper(ByteBuffer.allocate(Constants.DEFAULT_HEADER_READ_BUFFER_SIZE), false);
	private volatile int headersCurrentStream = -1;
	private volatile boolean headersEndStream = false;

	private int payloadSize = -1;
	private int frameTypeId = -1;
	private FrameType frameType = null;
	private int flags = -1;
	private int streamId = -1;
	private StreamException e = null;

	Http2Parser(String connectionId, Input input, Output output) {
		this.connectionId = connectionId;
		this.input = input;
		this.output = output;
		this.frameBuffer = ByteBufferWrapper.wrapper(ByteBuffer.allocate(9 + input.getMaxFrameSize()), false);
	}

	void onChannelReady(SocketChannel channel) {

	}

	/**
	 * Read and process a single frame. Once the start of a frame is read, the
	 * remainder will be read using blocking IO.
	 *
	 * @param block Should this method block until a frame is available if no frame
	 *              is available immediately?
	 *
	 * @return <code>true</code> if a frame was read otherwise <code>false</code>
	 *
	 * @throws IOException If an IO error occurs while trying to read a frame
	 */
	boolean readFrame(boolean block) throws Http2Exception, IOException {
		return readFrame(block, null);
	}

	protected boolean readFrame(boolean block, FrameType expected) throws IOException, Http2Exception {

		while (true) {
			switch (parsingState) {
			case PARSING_HEADER:
				frameBuffer.switchToWriteMode();// compact
				frameBuffer.switchToReadMode();
				while (frameBuffer.getRemaining() < 9) {
					frameBuffer.switchToWriteMode();
					if (!input.fill(block, frameBuffer)) {
						return false;
					}
					frameBuffer.switchToReadMode();
				}

//				frameHeaderBuffer1.flip();
//				frameBuffer.switchToReadMode();
//			if (!input.fill(block, frameHeaderBuffer)) {
//				return false;
//			}
				int position = frameBuffer.getPosition();
				payloadSize = ByteUtil.getThreeBytes(frameBuffer, position + 0);
				frameTypeId = ByteUtil.getOneByte(frameBuffer, position + 3);
				frameType = FrameType.valueOf(frameTypeId);
				flags = ByteUtil.getOneByte(frameBuffer, position + 4);
				streamId = ByteUtil.get31Bits(frameBuffer, position + 5);

//				frameHeaderBuffer1.compact();
				for (int i = 0; i < 9; i++) {
					frameBuffer.getByte();
				}
//				frameHeaderBuffer1.switchToWriteMode();

				try {
					validateFrame(expected, frameType, streamId, flags, payloadSize);
					parsingState = PARSING_BODY;

					if (frameBuffer.getCapacity() < 9 + input.getMaxFrameSize()) {
						System.out.println("resize frameBuffer");
						ByteBufferWrapper newFrameBuffer = ByteBufferWrapper
								.wrapper(ByteBuffer.allocate(9 + input.getMaxFrameSize()), false);
						frameBuffer.switchToReadMode();
						newFrameBuffer.switchToWriteMode();
						frameBuffer.transferTo(newFrameBuffer);
						frameBuffer = newFrameBuffer;
						frameBuffer.switchToReadMode();
					}

//					frameBodyBuffer.switchToWriteMode();
//					frameBodyBuffer.setLimit(payloadSize);
				} catch (StreamException se) {
					e = se;
					parsingState = PARSING_SWALLOW;
				}
				continue;
			case PARSING_SWALLOW:
				frameBuffer.switchToReadMode();
				while (frameBuffer.getRemaining() < payloadSize) {
					frameBuffer.switchToWriteMode();
					if (!input.fill(block, frameBuffer)) {
						return false;
					}
					frameBuffer.switchToReadMode();
				}
				swallowPayload(streamId, frameTypeId, payloadSize, false, frameBuffer);
				throw e;
			case PARSING_BODY:
				frameBuffer.switchToReadMode();
				while (frameBuffer.getRemaining() < payloadSize) {
					frameBuffer.switchToWriteMode();
					if (!input.fill(block, frameBuffer)) {
						return false;
					}
					frameBuffer.switchToReadMode();
				}
//				frameBodyBuffer.switchToReadMode();
				switch (frameType) {
				case DATA:
					readDataFrame(streamId, flags, payloadSize, frameBuffer);
					break;
				case HEADERS:
					readHeadersFrame(streamId, flags, payloadSize, frameBuffer);
					break;
				case PRIORITY:
					readPriorityFrame(streamId, frameBuffer);
					break;
				case RST:
					readRstFrame(streamId, frameBuffer);
					break;
				case SETTINGS:
					readSettingsFrame(flags, payloadSize, frameBuffer);
					break;
				case PUSH_PROMISE:
					readPushPromiseFrame(streamId, frameBuffer);
					break;
				case PING:
					readPingFrame(flags, frameBuffer);
					break;
				case GOAWAY:
					readGoawayFrame(payloadSize, frameBuffer);
					break;
				case WINDOW_UPDATE:
					readWindowUpdateFrame(streamId, frameBuffer);
					break;
				case CONTINUATION:
					readContinuationFrame(streamId, flags, payloadSize, frameBuffer);
					break;
				case UNKNOWN:
					readUnknownFrame(streamId, frameTypeId, flags, payloadSize, frameBuffer);
				default:
					break;
				}
				parsingState = PARSING_HEADER;
				break;
			default:
				break;
			}
			return true;
		}

	}

	protected void readDataFrame(int streamId, int flags, int payloadSize, ByteBufferWrapper buffer)
			throws Http2Exception, IOException {
		// Process the Stream
		int padLength = 0;

		boolean endOfStream = Flags.isEndOfStream(flags);

		int dataLength;
		if (Flags.hasPadding(flags)) {

			padLength = buffer.getByte() & 0xFF;

			if (padLength >= payloadSize) {
				throw new ConnectionException(
						sm.getString("http2Parser.processFrame.tooMuchPadding", connectionId,
								Integer.toString(streamId), Integer.toString(padLength), Integer.toString(payloadSize)),
						Http2Error.PROTOCOL_ERROR);
			}
			// +1 is for the padding length byte we just read above
			dataLength = payloadSize - (padLength + 1);
		} else {
			dataLength = payloadSize;
		}

		if (log.isDebugEnabled()) {
			String padding;
			if (Flags.hasPadding(flags)) {
				padding = Integer.toString(padLength);
			} else {
				padding = "none";
			}
			log.debug(sm.getString("http2Parser.processFrameData.lengths", connectionId, Integer.toString(streamId),
					Integer.toString(dataLength), padding));
		}

		StreamChannel stream = output.startRequestBodyFrame(streamId, payloadSize, endOfStream);
		if (stream == null) {
			swallowPayload(streamId, FrameType.DATA.getId(), dataLength, false, buffer);
			// Process padding before sending any notifications in case padding
			// is invalid.
			if (Flags.hasPadding(flags)) {
				swallowPayload(streamId, FrameType.DATA.getId(), padLength, true, buffer);
			}
			if (endOfStream) {
				output.receivedEndOfStream(streamId);
			}
		} else {
//			try {
//				handler.startWrite();
//				ByteBuffer dest = handler.getByteBuffer();
			if (stream.availableWindowSize() < payloadSize) {
				// Client has sent more data than permitted by Window size
				swallowPayload(streamId, FrameType.DATA.getId(), dataLength, false, buffer);
				if (Flags.hasPadding(flags)) {
					swallowPayload(streamId, FrameType.DATA.getId(), padLength, true, buffer);
				}
				throw new StreamException(sm.getString("http2Parser.processFrameData.window", connectionId),
						Http2Error.FLOW_CONTROL_ERROR, streamId);
			}
			if (dataLength == 0) {
				System.err.println("dataLength==0");
			}
			ByteBufferWrapper dest = ByteBufferWrapper.wrapper(ByteBuffer.allocate(dataLength), false);
			if (dataLength > 0) {// for firefox
				buffer.transferTo(dest);
			}
//			dest.flip();
			dest.switchToReadMode();
			if (!stream.offer(dest)) {

			}
			// Process padding before sending any notifications in case
			// padding is invalid.
			if (Flags.hasPadding(flags)) {
				swallowPayload(streamId, FrameType.DATA.getId(), padLength, true, buffer);
			}
			if (endOfStream) {
				output.receivedEndOfStream(streamId);
			}
			output.endRequestBodyFrame(streamId);
//			} finally {
//				handler.finishWrite();
//			}
		}
	}

	protected void readHeadersFrame(int streamId, int flags, int payloadSize, ByteBufferWrapper buffer)
			throws Http2Exception, IOException {

		headersEndStream = Flags.isEndOfStream(flags);

		if (hpackDecoder == null) {
			hpackDecoder = output.getHpackDecoder();
		}
		try {
			hpackDecoder.setHeaderEmitter(output.headersStart(streamId, headersEndStream));
		} catch (StreamException se) {
			swallowPayload(streamId, FrameType.HEADERS.getId(), payloadSize, false, buffer);
			throw se;
		}

		int padLength = 0;
		boolean padding = Flags.hasPadding(flags);
		boolean priority = Flags.hasPriority(flags);
		int optionalLen = 0;
		if (padding) {
			optionalLen = 1;
		}
		if (priority) {
			optionalLen += 5;
		}
		if (optionalLen > 0) {
			ByteBufferWrapper optional = ByteBufferWrapper.wrapper(ByteBuffer.allocate(optionalLen), false);
			buffer.transferTo(optional);

			optional.switchToReadMode();
			int optionalPos = 0;
			if (padding) {
				padLength = ByteUtil.getOneByte(optional, optionalPos++);
				if (padLength >= payloadSize) {
					throw new ConnectionException(sm.getString("http2Parser.processFrame.tooMuchPadding", connectionId,
							Integer.toString(streamId), Integer.toString(padLength), Integer.toString(payloadSize)),
							Http2Error.PROTOCOL_ERROR);
				}
			}
			if (priority) {
				boolean exclusive = ByteUtil.isBit7Set(optional.getByte(optionalPos));
				int parentStreamId = ByteUtil.get31Bits(optional, optionalPos);
				int weight = ByteUtil.getOneByte(optional, optionalPos + 4) + 1;
				output.reprioritise(streamId, parentStreamId, exclusive, weight);
			}

			payloadSize -= optionalLen;
			payloadSize -= padLength;
		}

		readHeaderPayload(streamId, payloadSize, buffer);

		swallowPayload(streamId, FrameType.HEADERS.getId(), padLength, true, buffer);

		if (Flags.isEndOfHeaders(flags)) {
			onHeadersComplete(streamId);
		} else {
			headersCurrentStream = streamId;
		}
	}

	protected void readPriorityFrame(int streamId, ByteBufferWrapper buffer) throws Http2Exception, IOException {
		ByteBufferWrapper payload = ByteBufferWrapper.wrapper(ByteBuffer.allocate(5), false);
		buffer.transferTo(payload);

		payload.switchToReadMode();
		boolean exclusive = ByteUtil.isBit7Set(payload.getByte(0));
		int parentStreamId = ByteUtil.get31Bits(payload, 0);
		int weight = ByteUtil.getOneByte(payload, 4) + 1;

		if (streamId == parentStreamId) {
			throw new StreamException(sm.getString("http2Parser.processFramePriority.invalidParent", connectionId,
					Integer.valueOf(streamId)), Http2Error.PROTOCOL_ERROR, streamId);
		}

		output.reprioritise(streamId, parentStreamId, exclusive, weight);
	}

	protected void readRstFrame(int streamId, ByteBufferWrapper buffer) throws Http2Exception, IOException {
		ByteBufferWrapper payload = ByteBufferWrapper.wrapper(ByteBuffer.allocate(4), false);
		buffer.transferTo(payload);

		payload.switchToReadMode();
		long errorCode = ByteUtil.getFourBytes(payload, 0);
		output.receiveReset(streamId, errorCode);
		headersCurrentStream = -1;
		headersEndStream = false;
	}

	protected void readSettingsFrame(int flags, int payloadSize, ByteBufferWrapper buffer)
			throws Http2Exception, IOException {
		boolean ack = Flags.isAck(flags);
		if (payloadSize > 0 && ack) {
			throw new ConnectionException(sm.getString("http2Parser.processFrameSettings.ackWithNonZeroPayload"),
					Http2Error.FRAME_SIZE_ERROR);
		}

		if (payloadSize == 0 && !ack) {
			// Ensure empty SETTINGS frame increments the overhead count
			output.receiveSetting(null, 0);
		} else {
			// Process the settings
			for (int i = 0; i < payloadSize / 6; i++) {
				ByteBufferWrapper setting = ByteBufferWrapper.wrapper(ByteBuffer.allocate(6), false);
				buffer.transferTo(setting);

				setting.switchToReadMode();
				int id = ByteUtil.getTwoBytes(setting, 0);
				long value = ByteUtil.getFourBytes(setting, 2);
				output.receiveSetting(Setting.valueOf(id), value);
			}
		}
		output.receiveSettingsEnd(ack);
	}

	/**
	 * This default server side implementation always throws an exception. If
	 * re-used for client side parsing, this method should be overridden with an
	 * appropriate implementation.
	 *
	 * @param streamId The pushed stream
	 * @param buffer   The payload, if available
	 *
	 * @throws Http2Exception
	 */
	protected void readPushPromiseFrame(int streamId, ByteBufferWrapper buffer) throws Http2Exception {
		throw new ConnectionException(
				sm.getString("http2Parser.processFramePushPromise", connectionId, Integer.valueOf(streamId)),
				Http2Error.PROTOCOL_ERROR);
	}

	protected void readPingFrame(int flags, ByteBufferWrapper buffer) throws IOException {
		// Read the payload
		ByteBufferWrapper payload = ByteBufferWrapper.wrapper(ByteBuffer.allocate(8), false);
		buffer.transferTo(payload);

		payload.switchToReadMode();
		output.receivePing(payload.getArray(), Flags.isAck(flags));
	}

	protected void readGoawayFrame(int payloadSize, ByteBufferWrapper buffer) throws IOException {
		ByteBufferWrapper payload = ByteBufferWrapper.wrapper(ByteBuffer.allocate(payloadSize), false);
		buffer.transferTo(payload);

		payload.switchToReadMode();
		int lastStreamId = ByteUtil.get31Bits(payload, 0);
		long errorCode = ByteUtil.getFourBytes(payload, 4);
		String debugData = null;
		if (payloadSize > 8) {
			debugData = new String(payload.getArray(), 8, payloadSize - 8, StandardCharsets.UTF_8);
		}
		output.receiveGoaway(lastStreamId, errorCode, debugData);
	}

	protected void readWindowUpdateFrame(int streamId, ByteBufferWrapper buffer) throws Http2Exception, IOException {
		ByteBufferWrapper payload = ByteBufferWrapper.wrapper(ByteBuffer.allocate(4), false);
		buffer.transferTo(payload);

		payload.switchToReadMode();
		int windowSizeIncrement = ByteUtil.get31Bits(payload, 0);

		if (log.isDebugEnabled()) {
			log.debug(sm.getString("http2Parser.processFrameWindowUpdate.debug", connectionId,
					Integer.toString(streamId), Integer.toString(windowSizeIncrement)));
		}

		// Validate the data
		if (windowSizeIncrement == 0) {
			if (streamId == 0) {
				throw new ConnectionException(sm.getString("http2Parser.processFrameWindowUpdate.invalidIncrement"),
						Http2Error.PROTOCOL_ERROR);
			} else {
				throw new StreamException(sm.getString("http2Parser.processFrameWindowUpdate.invalidIncrement"),
						Http2Error.PROTOCOL_ERROR, streamId);
			}
		}

		output.receiveIncWindows(streamId, windowSizeIncrement);
	}

	protected void readContinuationFrame(int streamId, int flags, int payloadSize, ByteBufferWrapper buffer)
			throws Http2Exception, IOException {
		if (headersCurrentStream == -1) {
			// No headers to continue
			throw new ConnectionException(sm.getString("http2Parser.processFrameContinuation.notExpected", connectionId,
					Integer.toString(streamId)), Http2Error.PROTOCOL_ERROR);
		}

		boolean endOfHeaders = Flags.isEndOfHeaders(flags);

		// Used to detect abusive clients sending large numbers of small
		// continuation frames
		output.headersContinue(payloadSize, endOfHeaders);

		readHeaderPayload(streamId, payloadSize, buffer);

		if (endOfHeaders) {
			headersCurrentStream = -1;
			onHeadersComplete(streamId);
		}
	}

	protected void readHeaderPayload(int streamId, int payloadSize, ByteBufferWrapper buffer)
			throws Http2Exception, IOException {

		if (log.isDebugEnabled()) {
			log.debug(sm.getString("http2Parser.processFrameHeaders.payload", connectionId, Integer.valueOf(streamId),
					Integer.valueOf(payloadSize)));
		}

		int remaining = payloadSize;

		while (remaining > 0) {
			if (headerReadBuffer.getRemaining() == 0) {
				// Buffer needs expansion
				int newSize;
				if (headerReadBuffer.getCapacity() < payloadSize) {
					// First step, expand to the current payload. That should
					// cover most cases.
					newSize = payloadSize;
				} else {
					// Header must be spread over multiple frames. Keep doubling
					// buffer size until the header can be read.
					newSize = headerReadBuffer.getCapacity() * 2;
				}
//				headerReadBuffer = ByteBufferUtils.expand(headerReadBuffer, newSize);
				headerReadBuffer.expand(newSize);
			}
			int toRead = Math.min(headerReadBuffer.getRemaining(), remaining);
			// headerReadBuffer in write mode
			headerReadBuffer.setLimit(headerReadBuffer.getPosition() + toRead);
			buffer.transferTo(headerReadBuffer);

			// switch to read mode
//			headerReadBuffer.flip();
			headerReadBuffer.switchToReadMode();
			try {
				hpackDecoder.decode(headerReadBuffer.getByteBuffer());
			} catch (HpackException hpe) {
				throw new ConnectionException(sm.getString("http2Parser.processFrameHeaders.decodingFailed"),
						Http2Error.COMPRESSION_ERROR, hpe);
			}

			// switches to write mode
//			headerReadBuffer.compact();
			headerReadBuffer.switchToWriteMode();
			remaining -= toRead;

			if (hpackDecoder.isHeaderCountExceeded()) {
				StreamException headerException = new StreamException(
						sm.getString("http2Parser.headerLimitCount", connectionId, Integer.valueOf(streamId)),
						Http2Error.ENHANCE_YOUR_CALM, streamId);
				hpackDecoder.getHeaderEmitter().setHeaderException(headerException);
			}

			if (hpackDecoder.isHeaderSizeExceeded(headerReadBuffer.getPosition())) {
				StreamException headerException = new StreamException(
						sm.getString("http2Parser.headerLimitSize", connectionId, Integer.valueOf(streamId)),
						Http2Error.ENHANCE_YOUR_CALM, streamId);
				hpackDecoder.getHeaderEmitter().setHeaderException(headerException);
			}

			if (hpackDecoder.isHeaderSwallowSizeExceeded(headerReadBuffer.getPosition())) {
				throw new ConnectionException(
						sm.getString("http2Parser.headerLimitSize", connectionId, Integer.valueOf(streamId)),
						Http2Error.ENHANCE_YOUR_CALM);
			}
		}
	}

	protected void readUnknownFrame(int streamId, int frameTypeId, int flags, int payloadSize, ByteBufferWrapper buffer)
			throws IOException {
		try {
			swallowPayload(streamId, frameTypeId, payloadSize, false, buffer);
		} catch (ConnectionException e) {
			// Will never happen because swallowPayload() is called with isPadding set
			// to false
		} finally {
			output.onSwallowedUnknownFrame(streamId, frameTypeId, flags, payloadSize);
		}
	}

	/**
	 * Swallow some or all of the bytes from the payload of an HTTP/2 frame.
	 *
	 * @param streamId    Stream being swallowed
	 * @param frameTypeId Type of HTTP/2 frame for which the bytes will be swallowed
	 * @param len         Number of bytes to swallow
	 * @param isPadding   Are the bytes to be swallowed padding bytes?
	 * @param byteBuffer  Used with {@link Http2AsyncParser} to access the data that
	 *                    has already been read
	 *
	 * @throws IOException         If an I/O error occurs reading additional bytes
	 *                             into the input buffer.
	 * @throws ConnectionException If the swallowed bytes are expected to have a
	 *                             value of zero but do not
	 */
	protected void swallowPayload(int streamId, int frameTypeId, int len, boolean isPadding,
			ByteBufferWrapper byteBuffer) throws IOException, ConnectionException {
		if (log.isDebugEnabled()) {
			log.debug(sm.getString("http2Parser.swallow.debug", connectionId, Integer.toString(streamId),
					Integer.toString(len)));
		}
		try {
			if (len == 0) {
				return;
			}
			if (!isPadding && byteBuffer != null) {
				byteBuffer.setPosition(byteBuffer.getPosition() + len);
			} else {
				int read = 0;
				ByteBufferWrapper buffer = ByteBufferWrapper.wrapper(ByteBuffer.allocate(1024), false);
				while (read < len) {
					buffer.switchToWriteMode();
					buffer.clearWrite();
					int thisTime = Math.min(buffer.getLimit(), len - read);
					buffer.setLimit(thisTime);
					byteBuffer.transferTo(buffer);

					if (isPadding) {
						buffer.switchToReadMode();
						// Validate the padding is zero since receiving non-zero padding
						// is a strong indication of either a faulty client or a server
						// side bug.
						for (int i = 0; i < thisTime; i++) {
							if (buffer.getByte(i) != 0) {
								throw new ConnectionException(sm.getString("http2Parser.nonZeroPadding", connectionId,
										Integer.toString(streamId)), Http2Error.PROTOCOL_ERROR);
							}
						}
					}
					read += thisTime;
				}
			}
		} finally {
			if (FrameType.DATA.getIdByte() == frameTypeId) {
				if (isPadding) {
					// Need to add 1 for the padding length bytes that was also
					// part of the payload.
					len += 1;
				}
				if (len > 0) {
					output.onSwallowedDataFramePayload(streamId, len);
				}
			}
		}
	}

	protected void onHeadersComplete(int streamId) throws Http2Exception {
		// Any left over data is a compression error
		if (headerReadBuffer.getPosition() > 0) {
			throw new ConnectionException(sm.getString("http2Parser.processFrameHeaders.decodingDataLeft"),
					Http2Error.COMPRESSION_ERROR);
		}

		// Delay validation (and triggering any exception) until this point
		// since all the headers still have to be read if a StreamException is
		// going to be thrown.
		hpackDecoder.getHeaderEmitter().validateHeaders();

		synchronized (output) {
			output.headersEnd(streamId);

			if (headersEndStream) {
				output.receivedEndOfStream(streamId);
				headersEndStream = false;
			}
		}

		// Reset size for new request if the buffer was previously expanded
		if (headerReadBuffer.getCapacity() > Constants.DEFAULT_HEADER_READ_BUFFER_SIZE) {
			headerReadBuffer = ByteBufferWrapper.wrapper(ByteBuffer.allocate(Constants.DEFAULT_HEADER_READ_BUFFER_SIZE),
					false);
		}
	}

	/*
	 * Implementation note: Validation applicable to all incoming frames should be
	 * implemented here. Frame type specific validation should be performed in the
	 * appropriate readXxxFrame() method. For validation applicable to some but not
	 * all frame types, use your judgement.
	 */
	protected void validateFrame(FrameType expected, FrameType frameType, int streamId, int flags, int payloadSize)
			throws Http2Exception {

		if (log.isDebugEnabled()) {
			log.debug(sm.getString("http2Parser.processFrame", connectionId, Integer.toString(streamId), frameType,
					Integer.toString(flags), Integer.toString(payloadSize)));
		}

		if (expected != null && frameType != expected) {
			throw new StreamException(sm.getString("http2Parser.processFrame.unexpectedType", expected, frameType),
					Http2Error.PROTOCOL_ERROR, streamId);
		}

		int maxFrameSize = input.getMaxFrameSize();
		if (payloadSize > maxFrameSize) {
			throw new ConnectionException(sm.getString("http2Parser.payloadTooBig", Integer.toString(payloadSize),
					Integer.toString(maxFrameSize)), Http2Error.FRAME_SIZE_ERROR);
		}

		if (headersCurrentStream != -1) {
			if (headersCurrentStream != streamId) {
				throw new ConnectionException(
						sm.getString("http2Parser.headers.wrongStream", connectionId,
								Integer.toString(headersCurrentStream), Integer.toString(streamId)),
						Http2Error.COMPRESSION_ERROR);
			}
			if (frameType == FrameType.RST) {
				// NO-OP: RST is OK here
			} else if (frameType != FrameType.CONTINUATION) {
				throw new ConnectionException(sm.getString("http2Parser.headers.wrongFrameType", connectionId,
						Integer.toString(headersCurrentStream), frameType), Http2Error.COMPRESSION_ERROR);
			}
		}

		frameType.check(streamId, payloadSize);
	}

	/**
	 * Read and validate the connection preface from input using blocking IO.
	 * 
	 * @param webConnection The connection
	 * @param stream        The current stream
	 */
	void readConnectionPreface(WebConnection webConnection, StreamChannel stream) throws Http2Exception {
		ByteBufferWrapper data = ByteBufferWrapper.wrapper(ByteBuffer.allocate(CLIENT_PREFACE_START.length), false);
		try {
			input.fullFill(data);
			data.switchToReadMode();
			for (int i = 0; i < CLIENT_PREFACE_START.length; i++) {
				if (CLIENT_PREFACE_START[i] != data.getByte(i)) {
					throw new ProtocolException(sm.getString("http2Parser.preface.invalid"));
				}
			}

			// Must always be followed by a settings frame
			readFrame(true, FrameType.SETTINGS);
		} catch (IOException ioe) {
			throw new ProtocolException(sm.getString("http2Parser.preface.io"), ioe);
		}
	}

	/**
	 * Interface that must be implemented by the source of data for the parser.
	 */
	static interface Input {

		boolean fill(boolean block, ByteBufferWrapper buffer) throws IOException;

		void fullFill(ByteBufferWrapper buffer) throws IOException;

//		void fill(byte[] data) throws IOException;

		/**
		 * Fill the given array with data unless non-blocking is requested and no data
		 * is available. If any data is available then the buffer will be filled using
		 * blocking I/O.
		 *
		 * @param block  Should the first read into the provided buffer be a blocking
		 *               read or not.
		 * @param data   Buffer to fill
		 * @param offset Position in buffer to start writing
		 * @param length Number of bytes to read
		 *
		 * @return <code>true</code> if the buffer was filled otherwise
		 *         <code>false</code>
		 *
		 * @throws IOException If an I/O occurred while obtaining data with which to
		 *                     fill the buffer
		 */
//		boolean fill(boolean block, byte[] data, int offset, int length) throws IOException;

//		default boolean fill(boolean block, byte[] data) throws IOException {
//			return fill(block, data, 0, data.length);
//		}

//		default boolean fill(boolean block, ByteBuffer data, int len) throws IOException {
//			boolean result = fill(block, data.array(), data.arrayOffset() + data.position(), len);
//			if (result) {
//				data.position(data.position() + len);
//			}
//			return result;
//		}

		int getMaxFrameSize();
	}

//	static interface ByteBufferHandler {

	// public void startWrite();

	// public ByteBuffer getByteBuffer();

	// public void finishWrite();

//	}

	/**
	 * Interface that must be implemented to receive notifications from the parser
	 * as it processes incoming frames.
	 */
	static interface Output {

		HpackDecoder getHpackDecoder();

		// Data frames
		StreamChannel startRequestBodyFrame(int streamId, int payloadSize, boolean endOfStream) throws Http2Exception;

		void endRequestBodyFrame(int streamId) throws Http2Exception;

		void receivedEndOfStream(int streamId) throws ConnectionException;

		/**
		 * Notification triggered when the parser swallows some or all of a DATA frame
		 * payload without writing it to the ByteBuffer returned by
		 * {@link #startRequestBodyFrame(int, int, boolean)}.
		 *
		 * @param streamId                The stream on which the payload that has been
		 *                                swallowed was received
		 * @param swallowedDataBytesCount The number of bytes that the parser swallowed.
		 *
		 * @throws ConnectionException If an error fatal to the HTTP/2 connection occurs
		 *                             while swallowing the payload
		 * @throws IOException         If an I/O occurred while swallowing the payload
		 */
		void onSwallowedDataFramePayload(int streamId, int swallowedDataBytesCount)
				throws ConnectionException, IOException;

		// Header frames
		HeaderEmitter headersStart(int streamId, boolean headersEndStream) throws Http2Exception, IOException;

		void headersContinue(int payloadSize, boolean endOfHeaders);

		void headersEnd(int streamId) throws ConnectionException, StreamException;

		// Priority frames (also headers)
		void reprioritise(int streamId, int parentStreamId, boolean exclusive, int weight) throws Http2Exception;

		// Reset frames
		void receiveReset(int streamId, long errorCode) throws Http2Exception;

		// Settings frames
		void receiveSetting(Setting setting, long value) throws ConnectionException;

		void receiveSettingsEnd(boolean ack) throws IOException;

		// Ping frames
		void receivePing(byte[] payload, boolean ack) throws IOException;

		// Goaway
		void receiveGoaway(int lastStreamId, long errorCode, String debugData);

		// Window size
		void receiveIncWindows(int streamId, int increment) throws Http2Exception;

		// Testing
		void onSwallowedUnknownFrame(int streamId, int frameTypeId, int flags, int size) throws IOException;
	}
}
