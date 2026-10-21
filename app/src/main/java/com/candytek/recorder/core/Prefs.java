package com.candytek.recorder.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioFormat;
import android.media.MediaRecorder;

import androidx.annotation.NonNull;

/**
 * Thin typed wrapper over SharedPreferences. All user-tunable parameters live here;
 * {@link RecordingConfig#from(Prefs)} snapshots them into an immutable object at record time.
 */
public final class Prefs {

	public static final String NAME = "candy_prefs";

	// ---- keys -------------------------------------------------------------------------------
	public static final String ENGINE = "engine";                 // "A" | "B"
	public static final String SOURCE = "source";                 // MediaRecorder.AudioSource.* or Choices.SOURCE_PLAYBACK_CAPTURE
	public static final String CHANNELS = "channels";             // 1 | 2
	public static final String SAMPLE_RATE = "sample_rate";
	public static final String BITRATE = "bitrate";               // bits per second
	public static final String BITRATE_MODE = "bitrate_mode";     // Choices.BR_MODE_*
	public static final String FLAC_LEVEL = "flac_level";         // 0..8
	public static final String ENCODER_A = "encoder_a";           // MediaRecorder.AudioEncoder.*
	public static final String FORMAT_A = "format_a";             // Choices.FormatA.id
	public static final String PCM_ENCODING = "pcm_encoding";     // AudioFormat.ENCODING_PCM_*
	public static final String CODEC_B = "codec_b";               // Choices.CodecB id
	public static final String BUFFER_MULT = "buffer_mult";       // 1,2,4,8
	public static final String PRIVACY_SENSITIVE = "privacy_sensitive";
	public static final String NS = "fx_ns";                      // Choices.FX_*
	public static final String AEC = "fx_aec";
	public static final String AGC = "fx_agc";
	public static final String RAW_MODE = "raw_mode";
	public static final String MIC_DIRECTION = "mic_direction";   // 0..3 (MicrophoneDirection)
	public static final String FIELD_DIMENSION = "field_dimension"; // Choices.FIELD_* key string
	public static final String ROUTE = "route";                   // Choices.ROUTE_*
	public static final String SILENCE_SKIP = "silence_skip";
	public static final String SILENCE_DB = "silence_db";
	public static final String SILENCE_HOLD = "silence_hold_ms";
	public static final String STORAGE = "storage";               // Choices.STORAGE_*
	public static final String FILE_PREFIX = "file_prefix";
	public static final String KEEP_SCREEN_ON = "keep_screen_on";
	public static final String FFT_SIZE = "fft_size";
	public static final String METER_SPEED = "meter_speed";       // Choices.METER_*

	private final SharedPreferences sp;

	public Prefs(@NonNull Context c) {
		sp = c.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
	}

	// ---- defaults ---------------------------------------------------------------------------
	public boolean engineB() { return "B".equals(getString(ENGINE, "A")); }
	public int source() { return getInt(SOURCE, MediaRecorder.AudioSource.MIC); }
	public int channels() { return getInt(CHANNELS, 2); }
	public int sampleRate() { return getInt(SAMPLE_RATE, 48000); }
	public int bitrate() { return getInt(BITRATE, 128000); }
	public int bitrateMode() { return getInt(BITRATE_MODE, Choices.BR_MODE_DEFAULT); }
	public int flacLevel() { return getInt(FLAC_LEVEL, 5); }
	public int encoderA() { return getInt(ENCODER_A, MediaRecorder.AudioEncoder.AAC); }
	public String formatA() { return getString(FORMAT_A, "m4a"); }
	public int pcmEncoding() { return getInt(PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT); }
	public String codecB() { return getString(CODEC_B, "wav"); }
	public int bufferMultiplier() { return getInt(BUFFER_MULT, 2); }
	public boolean privacySensitive() { return getBool(PRIVACY_SENSITIVE, false); }
	public int ns() { return getInt(NS, Choices.FX_DEFAULT); }
	public int aec() { return getInt(AEC, Choices.FX_DEFAULT); }
	public int agc() { return getInt(AGC, Choices.FX_DEFAULT); }
	public boolean rawMode() { return getBool(RAW_MODE, false); }
	public int micDirection() { return getInt(MIC_DIRECTION, 0); }
	public String fieldDimension() { return getString(FIELD_DIMENSION, "off"); }
	public int route() { return getInt(ROUTE, Choices.ROUTE_AUTO); }
	public boolean silenceSkip() { return getBool(SILENCE_SKIP, false); }
	public int silenceDb() { return getInt(SILENCE_DB, -50); }
	public int silenceHoldMs() { return getInt(SILENCE_HOLD, 1000); }
	public int storage() { return getInt(STORAGE, Choices.STORAGE_APP); }
	public String filePrefix() { return getString(FILE_PREFIX, "REC"); }
	public boolean keepScreenOn() { return getBool(KEEP_SCREEN_ON, true); }
	public int fftSize() { return getInt(FFT_SIZE, 2048); }
	public int meterSpeed() { return getInt(METER_SPEED, Choices.METER_FAST); }

	// ---- generic accessors ------------------------------------------------------------------
	public String getString(String k, String d) { return sp.getString(k, d); }
	public int getInt(String k, int d) { return sp.getInt(k, d); }
	public boolean getBool(String k, boolean d) { return sp.getBoolean(k, d); }

	public void put(String k, String v) { sp.edit().putString(k, v).apply(); }
	public void put(String k, int v) { sp.edit().putInt(k, v).apply(); }
	public void put(String k, boolean v) { sp.edit().putBoolean(k, v).apply(); }

	public void resetAll() { sp.edit().clear().apply(); }

	public RecordingConfig buildConfig() { return RecordingConfig.from(this); }
}
