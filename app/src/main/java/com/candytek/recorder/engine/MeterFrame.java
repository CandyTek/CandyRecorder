package com.candytek.recorder.engine;

import androidx.annotation.Nullable;

/** One visual update for the dashboard. Immutable after construction; a fresh object per update. */
public final class MeterFrame {

	/** Mono mix of the most recent chunk, -1..1, decimated for drawing. Null for Engine A. */
	@Nullable public final float[] wave;
	/** Magnitude spectrum in dBFS, one value per FFT bin. Null for Engine A. */
	@Nullable public final float[] spectrumDb;
	/** Sample rate of the analysed signal (needed to map bins to Hz). */
	public final int sampleRate;
	/** Levels in dBFS. For mono, R mirrors L. */
	public final float peakL, peakR, rmsL, rmsR;
	public final boolean gateClosed;
	public final boolean clipped;
	public final long timestampNs;

	public MeterFrame(@Nullable float[] wave, @Nullable float[] spectrumDb, int sampleRate,
	                  float peakL, float peakR, float rmsL, float rmsR,
	                  boolean gateClosed, boolean clipped, long timestampNs) {
		this.wave = wave;
		this.spectrumDb = spectrumDb;
		this.sampleRate = sampleRate;
		this.peakL = peakL; this.peakR = peakR; this.rmsL = rmsL; this.rmsR = rmsR;
		this.gateClosed = gateClosed;
		this.clipped = clipped;
		this.timestampNs = timestampNs;
	}
}
