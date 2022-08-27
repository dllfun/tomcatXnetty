package org.apache.coyote.http2;

import java.io.IOException;

import org.apache.coyote.AbstractProcessor;
import org.apache.coyote.ErrorState;
import org.apache.coyote.InputReader;
import org.apache.coyote.RequestAction;
import org.apache.coyote.RequestData;
import org.apache.coyote.http2.Stream.StreamInputBuffer;
import org.apache.tomcat.util.buf.ByteChunk;

public class Http2InputBuffer extends RequestAction {

	private AbstractProcessor processor;
	private Stream stream;
	private StreamInputBuffer streamInputBuffer;
	private RequestData requestData;

	public Http2InputBuffer(AbstractProcessor processor, Stream stream, RequestData requestData,
			StreamInputBuffer streamInputBuffer) {
		super(processor);
		this.processor = processor;
		this.stream = stream;
		this.requestData = requestData;
		this.streamInputBuffer = streamInputBuffer;
	}

	@Override
	protected InputReader getBaseInputReader() {
		return streamInputBuffer;
	}

//	@Override
//	public BufWrapper doRead() throws IOException {
//		return streamInputBuffer.doRead();
//	}

	@Override
	public int getAvailable(Object param) {
		int available = getAvailableInFilters();
		if (available > 0)
			return available;
		return streamInputBuffer.getAvailable(param);
	}

	@Override
	public boolean isReadyForRead() {
		if (getAvailable(true) > 0) {
			return true;
		}
		return streamInputBuffer.isReadyForRead();
	}

	@Override
	public boolean isRequestBodyFullyRead() {
		if (hasActiveFilters()) {
			return getLastActiveFilter().isFinished();
		} else {
			return streamInputBuffer.isRequestBodyFullyRead();
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
		return stream.isTrailerFieldsReady();
	}

	@Override
	public final void setRequestBody(ByteChunk body) {
		streamInputBuffer.insertReplayedBody(body);// stream.getInputBuffer()
		try {
			stream.receivedEndOfStream();// stream
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
		if (getPopulateRequestAttributesFromSocket() && stream.getSocketChannel() != null) {
			requestData.remoteHost().setString(stream.getSocketChannel().getRemoteHost());
		}
	}

	@Override
	public void actionREQ_HOST_ADDR_ATTRIBUTE() {
		if (getPopulateRequestAttributesFromSocket() && stream.getSocketChannel() != null) {
			requestData.remoteAddr().setString(stream.getSocketChannel().getRemoteAddr());
		}
	}

	@Override
	public void actionREQ_HOST_ATTRIBUTE() {
		populateRequestAttributeRemoteHost();
	}

	@Override
	public void actionREQ_LOCALPORT_ATTRIBUTE() {
		if (getPopulateRequestAttributesFromSocket() && stream.getSocketChannel() != null) {
			requestData.setLocalPort(stream.getSocketChannel().getLocalPort());
		}
	}

	@Override
	public void actionREQ_LOCAL_ADDR_ATTRIBUTE() {
		if (getPopulateRequestAttributesFromSocket() && stream.getSocketChannel() != null) {
			requestData.localAddr().setString(stream.getSocketChannel().getLocalAddr());
		}
	}

	@Override
	public void actionREQ_LOCAL_NAME_ATTRIBUTE() {
		if (getPopulateRequestAttributesFromSocket() && stream.getSocketChannel() != null) {
			requestData.localName().setString(stream.getSocketChannel().getLocalName());
		}
	}

	@Override
	public void actionREQ_REMOTEPORT_ATTRIBUTE() {
		if (getPopulateRequestAttributesFromSocket() && stream.getSocketChannel() != null) {
			requestData.setRemotePort(stream.getSocketChannel().getRemotePort());
		}
	}

	@Override
	public void actionREQ_SSL_ATTRIBUTE() {
		streamInputBuffer.populateSslRequestAttributes();
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

}
