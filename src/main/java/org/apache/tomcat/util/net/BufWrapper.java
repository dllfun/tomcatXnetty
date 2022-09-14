package org.apache.tomcat.util.net;

import java.nio.ByteBuffer;

public interface BufWrapper {

	public void switchToWriteMode(boolean compact);

	public void switchToWriteMode();

	public void setRetain(int retain);

	public void clearRetain();

	public int getRetain();

	public boolean isWriteMode();

	public void switchToReadMode();

	public boolean isReadMode();

	public boolean reuseable();

	public int getLimit();

	public void setLimit(int limit);

	public byte getByte();

	public void getBytes(byte[] b, int off, int len);

	public byte getByte(int index);

	public int getPosition();

	public void setPosition(int position);

	public boolean hasArray();

	public byte[] getArray();

	public boolean isDirect();

	public boolean isEmpty();

	public int getRemaining();

	public boolean hasRemaining();

	public boolean hasNoRemaining();

	// public int arrayOffset();

	public int getCapacity();

	public void setByte(int index, byte b);

	public void putByte(byte b);

	public void putBytes(byte[] b);

	public void putBytes(byte[] b, int off, int len);

	public void clearWrite();

//	public void unread(ByteBufferWrapper returned);

	public boolean isWritable();

	// public boolean fill(boolean block) throws IOException;

	// public int read(boolean block, byte[] b, int off, int len) throws
	// IOException;

	// public int read(boolean block, ByteBuffer to) throws IOException;

//	public void startParsingHeader(int headerBufferSize);

//	public void startParsingRequestLine();

//	public void finishParsingRequestLine();

//	public void finishParsingHeader(boolean keepHeadPos);

	public void reset();

	public ByteBuffer nioBuffer();

	public BufWrapper duplicate();

	public void startTrace();

	public void retain();

	public boolean released();

	public void release();

	public int refCount();

	public String printInfo();

	public void expand(int newSize);

}
