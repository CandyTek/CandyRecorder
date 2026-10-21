package com.candytek.recorder.util;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Number / time formatting helpers (locale-neutral, monospace friendly). */
public final class Fmt {

	private Fmt() {}

	/** hh:mm:ss.mmm */
	public static String clock(long ms) {
		if (ms < 0) ms = 0;
		long h = ms / 3600000L;
		long m = (ms / 60000L) % 60;
		long s = (ms / 1000L) % 60;
		long f = ms % 1000L;
		return String.format(Locale.US, "%02d:%02d:%02d.%03d", h, m, s, f);
	}

	/** mm:ss or h:mm:ss */
	public static String duration(long ms) {
		if (ms < 0) ms = 0;
		long h = ms / 3600000L;
		long m = (ms / 60000L) % 60;
		long s = (ms / 1000L) % 60;
		if (h > 0) return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
		return String.format(Locale.US, "%02d:%02d", m, s);
	}

	public static String bytes(long b) {
		if (b < 1024) return b + " B";
		if (b < 1024 * 1024) return String.format(Locale.US, "%.1f KB", b / 1024.0);
		if (b < 1024L * 1024 * 1024) return String.format(Locale.US, "%.2f MB", b / (1024.0 * 1024));
		return String.format(Locale.US, "%.2f GB", b / (1024.0 * 1024 * 1024));
	}

	public static String kbps(double bitsPerSecond) {
		return String.format(Locale.US, "%.1f kbps", bitsPerSecond / 1000.0);
	}

	public static String db(float db) {
		if (db <= -140f) return "-inf";
		return String.format(Locale.US, "%.1f", db);
	}

	public static String date(long epochMs) {
		return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(epochMs));
	}

	public static String stamp(long epochMs) {
		return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date(epochMs));
	}

	public static String fixed(double v, int decimals) {
		return String.format(Locale.US, "%." + decimals + "f", v);
	}
}
