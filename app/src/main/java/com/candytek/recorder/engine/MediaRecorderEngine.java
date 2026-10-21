package com.candytek.recorder.engine;

import android.media.AudioDeviceInfo;
import android.media.MediaRecorder;
import android.media.MicrophoneInfo;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;

import androidx.annotation.NonNull;

import com.candytek.recorder.core.Choices;
import com.candytek.recorder.core.RecordingConfig;
import com.candytek.recorder.dsp.PcmUtil;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Engine A: MediaRecorder. The platform packs the encoded stream straight into the container
 * (3GP / MP4 / M4A / Ogg / ADTS / TS / AMR). Only the peak level is observable, so the dashboard
 * runs in envelope mode and silence skipping uses pause()/resume() (API 24+).
 */
public final class MediaRecorderEngine implements RecorderEngine {

	private static final long POLL_MS = 40;

	private final EngineStats stats = new EngineStats();
	private RecordingConfig cfg;
	private Listener listener;
	private MediaRecorder mr;
	private File outFile;
	private HandlerThread pollThread;
	private Handler poll;
	private SilenceGate gate;
	private volatile boolean running, userPaused, gatePaused, finalized;
	private long accumNs, segmentStartNs;
	private final Object lifecycle = new Object();
	private int pollTick;

	@Override
	public void start(@NonNull StartParams p) throws Exception {
		cfg = p.config;
		listener = p.listener;
		outFile = p.outFile;
		int source = cfg.effectiveSource(p.context);

		mr = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(p.context) : new MediaRecorder();
		try {
			mr.setAudioSource(source);
			mr.setOutputFormat(cfg.formatA.outputFormat);
			mr.setAudioEncoder(cfg.encoderA);
			mr.setAudioChannels(cfg.channels);
			mr.setAudioSamplingRate(cfg.sampleRate);
			mr.setAudioEncodingBitRate(cfg.bitrate);
			if (Build.VERSION.SDK_INT >= 28 && p.preferredDevice != null) {
				boolean ok = mr.setPreferredDevice(p.preferredDevice);
				stats.putExtra("setPreferredDevice", (ok ? "ok: " : "rejected: ") + AudioRouter.describe(p.preferredDevice));
			}
			if (Build.VERSION.SDK_INT >= 29) {
				if (cfg.micDirection != 0) {
					boolean ok = mr.setPreferredMicrophoneDirection(cfg.micDirection);
					stats.putExtra("Mic direction", (ok ? "accepted" : "rejected") + " (mode " + cfg.micDirection + ")");
				}
				if (!Float.isNaN(cfg.fieldDimension)) {
					boolean ok = mr.setPreferredMicrophoneFieldDimension(cfg.fieldDimension);
					stats.putExtra("Field dimension", (ok ? "accepted " : "rejected ") + cfg.fieldDimension);
				}
			} else if (cfg.micDirection != 0 || !Float.isNaN(cfg.fieldDimension)) {
				stats.putExtra("Mic direction", "needs API 29");
			}
			if (Build.VERSION.SDK_INT >= 31 && cfg.privacySensitive) mr.setPrivacySensitive(true);
			mr.setOutputFile(outFile.getAbsolutePath());
			mr.setOnErrorListener(new MediaRecorder.OnErrorListener() {
				@Override public void onError(MediaRecorder r, int what, int extra) {
					fail("MediaRecorder error what=" + what + " extra=" + extra);
				}
			});
			mr.setOnInfoListener(new MediaRecorder.OnInfoListener() {
				@Override public void onInfo(MediaRecorder r, int what, int extra) {
					stats.putExtra("Last info", "what=" + what + " extra=" + extra);
				}
			});
			mr.prepare();
			mr.start();
		} catch (IOException | RuntimeException e) {
			releaseRecorder();
			throw new IOException("MediaRecorder start failed: " + e.getMessage(), e);
		}

		stats.engineName = "A · MediaRecorder";
		stats.sourceName = sourceName(source);
		stats.requestedSampleRate = cfg.sampleRate;
		stats.sampleRate = cfg.sampleRate;
		stats.channels = cfg.channels;
		stats.encodingName = "encoder-managed PCM";
		stats.codecName = encoderName(cfg.encoderA) + " in " + cfg.formatA.ext.toUpperCase() + " (OutputFormat "
			+ cfg.formatA.outputFormat + "), " + cfg.bitrate / 1000 + " kbps";
		stats.filePath = outFile.getAbsolutePath();
		stats.deviceName = p.preferredDevice != null ? AudioRouter.describe(p.preferredDevice) : "auto";
		stats.bufferFrames = 0;
		stats.chunkFrames = 0;
		stats.putExtra("Effects", cfg.rawMode ? "raw mode (UNPROCESSED when supported)" : "platform default for source");
		if (cfg.silenceSkip && Build.VERSION.SDK_INT < 24) stats.putExtra("Silence skip", "needs API 24 (pause/resume)");

		gate = (cfg.silenceSkip && Build.VERSION.SDK_INT >= 24) ? new SilenceGate(cfg.silenceDb, cfg.silenceHoldMs) : null;
		running = true;
		userPaused = false;
		gatePaused = false;
		accumNs = 0;
		segmentStartNs = System.nanoTime();
		refreshRouting();

		pollThread = new HandlerThread("candy-poll");
		pollThread.start();
		poll = new Handler(pollThread.getLooper());
		poll.post(pollRunnable);
	}

	private final Runnable pollRunnable = new Runnable() {
		@Override public void run() {
			if (!running) return;
			try { tick(); } catch (RuntimeException e) { fail("Polling failed: " + e.getMessage()); return; }
			if (running && poll != null) poll.postDelayed(this, POLL_MS);
		}
	};

	private void tick() {
		long now = System.nanoTime();
		int amp = 0;
		try { amp = mr.getMaxAmplitude(); } catch (RuntimeException ignored) {}
		float lin = amp / 32767f;
		float peakDb = PcmUtil.dbfs(lin);
		boolean clipped = amp >= 32700;
		if (clipped) stats.clipCount++;
		stats.peakDbL = peakDb; stats.peakDbR = peakDb;
		stats.rmsDbL = -140f; stats.rmsDbR = -140f;

		boolean recordingNow = !userPaused && !gatePaused;
		if (!userPaused && gate != null) {
			boolean drop = gate.update(peakDb, now);
			if (drop && !gatePaused) {
				if (pauseRecorder()) { gatePaused = true; accumNs += now - segmentStartNs; }
			} else if (!drop && gatePaused) {
				if (resumeRecorder()) { gatePaused = false; segmentStartNs = now; }
			}
			stats.gateClosed = gatePaused;
			stats.gateEvents = gate.events();
			if (gatePaused) stats.framesSkipped += (long) (POLL_MS * cfg.sampleRate / 1000L);
			recordingNow = !gatePaused;
		}
		stats.elapsedNs = recordingNow ? accumNs + (now - segmentStartNs) : accumNs;
		if (recordingNow) stats.framesRead = (long) (stats.elapsedNs / 1e9 * cfg.sampleRate);
		stats.chunksRead++;
		if ((++pollTick & 7) == 0) stats.bytesWritten = outFile.length();
		if ((pollTick & 63) == 0) refreshRouting();

		listener.onMeter(new MeterFrame(null, null, cfg.sampleRate, peakDb, peakDb, -140f, -140f,
			gatePaused, clipped, now));
	}

	private boolean pauseRecorder() {
		if (Build.VERSION.SDK_INT < 24) return false;
		try { mr.pause(); return true; } catch (RuntimeException e) { stats.putExtra("pause()", "failed: " + e.getMessage()); return false; }
	}

	private boolean resumeRecorder() {
		if (Build.VERSION.SDK_INT < 24) return false;
		try { mr.resume(); return true; } catch (RuntimeException e) { stats.putExtra("resume()", "failed: " + e.getMessage()); return false; }
	}

	private void refreshRouting() {
		try {
			if (Build.VERSION.SDK_INT >= 28) {
				AudioDeviceInfo d = mr.getRoutedDevice();
				stats.routedDevice = AudioRouter.describe(d);
			}
			if (Build.VERSION.SDK_INT >= 29) {
				List<MicrophoneInfo> mics = mr.getActiveMicrophones();
				StringBuilder sb = new StringBuilder();
				for (MicrophoneInfo m : mics) {
					if (sb.length() > 0) sb.append(" | ");
					sb.append(m.getDescription()).append(" dir=").append(m.getDirectionality());
				}
				stats.micInfo = sb.toString();
			}
		} catch (IOException | RuntimeException ignored) {
			// best effort
		}
	}

	private void fail(String msg) {
		if (!running) return;
		running = false;
		String m = msg;
		try { finalizeOnce(); } catch (IOException e) { m += " / " + e.getMessage(); }
		listener.onError(m);
	}

	@Override
	public void pause() {
		if (userPaused || !running) return;
		if (!gatePaused) {
			if (!pauseRecorder()) { stats.putExtra("pause()", "unsupported below API 24"); return; }
			accumNs += System.nanoTime() - segmentStartNs;
		}
		userPaused = true;
		stats.paused = true;
	}

	@Override
	public void resume() {
		if (!userPaused || !running) return;
		if (!gatePaused) {
			if (!resumeRecorder()) return;
			segmentStartNs = System.nanoTime();
		}
		userPaused = false;
		stats.paused = false;
	}

	@Override
	public boolean isPaused() { return userPaused; }

	@Override
	public void stop() throws Exception {
		running = false;
		if (!userPaused && !gatePaused) accumNs += System.nanoTime() - segmentStartNs;
		stats.elapsedNs = accumNs;
		finalizeOnce();
	}

	private void finalizeOnce() throws IOException {
		synchronized (lifecycle) {
			if (finalized) return;
			finalized = true;
		}
		if (poll != null) poll.removeCallbacksAndMessages(null);
		if (pollThread != null) { pollThread.quitSafely(); pollThread = null; poll = null; }
		IOException failure = null;
		if (mr != null) {
			try {
				mr.stop();
			} catch (RuntimeException e) {
				// stop() throws when nothing valid was written (e.g. stopped immediately).
				failure = new IOException("MediaRecorder.stop: " + e.getMessage() + " (file may be empty)");
			}
		}
		releaseRecorder();
		stats.bytesWritten = outFile != null ? outFile.length() : 0;
		if (failure != null) throw failure;
	}

	private void releaseRecorder() {
		if (mr != null) {
			try { mr.reset(); } catch (RuntimeException ignored) {}
			try { mr.release(); } catch (RuntimeException ignored) {}
			mr = null;
		}
	}

	private static String sourceName(int source) {
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

	private static String encoderName(int enc) {
		switch (enc) {
			case MediaRecorder.AudioEncoder.AMR_NB: return "AMR-NB";
			case MediaRecorder.AudioEncoder.AMR_WB: return "AMR-WB";
			case MediaRecorder.AudioEncoder.AAC: return "AAC-LC";
			case MediaRecorder.AudioEncoder.HE_AAC: return "HE-AAC";
			case MediaRecorder.AudioEncoder.AAC_ELD: return "AAC-ELD";
			case 7: return "Opus";
			default: return "encoder#" + enc;
		}
	}

	@NonNull
	@Override
	public EngineStats stats() { return stats; }
}
