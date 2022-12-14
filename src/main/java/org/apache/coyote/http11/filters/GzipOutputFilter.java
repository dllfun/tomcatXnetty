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
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.zip.GZIPOutputStream;

import org.apache.coyote.AbstractProcessor;
import org.apache.coyote.ExchangeData;
import org.apache.coyote.ProcessorComponent;
import org.apache.coyote.http11.Constants;
import org.apache.coyote.http11.HttpOutputBuffer;
import org.apache.coyote.http11.OutputFilter;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.net.BufWrapper;
import org.apache.tomcat.util.net.SocketWrapperBase.ByteBufferWrapper;

/**
 * Gzip output filter.
 *
 * @author Remy Maucherat
 */
public class GzipOutputFilter extends ProcessorComponent implements OutputFilter {

	protected static final Log log = LogFactory.getLog(GzipOutputFilter.class);

	// ----------------------------------------------------- Instance Variables

	/**
	 * Next buffer in the pipeline.
	 */
	protected HttpOutputBuffer next;

	/**
	 * Compression output stream.
	 */
	protected GZIPOutputStream compressionStream = null;

	/**
	 * Fake internal output stream.
	 */
	protected final OutputStream fakeOutputStream = new FakeOutputStream();

	public GzipOutputFilter(AbstractProcessor processor) {
		super(processor);
	}

	// --------------------------------------------------- OutputBuffer Methods

	@Override
	public int getId() {
		return Constants.GZIP_FILTER;
	}

	@Override
	public void actived() {

	}

	@Override
	public int doWrite(BufWrapper chunk) throws IOException {
		if (compressionStream == null) {
			compressionStream = new GZIPOutputStream(fakeOutputStream, true);
		}
		int len = chunk.getRemaining();
		if (chunk.hasArray()) {
			compressionStream.write(chunk.getArray(), chunk.getArrayOffset() + chunk.getPosition(), len);
		} else {
			byte[] bytes = new byte[len];
			chunk.putBytes(bytes);
			compressionStream.write(bytes, 0, len);
		}
		return len;
	}

	@Override
	public long getBytesWritten() {
		return next.getBytesWritten();
	}

	// --------------------------------------------------- OutputFilter Methods

	/**
	 * Added to allow flushing to happen for the gzip'ed outputstream
	 */
	@Override
	public void flush() throws IOException {
		if (compressionStream != null) {
			try {
				if (log.isDebugEnabled()) {
					log.debug("Flushing the compression stream!");
				}
				compressionStream.flush();
			} catch (IOException e) {
				if (log.isDebugEnabled()) {
					log.debug("Ignored exception while flushing gzip filter", e);
				}
			}
		}
		next.flush();
	}

	@Override
	public boolean flush(boolean block) throws IOException {
		if (block) {
			flush();
			return false;
		} else {
			return next.flush(block);
		}
	}

//	@Override
//	public void setResponse(ExchangeData exchangeData) {
	// NOOP: No need for parameters from response in this filter
//	}

	@Override
	public void setNext(HttpOutputBuffer next) {
		this.next = next;
	}

	@Override
	public void end() throws IOException {
		if (compressionStream == null) {
			compressionStream = new GZIPOutputStream(fakeOutputStream, true);
		}
		compressionStream.finish();
		compressionStream.close();
		next.end();
	}

	/**
	 * Make the filter ready to process the next request.
	 */
	@Override
	public void recycle() {
		// Set compression stream to null
		compressionStream = null;
	}

	// ------------------------------------------- FakeOutputStream Inner Class

	protected class FakeOutputStream extends OutputStream {
		protected final BufWrapper outputChunk = ByteBufferWrapper.wrapper(ByteBuffer.allocate(1), false);

		@Override
		public void write(int b) throws IOException {
			// Shouldn't get used for good performance, but is needed for
			// compatibility with Sun JDK 1.4.0
			outputChunk.switchToWriteMode();
			outputChunk.setByte(0, (byte) (b & 0xff));
			outputChunk.switchToReadMode();
			next.doWrite(outputChunk);
		}

		@Override
		public void write(byte[] b, int off, int len) throws IOException {
			next.doWrite(ByteBufferWrapper.wrapper(ByteBuffer.wrap(b, off, len), true));
		}

		@Override
		public void flush() throws IOException {
			/* NOOP */}

		@Override
		public void close() throws IOException {
			/* NOOP */}
	}

}
