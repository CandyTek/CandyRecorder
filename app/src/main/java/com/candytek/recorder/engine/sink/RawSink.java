package com.candytek.recorder.engine.sink;

import com.candytek.recorder.dsp.PcmUtil;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/** Headerless PCM dump. The sample layout is encoded into the file name for later playback. */
public class RawSink implements PcmSink {

	protected RandomAccessFile raf;
	protected FileChannel channel;
	protected long written;
	protected final int sampleRate, channels, encoding;

	public RawSink(int sampleRate, int channels, int encoding) {
		this.sampleRate = sampleRate; this.channels = channels; this.encoding = encoding;
	}

	@Override
	public void open(File file) throws IOException {
		raf = new RandomAccessFile(file, "rw");
		raf.setLength(0);
		channel = raf.getChannel();
	}

	@Override
	public void write(ByteBuffer src, int len) throws IOException {
		src.position(0);
		src.limit(len);
		while (src.hasRemaining()) written += channel.write(src);
		src.clear();
	}

	@Override
	public void close() throws IOException {
		if (raf != null) { raf.close(); raf = null; channel = null; }
	}

	@Override
	public long bytesOnDisk() { return written; }

	@Override
	public String describe() {
		return "raw " + PcmUtil.encodingName(encoding) + ", " + sampleRate + " Hz, " + channels + " ch";
	}
}
