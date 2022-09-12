package org.apache.tomcat.util.net;

import java.nio.ByteBuffer;

public interface BufWrapper {

	public void switchToWriteMode(boolean compact, int retain);

	public void switchToWriteMode(boolean compact);

	public void switchToWriteMode();

	public void switchToReadMode();

	public boolean reuseable();

	public int getLimit();

	public void setLimit(int limit);

	public byte getByte();

	public void getByte(byte[] b, int off, int len);

	public byte getByte(int index);

	public int getPosition();

	public void setPosition(int position);

	public boolean hasArray();

	public byte[] getArray();

	public int getRemaining();

	public boolean hasRemaining();

	public boolean hasNoRemaining();

	// public int arrayOffset();

	public int getCapacity();

	public void setByte(int index, byte b);

	public void putByte(byte b);

	public void putBytes(byte[] b);

	public void putBytes(byte[] b, int off, int len);

	// public boolean fill(boolean block) throws IOException;

	// public int read(boolean block, byte[] b, int off, int len) throws
	// IOException;

	// public int read(boolean block, ByteBuffer to) throws IOException;

	public void startParsingHeader(int headerBufferSize);

	public void startParsingRequestLine();

	public void finishParsingRequestLine();

	public void finishParsingHeader(boolean keepHeadPos);

	public void nextRequest();

	public void reset();

	public ByteBuffer nioBuffer();

	public BufWrapper duplicate();

	public void startTrace();

	public boolean released();

	public void release();

}
