package com.candytek.recorder.core;

import android.media.AudioFormat;
import android.media.MediaRecorder;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

import com.candytek.recorder.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Static catalogs of every selectable parameter. Labels are string resources so that the
 * whole interface stays in strings.xml; values are the raw platform constants.
 */
public final class Choices {

	private Choices() {}

	/** Pseudo audio source: AudioPlaybackCaptureConfiguration (MediaProjection) on API 29+. */
	public static final int SOURCE_PLAYBACK_CAPTURE = -100;

	public static final int BR_MODE_DEFAULT = 0, BR_MODE_CBR = 1, BR_MODE_VBR = 2, BR_MODE_CQ = 3;
	public static final int FX_DEFAULT = 0, FX_ON = 1, FX_OFF = 2;
	public static final int ROUTE_AUTO = 0, ROUTE_BUILTIN = 1, ROUTE_WIRED = 2, ROUTE_BT_SCO = 3, ROUTE_USB = 4;
	public static final int STORAGE_APP = 0, STORAGE_PUBLIC = 1;
	public static final int METER_SLOW = 0, METER_FAST = 1;

	/** A generic labelled option with a platform value and an API floor. */
	public static final class Opt {
		public final int value;
		@StringRes public final int label;
		public final int minApi;
		public final boolean privileged;

		Opt(int value, @StringRes int label, int minApi, boolean privileged) {
			this.value = value; this.label = label; this.minApi = minApi; this.privileged = privileged;
		}
		Opt(int value, @StringRes int label, int minApi) { this(value, label, minApi, false); }
		Opt(int value, @StringRes int label) { this(value, label, 1, false); }

		public boolean available() { return Build.VERSION.SDK_INT >= minApi; }
	}

	/** Engine A container choice: a MediaRecorder output format plus the file extension. */
	public static final class FormatA {
		public final String id;
		public final int outputFormat;
		public final String ext;
		@StringRes public final int label;
		public final int minApi;
		public final int[] encoders;

		FormatA(String id, int outputFormat, String ext, @StringRes int label, int minApi, int... encoders) {
			this.id = id; this.outputFormat = outputFormat; this.ext = ext; this.label = label;
			this.minApi = minApi; this.encoders = encoders;
		}
		public boolean available() { return Build.VERSION.SDK_INT >= minApi; }
		public boolean supports(int encoder) {
			for (int e : encoders) if (e == encoder) return true;
			return false;
		}
	}

	/** Engine B codec/container choice. */
	public static final class CodecB {
		public final String id;
		public final String ext;
		@StringRes public final int label;
		public final int minApi;
		public final boolean compressed;
		public final String mime;      // MediaCodec mime, null for PCM
		public final int aacProfile;   // MediaCodecInfo.CodecProfileLevel.AACObject*, 0 if n/a

		CodecB(String id, String ext, @StringRes int label, int minApi, boolean compressed, String mime, int aacProfile) {
			this.id = id; this.ext = ext; this.label = label; this.minApi = minApi;
			this.compressed = compressed; this.mime = mime; this.aacProfile = aacProfile;
		}
		public boolean available() { return Build.VERSION.SDK_INT >= minApi; }
	}

	// ---- audio sources ----------------------------------------------------------------------
	public static final Opt[] SOURCES = {
		new Opt(MediaRecorder.AudioSource.DEFAULT, R.string.src_default),
		new Opt(MediaRecorder.AudioSource.MIC, R.string.src_mic),
		new Opt(MediaRecorder.AudioSource.CAMCORDER, R.string.src_camcorder),
		new Opt(MediaRecorder.AudioSource.VOICE_RECOGNITION, R.string.src_voice_recognition),
		new Opt(MediaRecorder.AudioSource.VOICE_COMMUNICATION, R.string.src_voice_communication),
		new Opt(MediaRecorder.AudioSource.UNPROCESSED, R.string.src_unprocessed, 24),
		new Opt(MediaRecorder.AudioSource.VOICE_PERFORMANCE, R.string.src_voice_performance, 29),
		new Opt(SOURCE_PLAYBACK_CAPTURE, R.string.src_playback_capture, 29),
		new Opt(MediaRecorder.AudioSource.REMOTE_SUBMIX, R.string.src_remote_submix, 19, true),
		new Opt(MediaRecorder.AudioSource.VOICE_CALL, R.string.src_voice_call, 1, true),
		new Opt(MediaRecorder.AudioSource.VOICE_UPLINK, R.string.src_voice_uplink, 1, true),
		new Opt(MediaRecorder.AudioSource.VOICE_DOWNLINK, R.string.src_voice_downlink, 1, true),
	};

	// ---- Engine A encoders / formats --------------------------------------------------------
	public static final Opt[] ENCODERS_A = {
		new Opt(MediaRecorder.AudioEncoder.AAC, R.string.enc_aac),
		new Opt(MediaRecorder.AudioEncoder.HE_AAC, R.string.enc_he_aac),
		new Opt(MediaRecorder.AudioEncoder.AAC_ELD, R.string.enc_aac_eld),
		new Opt(MediaRecorder.AudioEncoder.OPUS, R.string.enc_opus, 29),
		new Opt(MediaRecorder.AudioEncoder.AMR_NB, R.string.enc_amr_nb),
		new Opt(MediaRecorder.AudioEncoder.AMR_WB, R.string.enc_amr_wb),
	};

	public static final FormatA[] FORMATS_A = {
		new FormatA("m4a", MediaRecorder.OutputFormat.MPEG_4, "m4a", R.string.fmt_m4a, 1,
			MediaRecorder.AudioEncoder.AAC, MediaRecorder.AudioEncoder.HE_AAC, MediaRecorder.AudioEncoder.AAC_ELD),
		new FormatA("mp4", MediaRecorder.OutputFormat.MPEG_4, "mp4", R.string.fmt_mp4, 1,
			MediaRecorder.AudioEncoder.AAC, MediaRecorder.AudioEncoder.HE_AAC, MediaRecorder.AudioEncoder.AAC_ELD),
		new FormatA("3gp", MediaRecorder.OutputFormat.THREE_GPP, "3gp", R.string.fmt_3gp, 1,
			MediaRecorder.AudioEncoder.AAC, MediaRecorder.AudioEncoder.HE_AAC, MediaRecorder.AudioEncoder.AAC_ELD,
			MediaRecorder.AudioEncoder.AMR_NB, MediaRecorder.AudioEncoder.AMR_WB),
		new FormatA("ogg", MediaRecorder.OutputFormat.OGG, "ogg", R.string.fmt_ogg, 29,
			MediaRecorder.AudioEncoder.OPUS),
		new FormatA("aac", MediaRecorder.OutputFormat.AAC_ADTS, "aac", R.string.fmt_aac_adts, 1,
			MediaRecorder.AudioEncoder.AAC, MediaRecorder.AudioEncoder.HE_AAC, MediaRecorder.AudioEncoder.AAC_ELD),
		new FormatA("ts", MediaRecorder.OutputFormat.MPEG_2_TS, "ts", R.string.fmt_mpeg2ts, 26,
			MediaRecorder.AudioEncoder.AAC),
		new FormatA("amr", MediaRecorder.OutputFormat.AMR_NB, "amr", R.string.fmt_amr_nb, 1,
			MediaRecorder.AudioEncoder.AMR_NB),
		new FormatA("awb", MediaRecorder.OutputFormat.AMR_WB, "awb", R.string.fmt_amr_wb, 1,
			MediaRecorder.AudioEncoder.AMR_WB),
	};

	// ---- Engine B codecs --------------------------------------------------------------------
	public static final CodecB[] CODECS_B = {
		new CodecB("wav", "wav", R.string.codec_wav, 1, false, null, 0),
		new CodecB("pcm", "pcm", R.string.codec_pcm, 1, false, null, 0),
		new CodecB("flac", "flac", R.string.codec_flac, 21, true, "audio/flac", 0),
		new CodecB("aac_lc", "m4a", R.string.codec_aac_lc, 21, true, "audio/mp4a-latm", 2 /* AACObjectLC */),
		new CodecB("aac_he", "m4a", R.string.codec_aac_he, 21, true, "audio/mp4a-latm", 5 /* AACObjectHE */),
		new CodecB("aac_eld", "m4a", R.string.codec_aac_eld, 21, true, "audio/mp4a-latm", 39 /* AACObjectELD */),
		new CodecB("opus", "ogg", R.string.codec_opus, 29, true, "audio/opus", 0),
	};

	// ---- PCM sample formats (Engine B) ------------------------------------------------------
	public static final Opt[] PCM_ENCODINGS = {
		new Opt(AudioFormat.ENCODING_PCM_16BIT, R.string.pcm_16),
		new Opt(AudioFormat.ENCODING_PCM_8BIT, R.string.pcm_8),
		new Opt(AudioFormat.ENCODING_PCM_24BIT_PACKED, R.string.pcm_24, 31),
		new Opt(AudioFormat.ENCODING_PCM_32BIT, R.string.pcm_32, 31),
		new Opt(AudioFormat.ENCODING_PCM_FLOAT, R.string.pcm_float, 23),
	};

	public static final Opt[] CHANNELS = {
		new Opt(1, R.string.ch_mono),
		new Opt(2, R.string.ch_stereo),
	};

	public static final int[] SAMPLE_RATES = {8000, 11025, 16000, 22050, 24000, 32000, 44100, 48000, 88200, 96000, 176400, 192000};
	public static final int[] BITRATES = {12200, 23850, 24000, 32000, 48000, 64000, 96000, 128000, 160000, 192000, 256000, 320000, 512000};
	public static final int[] BUFFER_MULTIPLIERS = {1, 2, 4, 8, 16};
	public static final int[] FFT_SIZES = {512, 1024, 2048, 4096};
	public static final int[] SILENCE_DB = {-20, -25, -30, -35, -40, -45, -50, -55, -60, -65, -70, -80};
	public static final int[] SILENCE_HOLD_MS = {250, 500, 1000, 1500, 2000, 3000, 5000, 10000};
	public static final int[] FLAC_LEVELS = {0, 1, 2, 3, 4, 5, 6, 7, 8};

	public static final Opt[] BITRATE_MODES = {
		new Opt(BR_MODE_DEFAULT, R.string.brm_default),
		new Opt(BR_MODE_CBR, R.string.brm_cbr),
		new Opt(BR_MODE_VBR, R.string.brm_vbr),
		new Opt(BR_MODE_CQ, R.string.brm_cq),
	};

	public static final Opt[] FX_MODES = {
		new Opt(FX_DEFAULT, R.string.fx_default),
		new Opt(FX_ON, R.string.fx_on),
		new Opt(FX_OFF, R.string.fx_off),
	};

	public static final Opt[] MIC_DIRECTIONS = {
		new Opt(0, R.string.dir_unspecified),      // MicrophoneDirection.MIC_DIRECTION_UNSPECIFIED
		new Opt(1, R.string.dir_towards_user),     // MIC_DIRECTION_TOWARDS_USER
		new Opt(2, R.string.dir_away_from_user),   // MIC_DIRECTION_AWAY_FROM_USER
		new Opt(3, R.string.dir_external),         // MIC_DIRECTION_EXTERNAL
	};

	/** Field dimension keys -> zoom factor. "off" means "do not call the API". */
	public static final String[] FIELD_KEYS = {"off", "-1", "-0.5", "0", "0.5", "1"};
	@StringRes public static final int[] FIELD_LABELS = {
		R.string.field_off, R.string.field_wide_max, R.string.field_wide, R.string.field_normal, R.string.field_narrow, R.string.field_narrow_max
	};

	public static final Opt[] ROUTES = {
		new Opt(ROUTE_AUTO, R.string.route_auto),
		new Opt(ROUTE_BUILTIN, R.string.route_builtin),
		new Opt(ROUTE_WIRED, R.string.route_wired),
		new Opt(ROUTE_BT_SCO, R.string.route_bt_sco),
		new Opt(ROUTE_USB, R.string.route_usb),
	};

	public static final Opt[] STORAGES = {
		new Opt(STORAGE_APP, R.string.storage_app),
		new Opt(STORAGE_PUBLIC, R.string.storage_public),
	};

	public static final Opt[] METER_SPEEDS = {
		new Opt(METER_FAST, R.string.meter_fast),
		new Opt(METER_SLOW, R.string.meter_slow),
	};

	// ---- lookup helpers ---------------------------------------------------------------------
	@NonNull
	public static List<Opt> available(Opt[] all) {
		List<Opt> out = new ArrayList<>();
		for (Opt o : all) if (o.available()) out.add(o);
		return out;
	}

	public static Opt find(Opt[] all, int value) {
		for (Opt o : all) if (o.value == value) return o;
		return null;
	}

	public static FormatA formatA(String id) {
		for (FormatA f : FORMATS_A) if (f.id.equals(id)) return f;
		return FORMATS_A[0];
	}

	public static CodecB codecB(String id) {
		for (CodecB c : CODECS_B) if (c.id.equals(id)) return c;
		return CODECS_B[0];
	}

	public static int indexOf(int[] arr, int v) {
		for (int i = 0; i < arr.length; i++) if (arr[i] == v) return i;
		return -1;
	}

	public static int indexOf(String[] arr, String v) {
		for (int i = 0; i < arr.length; i++) if (arr[i].equals(v)) return i;
		return -1;
	}

	public static float fieldDimension(String key) {
		if ("off".equals(key)) return Float.NaN;
		try { return Float.parseFloat(key); } catch (NumberFormatException e) { return Float.NaN; }
	}
}
