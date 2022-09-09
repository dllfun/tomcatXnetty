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

package org.apache.coyote.http11.filters;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.apache.coyote.AbstractProcessor;
import org.apache.coyote.ExchangeData;
import org.apache.coyote.InputReader;
import org.apache.coyote.ProcessorComponent;
import org.apache.coyote.http11.Constants;
import org.apache.coyote.http11.InputFilter;
import org.apache.tomcat.util.buf.ByteChunk;
import org.apache.tomcat.util.net.SocketChannel.BufWrapper;
import org.apache.tomcat.util.res.StringManager;

/**
 * Identity input filter.
 *
 * @author Remy Maucherat
 */
public class IdentityInputFilter extends ProcessorComponent implements InputFilter {

	private static final StringManager sm = StringManager.getManager(IdentityInputFilter.class.getPackage().getName());

	// -------------------------------------------------------------- Constants

	protected static final String ENCODING_NAME = "identity";
	protected static final ByteChunk ENCODING = new ByteChunk();

	// ----------------------------------------------------- Static Initializer

	static {
		ENCODING.setBytes(ENCODING_NAME.getBytes(StandardCharsets.ISO_8859_1), 0, ENCODING_NAME.length());
	}

	// ----------------------------------------------------- Instance Variables

	/**
	 * Content length.
	 */
	protected long contentLength = -1;

	/**
	 * Remaining bytes.
	 */
	protected long remaining = 0;

	/**
	 * Next buffer in the pipeline.
	 */
	protected InputReader next;

	/**
	 * ByteBuffer used to read leftover bytes.
	 */
	protected BufWrapper tempRead;

	private final int maxSwallowSize;

	public IdentityInputFilter(AbstractProcessor processor, int maxSwallowSize) {
		super(processor);
		this.maxSwallowSize = maxSwallowSize;
	}

	// ---------------------------------------------------- InputBuffer Methods
	/*
	 * @Override public int doRead(PreInputBuffer handler) throws IOException {
	 * 
	 * int result = -1;
	 * 
	 * if (contentLength >= 0) { if (remaining > 0) { int nRead =
	 * buffer.doRead(handler); if (nRead > remaining) { // The chunk is longer than
	 * the number of bytes remaining // in the body; changing the chunk length to
	 * the number // of bytes remaining
	 * handler.getBufWrapper().setLimit(handler.getBufWrapper().getPosition() +
	 * (int) remaining); result = (int) remaining; } else { result = nRead; } if
	 * (nRead > 0) { remaining = remaining - nRead; } } else { // No more bytes left
	 * to be read : return -1 and clear the // buffer if (handler.getBufWrapper() !=
	 * null) { handler.getBufWrapper().setPosition(0);
	 * handler.getBufWrapper().setLimit(0); } result = -1; } }
	 * 
	 * return result;
	 * 
	 * }
	 */

	// ---------------------------------------------------- InputFilter Methods

	@Override
	public int getId() {
		return Constants.IDENTITY_FILTER;
	}

	@Override
	public void actived() {
		contentLength = processor.getExchangeData().getRequestContentLengthLong();
		remaining = contentLength;
	}

	@Override
	public BufWrapper doRead() throws IOException {
		BufWrapper result = null;

		if (contentLength >= 0) {
			if (remaining > 0) {
				int nRead = -1;
				result = next.doRead();
				if (result != null) {
					nRead = result.getRemaining();
				}
				// int nRead = buffer.doRead(handler);
				if (nRead > remaining) {
					// The chunk is longer than the number of bytes remaining
					// in the body; changing the chunk length to the number
					// of bytes remaining
					result.setLimit(result.getPosition() + (int) remaining);
					// result = (int) remaining;
				} else {
					// result = nRead;
				}
				if (nRead > 0) {
					remaining = remaining - nRead;
				}
			} else {
				// No more bytes left to be read : return -1 and clear the
				// buffer
				// if (handler.getBufWrapper() != null) {
				// handler.getBufWrapper().setPosition(0);
				// handler.getBufWrapper().setLimit(0);
				// }
				result = null;
			}
		}

		return result;
	}

	/**
	 * Read the content length from the request.
	 */
//	@Override
//	public void setRequest(ExchangeData exchangeData) {

//	}

	@Override
	public long end() throws IOException {

		final boolean maxSwallowSizeExceeded = (maxSwallowSize > -1 && remaining > maxSwallowSize);
		long swallowed = 0;

		// Consume extra bytes.
		while (remaining > 0) {

			int nread = -1;// buffer.doRead(this);
			BufWrapper bufWrapper = next.doRead();
			if (bufWrapper != null) {
				nread = bufWrapper.getRemaining();
			}
			tempRead = null;
			if (nread > 0) {
				swallowed += nread;
				remaining = remaining - nread;
				if (maxSwallowSizeExceeded && swallowed > maxSwallowSize) {
					// Note: We do not fail early so the client has a chance to
					// read the response before the connection is closed. See:
					// https://httpd.apache.org/docs/2.0/misc/fin_wait_2.html#appendix
					throw new IOException(sm.getString("inputFilter.maxSwallow"));
				}
			} else { // errors are handled higher up.
				remaining = 0;
			}
		}

		// If too many bytes were read, return the amount.
		return -remaining;

	}

	/**
	 * Amount of bytes still available in a buffer.
	 */
	@Override
	public int available() {
		return 0;
	}

	/**
	 * Set the next buffer in the filter pipeline.
	 */
	@Override
	public void setNext(InputReader next) {
		this.next = next;
	}

	/**
	 * Make the filter ready to process the next request.
	 */
	@Override
	public void recycle() {
		contentLength = -1;
		remaining = 0;
	}

	/**
	 * Return the name of the associated encoding; Here, the value is "identity".
	 */
	@Override
	public ByteChunk getEncodingName() {
		return ENCODING;
	}

	@Override
	public boolean isFinished() {
		// Only finished if a content length is defined and there is no data
		// remaining
		return contentLength > -1 && remaining <= 0;
	}

}
