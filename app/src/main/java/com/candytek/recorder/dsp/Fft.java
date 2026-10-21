package com.candytek.recorder.dsp;

/**
 * Iterative radix-2 FFT with a Hann window, tuned for the real-time spectrum display.
 * Output is a magnitude spectrum in dBFS where a full-scale sine reads 0 dB.
 */
public final class Fft {

	private final int n;
	private final int bits;
	private final float[] cosT, sinT, window, re, im;
	private final int[] rev;

	public Fft(int size) {
		if (Integer.bitCount(size) != 1 || size < 16) throw new IllegalArgumentException("size must be a power of two");
		n = size;
		bits = Integer.numberOfTrailingZeros(size);
		cosT = new float[n / 2];
		sinT = new float[n / 2];
		for (int i = 0; i < n / 2; i++) {
			double a = -2.0 * Math.PI * i / n;
			cosT[i] = (float) Math.cos(a);
			sinT[i] = (float) Math.sin(a);
		}
		window = new float[n];
		for (int i = 0; i < n; i++) window[i] = (float) (0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (n - 1)));
		rev = new int[n];
		for (int i = 0; i < n; i++) rev[i] = Integer.reverse(i) >>> (32 - bits);
		re = new float[n];
		im = new float[n];
	}

	public int size() { return n; }

	public int bins() { return n / 2; }

	/**
	 * @param input  at least {@link #size()} samples in -1..1
	 * @param offset start index into input
	 * @param outDb  at least {@link #bins()} entries; receives magnitudes in dBFS
	 */
	public void magnitudesDb(float[] input, int offset, float[] outDb) {
		for (int i = 0; i < n; i++) {
			re[rev[i]] = input[offset + i] * window[i];
			im[rev[i]] = 0f;
		}
		for (int len = 2; len <= n; len <<= 1) {
			int half = len >> 1;
			int step = n / len;
			for (int i = 0; i < n; i += len) {
				for (int j = 0, k = 0; j < half; j++, k += step) {
					float wr = cosT[k], wi = sinT[k];
					int a = i + j, b = a + half;
					float tr = re[b] * wr - im[b] * wi;
					float ti = re[b] * wi + im[b] * wr;
					re[b] = re[a] - tr;
					im[b] = im[a] - ti;
					re[a] += tr;
					im[a] += ti;
				}
			}
		}
		// Hann coherent gain is 0.5 -> scale 4/N maps a full-scale sine to 0 dB.
		float scale = 4f / n;
		int half = n / 2;
		for (int k = 0; k < half; k++) {
			float m = (float) Math.sqrt(re[k] * re[k] + im[k] * im[k]) * scale;
			outDb[k] = m < 1e-7f ? -140f : (float) (20.0 * Math.log10(m));
		}
	}
}
