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
package org.apache.coyote.http2;

import java.nio.MappedByteBuffer;
import java.nio.file.Path;

import org.apache.tomcat.util.net.SocketWrapperBase.ByteBufferWrapper;

class SendfileData {

	private Path path;
	private StreamChannel stream;
//	private Http2OutputBuffer outputBuffer;
	// Note: a mapped buffer is a special construct with an underlying file
	// that doesn't need to be closed
	private ByteBufferWrapper mappedBuffer;
	private long left;
	private long streamReservation;
	private long connectionReservation;
	private long pos;
	private long end;

	public SendfileData() {

	}

	public Path getPath() {
		return path;
	}

	public void setPath(Path path) {
		this.path = path;
	}

	public StreamChannel getStream() {
		return stream;
	}

	public void setStream(StreamChannel stream) {
		this.stream = stream;
	}

	public ByteBufferWrapper getMappedBuffer() {
		return mappedBuffer;
	}

	public void setMappedBuffer(MappedByteBuffer mappedBuffer) {
		this.mappedBuffer = ByteBufferWrapper.wrapper(mappedBuffer, true);
	}

	public long getLeft() {
		return left;
	}

	public void setLeft(long left) {
		this.left = left;
	}

	public long getStreamReservation() {
		return streamReservation;
	}

	public void setStreamReservation(long streamReservation) {
		this.streamReservation = streamReservation;
	}

	public long getConnectionReservation() {
		return connectionReservation;
	}

	public void setConnectionReservation(long connectionReservation) {
		this.connectionReservation = connectionReservation;
	}

	public long getPos() {
		return pos;
	}

	public void setPos(long pos) {
		this.pos = pos;
	}

	public long getEnd() {
		return end;
	}

	public void setEnd(long end) {
		this.end = end;
	}

}