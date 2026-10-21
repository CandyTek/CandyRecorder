package com.candytek.recorder.engine;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Live telemetry exposed to the dashboard. Written from the audio thread, read from the UI
 * thread at ~10 Hz; every field is volatile so torn reads are impossible for primitives.
 */
public final class EngineStats {

	// --- static (set once at start) ---
	public volatile String engineName = "";
	public volatile String sourceName = "";
	public volatile String deviceName = "";
	public volatile String encodingName = "";
	public volatile String codecName = "";
	public volatile String filePath = "";
	public volatile int requestedSampleRate;
	public volatile int sampleRate;
	public volatile int channels;
	public volatile int bufferFrames;
	public volatile int chunkFrames;
	public volatile double bufferLatencyMs;
	public volatile int audioSessionId = -1;

	// --- dynamic ---
	public volatile long elapsedNs;
	public volatile long bytesWritten;
	public volatile long framesRead;
	public volatile long framesSkipped;
	public volatile long chunksRead;
	public volatile long readAvgUs;
	public volatile long readMaxUs;
	public volatile long driftFrames;
	public volatile long clipCount;
	public volatile float peakDbL = -140f, peakDbR = -140f, rmsDbL = -140f, rmsDbR = -140f;
	public volatile boolean gateClosed;
	public volatile int gateEvents;
	public volatile boolean paused;
	public volatile String routedDevice = "";
	public volatile String micInfo = "";

	/** Extra key/value lines (effects status, direction result, codec config, …). */
	private final Map<String, String> extras = new LinkedHashMap<>();

	public synchronized void putExtra(String key, String value) { extras.put(key, value); }

	public synchronized Map<String, String> extrasSnapshot() { return new LinkedHashMap<>(extras); }

	public long elapsedMs() { return elapsedNs / 1_000_000L; }

	/** Measured container bitrate (bits per second) from bytes on disk vs. elapsed time. */
	public double measuredBitrate() {
		long ms = elapsedMs();
		if (ms <= 0) return 0;
		return bytesWritten * 8.0 * 1000.0 / ms;
	}
}
