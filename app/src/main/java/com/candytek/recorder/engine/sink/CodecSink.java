package com.candytek.recorder.engine.sink;

import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Build;

import com.candytek.recorder.core.Choices;
import com.candytek.recorder.dsp.PcmUtil;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Compresses PCM through the platform MediaCodec encoders and writes:
 * <ul>
 *   <li>AAC (LC / HE / ELD) into an MPEG-4 container via MediaMuxer (.m4a)</li>
 *   <li>Opus into an Ogg container via MediaMuxer (.ogg, API 29+)</li>
 *   <li>FLAC as a native FLAC bitstream (.flac): the codec-config buffer carries the
 *       "fLaC" marker + STREAMINFO, every later buffer is a FLAC frame</li>
 * </ul>
 * Codec input is always 16-bit PCM; other capture layouts are converted on the fly.
 */
public final class CodecSink implements PcmSink {

	private static final int MODE_MP4 = 0, MODE_OGG = 1, MODE_FLAC_RAW = 2;
	private static final long DEQUEUE_TIMEOUT_US = 10_000;

	private final Choices.CodecB codec;
	private final int sampleRate, channels, bitrate, bitrateMode, flacLevel, srcEncoding;
	private final int mode;

	private MediaCodec encoder;
	private MediaMuxer muxer;
	private FileOutputStream rawOut;
	private int track = -1;
	private boolean muxerStarted;
	private long ptsUs;
	private long written;
	private ByteBuffer pcm16;
	private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
	private String encoderName = "?";
	private boolean closed;
	private File file;

	public CodecSink(Choices.CodecB codec, int sampleRate, int channels, int bitrate, int bitrateMode,
	                 int flacLevel, int srcEncoding) {
		this.codec = codec;
		this.sampleRate = sampleRate;
		this.channels = channels;
		this.bitrate = bitrate;
		this.bitrateMode = bitrateMode;
		this.flacLevel = flacLevel;
		this.srcEncoding = srcEncoding;
		if ("flac".equals(codec.id)) mode = MODE_FLAC_RAW;
		else if ("opus".equals(codec.id)) mode = MODE_OGG;
		else mode = MODE_MP4;
	}

	@Override
	public void open(File file) throws IOException {
		this.file = file;
		MediaFormat f = MediaFormat.createAudioFormat(codec.mime, sampleRate, channels);
		f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024);
		if (mode == MODE_FLAC_RAW) {
			f.setInteger(MediaFormat.KEY_FLAC_COMPRESSION_LEVEL, flacLevel);
		} else {
			f.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
			if (codec.aacProfile != 0) f.setInteger(MediaFormat.KEY_AAC_PROFILE, codec.aacProfile);
			int brm = mapBitrateMode(bitrateMode);
			if (brm >= 0) f.setInteger(MediaFormat.KEY_BITRATE_MODE, brm);
		}
		if (Build.VERSION.SDK_INT >= 24) f.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT);

		try {
			encoder = MediaCodec.createEncoderByType(codec.mime);
			encoderName = encoder.getName();
			encoder.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
			encoder.start();
		} catch (RuntimeException e) {
			releaseQuietly();
			throw new IOException("Encoder " + codec.mime + " failed: " + e.getMessage(), e);
		}

		if (mode == MODE_FLAC_RAW) {
			rawOut = new FileOutputStream(file);
		} else if (mode == MODE_OGG) {
			if (Build.VERSION.SDK_INT < 29) { releaseQuietly(); throw new IOException("Ogg muxer needs API 29"); }
			muxer = new MediaMuxer(file.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG);
		} else {
			muxer = new MediaMuxer(file.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
		}
	}

	private static int mapBitrateMode(int mode) {
		switch (mode) {
			case Choices.BR_MODE_CBR: return MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR;
			case Choices.BR_MODE_VBR: return MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR;
			case Choices.BR_MODE_CQ: return MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ;
			default: return -1;
		}
	}

	@Override
	public void write(ByteBuffer src, int len) throws IOException {
		ByteBuffer in;
		int inLen;
		if (srcEncoding == AudioFormat.ENCODING_PCM_16BIT) {
			in = src; inLen = len;
		} else {
			int need = len / PcmUtil.bytesPerSample(srcEncoding) * 2;
			if (pcm16 == null || pcm16.capacity() < need) pcm16 = ByteBuffer.allocateDirect(need).order(ByteOrder.LITTLE_ENDIAN);
			inLen = PcmUtil.toPcm16(src, len, srcEncoding, pcm16);
			in = pcm16;
		}
		feed(in, inLen);
		src.clear();
	}

	private void feed(ByteBuffer data, int len) throws IOException {
		int frameBytes = 2 * channels;
		int offset = 0;
		int guard = 0;
		while (offset < len) {
			int idx = encoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US);
			if (idx < 0) {
				drain(false);
				if (++guard > 500) throw new IOException("Encoder input stalled");
				continue;
			}
			ByteBuffer ib = encoder.getInputBuffer(idx);
			if (ib == null) throw new IOException("null input buffer");
			ib.clear();
			int chunk = Math.min(ib.remaining(), len - offset);
			chunk -= chunk % frameBytes;
			data.limit(offset + chunk);
			data.position(offset);
			ib.put(data);
			encoder.queueInputBuffer(idx, 0, chunk, ptsUs, 0);
			ptsUs += (long) (chunk / frameBytes) * 1_000_000L / sampleRate;
			offset += chunk;
			drain(false);
		}
		data.clear();
	}

	private void drain(boolean untilEos) throws IOException {
		while (true) {
			int idx = encoder.dequeueOutputBuffer(info, untilEos ? DEQUEUE_TIMEOUT_US : 0);
			if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
				if (muxer != null && !muxerStarted) {
					track = muxer.addTrack(encoder.getOutputFormat());
					muxer.start();
					muxerStarted = true;
				}
			} else if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
				if (!untilEos) return;
			} else if (idx >= 0) {
				ByteBuffer ob = encoder.getOutputBuffer(idx);
				boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
				boolean config = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
				if (ob != null && info.size > 0) {
					ob.position(info.offset);
					ob.limit(info.offset + info.size);
					if (rawOut != null) {
						byte[] tmp = new byte[info.size];
						ob.get(tmp);
						rawOut.write(tmp);
						written += info.size;
					} else if (!config) {
						if (!muxerStarted) {
							track = muxer.addTrack(encoder.getOutputFormat());
							muxer.start();
							muxerStarted = true;
						}
						muxer.writeSampleData(track, ob, info);
						written += info.size;
					}
				}
				encoder.releaseOutputBuffer(idx, false);
				if (eos) return;
			}
		}
	}

	@Override
	public void close() throws IOException {
		if (closed) return;
		closed = true;
		IOException failure = null;
		try {
			if (encoder != null) {
				int guard = 0;
				while (true) {
					int idx = encoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US);
					if (idx >= 0) {
						encoder.queueInputBuffer(idx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
						break;
					}
					drain(false);
					if (++guard > 200) break;
				}
				drain(true);
			}
		} catch (IOException | RuntimeException e) {
			failure = e instanceof IOException ? (IOException) e : new IOException(e);
		} finally {
			releaseQuietly();
		}
		if (failure != null) throw failure;
	}

	private void releaseQuietly() {
		if (encoder != null) {
			try { encoder.stop(); } catch (RuntimeException ignored) {}
			try { encoder.release(); } catch (RuntimeException ignored) {}
			encoder = null;
		}
		if (muxer != null) {
			if (muxerStarted) { try { muxer.stop(); } catch (RuntimeException ignored) {} }
			try { muxer.release(); } catch (RuntimeException ignored) {}
			muxer = null;
		}
		if (rawOut != null) {
			try { rawOut.flush(); rawOut.close(); } catch (IOException ignored) {}
			rawOut = null;
		}
	}

	@Override
	public long bytesOnDisk() {
		if (file != null && mode != MODE_FLAC_RAW) {
			long l = file.length();
			if (l > 0) return l;
		}
		return written;
	}

	@Override
	public String describe() {
		StringBuilder sb = new StringBuilder();
		sb.append(codec.mime).append(" via ").append(encoderName);
		if (mode == MODE_FLAC_RAW) sb.append(", level ").append(flacLevel);
		else sb.append(", ").append(bitrate / 1000).append(" kbps");
		Choices.Opt brm = Choices.find(Choices.BITRATE_MODES, bitrateMode);
		if (brm != null && bitrateMode != Choices.BR_MODE_DEFAULT && mode != MODE_FLAC_RAW) {
			sb.append(bitrateMode == Choices.BR_MODE_CBR ? " CBR" : bitrateMode == Choices.BR_MODE_VBR ? " VBR" : " CQ");
		}
		sb.append(", in: 16-bit PCM");
		return sb.toString();
	}
}
