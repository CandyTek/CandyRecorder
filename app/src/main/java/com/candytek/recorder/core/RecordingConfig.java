package com.candytek.recorder.core;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.MediaRecorder;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.candytek.recorder.R;

/**
 * Immutable snapshot of every parameter that influences a single recording session.
 */
public final class RecordingConfig {

	public final boolean engineB;
	public final int audioSource;
	public final int channels;
	public final int sampleRate;
	public final int bitrate;
	public final int bitrateMode;
	public final int flacLevel;
	public final int encoderA;
	public final Choices.FormatA formatA;
	public final int pcmEncoding;
	public final Choices.CodecB codecB;
	public final int bufferMultiplier;
	public final boolean privacySensitive;
	public final int ns, aec, agc;
	public final boolean rawMode;
	public final int micDirection;
	public final float fieldDimension;
	public final int route;
	public final boolean silenceSkip;
	public final int silenceDb;
	public final int silenceHoldMs;
	public final int storage;
	public final String filePrefix;
	public final boolean keepScreenOn;
	public final int fftSize;
	public final int meterSpeed;

	private RecordingConfig(Prefs p) {
		engineB = p.engineB();
		audioSource = p.source();
		channels = p.channels();
		int rate = p.sampleRate();
		int br = p.bitrate();
		encoderA = p.encoderA();
		formatA = Choices.formatA(p.formatA());
		codecB = Choices.codecB(p.codecB());
		pcmEncoding = p.pcmEncoding();
		bitrateMode = p.bitrateMode();
		flacLevel = p.flacLevel();
		bufferMultiplier = p.bufferMultiplier();
		privacySensitive = p.privacySensitive();
		ns = p.ns(); aec = p.aec(); agc = p.agc();
		rawMode = p.rawMode();
		micDirection = p.micDirection();
		fieldDimension = Choices.fieldDimension(p.fieldDimension());
		route = p.route();
		silenceSkip = p.silenceSkip();
		silenceDb = p.silenceDb();
		silenceHoldMs = p.silenceHoldMs();
		storage = p.storage();
		filePrefix = sanitizePrefix(p.filePrefix());
		keepScreenOn = p.keepScreenOn();
		fftSize = p.fftSize();
		meterSpeed = p.meterSpeed();

		// AMR codecs are fixed-rate narrow/wide band: force the sampling rate and bitrate.
		if (!engineB) {
			if (encoderA == MediaRecorder.AudioEncoder.AMR_NB) { rate = 8000; br = 12200; }
			else if (encoderA == MediaRecorder.AudioEncoder.AMR_WB) { rate = 16000; br = 23850; }
		}
		sampleRate = rate;
		bitrate = br;
	}

	public static RecordingConfig from(Prefs p) { return new RecordingConfig(p); }

	private static String sanitizePrefix(String s) {
		if (s == null) return "REC";
		String t = s.trim().replaceAll("[^A-Za-z0-9_\\-]", "");
		return t.isEmpty() ? "REC" : t;
	}

	public boolean isPlaybackCapture() { return audioSource == Choices.SOURCE_PLAYBACK_CAPTURE; }

	public boolean isCompressed() { return engineB ? codecB.compressed : true; }

	public boolean isPcmOutput() { return engineB && !codecB.compressed; }

	@NonNull
	public String fileExtension() { return engineB ? codecB.ext : formatA.ext; }

	public int channelMaskIn() {
		return channels == 1 ? AudioFormat.CHANNEL_IN_MONO : AudioFormat.CHANNEL_IN_STEREO;
	}

	/** Effective audio source after applying "raw mode" (UNPROCESSED when the device supports it). */
	public int effectiveSource(Context ctx) {
		if (rawMode && !isPlaybackCapture() && Build.VERSION.SDK_INT >= 24 && supportsUnprocessed(ctx)) {
			return MediaRecorder.AudioSource.UNPROCESSED;
		}
		return audioSource;
	}

	public static boolean supportsUnprocessed(Context ctx) {
		if (Build.VERSION.SDK_INT < 24) return false;
		AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
		if (am == null) return false;
		String v = am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED);
		return "true".equalsIgnoreCase(v);
	}

	/** @return a human-readable problem, or null when the configuration can be started. */
	@Nullable
	public String validate(Context ctx) {
		if (isPlaybackCapture()) {
			if (Build.VERSION.SDK_INT < 29) return ctx.getString(R.string.err_capture_api);
			if (!engineB) return ctx.getString(R.string.err_capture_engine);
		}
		if (!engineB) {
			if (!formatA.available()) return ctx.getString(R.string.err_format_api);
			Choices.Opt enc = Choices.find(Choices.ENCODERS_A, encoderA);
			if (enc == null || !enc.available()) return ctx.getString(R.string.err_encoder_api);
			if (!formatA.supports(encoderA)) return ctx.getString(R.string.err_format_encoder);
		} else {
			if (!codecB.available()) return ctx.getString(R.string.err_codec_api);
			Choices.Opt pcm = Choices.find(Choices.PCM_ENCODINGS, pcmEncoding);
			if (pcm == null || !pcm.available()) return ctx.getString(R.string.err_pcm_api);
			if (Build.VERSION.SDK_INT < 23 && pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) return ctx.getString(R.string.err_pcm_api);
		}
		return null;
	}

	/** Short summary used in the dashboard header, e.g. "B · WAV · 48000 Hz · 2ch · 16-bit". */
	@NonNull
	public String summary(Context ctx) {
		StringBuilder sb = new StringBuilder();
		sb.append(engineB ? ctx.getString(R.string.engine_b_short) : ctx.getString(R.string.engine_a_short));
		sb.append(" · ");
		if (engineB) {
			sb.append(ctx.getString(codecB.label));
			Choices.Opt pcm = Choices.find(Choices.PCM_ENCODINGS, pcmEncoding);
			if (pcm != null) sb.append(" · ").append(ctx.getString(pcm.label));
		} else {
			sb.append(ctx.getString(formatA.label));
			Choices.Opt enc = Choices.find(Choices.ENCODERS_A, encoderA);
			if (enc != null) sb.append(" · ").append(ctx.getString(enc.label));
		}
		sb.append(" · ").append(sampleRate).append(" Hz · ").append(channels).append("ch");
		if (isCompressed()) sb.append(" · ").append(bitrate / 1000).append(" kbps");
		return sb.toString();
	}
}
