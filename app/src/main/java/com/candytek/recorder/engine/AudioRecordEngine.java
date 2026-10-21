package com.candytek.recorder.engine;

import android.annotation.SuppressLint;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.AudioRecordingConfiguration;
import android.media.AudioAttributes;
import android.media.MicrophoneInfo;
import android.os.Build;
import android.os.Process;

import androidx.annotation.NonNull;

import com.candytek.recorder.core.Choices;
import com.candytek.recorder.core.RecordingConfig;
import com.candytek.recorder.dsp.Fft;
import com.candytek.recorder.dsp.PcmUtil;
import com.candytek.recorder.engine.sink.CodecSink;
import com.candytek.recorder.engine.sink.PcmSink;
import com.candytek.recorder.engine.sink.RawSink;
import com.candytek.recorder.engine.sink.WavSink;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/**
 * Engine B: raw AudioRecord capture with full control over the PCM layout, buffering,
 * pre-processing effects, microphone direction, silence gating and output codec.
 */
public final class AudioRecordEngine implements RecorderEngine {

	private static final int WAVE_POINTS = 256;

	private final EngineStats stats = new EngineStats();
	private RecordingConfig cfg;
	private Listener listener;
	private AudioRecord record;
	private PcmSink sink;
	private AudioEffectsController fx;
	private SilenceGate gate;
	private Fft fft;
	private Thread thread;

	private int encoding, channels, sampleRate, frameBytes, chunkBytes, chunkFrames;
	private volatile boolean running, paused, finalized;
	private long accumNs, segmentStartNs;
	private final Object lifecycle = new Object();

	@Override
	public void start(@NonNull StartParams p) throws Exception {
		cfg = p.config;
		listener = p.listener;
		encoding = cfg.pcmEncoding;
		channels = cfg.channels;
		sampleRate = cfg.sampleRate;
		int source = cfg.effectiveSource(p.context);
		int channelMask = cfg.channelMaskIn();

		int minBuf = AudioRecord.getMinBufferSize(sampleRate, channelMask, encoding);
		if (minBuf <= 0) throw new IOException("Unsupported capture format: " + sampleRate + " Hz / "
			+ channels + " ch / " + PcmUtil.encodingName(encoding) + " (getMinBufferSize=" + minBuf + ")");

		frameBytes = PcmUtil.bytesPerSample(encoding) * channels;
		chunkFrames = Math.max(128, sampleRate / 100);          // ~10 ms per read
		chunkBytes = chunkFrames * frameBytes;
		int bufferBytes = Math.max(minBuf * cfg.bufferMultiplier, chunkBytes * 4);

		try {
			record = buildRecord(p, source, channelMask, bufferBytes);
		} catch (RuntimeException e) {
			throw new IOException("AudioRecord init failed: " + e.getMessage(), e);
		}
		if (record == null || record.getState() != AudioRecord.STATE_INITIALIZED) {
			releaseRecord();
			throw new IOException("AudioRecord not initialized (source busy, unsupported or permission missing)");
		}

		// ---- static stats
		stats.engineName = "B · AudioRecord";
		stats.sourceName = sourceName(source, cfg);
		stats.requestedSampleRate = sampleRate;
		stats.sampleRate = record.getSampleRate();
		stats.channels = record.getChannelCount();
		stats.encodingName = PcmUtil.encodingName(encoding);
		stats.chunkFrames = chunkFrames;
		stats.audioSessionId = record.getAudioSessionId();
		stats.filePath = p.outFile.getAbsolutePath();
		stats.bufferFrames = Build.VERSION.SDK_INT >= 23 ? record.getBufferSizeInFrames() : bufferBytes / frameBytes;
		stats.bufferLatencyMs = stats.bufferFrames * 1000.0 / sampleRate;
		stats.putExtra("Min buffer", minBuf + " B (x" + cfg.bufferMultiplier + " = " + bufferBytes + " B)");

		// ---- routing
		if (Build.VERSION.SDK_INT >= 23 && p.preferredDevice != null) {
			boolean ok = record.setPreferredDevice(p.preferredDevice);
			stats.putExtra("setPreferredDevice", (ok ? "ok: " : "rejected: ") + AudioRouter.describe(p.preferredDevice));
		}
		stats.deviceName = p.preferredDevice != null ? AudioRouter.describe(p.preferredDevice) : "auto";

		// ---- microphone direction / field dimension (API 29)
		applyDirection();

		// ---- effects
		if (!cfg.isPlaybackCapture()) {
			fx = new AudioEffectsController();
			String status = fx.apply(record.getAudioSessionId(), cfg.ns, cfg.aec, cfg.agc, cfg.rawMode);
			stats.putExtra("Effects", status + (cfg.rawMode ? " (raw mode)" : ""));
		} else {
			stats.putExtra("Effects", "n/a (playback capture)");
		}

		// ---- sink
		if (cfg.codecB.compressed) {
			sink = new CodecSink(cfg.codecB, sampleRate, channels, cfg.bitrate, cfg.bitrateMode, cfg.flacLevel, encoding);
		} else if ("wav".equals(cfg.codecB.id)) {
			sink = new WavSink(sampleRate, channels, encoding);
		} else {
			sink = new RawSink(sampleRate, channels, encoding);
		}
		try {
			sink.open(p.outFile);
		} catch (IOException e) {
			releaseAll();
			throw e;
		}
		stats.codecName = sink.describe();

		gate = cfg.silenceSkip ? new SilenceGate(cfg.silenceDb, cfg.silenceHoldMs) : null;
		fft = new Fft(cfg.fftSize);

		// ---- go
		try {
			record.startRecording();
		} catch (IllegalStateException e) {
			releaseAll();
			throw new IOException("startRecording failed: " + e.getMessage(), e);
		}
		if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
			releaseAll();
			throw new IOException("Recording did not start (another app may own the microphone)");
		}
		refreshRouting();
		running = true;
		paused = false;
		accumNs = 0;
		segmentStartNs = System.nanoTime();
		thread = new Thread(new Runnable() { @Override public void run() { loop(); } }, "candy-audio");
		thread.start();
	}

	@SuppressLint("MissingPermission")
	private AudioRecord buildRecord(StartParams p, int source, int channelMask, int bufferBytes) {
		if (Build.VERSION.SDK_INT >= 23) {
			AudioFormat format = new AudioFormat.Builder()
				.setEncoding(encoding)
				.setSampleRate(sampleRate)
				.setChannelMask(channelMask)
				.build();
			AudioRecord.Builder b = new AudioRecord.Builder()
				.setAudioFormat(format)
				.setBufferSizeInBytes(bufferBytes);
			if (cfg.isPlaybackCapture() && Build.VERSION.SDK_INT >= 29 && p.projection != null) {
				AudioPlaybackCaptureConfiguration cap = new AudioPlaybackCaptureConfiguration.Builder(p.projection)
					.addMatchingUsage(AudioAttributes.USAGE_MEDIA)
					.addMatchingUsage(AudioAttributes.USAGE_GAME)
					.addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
					.build();
				b.setAudioPlaybackCaptureConfig(cap);
			} else {
				b.setAudioSource(source);
			}
			if (Build.VERSION.SDK_INT >= 30 && cfg.privacySensitive) b.setPrivacySensitive(true);
			return b.build();
		}
		return new AudioRecord(source, sampleRate, channelMask, encoding, bufferBytes);
	}

	private void applyDirection() {
		if (Build.VERSION.SDK_INT < 29) {
			if (cfg.micDirection != 0 || !Float.isNaN(cfg.fieldDimension)) stats.putExtra("Mic direction", "needs API 29");
			return;
		}
		if (cfg.micDirection != 0) {
			boolean ok = record.setPreferredMicrophoneDirection(cfg.micDirection);
			stats.putExtra("Mic direction", (ok ? "accepted" : "rejected") + " (mode " + cfg.micDirection + ")");
		}
		if (!Float.isNaN(cfg.fieldDimension)) {
			boolean ok = record.setPreferredMicrophoneFieldDimension(cfg.fieldDimension);
			stats.putExtra("Field dimension", (ok ? "accepted " : "rejected ") + cfg.fieldDimension);
		}
	}

	private void refreshRouting() {
		if (record == null) return;
		try {
			if (Build.VERSION.SDK_INT >= 24) {
				AudioDeviceInfo d = record.getRoutedDevice();
				stats.routedDevice = AudioRouter.describe(d);
			}
			if (Build.VERSION.SDK_INT >= 29) {
				List<MicrophoneInfo> mics = record.getActiveMicrophones();
				StringBuilder sb = new StringBuilder();
				for (MicrophoneInfo m : mics) {
					if (sb.length() > 0) sb.append(" | ");
					sb.append(m.getDescription()).append(" dir=").append(m.getDirectionality());
				}
				stats.micInfo = sb.toString();
				AudioRecordingConfiguration rc = record.getActiveRecordingConfiguration();
				if (rc != null) {
					AudioFormat dev = rc.getFormat();
					AudioFormat cli = rc.getClientFormat();
					stats.putExtra("HAL format", dev.getSampleRate() + " Hz, enc " + PcmUtil.encodingName(dev.getEncoding())
						+ ", mask 0x" + Integer.toHexString(dev.getChannelMask()));
					stats.putExtra("Client format", cli.getSampleRate() + " Hz, enc " + PcmUtil.encodingName(cli.getEncoding()));
					stats.putExtra("Client source", String.valueOf(rc.getClientAudioSource()));
					if (Build.VERSION.SDK_INT >= 30) stats.putExtra("Silenced by system", String.valueOf(rc.isClientSilenced()));
				}
			}
		} catch (java.io.IOException | RuntimeException ignored) {
			// routing info is best effort
		}
	}

	private static String sourceName(int source, RecordingConfig cfg) {
		if (cfg.isPlaybackCapture()) return "Playback capture (MediaProjection)";
		switch (source) {
			case 0: return "DEFAULT";
			case 1: return "MIC";
			case 2: return "VOICE_UPLINK";
			case 3: return "VOICE_DOWNLINK";
			case 4: return "VOICE_CALL";
			case 5: return "CAMCORDER";
			case 6: return "VOICE_RECOGNITION";
			case 7: return "VOICE_COMMUNICATION";
			case 8: return "REMOTE_SUBMIX";
			case 9: return "UNPROCESSED";
			case 10: return "VOICE_PERFORMANCE";
			default: return "source#" + source;
		}
	}

	// ------------------------------------------------------------------------------------------

	private void loop() {
		Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
		ByteBuffer buf = ByteBuffer.allocateDirect(chunkBytes).order(ByteOrder.nativeOrder());
		float[] samples = new float[chunkFrames * channels];
		int fftSize = fft.size();
		float[] ring = new float[fftSize];
		float[] lin = new float[fftSize];
		float[] spectrum = new float[fft.bins()];
		int ringPos = 0;
		long meterIntervalNs = (cfg.meterSpeed == Choices.METER_FAST ? 16L : 40L) * 1_000_000L;
		long lastMeterNs = 0, lastRouteNs = System.nanoTime();
		double readAvg = 0;
		long readMax = 0;
		String error = null;

		while (running) {
			buf.clear();
			long t0 = System.nanoTime();
			int n = record.read(buf, chunkBytes);
			long now = System.nanoTime();
			long dt = (now - t0) / 1000L;
			readAvg = readAvg == 0 ? dt : readAvg * 0.95 + dt * 0.05;
			if (dt > readMax) readMax = dt;
			stats.readAvgUs = (long) readAvg;
			stats.readMaxUs = readMax;

			if (n < 0) { error = readError(n); break; }
			if (n == 0) continue;
			int frames = n / frameBytes;
			int count = PcmUtil.toFloat(buf, n, encoding, samples);

			// ---- metering
			float peakL = 0, peakR = 0, sumL = 0, sumR = 0;
			boolean clipped = false;
			if (channels == 1) {
				for (int i = 0; i < count; i++) {
					float s = samples[i];
					float a = s < 0 ? -s : s;
					if (a > peakL) peakL = a;
					sumL += s * s;
					ring[ringPos] = s; ringPos = (ringPos + 1) & (fftSize - 1);
				}
				peakR = peakL; sumR = sumL;
			} else {
				for (int i = 0; i + 1 < count; i += 2) {
					float l = samples[i], r = samples[i + 1];
					float al = l < 0 ? -l : l, ar = r < 0 ? -r : r;
					if (al > peakL) peakL = al;
					if (ar > peakR) peakR = ar;
					sumL += l * l; sumR += r * r;
					ring[ringPos] = (l + r) * 0.5f; ringPos = (ringPos + 1) & (fftSize - 1);
				}
			}
			if (peakL >= 0.999f || peakR >= 0.999f) { clipped = true; stats.clipCount++; }
			float rmsL = (float) Math.sqrt(sumL / Math.max(1, frames));
			float rmsR = (float) Math.sqrt(sumR / Math.max(1, frames));
			float peakDbL = PcmUtil.dbfs(peakL), peakDbR = PcmUtil.dbfs(peakR);
			float rmsDbL = PcmUtil.dbfs(rmsL), rmsDbR = PcmUtil.dbfs(rmsR);
			stats.peakDbL = peakDbL; stats.peakDbR = peakDbR; stats.rmsDbL = rmsDbL; stats.rmsDbR = rmsDbR;

			// ---- write
			boolean isPaused = paused;
			boolean dropped = false;
			if (!isPaused) {
				stats.chunksRead++;
				float level = Math.max(rmsDbL, rmsDbR);
				dropped = gate != null && gate.update(level, now);
				if (dropped) {
					stats.framesSkipped += frames;
				} else {
					try {
						sink.write(buf, n);
					} catch (IOException | RuntimeException e) {
						error = "Write failed: " + e.getMessage();
						break;
					}
					stats.framesRead += frames;
				}
				if (gate != null) { stats.gateClosed = gate.isClosed(); stats.gateEvents = gate.events(); }
				stats.bytesWritten = sink.bytesOnDisk();
				long elapsed = accumNs + (now - segmentStartNs);
				stats.elapsedNs = elapsed;
				long expected = (long) (elapsed / 1e9 * sampleRate);
				stats.driftFrames = expected - (stats.framesRead + stats.framesSkipped);
			} else {
				stats.elapsedNs = accumNs;
			}

			// ---- dashboard frame (throttled)
			if (now - lastMeterNs >= meterIntervalNs) {
				lastMeterNs = now;
				int monoCount = frames;
				float[] wave = new float[Math.min(WAVE_POINTS, monoCount)];
				float stride = (float) monoCount / wave.length;
				for (int i = 0; i < wave.length; i++) {
					int f = (int) (i * stride);
					wave[i] = channels == 1 ? samples[f] : (samples[f * 2] + samples[f * 2 + 1]) * 0.5f;
				}
				for (int i = 0; i < fftSize; i++) lin[i] = ring[(ringPos + i) & (fftSize - 1)];
				fft.magnitudesDb(lin, 0, spectrum);
				float[] spec = spectrum.clone();
				listener.onMeter(new MeterFrame(wave, spec, sampleRate, peakDbL, peakDbR, rmsDbL, rmsDbR,
					dropped, clipped, now));
			}
			if (now - lastRouteNs > 2_000_000_000L) { lastRouteNs = now; refreshRouting(); }
		}

		if (error != null) {
			running = false;
			String msg = error;
			try { finalizeOnce(); } catch (IOException e) { msg += " / " + e.getMessage(); }
			listener.onError(msg);
		}
	}

	private static String readError(int code) {
		switch (code) {
			case AudioRecord.ERROR_INVALID_OPERATION: return "AudioRecord.read: ERROR_INVALID_OPERATION";
			case AudioRecord.ERROR_BAD_VALUE: return "AudioRecord.read: ERROR_BAD_VALUE";
			case AudioRecord.ERROR_DEAD_OBJECT: return "AudioRecord.read: ERROR_DEAD_OBJECT (audio server died or device removed)";
			default: return "AudioRecord.read error " + code;
		}
	}

	@Override
	public void pause() {
		if (paused) return;
		accumNs += System.nanoTime() - segmentStartNs;
		paused = true;
		stats.paused = true;
	}

	@Override
	public void resume() {
		if (!paused) return;
		segmentStartNs = System.nanoTime();
		paused = false;
		stats.paused = false;
	}

	@Override
	public boolean isPaused() { return paused; }

	@Override
	public void stop() throws Exception {
		running = false;
		if (!paused) { accumNs += System.nanoTime() - segmentStartNs; paused = true; }
		stats.elapsedNs = accumNs;
		Thread t = thread;
		if (t != null && t != Thread.currentThread()) {
			try { t.join(5000); } catch (InterruptedException ignored) {}
		}
		finalizeOnce();
	}

	private void finalizeOnce() throws IOException {
		synchronized (lifecycle) {
			if (finalized) return;
			finalized = true;
		}
		IOException failure = null;
		try {
			if (record != null) { try { record.stop(); } catch (RuntimeException ignored) {} }
			if (sink != null) {
				try { sink.close(); } catch (IOException e) { failure = e; }
				stats.bytesWritten = sink.bytesOnDisk();
			}
		} finally {
			releaseAll();
		}
		if (failure != null) throw failure;
	}

	private void releaseAll() {
		if (fx != null) { fx.release(); fx = null; }
		releaseRecord();
	}

	private void releaseRecord() {
		if (record != null) { try { record.release(); } catch (RuntimeException ignored) {} record = null; }
	}

	@NonNull
	@Override
	public EngineStats stats() { return stats; }
}
