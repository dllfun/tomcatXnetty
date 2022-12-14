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
import java.nio.ByteBuffer;

import org.apache.coyote.AbstractProcessor;
import org.apache.coyote.ExchangeData;
import org.apache.coyote.ProcessorComponent;
import org.apache.coyote.http11.Constants;
import org.apache.coyote.http11.HttpOutputBuffer;
import org.apache.coyote.http11.OutputFilter;
import org.apache.tomcat.util.net.BufWrapper;

/**
 * Identity output filter.
 *
 * @author Remy Maucherat
 */
public class IdentityOutputFilter extends ProcessorComponent implements OutputFilter {

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
	protected HttpOutputBuffer next;

	public IdentityOutputFilter(AbstractProcessor processor) {
		super(processor);
	}
	// --------------------------------------------------- OutputBuffer Methods

	@Override
	public int getId() {
		return Constants.IDENTITY_FILTER;
	}

	@Override
	public void actived() {
		contentLength = processor.getExchangeData().getResponseContentLengthLong();
		remaining = contentLength;
	}

	@Override
	public int doWrite(BufWrapper chunk) throws IOException {

		int result = -1;

		if (contentLength >= 0) {
			if (remaining > 0) {
				result = chunk.getRemaining();
				if (result > remaining) {
					// The chunk is longer than the number of bytes remaining
					// in the body; changing the chunk length to the number
					// of bytes remaining
					chunk.setLimit(chunk.getPosition() + (int) remaining);
//					chunk = chunk.getSlice((int) remaining);
					result = (int) remaining;
					remaining = 0;
				} else {
					remaining = remaining - result;
				}
				next.doWrite(chunk);
			} else {
				// No more bytes left to be written : return -1 and clear the
				// buffer
				chunk.setPosition(0);
				chunk.setLimit(0);
				result = -1;
			}
		} else {
			// If no content length was set, just write the bytes
			result = chunk.getRemaining();
			next.doWrite(chunk);
			result -= chunk.getRemaining();
		}

		return result;

	}

	@Override
	public long getBytesWritten() {
		return next.getBytesWritten();
	}

	// --------------------------------------------------- OutputFilter Methods

//	@Override
//	public void setResponse(ExchangeData exchangeData) {

//	}

	@Override
	public void setNext(HttpOutputBuffer next) {
		this.next = next;
	}

	@Override
	public void flush() throws IOException {
		// No data buffered in this filter. Flush next buffer.
		next.flush();
	}

	@Override
	public boolean flush(boolean block) throws IOException {
		return next.flush(block);
	}

	@Override
	public void end() throws IOException {
		next.end();
	}

	@Override
	public void recycle() {
		contentLength = -1;
		remaining = 0;
	}
}
