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

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;

import org.apache.coyote.ErrorState;
import org.apache.coyote.ExchangeData;
import org.apache.coyote.RequestAction;
import org.apache.coyote.http11.filters.BufferedInputFilter;
import org.apache.coyote.http11.filters.ChunkedInputFilter;
import org.apache.coyote.http11.filters.IdentityInputFilter;
import org.apache.coyote.http11.filters.SavedRequestInputFilter;
import org.apache.coyote.http11.filters.VoidInputFilter;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.buf.ByteChunk;
import org.apache.tomcat.util.net.BufWrapper;
import org.apache.tomcat.util.net.SSLSupport;
import org.apache.tomcat.util.net.SocketChannel;
import org.apache.tomcat.util.res.StringManager;

/**
 * InputBuffer for HTTP that provides request header parsing as well as transfer
 * encoding.
 */
public class Http11InputBuffer extends RequestAction {

	// -------------------------------------------------------------- Constants

	private static final Log log = LogFactory.getLog(Http11InputBuffer.class);

	/**
	 * The string manager for this package.
	 */
	private static final StringManager sm = StringManager.getManager(Http11InputBuffer.class);

	private Http11Processor processor;

	/**
	 * Associated Coyote request.
	 */
	private final ExchangeData exchangeData;

	private BufWrapper appReadBuffer = null;

	private boolean recycled = false;

	// ----------------------------------------------------------- Constructors

	public Http11InputBuffer(Http11Processor processor) {
		super(processor);
		this.processor = processor;
		this.exchangeData = processor.getExchangeData();

		AbstractHttp11Protocol<?> protocol = processor.getProtocol();

		// Create and add the identity filters.
		addFilter(new IdentityInputFilter(processor, protocol.getMaxSwallowSize()));
		// Create and add the chunked filters.
		addFilter(new ChunkedInputFilter(processor, protocol.getMaxTrailerSize(),
				protocol.getAllowedTrailerHeadersInternal(), protocol.getMaxExtensionSize(),
				protocol.getMaxSwallowSize()));
		// Create and add the void filters.
		addFilter(new VoidInputFilter(processor));
		// Create and add buffered input filter
		addFilter(new BufferedInputFilter(processor));
		addFilter(new SavedRequestInputFilter(processor, null));
		markPluggableFilterIndex();
	}

	void onChannelReady(SocketChannel socketChannel) {
		if (appReadBuffer != null) {
			System.err.println("appReadBuffer not released in inputbuffer");
		}
		appReadBuffer = socketChannel.getAppReadBuffer();
		if (appReadBuffer.released()) {
			System.err.println("err occure");
		}
		appReadBuffer.retain();
		recycled = false;
	}

	private boolean fillAppReadBuffer(boolean block) throws IOException {
		appReadBuffer.switchToWriteMode();
		boolean released = appReadBuffer.released();
		if (released) {
			throw new RuntimeException();
		}
		int nRead = ((SocketChannel) processor.getChannel()).read(block, appReadBuffer);
		if (appReadBuffer.released()) {
			System.err.println(appReadBuffer + "released after inputbuffer fill");
		}
		boolean success = false;
		if (nRead > 0) {
			success = true;
		} else if (nRead == -1) {
			throw new EOFException(sm.getString("iib.eof.error"));
		} else {
			success = false;
		}
		appReadBuffer.switchToReadMode();
		return success;
	}

	// ------------------------------------------------------------- Properties

	@Override
	protected BufWrapper doReadFromChannel() throws IOException {
		appReadBuffer.switchToReadMode();
		if (appReadBuffer.hasNoRemaining()) {
			// The application is reading the HTTP request body which is
			// always a blocking operation.
			if (!fillAppReadBuffer(true))
				return null;
			appReadBuffer.switchToReadMode();
		}

		int length = appReadBuffer.getRemaining();
		BufWrapper bufWrapper = appReadBuffer.duplicate();//
		appReadBuffer.setPosition(appReadBuffer.getLimit());
		return bufWrapper;
	}

	@Override
	public int getAvailable(Object param) {
		int available = available(Boolean.TRUE.equals(param));
		// exchangeData.setAvailable(available);
		return available;
	}

	/**
	 * Available bytes in the buffers (note that due to encoding, this may not
	 * correspond).
	 */
	private int available(boolean read) {
		appReadBuffer.switchToReadMode();

		int available = appReadBuffer.getRemaining();
		if (available == 0) {
			available = getAvailableInFilters();
		}
		if (available == 0) {
			available = ((SocketChannel) processor.getChannel()).getAvailable();
		}
//		if ((available == 0) && (hasActiveFilters())) {
//			for (int i = 0; (available == 0) && (i < getActiveFiltersCount()); i++) {
//				available = getActiveFilter(i).available();
//			}
//		}
		if (available > 0 || !read) {
			return available;
		}

		try {
			if (((SocketChannel) processor.getChannel()).hasDataToRead()) {
				fillAppReadBuffer(false);
				appReadBuffer.switchToReadMode();
				available = appReadBuffer.getRemaining();
			}
		} catch (IOException ioe) {
			if (log.isDebugEnabled()) {
				log.debug(sm.getString("iib.available.readFail"), ioe);
			}
			// Not ideal. This will indicate that data is available which should
			// trigger a read which in turn will trigger another IOException and
			// that one can be thrown.
			available = 1;
		}
		return available;
	}

	@Override
	public boolean isReadyForRead() {
		if (available(true) > 0) {
			return true;
		}

		if (!isRequestBodyFullyRead()) {
			registerReadInterest();
		}

		return false;
	}

	@Override
	public final boolean isRequestBodyFullyRead() {
		return this.requestBodyFullyRead();
	}

	@Override
	public boolean isTrailerFieldsReady() {
		if (this.isChunking()) {
			return this.requestBodyFullyRead();
		} else {
			return true;
		}
	}

	/**
	 * Has all of the request body been read? There are subtle differences between
	 * this and available() &gt; 0 primarily because of having to handle faking
	 * non-blocking reads with the blocking IO connector.
	 */
	private boolean requestBodyFullyRead() {

//		if (appReadBuffer.hasRemaining()) {
		// Data to read in the buffer so not finished
//			return false;
//		}

		/*
		 * Don't use fill(false) here because in the following circumstances BIO will
		 * block - possibly indefinitely - client is using keep-alive and connection is
		 * still open - client has sent the complete request - client has not sent any
		 * of the next request (i.e. no pipelining) - application has read the complete
		 * request
		 */

		// Check the InputFilters

		if (hasActiveFilters()) {
			return getLastActiveFilter().isFinished();
		} else {
			// No filters. Assume request is not finished. EOF will signal end of
			// request.
			return false;
		}
	}

	@Override
	public final void registerReadInterest() {
		((SocketChannel) processor.getChannel()).registerReadInterest();
	}

	/**
	 * End request (consumes leftover bytes).
	 *
	 * @throws IOException an underlying I/O error occurred
	 */
	@Override
	public void finish() throws IOException {
		if (isSwallowInput() && (hasActiveFilters())) {
			int extraBytes = (int) getLastActiveFilter().end();
			appReadBuffer.setPosition(appReadBuffer.getPosition() - extraBytes);
		}
	}

	/**
	 * Populate the remote host request attribute. Processors (e.g. AJP) that
	 * populate this from an alternative source should override this method.
	 */
	protected void populateRequestAttributeRemoteHost() {
		if (getPopulateRequestAttributesFromSocket() && ((SocketChannel) processor.getChannel()) != null) {
			exchangeData.getRemoteHost().setString(((SocketChannel) processor.getChannel()).getRemoteHost());
		}
	}

	@Override
	public void actionREQ_HOST_ADDR_ATTRIBUTE() {
		if (getPopulateRequestAttributesFromSocket() && ((SocketChannel) processor.getChannel()) != null) {
			exchangeData.getRemoteAddr().setString(((SocketChannel) processor.getChannel()).getRemoteAddr());
		}
	}

	@Override
	public void actionREQ_HOST_ATTRIBUTE() {
		populateRequestAttributeRemoteHost();
	}

	@Override
	public void actionREQ_LOCALPORT_ATTRIBUTE() {
		if (getPopulateRequestAttributesFromSocket() && ((SocketChannel) processor.getChannel()) != null) {
			exchangeData.setLocalPort(((SocketChannel) processor.getChannel()).getLocalPort());
		}
	}

	@Override
	public void actionREQ_LOCAL_ADDR_ATTRIBUTE() {
		if (getPopulateRequestAttributesFromSocket() && ((SocketChannel) processor.getChannel()) != null) {
			exchangeData.getLocalAddr().setString(((SocketChannel) processor.getChannel()).getLocalAddr());
		}
	}

	@Override
	public void actionREQ_LOCAL_NAME_ATTRIBUTE() {
		if (getPopulateRequestAttributesFromSocket() && ((SocketChannel) processor.getChannel()) != null) {
			exchangeData.getLocalName().setString(((SocketChannel) processor.getChannel()).getLocalName());
		}
	}

	@Override
	public void actionREQ_REMOTEPORT_ATTRIBUTE() {
		if (getPopulateRequestAttributesFromSocket() && ((SocketChannel) processor.getChannel()) != null) {
			exchangeData.setRemotePort(((SocketChannel) processor.getChannel()).getRemotePort());
		}
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * This implementation provides the server port from the local port.
	 */
	@Override
	protected void populatePort() {
		// Ensure the local port field is populated before using it.
		this.actionREQ_LOCALPORT_ATTRIBUTE();
		exchangeData.setServerPort(exchangeData.getLocalPort());
	}

	@Override
	public final void setRequestBody(ByteChunk body) {
		SavedRequestInputFilter savedBody = (SavedRequestInputFilter) getFilterById(Constants.SAVEDREQUEST_FILTER);// SavedRequestInputFilter(body);
		savedBody.setInput(body);
		// Http11InputBuffer internalBuffer = (Http11InputBuffer)
		// request.getInputBuffer();
		this.addActiveFilter(savedBody.getId());
	}

	@Override
	public final void disableSwallowRequest() {
		this.setSwallowInput(false);
	}

	ByteBuffer getLeftover() {
		int available = appReadBuffer.getRemaining();
		if (available > 0) {
			if (appReadBuffer.hasArray()) {
				return ByteBuffer.wrap(appReadBuffer.getArray(), appReadBuffer.getPosition(), available);
			} else {
				ByteBuffer byteBuffer = ByteBuffer.allocate(appReadBuffer.getRemaining());
				while (appReadBuffer.hasRemaining()) {
					byteBuffer.put(appReadBuffer.getByte());
				}
				return byteBuffer;
			}
		} else {
			return null;
		}
	}

	/**
	 * Populate the TLS related request attributes from the {@link SSLSupport}
	 * instance associated with this processor. Protocols that populate TLS
	 * attributes from a different source (e.g. AJP) should override this method.
	 */
	protected void populateSslRequestAttributes() {
		try {
			SSLSupport sslSupport = ((SocketChannel) processor.getChannel()).getSslSupport();
			if (sslSupport != null) {
				Object sslO = sslSupport.getCipherSuite();
				if (sslO != null) {
					exchangeData.setAttribute(SSLSupport.CIPHER_SUITE_KEY, sslO);
				}
				sslO = sslSupport.getPeerCertificateChain();
				if (sslO != null) {
					exchangeData.setAttribute(SSLSupport.CERTIFICATE_KEY, sslO);
				}
				sslO = sslSupport.getKeySize();
				if (sslO != null) {
					exchangeData.setAttribute(SSLSupport.KEY_SIZE_KEY, sslO);
				}
				sslO = sslSupport.getSessionId();
				if (sslO != null) {
					exchangeData.setAttribute(SSLSupport.SESSION_ID_KEY, sslO);
				}
				sslO = sslSupport.getProtocol();
				if (sslO != null) {
					exchangeData.setAttribute(SSLSupport.PROTOCOL_VERSION_KEY, sslO);
				}
				exchangeData.setAttribute(SSLSupport.SESSION_MGR, sslSupport);
			}
		} catch (Exception e) {
			log.warn(sm.getString("abstractProcessor.socket.ssl"), e);
		}
	}

	@Override
	public void actionREQ_SSL_ATTRIBUTE() {
		populateSslRequestAttributes();
	}

	@Override
	public void actionREQ_SSL_CERTIFICATE() {
		try {
			sslReHandShake();
		} catch (IOException ioe) {
			processor.setErrorState(ErrorState.CLOSE_CONNECTION_NOW, ioe);
		}
	}

	/**
	 * Processors that can perform a TLS re-handshake (e.g. HTTP/1.1) should
	 * override this method and implement the re-handshake.
	 *
	 * @throws IOException If authentication is required then there will be I/O with
	 *                     the client and this exception will be thrown if that goes
	 *                     wrong
	 */
	// @Override
	protected final void sslReHandShake() throws IOException {
		SSLSupport sslSupport = ((SocketChannel) processor.getChannel()).getSslSupport();
		if (sslSupport != null) {
			// Consume and buffer the request body, so that it does not
			// interfere with the client's handshake messages
			// InputFilter[] inputFilters = this.getFilters();
			((BufferedInputFilter) getFilterById(Constants.BUFFERED_FILTER))
					.setLimit(((AbstractHttp11Protocol<?>) processor.getProtocol()).getMaxSavePostSize());
			this.addActiveFilter(Constants.BUFFERED_FILTER);

			/*
			 * Outside the try/catch because we want I/O errors during renegotiation to be
			 * thrown for the caller to handle since they will be fatal to the connection.
			 */
			((SocketChannel) processor.getChannel()).doClientAuth(sslSupport);
			try {
				/*
				 * Errors processing the cert chain do not affect the client connection so they
				 * can be logged and swallowed here.
				 */
				Object sslO = sslSupport.getPeerCertificateChain();
				if (sslO != null) {
					exchangeData.setAttribute(SSLSupport.CERTIFICATE_KEY, sslO);
				}
			} catch (IOException ioe) {
				log.warn(sm.getString("http11processor.socket.ssl"), ioe);
			}
		}
	}

	/**
	 * End processing of current HTTP request. Note: All bytes of the current
	 * request should have been already consumed. This method only resets all the
	 * pointers so that we are ready to parse the next HTTP request.
	 */
	void nextRequest() {
		if (recycled) {
			throw new RuntimeException();
		}
		// exchangeData.recycle();
		appReadBuffer.switchToWriteMode();
		appReadBuffer.switchToReadMode();
		resetFilters();
	}

	/**
	 * Recycle the input buffer. This should be called when closing the connection.
	 */
	@Override
	public void recycle() {
		if (recycled) {
			throw new RuntimeException();
		}
		if (appReadBuffer != null) {
			if (!appReadBuffer.released()) {
				appReadBuffer.release();
			}
			appReadBuffer = null;
		}
		// exchangeData.recycle();

		resetFilters();
		recycled = true;
	}

}
