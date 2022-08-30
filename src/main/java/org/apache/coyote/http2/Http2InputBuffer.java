package org.apache.coyote.http2;

import java.io.IOException;

import org.apache.coyote.AbstractProcessor;
import org.apache.coyote.ErrorState;
import org.apache.coyote.ExchangeData;
import org.apache.coyote.RequestAction;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.buf.ByteChunk;
import org.apache.tomcat.util.net.SocketChannel.BufWrapper;
import org.apache.tomcat.util.res.StringManager;

public class Http2InputBuffer extends RequestAction {

	private static final Log log = LogFactory.getLog(Stream.class);
	private static final StringManager sm = StringManager.getManager(Stream.class);

	private AbstractProcessor processor;
//	private StreamChannel stream;
	// private StreamInputBuffer streamInputBuffer;
	private ExchangeData exchangeData;

	public Http2InputBuffer(AbstractProcessor processor) {
		super(processor);
		this.processor = processor;
//		this.stream = stream;
		this.exchangeData = processor.getExchangeData();
//		this.streamInputBuffer = stream.getInputBuffer();
	}

//	@Override
//	public BufWrapper doRead() throws IOException {
//		return streamInputBuffer.doRead();
//	}
	@Override
	protected BufWrapper doReadFromChannel() throws IOException {
		return ((StreamChannel) processor.getChannel()).doRead();
	}

	@Override
	public int getAvailable(Object param) {
		int available = getAvailableInFilters();
		if (available > 0)
			return available;
		return getAvailableInBuffer(param);
	}

	@Override
	public boolean isReadyForRead() {
		if (getAvailable(true) > 0) {
			return true;
		}

		if (!isRequestBodyFullyRead()) {
			return ((StreamChannel) processor.getChannel()).isReadyForRead();
		}

		return false;
	}

	@Override
	public boolean isRequestBodyFullyRead() {
		if (hasActiveFilters()) {
			return getLastActiveFilter().isFinished();
		} else {
			return ((StreamChannel) processor.getChannel()).isRequestBodyFullyRead();
		}
	}

	@Override
	public final void registerReadInterest() {
		// Should never be called for StreamProcessor as isReadyForRead() is
		// overridden
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean isTrailerFieldsReady() {
		return ((StreamChannel) processor.getChannel()).isTrailerFieldsReady();
	}

	@Override
	public final void setRequestBody(ByteChunk body) {
		((StreamChannel) processor.getChannel()).insertReplayedBody(body);// stream.getInputBuffer()
		try {
			((StreamChannel) processor.getChannel()).receivedEndOfStream();// stream
		} catch (ConnectionException e) {
			// Exception will not be thrown in this case
		}
	}

	@Override
	public final void disableSwallowRequest() {
		// NO-OP
		// HTTP/2 has to swallow any input received to ensure that the flow
		// control windows are correctly tracked.
	}

	/**
	 * Populate the remote host request attribute. Processors (e.g. AJP) that
	 * populate this from an alternative source should override this method.
	 */
	protected void populateRequestAttributeRemoteHost() {
		if (getPopulateRequestAttributesFromSocket()
				&& ((StreamChannel) processor.getChannel()).getSocketChannel() != null) {
			exchangeData.getRemoteHost()
					.setString(((StreamChannel) processor.getChannel()).getSocketChannel().getRemoteHost());
		}
	}

	@Override
	public void actionREQ_HOST_ADDR_ATTRIBUTE() {
		if (getPopulateRequestAttributesFromSocket()
				&& ((StreamChannel) processor.getChannel()).getSocketChannel() != null) {
			exchangeData.getRemoteAddr()
					.setString(((StreamChannel) processor.getChannel()).getSocketChannel().getRemoteAddr());
		}
	}

	@Override
	public void actionREQ_HOST_ATTRIBUTE() {
		populateRequestAttributeRemoteHost();
	}

	@Override
	public void actionREQ_LOCALPORT_ATTRIBUTE() {
		if (getPopulateRequestAttributesFromSocket()
				&& ((StreamChannel) processor.getChannel()).getSocketChannel() != null) {
			exchangeData.setLocalPort(((StreamChannel) processor.getChannel()).getSocketChannel().getLocalPort());
		}
	}

	@Override
	public void actionREQ_LOCAL_ADDR_ATTRIBUTE() {
		if (getPopulateRequestAttributesFromSocket()
				&& ((StreamChannel) processor.getChannel()).getSocketChannel() != null) {
			exchangeData.getLocalAddr()
					.setString(((StreamChannel) processor.getChannel()).getSocketChannel().getLocalAddr());
		}
	}

	@Override
	public void actionREQ_LOCAL_NAME_ATTRIBUTE() {
		if (getPopulateRequestAttributesFromSocket()
				&& ((StreamChannel) processor.getChannel()).getSocketChannel() != null) {
			exchangeData.getLocalName()
					.setString(((StreamChannel) processor.getChannel()).getSocketChannel().getLocalName());
		}
	}

	@Override
	public void actionREQ_REMOTEPORT_ATTRIBUTE() {
		if (getPopulateRequestAttributesFromSocket()
				&& ((StreamChannel) processor.getChannel()).getSocketChannel() != null) {
			exchangeData.setRemotePort(((StreamChannel) processor.getChannel()).getSocketChannel().getRemotePort());
		}
	}

	@Override
	public void actionREQ_SSL_ATTRIBUTE() {
		((StreamChannel) processor.getChannel()).populateSslRequestAttributes();
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

	// @Override
	public int getAvailableInBuffer(Object param) {
		int available = availableInBuffer(Boolean.TRUE.equals(param));
		// exchangeData.setAvailable(available);
		return available;
	}

	// @Override
	protected final int availableInBuffer(boolean doRead) {
		return ((StreamChannel) processor.getChannel()).available();// stream.getInputBuffer()
	}

	public void prepareRequest() {
		// TODO Auto-generated method stub

	}

	// @Override
	// protected final boolean isReadyForRead() {
	// return stream.getInputBuffer().isReadyForRead();
	// }

	// @Override
	// protected final boolean isRequestBodyFullyRead() {
	// return stream.getInputBuffer().isRequestBodyFullyRead();
	// }

	public void recycle() {

	}

}
