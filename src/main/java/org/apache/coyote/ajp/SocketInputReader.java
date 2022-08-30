package org.apache.coyote.ajp;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.security.NoSuchProviderException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import javax.servlet.http.HttpServletResponse;

import org.apache.coyote.ErrorState;
import org.apache.coyote.ExchangeData;
import org.apache.coyote.Request;
import org.apache.coyote.RequestAction;
import org.apache.coyote.Response;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.buf.ByteChunk;
import org.apache.tomcat.util.buf.MessageBytes;
import org.apache.tomcat.util.http.MimeHeaders;
import org.apache.tomcat.util.net.SSLSupport;
import org.apache.tomcat.util.net.SocketChannel;
import org.apache.tomcat.util.net.SocketChannel.BufWrapper;
import org.apache.tomcat.util.net.SocketWrapperBase.ByteBufferWrapper;
import org.apache.tomcat.util.res.StringManager;

/**
 * This class is an input buffer which will read its data from an input stream.
 */
public class SocketInputReader extends RequestAction {

	private static final Log log = LogFactory.getLog(AjpProcessor.class);
	/**
	 * The string manager for this package.
	 */
	private static final StringManager sm = StringManager.getManager(AjpProcessor.class);

	private static final Set<String> javaxAttributes;
	private static final Set<String> iisTlsAttributes;

	/**
	 * Pong message array.
	 */
	private static final byte[] pongMessageArray;

	static {

		// Build the Set of javax attributes
		Set<String> s = new HashSet<>();
		s.add("javax.servlet.request.cipher_suite");
		s.add("javax.servlet.request.key_size");
		s.add("javax.servlet.request.ssl_session");
		s.add("javax.servlet.request.X509Certificate");
		javaxAttributes = Collections.unmodifiableSet(s);

		Set<String> iis = new HashSet<>();
		iis.add("CERT_ISSUER");
		iis.add("CERT_SUBJECT");
		iis.add("CERT_COOKIE");
		iis.add("HTTPS_SERVER_SUBJECT");
		iis.add("CERT_FLAGS");
		iis.add("HTTPS_SECRETKEYSIZE");
		iis.add("CERT_SERIALNUMBER");
		iis.add("HTTPS_SERVER_ISSUER");
		iis.add("HTTPS_KEYSIZE");
		iisTlsAttributes = Collections.unmodifiableSet(iis);

		// Allocate the pong message array
		AjpMessage pongMessage = new AjpMessage(16);
		pongMessage.reset();
		pongMessage.appendByte(Constants.JK_AJP13_CPONG_REPLY);
		pongMessage.end();
		pongMessageArray = new byte[pongMessage.getLen()];
		System.arraycopy(pongMessage.getBuffer(), 0, pongMessageArray, 0, pongMessage.getLen());
	}

	private final AjpProcessor processor;

	private final ExchangeData exchangeData;

	/**
	 * First read.
	 */
	private boolean first = true;

	/**
	 * End of stream flag.
	 */
	private boolean endOfStream = false;

	/**
	 * Request body empty flag.
	 */
	private boolean empty = true;

	/**
	 * Replay read.
	 */
	private boolean replay = false;

	/**
	 * Indicates that a 'get body chunk' message has been sent but the body chunk
	 * has not yet been received.
	 */
	private boolean waitingForBodyMessage = false;

	/**
	 * Body message.
	 */
	private final MessageBytes bodyBytes = MessageBytes.newInstance();

	/**
	 * GetBody message array. Not static like the other message arrays since the
	 * message varies with packetSize and that can vary per connector.
	 */
	private final byte[] getBodyMessageArray;

	/**
	 * Header message. Note that this header is merely the one used during the
	 * processing of the first message of a "request", so it might not be a request
	 * header. It will stay unchanged during the processing of the whole request.
	 */
	private final AjpMessage requestHeaderMessage;

	/**
	 * Body message.
	 */
	private final AjpMessage bodyMessage;

	/**
	 * Temp message bytes used for processing.
	 */
	private final MessageBytes tmpMB = MessageBytes.newInstance();

	private final MessageBytes certificates;

	// @Override
	/*
	 * public int doRead(PreInputBuffer handler) throws IOException {
	 * 
	 * if (endOfStream) { return -1; } if (empty) { if (!refillReadBuffer(true)) {
	 * return -1; } } ByteChunk bc = bodyBytes.getByteChunk();
	 * handler.setBufWrapper(
	 * ByteBufferWrapper.wrapper(ByteBuffer.wrap(bc.getBuffer(), bc.getStart(),
	 * bc.getLength()))); empty = true; return
	 * handler.getBufWrapper().getRemaining(); }
	 */

	public SocketInputReader(AjpProcessor processor) {
		super(processor);
		this.processor = processor;
		int packetSize = processor.getProtocol().getPacketSize();
		this.exchangeData = processor.getExchangeData();
		this.requestHeaderMessage = new AjpMessage(packetSize);
		this.bodyMessage = new AjpMessage(packetSize);
		this.certificates = processor.getCertificates();
		// Set the getBody message buffer
		AjpMessage getBodyMessage = new AjpMessage(16);
		getBodyMessage.reset();
		getBodyMessage.appendByte(Constants.JK_AJP13_GET_BODY_CHUNK);
		// Adjust read size if packetSize != default (Constants.MAX_PACKET_SIZE)
		getBodyMessage.appendInt(Constants.MAX_READ_SIZE + packetSize - Constants.MAX_PACKET_SIZE);
		getBodyMessage.end();
		getBodyMessageArray = new byte[getBodyMessage.getLen()];
		System.arraycopy(getBodyMessage.getBuffer(), 0, getBodyMessageArray, 0, getBodyMessage.getLen());
	}

	public boolean isWaitingForBodyMessage() {
		return waitingForBodyMessage;
	}

	public boolean isFirst() {
		return first;
	}

	@Override
	protected BufWrapper doReadFromChannel() throws IOException {
		if (endOfStream) {
			return null;
		}
		if (empty) {
			if (!refillBodyBuffer(true)) {
				return null;
			}
		}
		ByteChunk bc = bodyBytes.getByteChunk();
		empty = true;
		return ByteBufferWrapper.wrapper(ByteBuffer.wrap(bc.getBuffer(), bc.getStart(), bc.getLength()));
	}

	/**
	 * Get more request body data from the web server and store it in the internal
	 * buffer.
	 * 
	 * @param block <code>true</code> if this is blocking IO
	 * @return <code>true</code> if there is more data, <code>false</code> if not.
	 * @throws IOException An IO error occurred
	 */
	protected boolean refillBodyBuffer(boolean block) throws IOException {
		// When using replay (e.g. after FORM auth) all the data to read has
		// been buffered so there is no opportunity to refill the buffer.
		if (replay) {
			endOfStream = true; // we've read everything there is
		}
		if (endOfStream) {
			return false;
		}

		if (first) {
			first = false;
			long contentLength = exchangeData.getRequestContentLengthLong();
			// - When content length > 0, AJP sends the first body message
			// automatically.
			// - When content length == 0, AJP does not send a body message.
			// - When content length is unknown, AJP does not send the first
			// body message automatically.
			if (contentLength > 0) {
				waitingForBodyMessage = true;
			} else if (contentLength == 0) {
				endOfStream = true;
				return false;
			}
		}

		// Request more data immediately
		if (!waitingForBodyMessage) {
			((SocketChannel) processor.getChannel()).write(true, getBodyMessageArray, 0, getBodyMessageArray.length);
			((SocketChannel) processor.getChannel()).flush(true);
			waitingForBodyMessage = true;
		}

		boolean moreData = receive(block);
		if (!moreData && !waitingForBodyMessage) {
			endOfStream = true;
		}
		return moreData;
	}

	// Methods used by SocketInputBuffer
	/**
	 * Read an AJP body message. Used to read both the 'special' packet in ajp13 and
	 * to receive the data after we send a GET_BODY packet.
	 *
	 * @param block If there is no data available to read when this method is
	 *              called, should this call block until data becomes available?
	 *
	 * @return <code>true</code> if at least one body byte was read, otherwise
	 *         <code>false</code>
	 */
	private boolean receive(boolean block) throws IOException {

		bodyMessage.reset();

		if (!fillMessage(bodyMessage, block)) {
			return false;
		}

		waitingForBodyMessage = false;

		// No data received.
		if (bodyMessage.getLen() == 0) {
			// just the header
			return false;
		}
		int blen = bodyMessage.peekInt();
		if (blen == 0) {
			return false;
		}

		bodyMessage.getBodyBytes(bodyBytes);
		empty = false;
		return true;
	}

	protected boolean fillHeaderMessage(boolean block) throws IOException {
		while (true) {
			boolean success = fillMessage(requestHeaderMessage, block);
			if (!success) {
				return false;
			}

			// Check message type, process right away and break if
			// not regular request processing
			int type = requestHeaderMessage.getByte();
			if (type == Constants.JK_AJP13_CPING_REQUEST) {
				System.err.println("receive ping");
				if (processor.getProtocol().isPaused()) {
//					nextRequest();
					return true;
				}
//			cping = true;
				try {
					((SocketChannel) processor.getChannel()).write(true, pongMessageArray, 0, pongMessageArray.length);
					((SocketChannel) processor.getChannel()).flush(true);
					continue;
				} catch (IOException e) {
					if (log.isDebugEnabled()) {
						log.debug("Pong message failed", e);
					}
					processor.setErrorState(ErrorState.CLOSE_CONNECTION_NOW, e);
					return true;
				}
//				nextRequest();
			} else if (type != Constants.JK_AJP13_FORWARD_REQUEST) {
				// Unexpected packet type. Unread body packets should have
				// been swallowed in finish().
				if (log.isDebugEnabled()) {
					log.debug("Unexpected message: " + type);
				}
				processor.setErrorState(ErrorState.CLOSE_CONNECTION_NOW, null);
				return true;
			} else {
				return true;
			}
		}
	}

	/**
	 * Read an AJP message.
	 *
	 * @param message The message to populate
	 * @param block   If there is no data available to read when this method is
	 *                called, should this call block until data becomes available?
	 * 
	 * @return true if the message has been read, false if no data was read
	 *
	 * @throws IOException any other failure, including incomplete reads
	 */
	private boolean fillMessage(AjpMessage message, boolean block) throws IOException {

		byte[] buf = message.getBuffer();

		if (!read(buf, 0, Constants.H_SIZE, block)) {
			return false;
		}

		int messageLength = message.processHeader(true);
		if (messageLength < 0) {
			// Invalid AJP header signature
			throw new IOException(sm.getString("ajpmessage.invalidLength", Integer.valueOf(messageLength)));
		} else if (messageLength == 0) {
			// Zero length message.
			return true;
		} else {
			if (messageLength > message.getBuffer().length) {
				// Message too long for the buffer
				// Need to trigger a 400 response
				String msg = sm.getString("ajpprocessor.header.tooLong", Integer.valueOf(messageLength),
						Integer.valueOf(buf.length));
				log.error(msg);
				throw new IllegalArgumentException(msg);
			}
			read(buf, Constants.H_SIZE, messageLength, true);
			return true;
		}
	}

	/**
	 * After reading the request headers, we have to setup the request filters.
	 */
	protected void prepareRequest() {

		// Translate the HTTP method code to a String.
		byte methodCode = requestHeaderMessage.getByte();
		if (methodCode != Constants.SC_M_JK_STORED) {
			String methodName = Constants.getMethodForCode(methodCode - 1);
			exchangeData.getMethod().setString(methodName);
		}

		requestHeaderMessage.getBytes(exchangeData.getProtocol());
		requestHeaderMessage.getBytes(exchangeData.getRequestURI());

		requestHeaderMessage.getBytes(exchangeData.getRemoteAddr());
		requestHeaderMessage.getBytes(exchangeData.getRemoteHost());
		requestHeaderMessage.getBytes(exchangeData.getLocalName());
		exchangeData.setLocalPort(requestHeaderMessage.getInt());

		boolean isSSL = requestHeaderMessage.getByte() != 0;
		if (isSSL) {
			exchangeData.getScheme().setString("https");
		}

		// Decode headers
		MimeHeaders headers = exchangeData.getRequestHeaders();

		// Set this every time in case limit has been changed via JMX
		headers.setLimit(processor.getProtocol().getMaxHeaderCount());

		boolean contentLengthSet = false;
		int hCount = requestHeaderMessage.getInt();
		for (int i = 0; i < hCount; i++) {
			String hName = null;

			// Header names are encoded as either an integer code starting
			// with 0xA0, or as a normal string (in which case the first
			// two bytes are the length).
			int isc = requestHeaderMessage.peekInt();
			int hId = isc & 0xFF;

			MessageBytes vMB = null;
			isc &= 0xFF00;
			if (0xA000 == isc) {
				requestHeaderMessage.getInt(); // To advance the read position
				hName = Constants.getHeaderForCode(hId - 1);
				vMB = headers.addValue(hName);
			} else {
				// reset hId -- if the header currently being read
				// happens to be 7 or 8 bytes long, the code below
				// will think it's the content-type header or the
				// content-length header - SC_REQ_CONTENT_TYPE=7,
				// SC_REQ_CONTENT_LENGTH=8 - leading to unexpected
				// behaviour. see bug 5861 for more information.
				hId = -1;
				requestHeaderMessage.getBytes(tmpMB);
				ByteChunk bc = tmpMB.getByteChunk();
				vMB = headers.addValue(bc.getBuffer(), bc.getStart(), bc.getLength());
			}

			requestHeaderMessage.getBytes(vMB);

			if (hId == Constants.SC_REQ_CONTENT_LENGTH || (hId == -1 && tmpMB.equalsIgnoreCase("Content-Length"))) {
				long cl = vMB.getLong();
				if (contentLengthSet) {
					exchangeData.setStatus(HttpServletResponse.SC_BAD_REQUEST);
					processor.setErrorState(ErrorState.CLOSE_CLEAN, null);
				} else {
					contentLengthSet = true;
					// Set the content-length header for the request
					exchangeData.setRequestContentLength(cl);
				}
			} else if (hId == Constants.SC_REQ_CONTENT_TYPE || (hId == -1 && tmpMB.equalsIgnoreCase("Content-Type"))) {
				// just read the content-type header, so set it
				ByteChunk bchunk = vMB.getByteChunk();
				exchangeData.getRequestContentType().setBytes(bchunk.getBytes(), bchunk.getOffset(),
						bchunk.getLength());
			}
		}

		// Decode extra attributes
		String secret = processor.getProtocol().getSecret();
		boolean secretPresentInRequest = false;
		byte attributeCode;
		while ((attributeCode = requestHeaderMessage.getByte()) != Constants.SC_A_ARE_DONE) {

			switch (attributeCode) {

			case Constants.SC_A_REQ_ATTRIBUTE:
				requestHeaderMessage.getBytes(tmpMB);
				String n = tmpMB.toString();
				requestHeaderMessage.getBytes(tmpMB);
				String v = tmpMB.toString();
				/*
				 * AJP13 misses to forward the local IP address and the remote port. Allow the
				 * AJP connector to add this info via private request attributes. We will accept
				 * the forwarded data and remove it from the public list of request attributes.
				 */
				if (n.equals(Constants.SC_A_REQ_LOCAL_ADDR)) {
					exchangeData.getLocalAddr().setString(v);
				} else if (n.equals(Constants.SC_A_REQ_REMOTE_PORT)) {
					try {
						exchangeData.setRemotePort(Integer.parseInt(v));
					} catch (NumberFormatException nfe) {
						// Ignore invalid value
					}
				} else if (n.equals(Constants.SC_A_SSL_PROTOCOL)) {
					exchangeData.setAttribute(SSLSupport.PROTOCOL_VERSION_KEY, v);
				} else if (n.equals("JK_LB_ACTIVATION")) {
					exchangeData.setAttribute(n, v);
				} else if (javaxAttributes.contains(n)) {
					exchangeData.setAttribute(n, v);
				} else if (iisTlsAttributes.contains(n)) {
					// Allow IIS TLS attributes
					exchangeData.setAttribute(n, v);
				} else {
					// All 'known' attributes will be processed by the previous
					// blocks. Any remaining attribute is an 'arbitrary' one.
					Pattern pattern = processor.getProtocol().getAllowedRequestAttributesPatternInternal();
					if (pattern != null && pattern.matcher(n).matches()) {
						exchangeData.setAttribute(n, v);
					} else {
						log.warn(sm.getString("ajpprocessor.unknownAttribute", n));
						exchangeData.setStatus(403);
						processor.setErrorState(ErrorState.CLOSE_CLEAN, null);
					}
				}
				break;

			case Constants.SC_A_CONTEXT:
				requestHeaderMessage.getBytes(tmpMB);
				// nothing
				break;

			case Constants.SC_A_SERVLET_PATH:
				requestHeaderMessage.getBytes(tmpMB);
				// nothing
				break;

			case Constants.SC_A_REMOTE_USER:
				boolean tomcatAuthorization = processor.getProtocol().getTomcatAuthorization();
				if (tomcatAuthorization || !processor.getProtocol().getTomcatAuthentication()) {
					// Implies tomcatAuthentication == false
					requestHeaderMessage.getBytes(exchangeData.getRemoteUser());
					exchangeData.setRemoteUserNeedsAuthorization(tomcatAuthorization);
				} else {
					// Ignore user information from reverse proxy
					requestHeaderMessage.getBytes(tmpMB);
				}
				break;

			case Constants.SC_A_AUTH_TYPE:
				if (processor.getProtocol().getTomcatAuthentication()) {
					// ignore server
					requestHeaderMessage.getBytes(tmpMB);
				} else {
					requestHeaderMessage.getBytes(exchangeData.getAuthType());
				}
				break;

			case Constants.SC_A_QUERY_STRING:
				requestHeaderMessage.getBytes(exchangeData.getQueryString());
				break;

			case Constants.SC_A_JVM_ROUTE:
				requestHeaderMessage.getBytes(tmpMB);
				// nothing
				break;

			case Constants.SC_A_SSL_CERT:
				// SSL certificate extraction is lazy, moved to JkCoyoteHandler
				requestHeaderMessage.getBytes(certificates);
				break;

			case Constants.SC_A_SSL_CIPHER:
				requestHeaderMessage.getBytes(tmpMB);
				exchangeData.setAttribute(SSLSupport.CIPHER_SUITE_KEY, tmpMB.toString());
				break;

			case Constants.SC_A_SSL_SESSION:
				requestHeaderMessage.getBytes(tmpMB);
				exchangeData.setAttribute(SSLSupport.SESSION_ID_KEY, tmpMB.toString());
				break;

			case Constants.SC_A_SSL_KEY_SIZE:
				exchangeData.setAttribute(SSLSupport.KEY_SIZE_KEY, Integer.valueOf(requestHeaderMessage.getInt()));
				break;

			case Constants.SC_A_STORED_METHOD:
				requestHeaderMessage.getBytes(exchangeData.getMethod());
				break;

			case Constants.SC_A_SECRET:
				requestHeaderMessage.getBytes(tmpMB);
				if (secret != null && secret.length() > 0) {
					secretPresentInRequest = true;
					if (!tmpMB.equals(secret)) {
						exchangeData.setStatus(403);
						processor.setErrorState(ErrorState.CLOSE_CLEAN, null);
					}
				}
				break;

			default:
				// Ignore unknown attribute for backward compatibility
				break;

			}

		}

		// Check if secret was submitted if required
		if (secret != null && secret.length() > 0 && !secretPresentInRequest) {
			exchangeData.setStatus(403);
			processor.setErrorState(ErrorState.CLOSE_CLEAN, null);
		}

		// Check for a full URI (including protocol://host:port/)
		ByteChunk uriBC = exchangeData.getRequestURI().getByteChunk();
		if (uriBC.startsWithIgnoreCase("http", 0)) {

			int pos = uriBC.indexOf("://", 0, 3, 4);
			int uriBCStart = uriBC.getStart();
			int slashPos = -1;
			if (pos != -1) {
				byte[] uriB = uriBC.getBytes();
				slashPos = uriBC.indexOf('/', pos + 3);
				if (slashPos == -1) {
					slashPos = uriBC.getLength();
					// Set URI as "/"
					exchangeData.getRequestURI().setBytes(uriB, uriBCStart + pos + 1, 1);
				} else {
					exchangeData.getRequestURI().setBytes(uriB, uriBCStart + slashPos, uriBC.getLength() - slashPos);
				}
				MessageBytes hostMB = headers.setValue("host");
				hostMB.setBytes(uriB, uriBCStart + pos + 3, slashPos - pos - 3);
			}

		}

		MessageBytes valueMB = exchangeData.getRequestHeaders().getValue("host");
		parseHost(valueMB);

	}

//	@Override
//	protected final boolean isReadyForWrite() {
//		return responseMsgPos == -1 && channel.isReadyForWrite();
//	}

	/**
	 * Read at least the specified amount of bytes, and place them in the input
	 * buffer. Note that if any data is available to read then this method will
	 * always block until at least the specified number of bytes have been read.
	 *
	 * @param buf   Buffer to read data into
	 * @param pos   Start position
	 * @param n     The minimum number of bytes to read
	 * @param block If there is no data available to read when this method is
	 *              called, should this call block until data becomes available?
	 * @return <code>true</code> if the requested number of bytes were read else
	 *         <code>false</code>
	 * @throws IOException
	 */
	private boolean read(byte[] buf, int pos, int n, boolean block) throws IOException {
		int read = ((SocketChannel) processor.getChannel()).read(block, buf, pos, n);
		if (read > 0 && read < n) {
			int left = n - read;
			int start = pos + read;
			while (left > 0) {
				read = ((SocketChannel) processor.getChannel()).read(true, buf, start, left);
				if (read == -1) {
					throw new EOFException();
				}
				left = left - read;
				start = start + read;
			}
		} else if (read == -1) {
			throw new EOFException();
		}

		return read > 0;
	}

	@Override
	public int getAvailable(Object param) {
		int available = available(Boolean.TRUE.equals(param));
		// exchangeData.setAvailable(available);
		return available;
	}

	// @Override
	protected final int available(boolean doRead) {
		if (endOfStream) {
			return 0;
		}
		if (empty && doRead) {
			try {
				refillBodyBuffer(false);
			} catch (IOException timeout) {
				// Not ideal. This will indicate that data is available
				// which should trigger a read which in turn will trigger
				// another IOException and that one can be thrown.
				return 1;
			}
		}
		if (empty) {
			return 0;
		} else {
			return bodyBytes.getByteChunk().getLength();
		}
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
		return endOfStream;
	}

	@Override
	public final void registerReadInterest() {
		((SocketChannel) processor.getChannel()).registerReadInterest();
	}

	@Override
	public boolean isTrailerFieldsReady() {
		// AJP does not support trailers so return true so app can request the
		// trailers and find out that there are none.
		return true;
	}

	/**
	 * Processors that populate request attributes directly (e.g. AJP) should
	 * over-ride this method and return {@code false}.
	 *
	 * @return {@code true} if the SocketWrapper should be used to populate the
	 *         request attributes, otherwise {@code false}.
	 */
	protected final boolean getPopulateRequestAttributesFromSocket() {
		// NO-OPs the attribute requests since they are pre-populated when
		// parsing the first AJP message.
		return false;
	}

	/**
	 * Populate the remote host request attribute. Processors (e.g. AJP) that
	 * populate this from an alternative source should override this method.
	 */
	protected final void populateRequestAttributeRemoteHost() {
		// Get remote host name using a DNS resolution
		if (exchangeData.getRemoteHost().isNull()) {
			try {
				exchangeData.getRemoteHost()
						.setString(InetAddress.getByName(exchangeData.getRemoteAddr().toString()).getHostName());
			} catch (IOException iex) {
				// Ignore
			}
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
	 * This implementation populates the server name from the local name provided by
	 * the AJP message.
	 */
	@Override
	protected void populateHost() {
		try {
			exchangeData.getServerName().duplicate(exchangeData.getLocalName());
		} catch (IOException e) {
			exchangeData.setStatus(400);
			processor.setErrorState(ErrorState.CLOSE_CLEAN, e);
		}
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * This implementation populates the server port from the local port provided by
	 * the AJP message.
	 */
	@Override
	protected void populatePort() {
		// No host information (HTTP/1.0)
		exchangeData.setServerPort(exchangeData.getLocalPort());
	}

	@Override
	public final void setRequestBody(ByteChunk body) {
		int length = body.getLength();
		bodyBytes.setBytes(body.getBytes(), body.getStart(), length);
		exchangeData.setRequestContentLength(length);
		first = false;
		empty = false;
		replay = true;
		endOfStream = false;
	}

	@Override
	public final void disableSwallowRequest() {
		/*
		 * NO-OP With AJP, Tomcat controls when the client sends request body data. At
		 * most there will be a single packet to read and that will be handled in
		 * finishResponse().
		 */
	}

	// @Override
	protected final void populateSslRequestAttributes() {
		if (!certificates.isNull()) {
			ByteChunk certData = certificates.getByteChunk();
			X509Certificate jsseCerts[] = null;
			ByteArrayInputStream bais = new ByteArrayInputStream(certData.getBytes(), certData.getStart(),
					certData.getLength());
			// Fill the elements.
			try {
				CertificateFactory cf;
				String clientCertProvider = processor.getProtocol().getClientCertProvider();
				if (clientCertProvider == null) {
					cf = CertificateFactory.getInstance("X.509");
				} else {
					cf = CertificateFactory.getInstance("X.509", clientCertProvider);
				}
				while (bais.available() > 0) {
					X509Certificate cert = (X509Certificate) cf.generateCertificate(bais);
					if (jsseCerts == null) {
						jsseCerts = new X509Certificate[1];
						jsseCerts[0] = cert;
					} else {
						X509Certificate[] temp = new X509Certificate[jsseCerts.length + 1];
						System.arraycopy(jsseCerts, 0, temp, 0, jsseCerts.length);
						temp[jsseCerts.length] = cert;
						jsseCerts = temp;
					}
				}
			} catch (java.security.cert.CertificateException e) {
				log.error(sm.getString("ajpprocessor.certs.fail"), e);
				return;
			} catch (NoSuchProviderException e) {
				log.error(sm.getString("ajpprocessor.certs.fail"), e);
				return;
			}
			exchangeData.setAttribute(SSLSupport.CERTIFICATE_KEY, jsseCerts);
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
	protected void sslReHandShake() throws IOException {
		// NO-OP
	}

	void recycle() {
		endOfStream = false;
		first = true;
		empty = true;
		replay = false;
		waitingForBodyMessage = false;
	}
}
