package org.apache.coyote;

import java.io.IOException;
import java.util.Arrays;

import org.apache.coyote.http11.Constants;
import org.apache.coyote.http11.InputFilter;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.buf.ByteChunk;
import org.apache.tomcat.util.buf.MessageBytes;
import org.apache.tomcat.util.http.parser.Host;
import org.apache.tomcat.util.log.UserDataHelper;
import org.apache.tomcat.util.net.SocketChannel.BufWrapper;
import org.apache.tomcat.util.res.StringManager;

public abstract class RequestAction implements InputReader {

	private static final StringManager sm = StringManager.getManager(AbstractProcessor.class);

	private static final Log log = LogFactory.getLog(RequestAction.class);

	private final AbstractProcessor processor;

	// private RequestData requestData;

	// Used to avoid useless B2C conversion on the host name.
	private char[] hostNameC = new char[0];

	/**
	 * Filter library. Note: Filter[Constants.CHUNKED_FILTER] is always the
	 * "chunked" filter.
	 */
	private InputFilter[] filterLibrary;

	/**
	 * Active filters (in order).
	 */
	private InputFilter[] activeFilters;

	/**
	 * Index of the last active filter.
	 */
	private int lastActiveFilter;

	/**
	 * Tracks how many internal filters are in the filter library so they are
	 * skipped when looking for pluggable filters.
	 */
	private int pluggableFilterIndex = Integer.MAX_VALUE;

	/**
	 * Swallow input ? (in the case of an expectation)
	 */
	private boolean swallowInput;

	private InputReader channelInputReader = new InputReader() {

		@Override
		public BufWrapper doRead() throws IOException {
			return doReadFromChannel();
		}
	};

	public RequestAction(AbstractProcessor processor) {
		this.processor = processor;
		// this.requestData = processor.requestData;
		filterLibrary = new InputFilter[0];
		activeFilters = new InputFilter[0];
		lastActiveFilter = -1;
		swallowInput = true;
	}

	/**
	 * Set the swallow input flag.
	 */
	public void setSwallowInput(boolean swallowInput) {
		this.swallowInput = swallowInput;
	}

	/**
	 * Add an input filter to the filter library.
	 *
	 * @throws NullPointerException if the supplied filter is null
	 */
	public final void addFilter(InputFilter filter) {

		if (filter == null) {
			throw new NullPointerException(sm.getString("iib.filter.npe"));
		}

		for (int i = 0; i < filterLibrary.length; i++) {
			if (filterLibrary[i].getId() == filter.getId()) {
				throw new IllegalArgumentException("id=" + filter.getId() + " already exist");
			}
			if (filterLibrary[i].getEncodingName() != null && filter.getEncodingName() != null) {
				if (filterLibrary[i].getEncodingName().toString().equals(filter.getEncodingName().toString())) {
					throw new IllegalArgumentException("encodingName=" + filter.getEncodingName() + " already exist");
				}
			}
		}

		InputFilter[] newFilterLibrary = Arrays.copyOf(filterLibrary, filterLibrary.length + 1);
		newFilterLibrary[filterLibrary.length] = filter;
		filterLibrary = newFilterLibrary;

		activeFilters = new InputFilter[filterLibrary.length];
	}

	public final InputFilter getFilterById(int id) {
		for (int i = 0; i < filterLibrary.length; i++) {
			if (filterLibrary[i].getId() == id) {
				return filterLibrary[i];
			}
		}
		return null;
	}

	public final InputFilter getFilterByEncodingName(String encodingName) {
		for (int i = pluggableFilterIndex; i < filterLibrary.length; i++) {
			if (filterLibrary[i].getEncodingName() != null && encodingName != null) {
				if (filterLibrary[i].getEncodingName().toString().equals(encodingName)) {
					return filterLibrary[i];
				}
			}
		}
		return null;
	}

	protected final void markPluggableFilterIndex() {
		pluggableFilterIndex = this.getFilters().length;
	}

	/**
	 * Get filters.
	 */
	private InputFilter[] getFilters() {
		return filterLibrary;
	}

//	public int getLastActiveFilter() {
//		return lastActiveFilter;
//	}
	/**
	 * Add an input filter to the filter library.
	 */
	public final void addActiveFilter(int id) {

		InputFilter filter = null;
		for (int i = 0; i < filterLibrary.length; i++) {
			if (filterLibrary[i].getId() == id) {
				filter = filterLibrary[i];
			}
		}

		if (lastActiveFilter == -1) {
			filter.setNext(channelInputReader);
		} else {
			for (int i = 0; i <= lastActiveFilter; i++) {
				if (activeFilters[i] == filter)
					return;
			}
			filter.setNext(activeFilters[lastActiveFilter]);
		}

		activeFilters[++lastActiveFilter] = filter;

		filter.actived(); // filter.setRequest(processor.exchangeData);
	}

	public final boolean hasActiveFilters() {
		return lastActiveFilter != -1;
	}

	public final InputFilter getLastActiveFilter() {
		if (lastActiveFilter >= 0) {
			return activeFilters[lastActiveFilter];
		}
		return null;
	}

	public final int getActiveFiltersCount() {
		return lastActiveFilter + 1;
	}

	public final InputFilter getActiveFilter(int index) {
		if (lastActiveFilter >= 0 && index <= lastActiveFilter) {
			return activeFilters[index];
		}
		return null;
	}

	protected boolean isSwallowInput() {
		return swallowInput;
	}

//	protected abstract InputReader getBaseInputReader();

	@Override
	public final BufWrapper doRead() throws IOException {
		if (lastActiveFilter == -1)
			return channelInputReader.doRead();
		else
			return activeFilters[lastActiveFilter].doRead();
	}

	protected abstract BufWrapper doReadFromChannel() throws IOException;

	protected final boolean isChunking() {
		for (int i = 0; i < lastActiveFilter; i++) {
			if (activeFilters[i] == filterLibrary[Constants.CHUNKED_FILTER]) {
				return true;
			}
		}
		return false;
	}

	protected final int swallowInput() throws IOException {
		int extraBytes = 0;
		if (swallowInput && (lastActiveFilter != -1)) {
			extraBytes = (int) activeFilters[lastActiveFilter].end();
		}
		return extraBytes;
	}

	protected final int getAvailableInFilters() {
		int available = 0;
		if ((hasActiveFilters())) {
			for (int i = 0; (available == 0) && (i < getActiveFiltersCount()); i++) {
				if (getActiveFilter(i) == null) {
					System.out.println();
				}
				available = getActiveFilter(i).available();
			}
		}
		return available;
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
			processor.exchangeData.getServerName().setString("");
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
						processor.exchangeData.setStatus(400);
						processor.setErrorState(ErrorState.CLOSE_CLEAN, null);
						return;
					}
					port = port * 10 + c - '0';
				}
				processor.exchangeData.setServerPort(port);

				// Only need to copy the host name up to the :
				valueL = colonPos;
			}

			// Extract the host name
			for (int i = 0; i < valueL; i++) {
				hostNameC[i] = (char) valueB[i + valueS];
			}
			processor.exchangeData.getServerName().setChars(hostNameC, 0, valueL);

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

			processor.exchangeData.setStatus(400);
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

	protected void resetFilters() {
		for (int i = 0; i <= lastActiveFilter; i++) {
			activeFilters[i].recycle();
		}

		lastActiveFilter = -1;
		swallowInput = true;
	}

}
