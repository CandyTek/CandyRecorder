package com.candytek.recorder.engine.sink;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/** Receives raw interleaved PCM chunks from Engine B and produces the output file. */
public interface PcmSink {

	void open(File file) throws IOException;

	/** @param src little-endian interleaved PCM in the capture encoding; bytes [0, len) are valid. */
	void write(ByteBuffer src, int len) throws IOException;

	/** Finalizes the file. Must be safe to call once even after a failed write. */
	void close() throws IOException;

	/** Bytes currently committed to the output file (best effort). */
	long bytesOnDisk();

	/** Human-readable description of the codec / container configuration actually in use. */
	String describe();
}
