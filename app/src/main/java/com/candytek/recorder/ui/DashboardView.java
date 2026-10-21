package com.candytek.recorder.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import com.candytek.recorder.R;
import com.candytek.recorder.engine.MeterFrame;

import java.util.Locale;

/**
 * High-refresh visual dashboard: oscilloscope (or envelope history for Engine A),
 * log-frequency spectrum analyser with peak hold, and stereo peak/RMS meters with hold ticks.
 * Everything is drawn with plain Canvas calls; one invalidate per incoming frame.
 */
public final class DashboardView extends View {

	private static final int BARS = 56;
	private static final float DB_FLOOR_SPEC = -96f;
	private static final float DB_FLOOR_METER = -60f;
	private static final int ENVELOPE_POINTS = 240;

	private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint gridSoft = new Paint();
	private final Paint wavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint waveGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint barPaint = new Paint();
	private final Paint holdPaint = new Paint();
	private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint meterPaint = new Paint();
	private final Paint rmsPaint = new Paint();
	private final Paint panel = new Paint();
	private final Paint tagPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Path path = new Path();
	private final RectF rect = new RectF();

	private final int cAccent, cGreen, cAmber, cRed, cDim, cText, cPanel, cLine, cMagenta;

	@Nullable private MeterFrame frame;
	private final float[] barDb = new float[BARS];
	private final float[] barHold = new float[BARS];
	private final float[] envelope = new float[ENVELOPE_POINTS];
	private int envelopePos;
	private float holdL = -140f, holdR = -140f;
	private long holdLAt, holdRAt, clipAt, lastFrameNs;
	private float smoothPeakL = -140f, smoothPeakR = -140f;
	private boolean idle = true;

	public DashboardView(Context c) { this(c, null); }

	public DashboardView(Context c, @Nullable AttributeSet a) {
		super(c, a);
		cAccent = color(R.color.accent);
		cGreen = color(R.color.green);
		cAmber = color(R.color.amber);
		cRed = color(R.color.red);
		cDim = color(R.color.text_dim);
		cText = color(R.color.text);
		cPanel = color(R.color.bg_panel_alt);
		cLine = color(R.color.line);
		cMagenta = color(R.color.magenta);

		grid.setColor(cLine);
		grid.setStrokeWidth(1f);
		gridSoft.setColor(color(R.color.line_soft));
		wavePaint.setColor(cAccent);
		wavePaint.setStyle(Paint.Style.STROKE);
		wavePaint.setStrokeWidth(dp(1.5f));
		wavePaint.setStrokeJoin(Paint.Join.ROUND);
		waveGlow.setColor(cAccent);
		waveGlow.setAlpha(60);
		waveGlow.setStyle(Paint.Style.STROKE);
		waveGlow.setStrokeWidth(dp(5f));
		holdPaint.setColor(cText);
		holdPaint.setStrokeWidth(dp(1.5f));
		textPaint.setTypeface(Typeface.MONOSPACE);
		textPaint.setColor(cText);
		textPaint.setTextSize(dp(11));
		labelPaint.setTypeface(Typeface.MONOSPACE);
		labelPaint.setColor(cDim);
		labelPaint.setTextSize(dp(9));
		panel.setColor(cPanel);
		tagPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
		tagPaint.setTextSize(dp(10));
		for (int i = 0; i < BARS; i++) { barDb[i] = DB_FLOOR_SPEC; barHold[i] = DB_FLOOR_SPEC; }
		for (int i = 0; i < ENVELOPE_POINTS; i++) envelope[i] = -140f;
	}

	private int color(int res) { return getResources().getColor(res); }

	private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

	/** Clears everything back to the idle look. */
	public void reset() {
		frame = null;
		idle = true;
		for (int i = 0; i < BARS; i++) { barDb[i] = DB_FLOOR_SPEC; barHold[i] = DB_FLOOR_SPEC; }
		for (int i = 0; i < ENVELOPE_POINTS; i++) envelope[i] = -140f;
		holdL = holdR = smoothPeakL = smoothPeakR = -140f;
		invalidate();
	}

	public void push(MeterFrame f) {
		frame = f;
		idle = false;
		long now = f.timestampNs;
		float dtSec = lastFrameNs == 0 ? 0.016f : Math.min(0.1f, (now - lastFrameNs) / 1e9f);
		lastFrameNs = now;

		// peak hold with 1.2 s hold then 20 dB/s decay
		if (f.peakL >= holdL) { holdL = f.peakL; holdLAt = now; }
		else if (now - holdLAt > 1_200_000_000L) holdL = Math.max(f.peakL, holdL - 20f * dtSec);
		if (f.peakR >= holdR) { holdR = f.peakR; holdRAt = now; }
		else if (now - holdRAt > 1_200_000_000L) holdR = Math.max(f.peakR, holdR - 20f * dtSec);
		if (f.clipped) clipAt = now;

		// fast attack, 40 dB/s release for the bar
		smoothPeakL = f.peakL > smoothPeakL ? f.peakL : Math.max(f.peakL, smoothPeakL - 40f * dtSec);
		smoothPeakR = f.peakR > smoothPeakR ? f.peakR : Math.max(f.peakR, smoothPeakR - 40f * dtSec);

		envelope[envelopePos] = Math.max(f.peakL, f.peakR);
		envelopePos = (envelopePos + 1) % ENVELOPE_POINTS;

		if (f.spectrumDb != null) {
			int bins = f.spectrumDb.length;
			float nyquist = f.sampleRate / 2f;
			float fMin = 30f, fMax = Math.min(nyquist, 20000f);
			double ratio = Math.log(fMax / fMin);
			for (int i = 0; i < BARS; i++) {
				float lo = (float) (fMin * Math.exp(ratio * i / BARS));
				float hi = (float) (fMin * Math.exp(ratio * (i + 1) / BARS));
				int b0 = Math.max(1, (int) (lo / nyquist * bins));
				int b1 = Math.max(b0, (int) (hi / nyquist * bins));
				float m = -200f;
				for (int b = b0; b <= b1 && b < bins; b++) if (f.spectrumDb[b] > m) m = f.spectrumDb[b];
				if (m < DB_FLOOR_SPEC) m = DB_FLOOR_SPEC;
				barDb[i] = m > barDb[i] ? m : Math.max(m, barDb[i] - 60f * dtSec);
				barHold[i] = m >= barHold[i] ? m : Math.max(m, barHold[i] - 12f * dtSec);
			}
		}
		postInvalidateOnAnimation();
	}

	@Override
	protected void onDraw(Canvas c) {
		super.onDraw(c);
		float w = getWidth(), h = getHeight();
		float pad = dp(8);
		float scopeH = h * 0.42f;
		float specH = h * 0.36f;
		float meterH = h - scopeH - specH;

		drawScope(c, pad, pad, w - pad, scopeH - pad / 2);
		drawSpectrum(c, pad, scopeH + pad / 2, w - pad, scopeH + specH - pad / 2);
		drawMeters(c, pad, scopeH + specH + pad / 2, w - pad, h - pad);
	}

	private void drawScope(Canvas c, float l, float t, float r, float b) {
		rect.set(l, t, r, b);
		c.drawRoundRect(rect, dp(6), dp(6), panel);
		float mid = (t + b) / 2f;
		c.drawLine(l, mid, r, mid, grid);
		for (int i = 1; i < 8; i++) {
			float x = l + (r - l) * i / 8f;
			c.drawLine(x, t, x, b, gridSoft);
		}
		c.drawLine(l, t + (b - t) * 0.25f, r, t + (b - t) * 0.25f, gridSoft);
		c.drawLine(l, t + (b - t) * 0.75f, r, t + (b - t) * 0.75f, gridSoft);

		MeterFrame f = frame;
		if (f != null && f.wave != null && f.wave.length > 1) {
			float[] wv = f.wave;
			float amp = (b - t) * 0.48f;
			path.reset();
			for (int i = 0; i < wv.length; i++) {
				float x = l + (r - l) * i / (wv.length - 1);
				float v = wv[i];
				if (v > 1f) v = 1f; else if (v < -1f) v = -1f;
				float y = mid - v * amp;
				if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
			}
			c.drawPath(path, waveGlow);
			c.drawPath(path, wavePaint);
			tag(c, l + dp(6), t + dp(14), "SCOPE", cAccent);
		} else {
			// envelope history (Engine A, peak only)
			float barW = (r - l) / ENVELOPE_POINTS;
			for (int i = 0; i < ENVELOPE_POINTS; i++) {
				float db = envelope[(envelopePos + i) % ENVELOPE_POINTS];
				if (db <= DB_FLOOR_METER) continue;
				float n = (db - DB_FLOOR_METER) / -DB_FLOOR_METER;
				float hh = n * (b - t) * 0.48f;
				meterPaint.setColor(db > -6f ? cRed : db > -18f ? cAmber : cGreen);
				meterPaint.setAlpha(i == ENVELOPE_POINTS - 1 ? 255 : 150);
				c.drawRect(l + i * barW, mid - hh, l + (i + 1) * barW - 0.5f, mid + hh, meterPaint);
			}
			tag(c, l + dp(6), t + dp(14), idle ? "SCOPE" : "ENVELOPE", idle ? cDim : cAmber);
		}
		if (f != null) {
			String s = String.format(Locale.US, "PK %6s  RMS %6s dB", fmtDb(Math.max(f.peakL, f.peakR)), fmtDb(Math.max(f.rmsL, f.rmsR)));
			textPaint.setColor(cText);
			c.drawText(s, r - textPaint.measureText(s) - dp(8), t + dp(14), textPaint);
			if (f.gateClosed) tag(c, l + dp(60), t + dp(14), "GATE CLOSED", cAmber);
		}
		if (idle) {
			labelPaint.setColor(cDim);
			String s = "STANDBY";
			c.drawText(s, (l + r) / 2 - labelPaint.measureText(s) / 2, mid - dp(4), labelPaint);
		}
	}

	private void drawSpectrum(Canvas c, float l, float t, float r, float b) {
		rect.set(l, t, r, b);
		c.drawRoundRect(rect, dp(6), dp(6), panel);
		float top = t + dp(16), bottom = b - dp(12);
		for (int db = -80; db <= -20; db += 20) {
			float y = bottom - (db - DB_FLOOR_SPEC) / -DB_FLOOR_SPEC * (bottom - top);
			c.drawLine(l, y, r, y, gridSoft);
			labelPaint.setColor(cDim);
			c.drawText(db + "", l + dp(3), y - dp(2), labelPaint);
		}
		MeterFrame f = frame;
		boolean hasSpec = f != null && f.spectrumDb != null;
		float gap = dp(1);
		float barW = (r - l - dp(8)) / BARS;
		float x0 = l + dp(4);
		if (barPaint.getShader() == null || lastShaderHeight != (bottom - top)) {
			barPaint.setShader(new LinearGradient(0, top, 0, bottom, new int[] {cRed, cAmber, cGreen, cAccent},
				new float[] {0f, 0.15f, 0.45f, 1f}, Shader.TileMode.CLAMP));
			lastShaderHeight = bottom - top;
		}
		for (int i = 0; i < BARS; i++) {
			float n = (barDb[i] - DB_FLOOR_SPEC) / -DB_FLOOR_SPEC;
			if (n < 0) n = 0; else if (n > 1) n = 1;
			float x = x0 + i * barW;
			float y = bottom - n * (bottom - top);
			if (hasSpec || n > 0) c.drawRect(x, y, x + barW - gap, bottom, barPaint);
			float nh = (barHold[i] - DB_FLOOR_SPEC) / -DB_FLOOR_SPEC;
			if (nh > 0.005f) {
				float yh = bottom - Math.min(1f, nh) * (bottom - top);
				c.drawRect(x, yh - dp(1), x + barW - gap, yh, holdPaint);
			}
		}
		// frequency labels
		if (f != null) {
			float nyquist = f.sampleRate / 2f;
			float fMin = 30f, fMax = Math.min(nyquist, 20000f);
			double ratio = Math.log(fMax / fMin);
			int[] marks = {100, 1000, 10000};
			labelPaint.setColor(cDim);
			for (int m : marks) {
				if (m > fMax) continue;
				float pos = (float) (Math.log(m / fMin) / ratio);
				float x = x0 + pos * BARS * barW;
				c.drawLine(x, bottom, x, bottom + dp(3), grid);
				String s = m >= 1000 ? (m / 1000) + "k" : m + "";
				c.drawText(s, x - labelPaint.measureText(s) / 2, b - dp(2), labelPaint);
			}
		}
		tag(c, l + dp(6), t + dp(12), hasSpec ? "SPECTRUM  LOG 30Hz-20kHz" : (idle ? "SPECTRUM" : "SPECTRUM  N/A (ENGINE A)"),
			hasSpec ? cMagenta : cDim);
	}

	private float lastShaderHeight = -1;

	private void drawMeters(Canvas c, float l, float t, float r, float b) {
		rect.set(l, t, r, b);
		c.drawRoundRect(rect, dp(6), dp(6), panel);
		float labelW = dp(16);
		float scaleH = dp(10);
		float rowH = (b - t - scaleH - dp(8)) / 2f;
		float x0 = l + labelW + dp(4), x1 = r - dp(50);
		MeterFrame f = frame;
		drawMeterRow(c, "L", x0, x1, t + dp(4), rowH, smoothPeakL, f == null ? -140f : f.rmsL, holdL);
		drawMeterRow(c, "R", x0, x1, t + dp(4) + rowH, rowH, smoothPeakR, f == null ? -140f : f.rmsR, holdR);
		// scale
		labelPaint.setColor(cDim);
		float sy = b - dp(2);
		for (int db = -60; db <= 0; db += 12) {
			float x = x0 + (db - DB_FLOOR_METER) / -DB_FLOOR_METER * (x1 - x0);
			String s = String.valueOf(db);
			c.drawText(s, x - labelPaint.measureText(s) / 2, sy, labelPaint);
		}
		// clip indicator
		boolean clip = clipAt != 0 && System.nanoTime() - clipAt < 800_000_000L;
		tagPaint.setColor(clip ? cRed : cLine);
		rect.set(x1 + dp(8), t + dp(6), r - dp(6), t + dp(6) + rowH - dp(4));
		c.drawRoundRect(rect, dp(3), dp(3), tagPaint);
		tagPaint.setColor(clip ? Color.WHITE : cDim);
		c.drawText("CLIP", rect.centerX() - tagPaint.measureText("CLIP") / 2, rect.centerY() + dp(4), tagPaint);
		// hold readout
		textPaint.setColor(cText);
		String hold = fmtDb(Math.max(holdL, holdR));
		c.drawText(hold, rect.centerX() - textPaint.measureText(hold) / 2, t + dp(6) + rowH + dp(14), textPaint);
	}

	private void drawMeterRow(Canvas c, String label, float x0, float x1, float y, float rowH, float peakDb, float rmsDb, float holdDb) {
		float barT = y + dp(3), barB = y + rowH - dp(3);
		textPaint.setColor(cDim);
		c.drawText(label, x0 - dp(16), barB - dp(1), textPaint);
		rect.set(x0, barT, x1, barB);
		meterPaint.setColor(cLine);
		c.drawRect(rect, meterPaint);
		float n = (peakDb - DB_FLOOR_METER) / -DB_FLOOR_METER;
		if (n < 0) n = 0; else if (n > 1) n = 1;
		float xg = x0 + (x1 - x0) * (-18f - DB_FLOOR_METER) / -DB_FLOOR_METER;
		float xa = x0 + (x1 - x0) * (-6f - DB_FLOOR_METER) / -DB_FLOOR_METER;
		float xe = x0 + (x1 - x0) * n;
		meterPaint.setColor(cGreen);
		if (xe > x0) c.drawRect(x0, barT, Math.min(xe, xg), barB, meterPaint);
		if (xe > xg) { meterPaint.setColor(cAmber); c.drawRect(xg, barT, Math.min(xe, xa), barB, meterPaint); }
		if (xe > xa) { meterPaint.setColor(cRed); c.drawRect(xa, barT, xe, barB, meterPaint); }
		if (rmsDb > DB_FLOOR_METER) {
			float nr = Math.min(1f, (rmsDb - DB_FLOOR_METER) / -DB_FLOOR_METER);
			rmsPaint.setColor(Color.WHITE);
			rmsPaint.setAlpha(90);
			c.drawRect(x0, barT + (barB - barT) * 0.3f, x0 + (x1 - x0) * nr, barB - (barB - barT) * 0.3f, rmsPaint);
		}
		if (holdDb > DB_FLOOR_METER) {
			float nh = Math.min(1f, (holdDb - DB_FLOOR_METER) / -DB_FLOOR_METER);
			float xh = x0 + (x1 - x0) * nh;
			holdPaint.setColor(holdDb > -1f ? cRed : cText);
			c.drawRect(xh - dp(1), barT, xh + dp(1), barB, holdPaint);
		}
	}

	private void tag(Canvas c, float x, float y, String s, int color) {
		tagPaint.setColor(color);
		c.drawText(s, x, y, tagPaint);
	}

	private static String fmtDb(float db) {
		if (db <= -139f) return "-inf";
		return String.format(Locale.US, "%.1f", db);
	}
}
