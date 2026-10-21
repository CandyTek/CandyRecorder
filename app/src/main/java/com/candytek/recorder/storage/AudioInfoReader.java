package com.candytek.recorder.storage;

import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.os.Build;

import androidx.annotation.NonNull;

import com.candytek.recorder.dsp.PcmUtil;
import com.candytek.recorder.util.Fmt;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collects every underlying parameter of an audio file using only platform APIs:
 * container/track formats via MediaExtractor, metadata via MediaMetadataRetriever, and
 * hand-parsed headers for WAV / FLAC / tagged raw PCM. Plus the sidecar recording settings.
 */
public final class AudioInfoReader {

	public static final class Section {
		public final String title;
		public final Map<String, String> rows = new LinkedHashMap<>();
		Section(String title) { this.title = title; }
		void put(String k, Object v) { rows.put(k, String.valueOf(v)); }
	}

	private AudioInfoReader() {}

	@NonNull
	public static List<Section> read(File f) {
		List<Section> out = new ArrayList<>();
		String ext = ext(f);
		long durationMs = -1;
		long pcmBytesPerSec = -1;

		Section file = new Section("File");
		file.put("Name", f.getName());
		file.put("Folder", f.getParent());
		file.put("Size", Fmt.bytes(f.length()) + " (" + f.length() + " B)");
		file.put("Modified", Fmt.date(f.lastModified()));
		file.put("Extension", ext);
		file.put("MIME (by extension)", RecordingStore.mimeFor(ext));
		out.add(file);

		Map<String, String> meta = MetaFile.read(f);
		if (!meta.isEmpty()) {
			Section s = new Section("Recording settings (sidecar .meta)");
			s.rows.putAll(meta);
			out.add(s);
		}

		if ("wav".equals(ext)) {
			Section s = new Section("WAV header (RIFF)");
			long[] r = parseWav(f, s);
			if (r != null) { durationMs = r[0]; pcmBytesPerSec = r[1]; }
			out.add(s);
		} else if ("flac".equals(ext)) {
			Section s = new Section("FLAC STREAMINFO");
			long[] r = parseFlac(f, s);
			if (r != null) { durationMs = r[0]; pcmBytesPerSec = r[1]; }
			out.add(s);
		} else if ("pcm".equals(ext)) {
			Section s = new Section("Raw PCM (from file name)");
			int[] p = RecordingStore.pcmParams(f);
			if (p != null) {
				int bps = PcmUtil.bytesPerSample(p[2]);
				pcmBytesPerSec = (long) p[0] * p[1] * bps;
				durationMs = pcmBytesPerSec > 0 ? f.length() * 1000L / pcmBytesPerSec : -1;
				s.put("Sample rate", p[0] + " Hz");
				s.put("Channels", p[1]);
				s.put("Encoding", PcmUtil.encodingName(p[2]));
				s.put("Frame size", (bps * p[1]) + " B");
				s.put("Frames", f.length() / Math.max(1, bps * p[1]));
				s.put("Duration (computed)", Fmt.clock(durationMs));
			} else {
				s.put("Layout", "unknown (file name not tagged)");
			}
			out.add(s);
		}

		if (!"pcm".equals(ext)) {
			MediaExtractor ex = new MediaExtractor();
			try {
				ex.setDataSource(f.getAbsolutePath());
				int n = ex.getTrackCount();
				Section s = new Section("MediaExtractor: " + n + " track(s)");
				out.add(s);
				for (int i = 0; i < n; i++) {
					MediaFormat fmt = ex.getTrackFormat(i);
					Section t = new Section("Track #" + i + " MediaFormat");
					dumpFormat(fmt, t);
					if (fmt.containsKey(MediaFormat.KEY_DURATION) && durationMs < 0) durationMs = fmt.getLong(MediaFormat.KEY_DURATION) / 1000L;
					if (pcmBytesPerSec < 0 && fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE) && fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
						pcmBytesPerSec = (long) fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) * fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) * 2;
					}
					out.add(t);
				}
			} catch (IOException | RuntimeException e) {
				Section s = new Section("MediaExtractor");
				s.put("Error", String.valueOf(e.getMessage()));
				out.add(s);
			} finally {
				ex.release();
			}

			MediaMetadataRetriever mr = new MediaMetadataRetriever();
			try {
				mr.setDataSource(f.getAbsolutePath());
				Section s = new Section("MediaMetadataRetriever");
				put(s, mr, "Duration (ms)", MediaMetadataRetriever.METADATA_KEY_DURATION);
				put(s, mr, "Bitrate (bps)", MediaMetadataRetriever.METADATA_KEY_BITRATE);
				put(s, mr, "MIME type", MediaMetadataRetriever.METADATA_KEY_MIMETYPE);
				put(s, mr, "Has audio", MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO);
				put(s, mr, "Has video", MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO);
				put(s, mr, "Num tracks", MediaMetadataRetriever.METADATA_KEY_NUM_TRACKS);
				if (Build.VERSION.SDK_INT >= 31) {
					put(s, mr, "Sample rate", MediaMetadataRetriever.METADATA_KEY_SAMPLERATE);
					put(s, mr, "Bits per sample", MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE);
				}
				put(s, mr, "Date", MediaMetadataRetriever.METADATA_KEY_DATE);
				put(s, mr, "Title", MediaMetadataRetriever.METADATA_KEY_TITLE);
				put(s, mr, "Artist", MediaMetadataRetriever.METADATA_KEY_ARTIST);
				put(s, mr, "Album", MediaMetadataRetriever.METADATA_KEY_ALBUM);
				put(s, mr, "Writer", MediaMetadataRetriever.METADATA_KEY_WRITER);
				String d = mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
				if (d != null && durationMs < 0) { try { durationMs = Long.parseLong(d); } catch (NumberFormatException ignored) {} }
				out.add(s);
			} catch (RuntimeException e) {
				Section s = new Section("MediaMetadataRetriever");
				s.put("Error", String.valueOf(e.getMessage()));
				out.add(s);
			} finally {
				try { mr.release(); } catch (Exception ignored) {}
			}
		}

		Section derived = new Section("Derived");
		if (durationMs > 0) {
			derived.put("Duration", Fmt.clock(durationMs));
			double bps = f.length() * 8.0 * 1000.0 / durationMs;
			derived.put("Average container bitrate", Fmt.kbps(bps) + " (" + (long) bps + " bps)");
			derived.put("Bytes per second", (long) (f.length() * 1000.0 / durationMs));
			if (pcmBytesPerSec > 0) {
				double ratio = (pcmBytesPerSec * durationMs / 1000.0) / Math.max(1, f.length());
				derived.put("Compression ratio vs PCM", Fmt.fixed(ratio, 2) + " : 1");
				derived.put("PCM equivalent", Fmt.bytes((long) (pcmBytesPerSec * durationMs / 1000.0)));
			}
		} else {
			derived.put("Duration", "unknown");
		}
		out.add(derived);
		return out;
	}

	private static void put(Section s, MediaMetadataRetriever mr, String label, int key) {
		String v = mr.extractMetadata(key);
		if (v != null) s.put(label, v);
	}

	private static String ext(File f) {
		String n = f.getName();
		int dot = n.lastIndexOf('.');
		return dot >= 0 ? n.substring(dot + 1).toLowerCase() : "";
	}

	private static final String[] KNOWN_KEYS = {
		MediaFormat.KEY_MIME, MediaFormat.KEY_SAMPLE_RATE, MediaFormat.KEY_CHANNEL_COUNT, MediaFormat.KEY_BIT_RATE,
		MediaFormat.KEY_DURATION, MediaFormat.KEY_MAX_INPUT_SIZE, MediaFormat.KEY_AAC_PROFILE, MediaFormat.KEY_CHANNEL_MASK,
		MediaFormat.KEY_IS_ADTS, MediaFormat.KEY_LANGUAGE, "pcm-encoding", "bits-per-sample", "encoder-delay", "encoder-padding",
		"track-id", "is-default", "csd-0", "csd-1", "csd-2", "max-bit-rate", "aac-format-adif", "priority", "bitrate-mode",
		"flac-compression-level", "sample-rate-hz", "max-output-channel-count"
	};

	private static void dumpFormat(MediaFormat fmt, Section s) {
		if (Build.VERSION.SDK_INT >= 29) {
			for (String k : fmt.getKeys()) s.put(k, valueOf(fmt, k));
			return;
		}
		for (String k : KNOWN_KEYS) if (fmt.containsKey(k)) s.put(k, valueOf(fmt, k));
	}

	private static String valueOf(MediaFormat fmt, String k) {
		if (Build.VERSION.SDK_INT >= 29) {
			switch (fmt.getValueTypeForKey(k)) {
				case MediaFormat.TYPE_INTEGER: return String.valueOf(fmt.getInteger(k));
				case MediaFormat.TYPE_LONG: return String.valueOf(fmt.getLong(k));
				case MediaFormat.TYPE_FLOAT: return String.valueOf(fmt.getFloat(k));
				case MediaFormat.TYPE_STRING: return String.valueOf(fmt.getString(k));
				case MediaFormat.TYPE_BYTE_BUFFER: return hex(fmt.getByteBuffer(k));
				default: return "?";
			}
		}
		try { return String.valueOf(fmt.getInteger(k)); } catch (RuntimeException ignored) {}
		try { return String.valueOf(fmt.getLong(k)); } catch (RuntimeException ignored) {}
		try { return String.valueOf(fmt.getString(k)); } catch (RuntimeException ignored) {}
		try { return String.valueOf(fmt.getFloat(k)); } catch (RuntimeException ignored) {}
		try { return hex(fmt.getByteBuffer(k)); } catch (RuntimeException ignored) {}
		return "?";
	}

	private static String hex(ByteBuffer b) {
		if (b == null) return "null";
		ByteBuffer d = b.duplicate();
		d.rewind();
		StringBuilder sb = new StringBuilder();
		int n = 0;
		while (d.hasRemaining() && n < 64) {
			sb.append(String.format("%02x", d.get() & 0xFF));
			n++;
		}
		if (d.hasRemaining()) sb.append("… (").append(b.limit()).append(" B)");
		return sb.toString();
	}

	/** @return {durationMs, pcmBytesPerSec} or null */
	private static long[] parseWav(File f, Section s) {
		try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
			byte[] hdr = new byte[12];
			raf.readFully(hdr);
			ByteBuffer h = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN);
			String riff = new String(hdr, 0, 4, "US-ASCII");
			String wave = new String(hdr, 8, 4, "US-ASCII");
			s.put("RIFF marker", riff + " / " + wave);
			s.put("RIFF size", h.getInt(4) & 0xFFFFFFFFL);
			long pos = 12;
			int fmtTag = 0, channels = 0, rate = 0, byteRate = 0, align = 0, bits = 0;
			long dataLen = -1;
			while (pos + 8 <= raf.length()) {
				raf.seek(pos);
				byte[] ch = new byte[8];
				raf.readFully(ch);
				ByteBuffer cb = ByteBuffer.wrap(ch).order(ByteOrder.LITTLE_ENDIAN);
				String id = new String(ch, 0, 4, "US-ASCII");
				long len = cb.getInt(4) & 0xFFFFFFFFL;
				if ("fmt ".equals(id)) {
					byte[] fb = new byte[(int) Math.min(len, 40)];
					raf.readFully(fb);
					ByteBuffer fbb = ByteBuffer.wrap(fb).order(ByteOrder.LITTLE_ENDIAN);
					fmtTag = fbb.getShort(0) & 0xFFFF;
					channels = fbb.getShort(2) & 0xFFFF;
					rate = fbb.getInt(4);
					byteRate = fbb.getInt(8);
					align = fbb.getShort(12) & 0xFFFF;
					bits = fbb.getShort(14) & 0xFFFF;
					s.put("fmt chunk size", len);
					s.put("Format tag", fmtTag + " (" + wavFormatName(fmtTag) + ")");
					s.put("Channels", channels);
					s.put("Sample rate", rate + " Hz");
					s.put("Byte rate", byteRate + " B/s");
					s.put("Block align", align + " B");
					s.put("Bits per sample", bits);
					if (fmtTag == 0xFFFE && fb.length >= 40) {
						s.put("Valid bits", fbb.getShort(18) & 0xFFFF);
						s.put("Channel mask", "0x" + Integer.toHexString(fbb.getInt(20)));
						s.put("Sub-format", fbb.getShort(24) & 0xFFFF);
					}
				} else if ("data".equals(id)) {
					dataLen = len;
					s.put("data chunk offset", pos + 8);
					s.put("data chunk size", len);
				} else {
					s.put("Chunk '" + id + "'", len + " B");
				}
				pos += 8 + len + (len & 1);
			}
			if (dataLen >= 0 && byteRate > 0) {
				long dur = dataLen * 1000L / byteRate;
				s.put("Frames", align > 0 ? dataLen / align : 0);
				s.put("Duration (computed)", Fmt.clock(dur));
				return new long[] {dur, byteRate};
			}
		} catch (IOException | RuntimeException e) {
			s.put("Parse error", String.valueOf(e.getMessage()));
		}
		return null;
	}

	private static String wavFormatName(int tag) {
		switch (tag) {
			case 1: return "PCM";
			case 3: return "IEEE float";
			case 6: return "A-law";
			case 7: return "mu-law";
			case 0xFFFE: return "WAVE_FORMAT_EXTENSIBLE";
			default: return "other";
		}
	}

	/** @return {durationMs, pcmBytesPerSec} or null */
	private static long[] parseFlac(File f, Section s) {
		try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
			byte[] marker = new byte[4];
			raf.readFully(marker);
			String m = new String(marker, "US-ASCII");
			s.put("Marker", m);
			if (!"fLaC".equals(m)) { s.put("Parse error", "no fLaC marker"); return null; }
			long[] result = null;
			while (true) {
				int b0 = raf.read();
				if (b0 < 0) break;
				boolean last = (b0 & 0x80) != 0;
				int type = b0 & 0x7F;
				int len = (raf.read() << 16) | (raf.read() << 8) | raf.read();
				if (type == 0 && len >= 34) {
					byte[] si = new byte[34];
					raf.readFully(si);
					int minBlock = ((si[0] & 0xFF) << 8) | (si[1] & 0xFF);
					int maxBlock = ((si[2] & 0xFF) << 8) | (si[3] & 0xFF);
					int minFrame = ((si[4] & 0xFF) << 16) | ((si[5] & 0xFF) << 8) | (si[6] & 0xFF);
					int maxFrame = ((si[7] & 0xFF) << 16) | ((si[8] & 0xFF) << 8) | (si[9] & 0xFF);
					long v = 0;
					for (int i = 10; i < 18; i++) v = (v << 8) | (si[i] & 0xFF);
					int rate = (int) (v >>> 44);
					int channels = (int) ((v >>> 41) & 0x7) + 1;
					int bps = (int) ((v >>> 36) & 0x1F) + 1;
					long total = v & 0xFFFFFFFFFL;
					StringBuilder md5 = new StringBuilder();
					for (int i = 18; i < 34; i++) md5.append(String.format("%02x", si[i] & 0xFF));
					s.put("Min block size", minBlock);
					s.put("Max block size", maxBlock);
					s.put("Min frame size", minFrame);
					s.put("Max frame size", maxFrame);
					s.put("Sample rate", rate + " Hz");
					s.put("Channels", channels);
					s.put("Bits per sample", bps);
					s.put("Total samples", total == 0 ? "0 (unknown, streamed)" : String.valueOf(total));
					s.put("MD5", md5.toString());
					long pcmBps = (long) rate * channels * ((bps + 7) / 8);
					long dur = (total > 0 && rate > 0) ? total * 1000L / rate : -1;
					result = new long[] {dur, pcmBps};
				} else {
					s.put("Metadata block type " + type, len + " B");
					raf.seek(raf.getFilePointer() + len);
				}
				if (last) break;
			}
			s.put("Audio data offset", raf.getFilePointer());
			return result;
		} catch (IOException | RuntimeException e) {
			s.put("Parse error", String.valueOf(e.getMessage()));
			return null;
		}
	}
}
