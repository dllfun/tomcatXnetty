package org.apache.coyote.ajp;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.coyote.ErrorState;
import org.apache.coyote.ExchangeData;
import org.apache.coyote.ResponseAction;
import org.apache.tomcat.util.buf.MessageBytes;
import org.apache.tomcat.util.http.MimeHeaders;
import org.apache.tomcat.util.net.SocketChannel;

/**
 * This class is an output buffer which will write data to an output stream.
 */
public class SocketOutputBuffer extends ResponseAction {

	/**
	 * End message array.
	 */
	private static final byte[] endMessageArray;
	private static final byte[] endAndCloseMessageArray;

	/**
	 * Flush message array.
	 */
	private static final byte[] flushMessageArray;

	static {

		// Allocate the end message array
		AjpMessage endMessage = new AjpMessage(16);
		endMessage.reset();
		endMessage.appendByte(Constants.JK_AJP13_END_RESPONSE);
		endMessage.appendByte(1);
		endMessage.end();
		endMessageArray = new byte[endMessage.getLen()];
		System.arraycopy(endMessage.getBuffer(), 0, endMessageArray, 0, endMessage.getLen());

		// Allocate the end and close message array
		AjpMessage endAndCloseMessage = new AjpMessage(16);
		endAndCloseMessage.reset();
		endAndCloseMessage.appendByte(Constants.JK_AJP13_END_RESPONSE);
		endAndCloseMessage.appendByte(0);
		endAndCloseMessage.end();
		endAndCloseMessageArray = new byte[endAndCloseMessage.getLen()];
		System.arraycopy(endAndCloseMessage.getBuffer(), 0, endAndCloseMessageArray, 0, endAndCloseMessage.getLen());

		// Allocate the flush message array
		AjpMessage flushMessage = new AjpMessage(16);
		flushMessage.reset();
		flushMessage.appendByte(Constants.JK_AJP13_SEND_BODY_CHUNK);
		flushMessage.appendInt(0);
		flushMessage.appendByte(0);
		flushMessage.end();
		flushMessageArray = new byte[flushMessage.getLen()];
		System.arraycopy(flushMessage.getBuffer(), 0, flushMessageArray, 0, flushMessage.getLen());

	}

	/**
	 * AJP packet size.
	 */
	private final int outputMaxChunkSize;

	/**
	 * Message used for response composition.
	 */
	private final AjpMessage responseMessage;

	/**
	 * Location of next write of the response message (used with non-blocking writes
	 * when the message may not be written in a single write). A value of -1
	 * indicates that no message has been written to the buffer.
	 */
	private int responseMsgPos = -1;

	/**
	 * Bytes written to client for the current request.
	 */
	private long bytesWritten = 0;

	/**
	 * Finished response.
	 */
//	private boolean responseFinished = false;

	/**
	 * Should any response body be swallowed and not sent to the client.
	 */
	private boolean swallowResponse = false;

	private final AjpProcessor processor;

	private final ExchangeData exchangeData;

	/**
	 * Temp message bytes used for processing.
	 */
	private final MessageBytes tmpMB = MessageBytes.newInstance();

//	private final SocketInputReader inputReader;

	public SocketOutputBuffer(AjpProcessor processor) {
		super(processor);
		this.processor = processor;
		this.exchangeData = processor.getExchangeData();
		int packetSize = processor.getProtocol().getPacketSize();
		// Calculate maximum chunk size as packetSize may have been changed from
		// the default (Constants.MAX_PACKET_SIZE)
		this.outputMaxChunkSize = packetSize - Constants.SEND_HEAD_LEN;
		this.responseMessage = new AjpMessage(packetSize);
//		this.inputReader = processor.getInputReader();
	}

//	@Override
//	protected HttpOutputBuffer getBaseOutputBuffer() {
//		return this;
//	}

	public int getResponseMsgPos() {
		return responseMsgPos;
	}

	@Override
	public int doWriteToChannel(ByteBuffer chunk) throws IOException {

//		if (!exchangeData.isCommitted()) {
		// Validate and write response headers
//			try {
//				prepareResponse(false);
//			} catch (IOException e) {
//				setErrorState(ErrorState.CLOSE_CONNECTION_NOW, e);
//			}
//		}

		int len = 0;
		if (!swallowResponse) {
			try {
				len = chunk.remaining();
				writeData(chunk);
				len -= chunk.remaining();
			} catch (IOException ioe) {
				processor.setErrorState(ErrorState.CLOSE_CONNECTION_NOW, ioe);
				// Re-throw
				throw ioe;
			}
		}
		return len;
	}

	private void writeData(ByteBuffer chunk) throws IOException {
		boolean blocking = processor.isBlockingWrite();// (exchangeData.getAsyncStateMachine().getWriteListener() ==
														// null);

		int len = chunk.remaining();
		int off = 0;

		// Write this chunk
		while (len > 0) {
			int thisTime = Math.min(len, outputMaxChunkSize);

			responseMessage.reset();
			responseMessage.appendByte(Constants.JK_AJP13_SEND_BODY_CHUNK);
			chunk.limit(chunk.position() + thisTime);
			responseMessage.appendBytes(chunk);
			responseMessage.end();
			if (((SocketChannel) processor.getChannel()) == null) {
				System.out.println();
			}
			((SocketChannel) processor.getChannel()).write(blocking, responseMessage.getBuffer(), 0,
					responseMessage.getLen());
			((SocketChannel) processor.getChannel()).flush(blocking);

			len -= thisTime;
			off += thisTime;
		}

		bytesWritten += off;
	}

	@Override
	public long getBytesWrittenToChannel() {
		return bytesWritten;
	}

	/**
	 * Protocols that support trailer fields should override this method and return
	 * {@code true}.
	 *
	 * @return {@code true} if trailer fields are supported by this processor,
	 *         otherwise {@code false}.
	 */
	public boolean isTrailerFieldsSupported() {
		return false;
	}

	@Override
	public final boolean isReadyForWrite() {
		return responseMsgPos == -1 && ((SocketChannel) processor.getChannel()).isReadyForWrite();
	}

	/**
	 * When committing the response, we have to validate the set of headers, as well
	 * as setup the response filters.
	 */
	@Override
	public final void prepareResponse(boolean finished) throws IOException {

		tmpMB.recycle();
		responseMsgPos = -1;
		responseMessage.reset();
		responseMessage.appendByte(Constants.JK_AJP13_SEND_HEADERS);

		// Responses with certain status codes are not permitted to include a
		// response body.
		int statusCode = exchangeData.getStatus();
		if (statusCode < 200 || statusCode == 204 || statusCode == 205 || statusCode == 304) {
			// No entity body
			swallowResponse = true;
		}

		// Responses to HEAD requests are not permitted to include a response
		// body.
		MessageBytes methodMB = exchangeData.getMethod();
		if (methodMB.equals("HEAD")) {
			// No entity body
			swallowResponse = true;
		}

		// HTTP header contents
		responseMessage.appendInt(statusCode);
		// Reason phrase is optional but mod_jk + httpd 2.x fails with a null
		// reason phrase - bug 45026
		tmpMB.setString(Integer.toString(exchangeData.getStatus()));
		responseMessage.appendBytes(tmpMB);

		// Special headers
		MimeHeaders headers = exchangeData.getResponseHeaders();
		String contentType = exchangeData.getResponseContentType();
		if (contentType != null) {
			headers.setValue("Content-Type").setString(contentType);
		}
		String contentLanguage = exchangeData.getContentLanguage();
		if (contentLanguage != null) {
			headers.setValue("Content-Language").setString(contentLanguage);
		}
		long contentLength = exchangeData.getResponseContentLengthLong();
		if (contentLength >= 0) {
			headers.setValue("Content-Length").setLong(contentLength);
		}

		// Other headers
		int numHeaders = headers.size();
		responseMessage.appendInt(numHeaders);
		for (int i = 0; i < numHeaders; i++) {
			MessageBytes hN = headers.getName(i);
			int hC = Constants.getResponseAjpIndex(hN.toString());
			if (hC > 0) {
				responseMessage.appendInt(hC);
			} else {
				responseMessage.appendBytes(hN);
			}
			MessageBytes hV = headers.getValue(i);
			responseMessage.appendBytes(hV);
		}

		// Write to buffer
		responseMessage.end();
		((SocketChannel) processor.getChannel()).write(true, responseMessage.getBuffer(), 0, responseMessage.getLen());
		((SocketChannel) processor.getChannel()).flush(true);
	}

	/**
	 * Finish AJP response.
	 */
	@Override
	public final void finishResponse() throws IOException {
		if (responseFinished)
			return;

		responseFinished = true;

		// Swallow the unread body packet if present
//		if (((SocketInputReader) processor.getRequestAction()).isWaitingForBodyMessage()
//				|| ((SocketInputReader) processor.getRequestAction()).isFirst()
//						&& exchangeData.getRequestContentLengthLong() > 0) {
//			((SocketInputReader) processor.getRequestAction()).refillBodyBuffer(true);
//		}

		// Add the end message
		if (processor.getErrorState().isError()) {
			((SocketChannel) processor.getChannel()).write(true, endAndCloseMessageArray, 0,
					endAndCloseMessageArray.length);
		} else {
			((SocketChannel) processor.getChannel()).write(true, endMessageArray, 0, endMessageArray.length);
		}
		((SocketChannel) processor.getChannel()).flush(true);
	}

	// @Override
	protected final void ack() {
		// NO-OP for AJP
	}

	/**
	 * Callback to write data from the buffer.
	 */
	@Override
	public final void flushToChannel() throws IOException {
		// Calling code should ensure that there is no data in the buffers for
		// non-blocking writes.
		// TODO Validate the assertion above
		if (!responseFinished) {
			if (processor.getProtocol().getAjpFlush()) {
				// Send the flush message
				((SocketChannel) processor.getChannel()).write(true, flushMessageArray, 0, flushMessageArray.length);
			}
			((SocketChannel) processor.getChannel()).flush(true);
		}
	}

	@Override
	protected boolean flushToChannel(boolean block) throws IOException {
		return ((SocketChannel) processor.getChannel()).flush(block);
	}

	@Override
	public boolean flushBufferedWrite() throws IOException {
		if (hasDataToWrite()) {
			((SocketChannel) processor.getChannel()).flush(false);
			if (hasDataToWrite()) {
				// There is data to write but go via Response to
				// maintain a consistent view of non-blocking state
				// response.checkRegisterForWrite();
				AtomicBoolean ready = new AtomicBoolean(false);
				synchronized (processor.getAsyncStateMachine().getNonBlockingStateLock()) {
					if (!processor.getAsyncStateMachine().isRegisteredForWrite()) {
						// actionNB_WRITE_INTEREST(ready);
						ready.set(isReadyForWrite());
						processor.getAsyncStateMachine().setRegisteredForWrite(!ready.get());
					}
				}
				return true;
			}
		}
		return false;
	}

	private boolean hasDataToWrite() {
		return getResponseMsgPos() != -1 || ((SocketChannel) processor.getChannel()).hasDataToWrite();
	}

	@Override
	public void endToChannel() throws IOException {
		// NO-OP for AJP
	}

	@Override
	public final void setSwallowResponse() {
		swallowResponse = true;
	}

	@Override
	public void recycle() {
		bytesWritten = 0;
		responseFinished = false;
		swallowResponse = false;
	}
}
