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
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.apache.coyote.InputReader;
import org.apache.coyote.RequestData;
import org.apache.coyote.http11.Constants;
import org.apache.coyote.http11.InputFilter;
import org.apache.tomcat.util.buf.ByteChunk;
import org.apache.tomcat.util.net.SocketChannel.BufWrapper;
import org.apache.tomcat.util.net.SocketWrapperBase.ByteBufferWrapper;

/**
 * Input filter responsible for reading and buffering the request body, so that
 * it does not interfere with client SSL handshake messages.
 */
public class BufferedInputFilter implements InputFilter {

	// -------------------------------------------------------------- Constants

	private static final String ENCODING_NAME = "buffered";
	private static final ByteChunk ENCODING = new ByteChunk();

	// ----------------------------------------------------- Instance Variables

	private ByteBuffer buffered;
	private BufWrapper tempRead;
	private InputReader buffer;
	private boolean hasRead = false;

	// ----------------------------------------------------- Static Initializer

	static {
		ENCODING.setBytes(ENCODING_NAME.getBytes(StandardCharsets.ISO_8859_1), 0, ENCODING_NAME.length());
	}

	// --------------------------------------------------------- Public Methods

	@Override
	public int getId() {
		return Constants.BUFFERED_FILTER;
	}

	/**
	 * Set the buffering limit. This should be reset every time the buffer is used.
	 *
	 * @param limit The maximum number of bytes that will be buffered
	 */
	public void setLimit(int limit) {
		if (buffered == null) {
			buffered = ByteBuffer.allocate(limit);
			buffered.flip();
		}
	}

	// ---------------------------------------------------- InputBuffer Methods

	/**
	 * Reads the request body and buffers it.
	 */
	@Override
	public void setRequest(RequestData request) {
		// save off the Request body
		try {
			while ((tempRead = buffer.doRead()) != null) {
				buffered.mark().position(buffered.limit()).limit(buffered.capacity());
				while (buffered.hasRemaining() && tempRead.getRemaining() > 0) {
					buffered.put(tempRead.getByte());
				}
				buffered.limit(buffered.position()).reset();
				tempRead = null;
			}
		} catch (IOException | BufferOverflowException ioe) {
			// No need for i18n - this isn't going to get logged anywhere
			throw new IllegalStateException("Request body too large for buffer");
		}
	}

	/**
	 * Fills the given ByteBuffer with the buffered request body.
	 */
	// @Override
	// public int doRead(PreInputBuffer handler) throws IOException {
	// if (isFinished()) {
	// return -1;
	// }

	// handler.setBufWrapper(ByteBufferWrapper.wrapper(buffered));
	// hasRead = true;
	// return buffered.remaining();
	// }

	@Override
	public BufWrapper doRead() throws IOException {
		if (isFinished()) {
			return null;
		}

		hasRead = true;
		return ByteBufferWrapper.wrapper(buffered);
	}

	@Override
	public void setBuffer(InputReader buffer) {
		this.buffer = buffer;
	}

	@Override
	public void recycle() {
		if (buffered != null) {
			if (buffered.capacity() > 65536) {
				buffered = null;
			} else {
				buffered.position(0).limit(0);
			}
		}
		hasRead = false;
		buffer = null;
	}

	@Override
	public ByteChunk getEncodingName() {
		return ENCODING;
	}

	@Override
	public long end() throws IOException {
		return 0;
	}

	@Override
	public int available() {
		return buffered.remaining();
	}

	@Override
	public boolean isFinished() {
		return hasRead || buffered.remaining() <= 0;
	}

}
