package com.candytek.recorder.engine;

import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;

import com.candytek.recorder.core.Choices;

/**
 * Attaches the platform pre-processing effects (android.media.audiofx) to an AudioRecord session.
 * "Raw / pure audio mode" forces every effect off so the signal reaches the file untouched.
 */
public final class AudioEffectsController {

	private NoiseSuppressor ns;
	private AcousticEchoCanceler aec;
	private AutomaticGainControl agc;

	/** @return a compact status line such as "NS:on AEC:off AGC:n/a". */
	public String apply(int sessionId, int nsMode, int aecMode, int agcMode, boolean rawMode) {
		if (rawMode) { nsMode = Choices.FX_OFF; aecMode = Choices.FX_OFF; agcMode = Choices.FX_OFF; }
		StringBuilder sb = new StringBuilder();
		sb.append("NS:").append(applyNs(sessionId, nsMode));
		sb.append(" AEC:").append(applyAec(sessionId, aecMode));
		sb.append(" AGC:").append(applyAgc(sessionId, agcMode));
		return sb.toString();
	}

	private String applyNs(int session, int mode) {
		if (!NoiseSuppressor.isAvailable()) return "n/a";
		if (mode == Choices.FX_DEFAULT) return "default";
		try {
			ns = NoiseSuppressor.create(session);
			if (ns == null) return "n/a";
			return set(ns, mode == Choices.FX_ON);
		} catch (RuntimeException e) {
			return "err";
		}
	}

	private String applyAec(int session, int mode) {
		if (!AcousticEchoCanceler.isAvailable()) return "n/a";
		if (mode == Choices.FX_DEFAULT) return "default";
		try {
			aec = AcousticEchoCanceler.create(session);
			if (aec == null) return "n/a";
			return set(aec, mode == Choices.FX_ON);
		} catch (RuntimeException e) {
			return "err";
		}
	}

	private String applyAgc(int session, int mode) {
		if (!AutomaticGainControl.isAvailable()) return "n/a";
		if (mode == Choices.FX_DEFAULT) return "default";
		try {
			agc = AutomaticGainControl.create(session);
			if (agc == null) return "n/a";
			return set(agc, mode == Choices.FX_ON);
		} catch (RuntimeException e) {
			return "err";
		}
	}

	private static String set(AudioEffect fx, boolean on) {
		int r = fx.setEnabled(on);
		if (r != AudioEffect.SUCCESS) return "err" + r;
		return fx.getEnabled() ? "on" : "off";
	}

	public void release() {
		if (ns != null) { try { ns.release(); } catch (RuntimeException ignored) {} ns = null; }
		if (aec != null) { try { aec.release(); } catch (RuntimeException ignored) {} aec = null; }
		if (agc != null) { try { agc.release(); } catch (RuntimeException ignored) {} agc = null; }
	}
}
