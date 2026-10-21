package com.candytek.recorder.engine.sink;

import com.candytek.recorder.dsp.PcmUtil;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * RIFF/WAVE writer. Integer layouts use format tag 1 (PCM), float uses tag 3 (IEEE float).
 * The header is patched with the final sizes on close.
 */
public final class WavSink extends RawSink {

	private static final int HEADER = 44;

	public WavSink(int sampleRate, int channels, int encoding) {
		super(sampleRate, channels, encoding);
	}

	@Override
	public void open(File file) throws IOException {
		super.open(file);
		channel.write(header(0));
	}

	private ByteBuffer header(long dataLen) {
		int bps = PcmUtil.bytesPerSample(encoding);
		int blockAlign = bps * channels;
		ByteBuffer b = ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN);
		b.put((byte) 'R').put((byte) 'I').put((byte) 'F').put((byte) 'F');
		b.putInt((int) Math.min(0xFFFFFFFFL, 36 + dataLen));
		b.put((byte) 'W').put((byte) 'A').put((byte) 'V').put((byte) 'E');
		b.put((byte) 'f').put((byte) 'm').put((byte) 't').put((byte) ' ');
		b.putInt(16);
		b.putShort((short) (PcmUtil.isFloat(encoding) ? 3 : 1));
		b.putShort((short) channels);
		b.putInt(sampleRate);
		b.putInt(sampleRate * blockAlign);
		b.putShort((short) blockAlign);
		b.putShort((short) (bps * 8));
		b.put((byte) 'd').put((byte) 'a').put((byte) 't').put((byte) 'a');
		b.putInt((int) Math.min(0xFFFFFFFFL, dataLen));
		b.flip();
		return b;
	}

	@Override
	public void close() throws IOException {
		if (raf == null) return;
		try {
			channel.position(0);
			channel.write(header(written));
			channel.force(true);
		} finally {
			super.close();
		}
	}

	@Override
	public long bytesOnDisk() { return written + HEADER; }

	@Override
	public String describe() {
		return "WAV " + (PcmUtil.isFloat(encoding) ? "IEEE float" : "PCM") + " " + PcmUtil.bitsPerSample(encoding)
			+ "-bit, " + sampleRate + " Hz, " + channels + " ch";
	}
}
