package com.candytek.recorder.dsp;

import android.media.AudioFormat;

import java.nio.ByteBuffer;

/** Conversions between the raw little-endian PCM layouts AudioRecord can produce and float samples. */
public final class PcmUtil {

	private PcmUtil() {}

	public static int bytesPerSample(int encoding) {
		switch (encoding) {
			case AudioFormat.ENCODING_PCM_8BIT: return 1;
			case AudioFormat.ENCODING_PCM_24BIT_PACKED: return 3;
			case AudioFormat.ENCODING_PCM_32BIT:
			case AudioFormat.ENCODING_PCM_FLOAT: return 4;
			case AudioFormat.ENCODING_PCM_16BIT:
			default: return 2;
		}
	}

	public static int bitsPerSample(int encoding) { return bytesPerSample(encoding) * 8; }

	public static boolean isFloat(int encoding) { return encoding == AudioFormat.ENCODING_PCM_FLOAT; }

	public static String encodingName(int encoding) {
		switch (encoding) {
			case AudioFormat.ENCODING_PCM_8BIT: return "PCM 8-bit unsigned";
			case AudioFormat.ENCODING_PCM_16BIT: return "PCM 16-bit signed LE";
			case AudioFormat.ENCODING_PCM_24BIT_PACKED: return "PCM 24-bit packed LE";
			case AudioFormat.ENCODING_PCM_32BIT: return "PCM 32-bit signed LE";
			case AudioFormat.ENCODING_PCM_FLOAT: return "PCM 32-bit float";
			default: return "encoding#" + encoding;
		}
	}

	/** Short tag embedded in raw-PCM file names so they can be played back later. */
	public static String encodingTag(int encoding) {
		switch (encoding) {
			case AudioFormat.ENCODING_PCM_8BIT: return "u8";
			case AudioFormat.ENCODING_PCM_24BIT_PACKED: return "s24";
			case AudioFormat.ENCODING_PCM_32BIT: return "s32";
			case AudioFormat.ENCODING_PCM_FLOAT: return "f32";
			default: return "s16";
		}
	}

	public static int encodingFromTag(String tag) {
		if ("u8".equals(tag)) return AudioFormat.ENCODING_PCM_8BIT;
		if ("s24".equals(tag)) return AudioFormat.ENCODING_PCM_24BIT_PACKED;
		if ("s32".equals(tag)) return AudioFormat.ENCODING_PCM_32BIT;
		if ("f32".equals(tag)) return AudioFormat.ENCODING_PCM_FLOAT;
		return AudioFormat.ENCODING_PCM_16BIT;
	}

	/**
	 * Decode interleaved samples into floats (-1..1).
	 * @return number of samples decoded. The buffer position is not changed.
	 */
	public static int toFloat(ByteBuffer buf, int byteCount, int encoding, float[] out) {
		int bps = bytesPerSample(encoding);
		int count = Math.min(byteCount / bps, out.length);
		switch (encoding) {
			case AudioFormat.ENCODING_PCM_8BIT:
				for (int i = 0; i < count; i++) out[i] = ((buf.get(i) & 0xFF) - 128) / 128f;
				break;
			case AudioFormat.ENCODING_PCM_24BIT_PACKED:
				for (int i = 0, p = 0; i < count; i++, p += 3) {
					int v = (buf.get(p) & 0xFF) | ((buf.get(p + 1) & 0xFF) << 8) | (buf.get(p + 2) << 16);
					out[i] = v / 8388608f;
				}
				break;
			case AudioFormat.ENCODING_PCM_32BIT:
				for (int i = 0, p = 0; i < count; i++, p += 4) out[i] = buf.getInt(p) / 2147483648f;
				break;
			case AudioFormat.ENCODING_PCM_FLOAT:
				for (int i = 0, p = 0; i < count; i++, p += 4) out[i] = buf.getFloat(p);
				break;
			default:
				for (int i = 0, p = 0; i < count; i++, p += 2) out[i] = buf.getShort(p) / 32768f;
				break;
		}
		return count;
	}

	/**
	 * Convert any supported layout to 16-bit signed LE for codec input.
	 * @return number of bytes written to dst (from position 0). dst must hold byteCount/bps*2 bytes.
	 */
	public static int toPcm16(ByteBuffer src, int byteCount, int encoding, ByteBuffer dst) {
		int bps = bytesPerSample(encoding);
		int count = byteCount / bps;
		dst.clear();
		switch (encoding) {
			case AudioFormat.ENCODING_PCM_16BIT:
				for (int p = 0; p < count * 2; p += 2) dst.putShort(src.getShort(p));
				break;
			case AudioFormat.ENCODING_PCM_8BIT:
				for (int i = 0; i < count; i++) dst.putShort((short) (((src.get(i) & 0xFF) - 128) << 8));
				break;
			case AudioFormat.ENCODING_PCM_24BIT_PACKED:
				for (int i = 0, p = 0; i < count; i++, p += 3) {
					dst.putShort((short) ((src.get(p + 1) & 0xFF) | (src.get(p + 2) << 8)));
				}
				break;
			case AudioFormat.ENCODING_PCM_32BIT:
				for (int i = 0, p = 0; i < count; i++, p += 4) dst.putShort((short) (src.getInt(p) >> 16));
				break;
			case AudioFormat.ENCODING_PCM_FLOAT:
				for (int i = 0, p = 0; i < count; i++, p += 4) {
					float f = src.getFloat(p);
					if (f > 1f) f = 1f; else if (f < -1f) f = -1f;
					dst.putShort((short) Math.round(f * 32767f));
				}
				break;
			default:
				break;
		}
		int n = dst.position();
		dst.flip();
		return n;
	}

	public static float dbfs(float linear) {
		if (linear <= 1e-7f) return -140f;
		return (float) (20.0 * Math.log10(linear));
	}
}
