package org.apache.coyote;

import org.apache.coyote.http11.Http11Processor;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.buf.ByteChunk;
import org.apache.tomcat.util.buf.MessageBytes;
import org.apache.tomcat.util.http.parser.Host;
import org.apache.tomcat.util.log.UserDataHelper;
import org.apache.tomcat.util.res.StringManager;

public abstract class RequestAction implements InputReader {

	private static final StringManager sm = StringManager.getManager(AbstractProcessor.class);

	private static final Log log = LogFactory.getLog(RequestAction.class);

	private final AbstractProcessor processor;

	private RequestData requestData;

	// Used to avoid useless B2C conversion on the host name.
	private char[] hostNameC = new char[0];

	public RequestAction(AbstractProcessor processor) {
		this.processor = processor;
		this.requestData = processor.requestData;
	}

	public abstract int getAvailable(Object param);

	public abstract boolean isReadyForRead();

	public abstract boolean isRequestBodyFullyRead();

	public abstract void registerReadInterest();

	public abstract boolean isTrailerFieldsReady();

	public abstract void setRequestBody(ByteChunk body);

	public abstract void disableSwallowRequest();

	public abstract void actionREQ_HOST_ADDR_ATTRIBUTE();

	public abstract void actionREQ_HOST_ATTRIBUTE();

	public abstract void actionREQ_REMOTEPORT_ATTRIBUTE();

	public abstract void actionREQ_LOCAL_NAME_ATTRIBUTE();

	public abstract void actionREQ_LOCAL_ADDR_ATTRIBUTE();

	public abstract void actionREQ_LOCALPORT_ATTRIBUTE();

	public abstract void actionREQ_SSL_ATTRIBUTE();

	public abstract void actionREQ_SSL_CERTIFICATE();

	/**
	 * Processors that populate request attributes directly (e.g. AJP) should
	 * over-ride this method and return {@code false}.
	 *
	 * @return {@code true} if the SocketWrapper should be used to populate the
	 *         request attributes, otherwise {@code false}.
	 */
	protected boolean getPopulateRequestAttributesFromSocket() {
		return true;
	}

	public void parseHost(MessageBytes valueMB) {
		if (valueMB == null || valueMB.isNull()) {
			populateHost();
			populatePort();
			return;
		} else if (valueMB.getLength() == 0) {
			// Empty Host header so set sever name to empty string
			requestData.serverName().setString("");
			populatePort();
			return;
		}

		ByteChunk valueBC = valueMB.getByteChunk();
		byte[] valueB = valueBC.getBytes();
		int valueL = valueBC.getLength();
		int valueS = valueBC.getStart();
		if (hostNameC.length < valueL) {
			hostNameC = new char[valueL];
		}

		try {
			// Validates the host name
			int colonPos = Host.parse(valueMB);

			// Extract the port information first, if any
			if (colonPos != -1) {
				int port = 0;
				for (int i = colonPos + 1; i < valueL; i++) {
					char c = (char) valueB[i + valueS];
					if (c < '0' || c > '9') {
						requestData.getResponseData().setStatus(400);
						processor.setErrorState(ErrorState.CLOSE_CLEAN, null);
						return;
					}
					port = port * 10 + c - '0';
				}
				requestData.setServerPort(port);

				// Only need to copy the host name up to the :
				valueL = colonPos;
			}

			// Extract the host name
			for (int i = 0; i < valueL; i++) {
				hostNameC[i] = (char) valueB[i + valueS];
			}
			requestData.serverName().setChars(hostNameC, 0, valueL);

		} catch (IllegalArgumentException e) {
			// IllegalArgumentException indicates that the host name is invalid
			UserDataHelper.Mode logMode = processor.userDataHelper.getNextMode();
			if (logMode != null) {
				String message = sm.getString("abstractProcessor.hostInvalid", valueMB.toString());
				switch (logMode) {
				case INFO_THEN_DEBUG:
					message += sm.getString("abstractProcessor.fallToDebug");
					//$FALL-THROUGH$
				case INFO:
					log.info(message, e);
					break;
				case DEBUG:
					log.debug(message, e);
				}
			}

			requestData.getResponseData().setStatus(400);
			processor.setErrorState(ErrorState.CLOSE_CLEAN, e);
		}
	}

	/**
	 * Called when a host header is not present in the request (e.g. HTTP/1.0). It
	 * populates the server name with appropriate information. The source is
	 * expected to vary by protocol.
	 * <p>
	 * The default implementation is a NO-OP.
	 */
	protected void populateHost() {
		// NO-OP
	}

	/**
	 * Called when a host header is not present or is empty in the request (e.g.
	 * HTTP/1.0). It populates the server port with appropriate information. The
	 * source is expected to vary by protocol.
	 * <p>
	 * The default implementation is a NO-OP.
	 */
	protected void populatePort() {
		// NO-OP
	}

}
