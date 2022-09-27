/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.catalina.connector;

import java.io.IOException;
import java.io.Reader;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.security.AccessController;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.servlet.ReadListener;

import org.apache.catalina.security.SecurityUtil;
import org.apache.coyote.ContainerThreadMarker;
import org.apache.coyote.Request;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.buf.B2CConverter;
import org.apache.tomcat.util.buf.ByteChunk;
import org.apache.tomcat.util.collections.SynchronizedStack;
import org.apache.tomcat.util.net.BufWrapper;
import org.apache.tomcat.util.res.StringManager;

/**
 * The buffer used by Tomcat request. This is a derivative of the Tomcat 3.3
 * OutputBuffer, adapted to handle input instead of output. This allows complete
 * recycling of the facade objects (the ServletInputStream and the
 * BufferedReader).
 *
 * @author Remy Maucherat
 */
public class InputBuffer extends Reader implements ByteChunk.ByteInputChannel {

	/**
	 * The string manager for this package.
	 */
	protected static final StringManager sm = StringManager.getManager(InputBuffer.class);

	private static final Log log = LogFactory.getLog(InputBuffer.class);

	public static final int DEFAULT_BUFFER_SIZE = 8 * 1024;

	// The buffer can be used for byte[] and char[] reading
	// ( this is needed to support ServletInputStream and BufferedReader )
	public final int INITIAL_STATE = 0;
	public final int CHAR_STATE = 1;
	public final int BYTE_STATE = 2;

	/**
	 * Encoder cache.
	 */
	private static final Map<Charset, SynchronizedStack<B2CConverter>> encoders = new ConcurrentHashMap<>();

	// ----------------------------------------------------- Instance Variables

	/**
	 * The byte buffer.
	 */
	private BufWrapper bufWrapper;

	/**
	 * The char buffer.
	 */
	private CharBuffer charBuffer;

	/**
	 * State of the output buffer.
	 */
	private int state = 0;

	/**
	 * Flag which indicates if the input buffer is closed.
	 */
	private boolean closed = false;

	/**
	 * Current byte to char converter.
	 */
	protected B2CConverter conv;

	/**
	 * Associated Coyote request.
	 */
	private Request coyoteRequest;

	/**
	 * Buffer position.
	 */
	private int markPos = -1;

	/**
	 * Char buffer limit.
	 */
	private int readLimit;

	/**
	 * Buffer size.
	 */
	private final int size;

	// ----------------------------------------------------------- Constructors

	/**
	 * Default constructor. Allocate the buffer with the default buffer size.
	 */
	public InputBuffer() {

		this(DEFAULT_BUFFER_SIZE);

	}

	/**
	 * Alternate constructor which allows specifying the initial buffer size.
	 *
	 * @param size Buffer size to use
	 */
	public InputBuffer(int size) {

		this.size = size;
		// bufWrapper = ByteBuffer.allocate(size);
		// clear(bufWrapper);
		charBuffer = CharBuffer.allocate(size);
		clear(charBuffer);
		readLimit = size;

	}

	// ------------------------------------------------------------- Properties

	/**
	 * Associated Coyote request.
	 *
	 * @param coyoteRequest Associated Coyote request
	 */
	public void setRequest(Request coyoteRequest) {
		this.coyoteRequest = coyoteRequest;
	}

	// --------------------------------------------------------- Public Methods

	/**
	 * Recycle the output buffer.
	 */
	public void recycle() {

		state = INITIAL_STATE;

		// If usage of mark made the buffer too big, reallocate it
		if (charBuffer.capacity() > size) {
			charBuffer = CharBuffer.allocate(size);
			clear(charBuffer);
		} else {
			clear(charBuffer);
		}
		readLimit = size;
		markPos = -1;
		if (bufWrapper != null) {
			// bufWrapper.reset();
			bufWrapper = null;
		}
		closed = false;

		if (conv != null) {
			conv.recycle();
			encoders.get(conv.getCharset()).push(conv);
			conv = null;
		}
	}

	/**
	 * Close the input buffer.
	 *
	 * @throws IOException An underlying IOException occurred
	 */
	@Override
	public void close() throws IOException {
		closed = true;
	}

	public int available() {
		int available = availableInThisBuffer();
		if (available == 0) {
			int coyoteAvailable = coyoteRequest.available(Boolean.valueOf(coyoteRequest.getReadListener() != null));
			available = (coyoteAvailable > 0) ? 1 : 0;
		}
		return available;
	}

	private int availableInThisBuffer() {
		int available = 0;
		if (state == BYTE_STATE) {
			available = bufWrapper.getRemaining();
		} else if (state == CHAR_STATE) {
			available = charBuffer.remaining();
		}
		return available;
	}

	public void setReadListener(ReadListener listener) {
		coyoteRequest.setReadListener(listener);

		// The container is responsible for the first call to
		// listener.onDataAvailable(). If isReady() returns true, the container
		// needs to call listener.onDataAvailable() from a new thread. If
		// isReady() returns false, the socket will be registered for read and
		// the container will call listener.onDataAvailable() once data arrives.
		// Must call isFinished() first as a call to isReady() if the request
		// has been finished will register the socket for read interest and that
		// is not required.
		if (!coyoteRequest.isFinished() && isReady()) {
			coyoteRequest.dispatchRead();
			if (!ContainerThreadMarker.isContainerThread()) {
				// Not on a container thread so need to execute the dispatch
				coyoteRequest.dispatchExecute();
			}
		}
	}

	public boolean isFinished() {
		int available = 0;
		if (state == BYTE_STATE) {
			available = bufWrapper.getRemaining();
		} else if (state == CHAR_STATE) {
			available = charBuffer.remaining();
		}
		if (available > 0) {
			return false;
		} else {
			return coyoteRequest.isFinished();
		}
	}

	public boolean isReady() {
		if (coyoteRequest.getReadListener() == null) {
			if (log.isDebugEnabled()) {
				log.debug(sm.getString("inputBuffer.requiresNonBlocking"));
			}
			return false;
		}
		if (isFinished()) {
			// If this is a non-container thread, need to trigger a read
			// which will eventually lead to a call to onAllDataRead() via a
			// container thread.
			if (!ContainerThreadMarker.isContainerThread()) {
				coyoteRequest.dispatchRead();
				coyoteRequest.dispatchExecute();
			}
			return false;
		}
		// Checking for available data at the network level and registering for
		// read can be done sequentially for HTTP/1.x and AJP as there is only
		// ever a single thread processing the socket at any one time. However,
		// for HTTP/2 there is one thread processing the connection and separate
		// threads for each stream. For HTTP/2 the two operations have to be
		// performed atomically else it is possible for the connection thread to
		// read more data in to the buffer after the stream thread checks for
		// available network data but before it registers for read.
		if (availableInThisBuffer() > 0) {
			return true;
		}

		AtomicBoolean result = new AtomicBoolean();
		coyoteRequest.actionNB_READ_INTEREST(result);
		return result.get();
	}

	boolean isBlocking() {
		return coyoteRequest.getReadListener() == null;
	}

	// ------------------------------------------------- Bytes Handling Methods

	/**
	 * Reads new bytes in the byte chunk.
	 *
	 * @throws IOException An underlying IOException occurred
	 */
	@Override
	public BufWrapper realReadBytes() throws IOException {
		if (closed) {
			return null;
		}
		if (coyoteRequest == null) {
			return null;
		}

		if (state == INITIAL_STATE) {
			state = BYTE_STATE;
		}

		try {
			bufWrapper = coyoteRequest.doRead();
			return bufWrapper;
		} catch (IOException ioe) {
			// An IOException on a read is almost always due to
			// the remote client aborting the request.
			throw new ClientAbortException(ioe);
		}
	}

	public int readByte() throws IOException {
		if (closed) {
			throw new IOException(sm.getString("inputBuffer.streamClosed"));
		}

		if (checkByteBufferEof()) {
			return -1;
		}
		return bufWrapper.getByte() & 0xFF;
	}

	public int read(byte[] b, int off, int len) throws IOException {
		if (closed) {
			throw new IOException(sm.getString("inputBuffer.streamClosed"));
		}

		if (checkByteBufferEof()) {
			return -1;
		}
		int n = Math.min(len, bufWrapper.getRemaining());
		bufWrapper.getBytes(b, off, n);
		return n;
	}

	/**
	 * Transfers bytes from the buffer to the specified ByteBuffer. After the
	 * operation the position of the ByteBuffer will be returned to the one before
	 * the operation, the limit will be the position incremented by the number of
	 * the transferred bytes.
	 *
	 * @param to the ByteBuffer into which bytes are to be written.
	 * @return an integer specifying the actual number of bytes read, or -1 if the
	 *         end of the stream is reached
	 * @throws IOException if an input or output exception has occurred
	 */
	public int read(ByteBuffer to) throws IOException {
		if (closed) {
			throw new IOException(sm.getString("inputBuffer.streamClosed"));
		}

		if (checkByteBufferEof()) {
			return -1;
		}
		int n = Math.min(to.remaining(), bufWrapper.getRemaining());
//		int orgLimit = bufWrapper.getLimit();
//		bufWrapper.setLimit(bufWrapper.getPosition() + n);
		BufWrapper slice = bufWrapper.getSlice(n);
		to.put(slice.nioBuffer());
//		bufWrapper.setLimit(orgLimit);
		to.limit(to.position()).position(to.position() - n);
		return n;
	}

	// ------------------------------------------------- Chars Handling Methods

	public int realReadChars() throws IOException {
		checkConverter();

		boolean eof = false;

		if (bufWrapper == null || bufWrapper.getRemaining() <= 0) {
			int nRead = -1;
			bufWrapper = realReadBytes();
			if (bufWrapper != null) {
				nRead = bufWrapper.getRemaining();
			}
			// int nRead = realReadBytes();
			if (nRead < 0) {
				eof = true;
			}
		}

		if (bufWrapper != null && bufWrapper.getRemaining() > 0) {
			if (markPos == -1) {
				clear(charBuffer);
			} else {
				// Make sure there's enough space in the worst case
				makeSpace(bufWrapper.getRemaining());
				if ((charBuffer.capacity() - charBuffer.limit()) == 0 && bufWrapper.getRemaining() != 0) {
					// We went over the limit
					clear(charBuffer);
					markPos = -1;
				}
			}

			state = CHAR_STATE;
			conv.convert(bufWrapper, charBuffer, this, eof);
		}

		if (charBuffer.remaining() == 0 && eof) {
			return -1;
		} else {
			return charBuffer.remaining();
		}
	}

	@Override
	public int read() throws IOException {

		if (closed) {
			throw new IOException(sm.getString("inputBuffer.streamClosed"));
		}

		if (checkCharBufferEof()) {
			return -1;
		}
		return charBuffer.get();
	}

	@Override
	public int read(char[] cbuf) throws IOException {

		if (closed) {
			throw new IOException(sm.getString("inputBuffer.streamClosed"));
		}

		return read(cbuf, 0, cbuf.length);
	}

	@Override
	public int read(char[] cbuf, int off, int len) throws IOException {

		if (closed) {
			throw new IOException(sm.getString("inputBuffer.streamClosed"));
		}

		if (checkCharBufferEof()) {
			return -1;
		}
		int n = Math.min(len, charBuffer.remaining());
		charBuffer.get(cbuf, off, n);
		return n;
	}

	@Override
	public long skip(long n) throws IOException {
		if (closed) {
			throw new IOException(sm.getString("inputBuffer.streamClosed"));
		}

		if (n < 0) {
			throw new IllegalArgumentException();
		}

		long nRead = 0;
		while (nRead < n) {
			if (charBuffer.remaining() >= n) {
				charBuffer.position(charBuffer.position() + (int) n);
				nRead = n;
			} else {
				nRead += charBuffer.remaining();
				charBuffer.position(charBuffer.limit());
				int nb = realReadChars();
				if (nb < 0) {
					break;
				}
			}
		}
		return nRead;
	}

	@Override
	public boolean ready() throws IOException {
		if (closed) {
			throw new IOException(sm.getString("inputBuffer.streamClosed"));
		}
		if (state == INITIAL_STATE) {
			state = CHAR_STATE;
		}
		return (available() > 0);
	}

	@Override
	public boolean markSupported() {
		return true;
	}

	@Override
	public void mark(int readAheadLimit) throws IOException {

		if (closed) {
			throw new IOException(sm.getString("inputBuffer.streamClosed"));
		}

		if (charBuffer.remaining() <= 0) {
			clear(charBuffer);
		} else {
			if ((charBuffer.capacity() > (2 * size)) && (charBuffer.remaining()) < (charBuffer.position())) {
				charBuffer.compact();
				charBuffer.flip();
			}
		}
		readLimit = charBuffer.position() + readAheadLimit + size;
		markPos = charBuffer.position();
	}

	@Override
	public void reset() throws IOException {

		if (closed) {
			throw new IOException(sm.getString("inputBuffer.streamClosed"));
		}

		if (state == CHAR_STATE) {
			if (markPos < 0) {
				clear(charBuffer);
				markPos = -1;
				throw new IOException();
			} else {
				charBuffer.position(markPos);
			}
		} else {
			bufWrapper.reset();
		}
	}

	public void checkConverter() throws IOException {
		if (conv != null) {
			return;
		}

		Charset charset = null;
		if (coyoteRequest != null) {
			charset = coyoteRequest.getCharset();
		}

		if (charset == null) {
			charset = org.apache.coyote.Constants.DEFAULT_BODY_CHARSET;
		}

		SynchronizedStack<B2CConverter> stack = encoders.get(charset);
		if (stack == null) {
			stack = new SynchronizedStack<>();
			encoders.putIfAbsent(charset, stack);
			stack = encoders.get(charset);
		}
		conv = stack.pop();

		if (conv == null) {
			conv = createConverter(charset);
		}
	}

	private static B2CConverter createConverter(Charset charset) throws IOException {
		if (SecurityUtil.isPackageProtectionEnabled()) {
			try {
				return AccessController.doPrivileged(new PrivilegedCreateConverter(charset));
			} catch (PrivilegedActionException ex) {
				Exception e = ex.getException();
				if (e instanceof IOException) {
					throw (IOException) e;
				} else {
					throw new IOException(e);
				}
			}
		} else {
			return new B2CConverter(charset);
		}

	}

	private boolean checkByteBufferEof() throws IOException {
		if (bufWrapper == null || bufWrapper.getRemaining() == 0) {
			int n = -1;// realReadBytes();
			bufWrapper = realReadBytes();
			if (bufWrapper != null) {
				n = bufWrapper.getRemaining();
			}
			if (n < 0) {
				return true;
			}
		}
		return false;
	}

	private boolean checkCharBufferEof() throws IOException {
		if (charBuffer.remaining() == 0) {
			int n = realReadChars();
			if (n < 0) {
				return true;
			}
		}
		return false;
	}

	private void clear(Buffer buffer) {
		buffer.rewind().limit(0);
	}

	private void makeSpace(int count) {
		int desiredSize = charBuffer.limit() + count;
		if (desiredSize > readLimit) {
			desiredSize = readLimit;
		}

		if (desiredSize <= charBuffer.capacity()) {
			return;
		}

		int newSize = 2 * charBuffer.capacity();
		if (desiredSize >= newSize) {
			newSize = 2 * charBuffer.capacity() + count;
		}

		if (newSize > readLimit) {
			newSize = readLimit;
		}

		CharBuffer tmp = CharBuffer.allocate(newSize);
		int oldPosition = charBuffer.position();
		charBuffer.position(0);
		tmp.put(charBuffer);
		tmp.flip();
		tmp.position(oldPosition);
		charBuffer = tmp;
		tmp = null;
	}

	private static class PrivilegedCreateConverter implements PrivilegedExceptionAction<B2CConverter> {

		private final Charset charset;

		public PrivilegedCreateConverter(Charset charset) {
			this.charset = charset;
		}

		@Override
		public B2CConverter run() throws IOException {
			return new B2CConverter(charset);
		}
	}

}
