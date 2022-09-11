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
package org.apache.coyote.http11;

import java.io.IOException;
import java.nio.ByteBuffer;

import javax.servlet.http.HttpServletResponse;

import org.apache.coyote.CloseNowException;
import org.apache.coyote.ErrorState;
import org.apache.coyote.ExchangeData;
import org.apache.coyote.ResponseAction;
import org.apache.coyote.http11.filters.ChunkedOutputFilter;
import org.apache.coyote.http11.filters.GzipOutputFilter;
import org.apache.coyote.http11.filters.IdentityOutputFilter;
import org.apache.coyote.http11.filters.VoidOutputFilter;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.buf.ByteChunk;
import org.apache.tomcat.util.buf.MessageBytes;
import org.apache.tomcat.util.http.FastHttpDateFormat;
import org.apache.tomcat.util.http.MimeHeaders;
import org.apache.tomcat.util.net.SendfileDataBase;
import org.apache.tomcat.util.net.SocketChannel;
import org.apache.tomcat.util.net.SocketChannel.BufWrapper;
import org.apache.tomcat.util.res.StringManager;

/**
 * Provides buffering for the HTTP headers (allowing responses to be reset
 * before they have been committed) and the link to the Socket for writing the
 * headers (once committed) and the response body. Note that buffering of the
 * response body happens at a higher level.
 */
public class Http11OutputBuffer extends ResponseAction {

	// -------------------------------------------------------------- Variables

	/**
	 * The string manager for this package.
	 */
	protected static final StringManager sm = StringManager.getManager(Http11OutputBuffer.class);

	// ----------------------------------------------------- Instance Variables

	private Http11Processor processor;

	/**
	 * Associated Coyote response.
	 */
	protected final ExchangeData exchangeData;

	/**
	 * The buffer used for header composition.
	 */
	protected BufWrapper headerBuffer;

	/**
	 * Bytes written to client for the current request
	 */
	protected long byteCount = 0;

	private int headerBufferSize;

	private volatile boolean ackSent = false;

	/**
	 * Sendfile data.
	 */
	private SendfileDataBase sendfileData = null;

	protected Http11OutputBuffer(Http11Processor processor) {
		super(processor);
		this.processor = processor;

		this.exchangeData = processor.getExchangeData();

		this.headerBufferSize = processor.getProtocol().getMaxHttpHeaderSize();

		// Create and add the identity filters.
		addFilter(new IdentityOutputFilter(processor));
		// Create and add the chunked filters.
		addFilter(new ChunkedOutputFilter(processor));
		// Create and add the void filters.
		addFilter(new VoidOutputFilter(processor));
		// Create and add the gzip filters.
		// inputBuffer.addFilter(new GzipInputFilter());
		addFilter(new GzipOutputFilter(processor));

	}

//	public void setInputBuffer(Http11InputBuffer inputBuffer) {
//		this.inputBuffer = inputBuffer;
//	}

	public SendfileDataBase getSendfileData() {
		return sendfileData;
	}

	public void setSendfileData(SendfileDataBase sendfileData) {
		this.sendfileData = sendfileData;
	}

	// ------------------------------------------------------------- Properties

	public void init(SocketChannel channel) {
		// this.channel = channel;
		if (headerBuffer == null) {
			headerBuffer = channel.allocate(headerBufferSize);
		} else {
			if (headerBuffer.getCapacity() < headerBufferSize) {
				if (!headerBuffer.reuseable()) {
					if (!headerBuffer.released()) {
						headerBuffer.release();
					}
				}
				headerBuffer = channel.allocate(headerBufferSize);
			} else {
				if (headerBuffer.released()) {
					headerBuffer = channel.allocate(headerBufferSize);
				}
			}
		}
		headerBuffer.switchToWriteMode();
		headerBuffer.setPosition(0);
		headerBuffer.setLimit(headerBuffer.getCapacity());
	}

	@Override
	public boolean isTrailerFieldsSupported() {
		// Request must be HTTP/1.1 to support trailer fields
		if (!processor.http11) {
			return false;
		}

		// If the response is not yet committed, chunked encoding can be used
		// and the trailer fields sent
		if (!exchangeData.isCommitted()) {
			return true;
		}

		// Response has been committed - need to see if chunked is being used
		return isChunking();
	}

	@Override
	public final boolean isReadyForWrite() {
		return this.isReady();
	}

	/**
	 * When committing the response, we have to validate the set of headers, as well
	 * as setup the response filters.
	 */
	@Override
	public final void prepareResponse(boolean finished) throws IOException {

		AbstractHttp11Protocol<?> protocol = (AbstractHttp11Protocol<?>) processor.getProtocol();

		boolean entityBody = true;
//		inputBuffer.contentDelimitation = false;
		if (exchangeData.getResponseBodyType() != -1) {
			throw new RuntimeException();
		}

		// OutputFilter[] outputFilters = this.getFilters();

		if (processor.http09 == true) {
			// HTTP/0.9
			this.addActiveFilter(Constants.IDENTITY_FILTER);
			exchangeData.setResponseBodyType(ExchangeData.BODY_TYPE_FIXEDLENGTH);
			this.writeHeaderBuffer();
			return;
		}

		int statusCode = exchangeData.getStatus();
		if (statusCode < 200 || statusCode == 204 || statusCode == 205 || statusCode == 304) {
			// No entity body
			this.addActiveFilter(Constants.VOID_FILTER);
			entityBody = false;
//			inputBuffer.contentDelimitation = true;
			exchangeData.setResponseBodyType(ExchangeData.BODY_TYPE_NOBODY);
			if (statusCode == 205) {
				// RFC 7231 requires the server to explicitly signal an empty
				// response in this case
				exchangeData.setResponseContentLength(0);
			} else {
				exchangeData.setResponseContentLength(-1);
			}
		}

		MessageBytes methodMB = exchangeData.getMethod();
		if (methodMB.equals("HEAD")) {
			// No entity body
			this.addActiveFilter(Constants.VOID_FILTER);
//			inputBuffer.contentDelimitation = true;
			exchangeData.setResponseBodyType(ExchangeData.BODY_TYPE_NOBODY);
		}

		// Sendfile support
		if (protocol.getUseSendfile()) {
			prepareSendfile();
		}

		// Check for compression
		boolean useCompression = false;
		if (entityBody && sendfileData == null) {
			useCompression = protocol.useCompression(exchangeData);
		}

		MimeHeaders headers = exchangeData.getResponseHeaders();
		// A SC_NO_CONTENT response may include entity headers
		if (entityBody || statusCode == HttpServletResponse.SC_NO_CONTENT) {
			String contentType = exchangeData.getResponseContentType();
			if (contentType != null) {
				headers.setValue("Content-Type").setString(contentType);
			}
			String contentLanguage = exchangeData.getContentLanguage();
			if (contentLanguage != null) {
				headers.setValue("Content-Language").setString(contentLanguage);
			}
		}

		long contentLength = exchangeData.getResponseContentLengthLong();
		boolean connectionClosePresent = Http11Processor.isConnectionToken(headers, Constants.CLOSE);
		// System.out.println("http11:" + http11);
		// System.out.println("contentLength:" + contentLength);
		if (processor.http11 && exchangeData.getTrailerFieldsSupplier() != null) {
			// If trailer fields are set, always use chunking
			this.addActiveFilter(Constants.CHUNKED_FILTER);
//			inputBuffer.contentDelimitation = true;
			exchangeData.setResponseBodyType(ExchangeData.BODY_TYPE_CHUNKED);
			headers.addValue(Constants.TRANSFERENCODING).setString(Constants.CHUNKED);
		} else if (contentLength != -1) {
			headers.setValue("Content-Length").setLong(contentLength);
			this.addActiveFilter(Constants.IDENTITY_FILTER);
//			inputBuffer.contentDelimitation = true;
			exchangeData.setResponseBodyType(ExchangeData.BODY_TYPE_FIXEDLENGTH);
		} else {
			// If the response code supports an entity body and we're on
			// HTTP 1.1 then we chunk unless we have a Connection: close header
			// System.out.println("entityBody:" + entityBody);
			// System.out.println("connectionClosePresent:" + connectionClosePresent);
			if (processor.http11 && entityBody && !connectionClosePresent) {
				this.addActiveFilter(Constants.CHUNKED_FILTER);
//				inputBuffer.contentDelimitation = true;
				exchangeData.setResponseBodyType(ExchangeData.BODY_TYPE_CHUNKED);
				headers.addValue(Constants.TRANSFERENCODING).setString(Constants.CHUNKED);
			} else {
				if ((entityBody) && (exchangeData.getResponseBodyType() == -1)) {// !inputBuffer.contentDelimitation
					// Mark as close the connection after the request, and add the
					// connection: close header
					processor.keepAlive = false;
				}
				this.addActiveFilter(Constants.IDENTITY_FILTER);
				exchangeData.setResponseBodyType(ExchangeData.BODY_TYPE_FIXEDLENGTH);
			}
		}

		if (useCompression) {
			this.addActiveFilter(Constants.GZIP_FILTER);
		}

		// Add date header unless application has already set one (e.g. in a
		// Caching Filter)
		if (headers.getValue("Date") == null) {
			headers.addValue("Date").setString(FastHttpDateFormat.getCurrentDate());
		}

		// FIXME: Add transfer encoding header

		// System.out.println("contentDelimitation:" + contentDelimitation);

		// This may disabled keep-alive to check before working out the
		// Connection header.
		processor.checkExpectationAndResponseStatus();

		// If we know that the request is bad this early, add the
		// Connection: close header.
		if (processor.keepAlive && Http11Processor.statusDropsConnection(statusCode)) {
			processor.keepAlive = false;
		}
		// System.out.println("keepAlive:" + keepAlive);
		if (!processor.keepAlive) {
			// Avoid adding the close header twice
			if (!connectionClosePresent) {
				headers.addValue(Constants.CONNECTION).setString(Constants.CLOSE);
			}
		} else if (!processor.getErrorState().isError()) {
			if (!processor.http11) {
				headers.addValue(Constants.CONNECTION).setString(Constants.KEEP_ALIVE_HEADER_VALUE_TOKEN);
			}

			if (protocol.getUseKeepAliveResponseHeader()) {
				boolean connectionKeepAlivePresent = Http11Processor.isConnectionToken(exchangeData.getRequestHeaders(),
						Constants.KEEP_ALIVE_HEADER_VALUE_TOKEN);

				if (connectionKeepAlivePresent) {
					int keepAliveTimeout = protocol.getKeepAliveTimeout();

					if (keepAliveTimeout > 0) {
						String value = "timeout=" + keepAliveTimeout / 1000L;
						headers.setValue(Constants.KEEP_ALIVE_HEADER_NAME).setString(value);

						if (processor.http11) {
							// Append if there is already a Connection header,
							// else create the header
							MessageBytes connectionHeaderValue = headers.getValue(Constants.CONNECTION);
							if (connectionHeaderValue == null) {
								headers.addValue(Constants.CONNECTION)
										.setString(Constants.KEEP_ALIVE_HEADER_VALUE_TOKEN);
							} else {
								connectionHeaderValue.setString(connectionHeaderValue.getString() + ", "
										+ Constants.KEEP_ALIVE_HEADER_VALUE_TOKEN);
							}
						}
					}
				}
			}
		}

		// Add server header
		String server = protocol.getServer();
		if (server == null) {
			if (protocol.getServerRemoveAppProvidedValues()) {
				headers.removeHeader("server");
			}
		} else {
			// server always overrides anything the app might set
			headers.setValue("Server").setString(server);
		}

		// Build the response header
		try {
			this.writeHeaderStatus();
			System.out.println("==========================print header start==========================");
			int size = headers.size();
			for (int i = 0; i < size; i++) {
				System.out.println(" " + headers.getName(i).toString() + " : " + headers.getValue(i).toString());
				this.writeHeaderBody(headers.getName(i), headers.getValue(i));
			}
			System.out.println("==========================print header end============================");
			this.writeHeaderTail();
		} catch (Throwable t) {
			ExceptionUtils.handleThrowable(t);
			// If something goes wrong, reset the header buffer so the error
			// response can be written instead.
			this.resetHeaderBuffer();
			throw t;
		}

		this.writeHeaderBuffer();
	}

	private void prepareSendfile() {
		String fileName = (String) exchangeData.getAttribute(org.apache.coyote.Constants.SENDFILE_FILENAME_ATTR);
		if (fileName == null) {
			sendfileData = null;
		} else {
			// No entity body sent here
			this.addActiveFilter(Constants.VOID_FILTER);
//			inputBuffer.contentDelimitation = true;
			exchangeData.setResponseBodyType(ExchangeData.BODY_TYPE_NOBODY);
			long pos = ((Long) exchangeData.getAttribute(org.apache.coyote.Constants.SENDFILE_FILE_START_ATTR))
					.longValue();
			long end = ((Long) exchangeData.getAttribute(org.apache.coyote.Constants.SENDFILE_FILE_END_ATTR))
					.longValue();
			sendfileData = ((SocketChannel) processor.getChannel()).createSendfileData(fileName, pos, end - pos);
		}
	}

	@Override
	public final void finishResponse() throws IOException {
		this.end();
	}

	// @Override
	protected final void ack() {
		// Acknowledge request
		// Send a 100 status back if it makes sense (response not committed
		// yet, and client specified an expectation for 100-continue)
		if (!exchangeData.isCommitted() && exchangeData.hasExpectation()) {
			processor.getRequestAction().setSwallowInput(true);
			try {
				this.writeAckAndFlush();
			} catch (IOException e) {
				processor.setErrorState(ErrorState.CLOSE_CONNECTION_NOW, e);
			}
		}
	}

	// @Override
	// protected final void flush() throws IOException {
	// this.flush();
	// }

	// ----------------------------------------------- HttpOutputBuffer Methods

	// --------------------------------------------------------- Public Methods

	/**
	 * Reset the header buffer if an error occurs during the writing of the headers
	 * so the error response can be written.
	 */
	private void resetHeaderBuffer() {
		if (headerBuffer.reuseable()) {
			headerBuffer.switchToWriteMode();
			headerBuffer.setPosition(0);
			headerBuffer.setLimit(headerBuffer.getCapacity());
		} else {
			if (headerBuffer.released()) {
				headerBuffer = ((SocketChannel) processor.getChannel()).allocate(headerBufferSize);
			}
			headerBuffer.switchToWriteMode();
			headerBuffer.setPosition(0);
			headerBuffer.setLimit(headerBuffer.getCapacity());
		}
	}

	private void writeAckAndFlush() throws IOException {
		if (!exchangeData.isCommitted() && !ackSent) {
			ackSent = true;
			((SocketChannel) processor.getChannel()).write(processor.isBlockingWrite(), Constants.ACK_BYTES, 0,
					Constants.ACK_BYTES.length);
			if (flushBuffer(true)) {
				throw new IOException(sm.getString("iob.failedwrite.ack"));
			}
		}
	}

	/**
	 * Send the response status line.
	 */
	private void writeHeaderStatus() {
		checkHeadBufferIfNull();
		// Write protocol name
		write(Constants.HTTP_11_BYTES);
		headerBuffer.putByte(Constants.SP);

		// Write status code
		int status = exchangeData.getStatus();
		switch (status) {
		case 200:
			write(Constants._200_BYTES);
			break;
		case 400:
			write(Constants._400_BYTES);
			break;
		case 404:
			write(Constants._404_BYTES);
			break;
		default:
			write(status);
		}

		headerBuffer.putByte(Constants.SP);

		// The reason phrase is optional but the space before it is not. Skip
		// sending the reason phrase. Clients should ignore it (RFC 7230) and it
		// just wastes bytes.

		headerBuffer.putByte(Constants.CR);
		headerBuffer.putByte(Constants.LF);
	}

	/**
	 * Send a header.
	 *
	 * @param name  Header name
	 * @param value Header value
	 */
	private void writeHeaderBody(MessageBytes name, MessageBytes value) {
		checkHeadBufferIfNull();
		write(name);
		headerBuffer.putByte(Constants.COLON);
		headerBuffer.putByte(Constants.SP);
		write(value);
		headerBuffer.putByte(Constants.CR);
		headerBuffer.putByte(Constants.LF);
	}

	/**
	 * End the header block.
	 */
	private void writeHeaderTail() {
		checkHeadBufferIfNull();
		headerBuffer.putByte(Constants.CR);
		headerBuffer.putByte(Constants.LF);
	}

	/**
	 * This method will write the contents of the specified message bytes buffer to
	 * the output stream, without filtering. This method is meant to be used to
	 * write the response header.
	 *
	 * @param mb data to be written
	 */
	private void write(MessageBytes mb) {
		if (mb.getType() != MessageBytes.T_BYTES) {
			mb.toBytes();
			ByteChunk bc = mb.getByteChunk();
			// Need to filter out CTLs excluding TAB. ISO-8859-1 and UTF-8
			// values will be OK. Strings using other encodings may be
			// corrupted.
			byte[] buffer = bc.getBuffer();
			for (int i = bc.getOffset(); i < bc.getLength(); i++) {
				// byte values are signed i.e. -128 to 127
				// The values are used unsigned. 0 to 31 are CTLs so they are
				// filtered (apart from TAB which is 9). 127 is a control (DEL).
				// The values 128 to 255 are all OK. Converting those to signed
				// gives -128 to -1.
				if ((buffer[i] > -1 && buffer[i] <= 31 && buffer[i] != 9) || buffer[i] == 127) {
					buffer[i] = ' ';
				}
			}
		}
		write(mb.getByteChunk());
	}

	/**
	 * This method will write the contents of the specified byte chunk to the output
	 * stream, without filtering. This method is meant to be used to write the
	 * response header.
	 *
	 * @param bc data to be written
	 */
	private void write(ByteChunk bc) {
		checkHeadBufferIfNull();
		// Writing the byte chunk to the output buffer
		int length = bc.getLength();
		checkLengthBeforeWrite(length);
		headerBuffer.putBytes(bc.getBytes(), bc.getStart(), length);
	}

	/**
	 * This method will write the contents of the specified byte buffer to the
	 * output stream, without filtering. This method is meant to be used to write
	 * the response header.
	 *
	 * @param b data to be written
	 */
	private void write(byte[] b) {
		checkHeadBufferIfNull();
		checkLengthBeforeWrite(b.length);

		// Writing the byte chunk to the output buffer
		headerBuffer.putBytes(b);
	}

	/**
	 * This method will write the specified integer to the output stream. This
	 * method is meant to be used to write the response header.
	 *
	 * @param value data to be written
	 */
	private void write(int value) {
		checkHeadBufferIfNull();
		// From the Tomcat 3.3 HTTP/1.0 connector
		String s = Integer.toString(value);
		int len = s.length();
		checkLengthBeforeWrite(len);
		for (int i = 0; i < len; i++) {
			char c = s.charAt(i);
			headerBuffer.putByte((byte) c);
		}
	}

	/**
	 * Checks to see if there is enough space in the buffer to write the requested
	 * number of bytes.
	 */
	private void checkLengthBeforeWrite(int length) {
		checkHeadBufferIfNull();
		// "+ 4": BZ 57509. Reserve space for CR/LF/COLON/SP characters that
		// are put directly into the buffer following this write operation.
		if (headerBuffer.getPosition() + length + 4 > headerBuffer.getCapacity()) {
			throw new HeadersTooLargeException(sm.getString("iob.responseheadertoolarge.error"));
		}
	}

	private void checkHeadBufferIfNull() {
		// if (headerBuffer == null) {
		// headerBuffer = channel.allocate(headerBufferSize);
		// headerBuffer.switchToWriteMode();
		// headerBuffer.setPosition(0);
		// headerBuffer.setLimit(headerBuffer.getCapacity());
		// }
	}

	/**
	 * Commit the response.
	 *
	 * @throws IOException an underlying I/O error occurred
	 */
	private void writeHeaderBuffer() throws IOException {
		checkHeadBufferIfNull();
		if (headerBuffer.getPosition() > 0) {
			// Sending the response header buffer
			headerBuffer.switchToReadMode();
			try {
				SocketChannel channel = ((SocketChannel) processor.getChannel());
				if (channel != null) {
					channel.write(processor.isBlockingWrite(), headerBuffer);
				} else {
					throw new CloseNowException(sm.getString("iob.failedwrite"));
				}
			} finally {
				// if (headerBuffer.reuseable()) {
				// headerBuffer.switchToWriteMode();
				// headerBuffer.setPosition(0);
				// headerBuffer.setLimit(headerBuffer.getCapacity());
				// }
			}
		}
	}

	// ------------------------------------------------------ Non-blocking writes

	/**
	 * Writes any remaining buffered data.
	 *
	 * @param block Should this method block until the buffer is empty
	 * @return <code>true</code> if data remains in the buffer (which can only
	 *         happen in non-blocking mode) else <code>false</code>.
	 * @throws IOException Error writing data
	 */
	protected boolean flushBuffer(boolean block) throws IOException {
		return ((SocketChannel) processor.getChannel()).flush(block);
	}

	protected final boolean isReady() {
		boolean result = !hasDataToWrite();
		if (!result) {
			((SocketChannel) processor.getChannel()).registerWriteInterest();
		}
		return result;
	}

	public boolean hasDataToWrite() {
		return ((SocketChannel) processor.getChannel()).hasDataToWrite();
	}

	public void registerWriteInterest() {
		((SocketChannel) processor.getChannel()).registerWriteInterest();
	}

	@Override
	public boolean flushBufferedWrite() throws IOException {
		if (hasDataToWrite()) {
			if (flushBuffer(false)) {
				// The buffer wasn't fully flushed so re-register the
				// socket for write. Note this does not go via the
				// Response since the write registration state at
				// that level should remain unchanged. Once the buffer
				// has been emptied then the code below will call
				// Adaptor.asyncDispatch() which will enable the
				// Response to respond to this event.
				registerWriteInterest();
				return true;
			}
		}
		return false;
	}

	/**
	 * Write chunk.
	 */
	@Override
	public int doWriteToChannel(ByteBuffer chunk) throws IOException {
		try {
			int len = chunk.remaining();
			SocketChannel channel = ((SocketChannel) processor.getChannel());
			if (channel != null) {
				channel.write(processor.isBlockingWrite(), chunk);
			} else {
				throw new CloseNowException(sm.getString("iob.failedwrite"));
			}
			len -= chunk.remaining();
			byteCount += len;
			return len;
		} catch (IOException ioe) {
			closeNow(ioe);
			// Re-throw
			throw ioe;
		}
	}

	@Override
	public long getBytesWrittenToChannel() {
		return byteCount;
	}

	@Override
	public void flushToChannel() throws IOException {
		((SocketChannel) processor.getChannel()).flush(processor.isBlockingWrite());
	}

	@Override
	public boolean flushToChannel(boolean block) throws IOException {
		return ((SocketChannel) processor.getChannel()).flush(processor.isBlockingWrite());
	}

	@Override
	public void endToChannel() throws IOException {
		((SocketChannel) processor.getChannel()).flush(true);
	}

	/**
	 * End processing of current HTTP request. Note: All bytes of the current
	 * request should have been already consumed. This method only resets all the
	 * pointers so that we are ready to parse the next HTTP request.
	 */
	public void nextRequest() {

		resetFilter();
		// Recycle response object
		// responseData.recycle();
		// Reset pointers
		if (headerBuffer.reuseable()) {
			headerBuffer.switchToWriteMode();
			headerBuffer.setPosition(0);
			headerBuffer.setLimit(headerBuffer.getCapacity());
		} else {
			if (headerBuffer.released()) {
				headerBuffer = ((SocketChannel) processor.getChannel()).allocate(headerBufferSize);
			}
			headerBuffer.switchToWriteMode();
			headerBuffer.setPosition(0);
			headerBuffer.setLimit(headerBuffer.getCapacity());
		}

		ackSent = false;
		byteCount = 0;
	}

	/**
	 * Recycle the output buffer. This should be called when closing the connection.
	 */
	@Override
	public void recycle() {
		nextRequest();
		// channel = null;
		sendfileData = null;
		if (!headerBuffer.reuseable()) {
			if (!headerBuffer.released()) {
				headerBuffer.release();
			}
		}
	}

}
