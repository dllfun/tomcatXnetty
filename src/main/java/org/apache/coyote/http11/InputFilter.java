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

import org.apache.coyote.InputReader;
import org.apache.tomcat.util.buf.ByteChunk;

/**
 * Input filter interface.
 *
 * @author Remy Maucherat
 */
public interface InputFilter extends InputReader {

	public int getId();

	public void actived();

	/**
	 * Some filters need additional parameters from the request.
	 *
	 * @param request The request to be associated with this filter
	 */
	// public void setProcessor(AbstractProcessor processor);

	/**
	 * Make the filter ready to process the next request.
	 */
	public void recycle();

	/**
	 * Get the name of the encoding handled by this filter.
	 *
	 * @return The encoding name as a byte chunk to facilitate comparison with the
	 *         value read from the HTTP headers which will also be a ByteChunk
	 */
	public ByteChunk getEncodingName();

	/**
	 * Set the next buffer in the filter pipeline.
	 *
	 * @param buffer The next buffer
	 */
	public void setNext(InputReader next);

	/**
	 * End the current request.
	 *
	 * @return 0 is the expected return value. A positive value indicates that too
	 *         many bytes were read. This method is allowed to use buffer.doRead to
	 *         consume extra bytes. The result of this method can't be negative (if
	 *         an error happens, an IOException should be thrown instead).
	 *
	 * @throws IOException If an error happens
	 */
	public long end() throws IOException;

	/**
	 * Amount of bytes still available in a buffer.
	 *
	 * @return The number of bytes in the buffer
	 */
	public int available();

	/**
	 * Has the request body been read fully?
	 *
	 * @return {@code true} if the request body has been fully read, otherwise
	 *         {@code false}
	 */
	public boolean isFinished();
}
