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
 * Void output filter, which silently swallows bytes written. Used with a 204
 * status (no content) or a HEAD request.
 *
 * @author Remy Maucherat
 */
public class VoidOutputFilter extends ProcessorComponent implements OutputFilter {

	private HttpOutputBuffer next = null;

	public VoidOutputFilter(AbstractProcessor processor) {
		super(processor);
	}

	// --------------------------------------------------- OutputBuffer Methods

	@Override
	public int doWrite(BufWrapper chunk) throws IOException {
		return chunk.getRemaining();
	}

	@Override
	public int getId() {
		return Constants.VOID_FILTER;
	}

	@Override
	public void actived() {

	}

	@Override
	public long getBytesWritten() {
		return 0;
	}

	// --------------------------------------------------- OutputFilter Methods

//	@Override
//	public void setResponse(ExchangeData exchangeData) {
	// NOOP: No need for parameters from response in this filter
//	}

	@Override
	public void setNext(HttpOutputBuffer next) {
		this.next = next;
	}

	@Override
	public void flush() throws IOException {
		this.next.flush();
	}

	@Override
	public boolean flush(boolean block) throws IOException {
		return this.next.flush(block);
	}

	@Override
	public void recycle() {
		next = null;
	}

	@Override
	public void end() throws IOException {
		next.end();
	}
}
