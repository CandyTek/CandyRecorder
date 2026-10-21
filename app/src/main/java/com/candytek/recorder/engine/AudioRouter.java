package com.candytek.recorder.engine;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.candytek.recorder.core.Choices;

import java.util.ArrayList;
import java.util.List;

/**
 * Chooses the physical input: built-in mic, wired headset, USB / Type-C audio or a Bluetooth
 * SCO headset. Bluetooth needs the SCO link opened through {@link AudioManager#startBluetoothSco()}
 * before capture begins, which is asynchronous; the callback fires once the link is up (or on timeout).
 */
public final class AudioRouter {

	public interface Callback {
		void onReady(@Nullable AudioDeviceInfo device, @NonNull String note);
	}

	private static final long SCO_TIMEOUT_MS = 6000;

	private final Context ctx;
	private final AudioManager am;
	private final Handler main = new Handler(Looper.getMainLooper());
	private BroadcastReceiver scoReceiver;
	private boolean scoStarted;
	private Runnable timeout;
	private boolean delivered;

	public AudioRouter(Context ctx) {
		this.ctx = ctx.getApplicationContext();
		this.am = (AudioManager) this.ctx.getSystemService(Context.AUDIO_SERVICE);
	}

	public void prepare(int route, @NonNull final Callback cb) {
		delivered = false;
		if (route == Choices.ROUTE_AUTO || am == null) { deliver(cb, null, "auto"); return; }
		if (Build.VERSION.SDK_INT < 23) { deliver(cb, null, "routing needs API 23+"); return; }
		if (route == Choices.ROUTE_BT_SCO) { prepareSco(cb); return; }
		AudioDeviceInfo dev = findInput(route);
		deliver(cb, dev, dev == null ? "device not present, using default" : "preferred: " + describe(dev));
	}

	private void prepareSco(final Callback cb) {
		if (!am.isBluetoothScoAvailableOffCall()) { deliver(cb, null, "SCO not available off-call"); return; }
		AudioDeviceInfo already = findInput(Choices.ROUTE_BT_SCO);
		scoReceiver = new BroadcastReceiver() {
			@Override public void onReceive(Context c, Intent i) {
				int state = i.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_ERROR);
				if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED) {
					am.setBluetoothScoOn(true);
					AudioDeviceInfo dev = findInput(Choices.ROUTE_BT_SCO);
					deliver(cb, dev, "SCO connected" + (dev != null ? ": " + describe(dev) : ""));
				} else if (state == AudioManager.SCO_AUDIO_STATE_ERROR) {
					deliver(cb, null, "SCO error, using default");
				}
			}
		};
		ctx.registerReceiver(scoReceiver, new IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED));
		timeout = new Runnable() {
			@Override public void run() {
				AudioDeviceInfo dev = findInput(Choices.ROUTE_BT_SCO);
				deliver(cb, dev, "SCO timeout" + (dev != null ? ", device present" : ", using default"));
			}
		};
		main.postDelayed(timeout, SCO_TIMEOUT_MS);
		try {
			am.setMode(AudioManager.MODE_NORMAL);
			am.startBluetoothSco();
			scoStarted = true;
		} catch (RuntimeException e) {
			deliver(cb, already, "startBluetoothSco failed: " + e.getMessage());
		}
	}

	private void deliver(final Callback cb, final AudioDeviceInfo dev, final String note) {
		if (delivered) return;
		delivered = true;
		if (timeout != null) { main.removeCallbacks(timeout); timeout = null; }
		if (scoReceiver != null) {
			try { ctx.unregisterReceiver(scoReceiver); } catch (RuntimeException ignored) {}
			scoReceiver = null;
		}
		if (Looper.myLooper() == Looper.getMainLooper()) cb.onReady(dev, note);
		else main.post(new Runnable() { @Override public void run() { cb.onReady(dev, note); } });
	}

	/** Tears down the SCO link if we opened it. Safe to call multiple times. */
	public void release() {
		if (timeout != null) { main.removeCallbacks(timeout); timeout = null; }
		if (scoReceiver != null) {
			try { ctx.unregisterReceiver(scoReceiver); } catch (RuntimeException ignored) {}
			scoReceiver = null;
		}
		if (scoStarted && am != null) {
			try { am.setBluetoothScoOn(false); am.stopBluetoothSco(); } catch (RuntimeException ignored) {}
			scoStarted = false;
		}
	}

	@Nullable
	private AudioDeviceInfo findInput(int route) {
		if (Build.VERSION.SDK_INT < 23 || am == null) return null;
		AudioDeviceInfo[] devs = am.getDevices(AudioManager.GET_DEVICES_INPUTS);
		for (AudioDeviceInfo d : devs) if (matches(route, d.getType())) return d;
		return null;
	}

	private static boolean matches(int route, int type) {
		switch (route) {
			case Choices.ROUTE_BUILTIN: return type == AudioDeviceInfo.TYPE_BUILTIN_MIC;
			case Choices.ROUTE_WIRED: return type == AudioDeviceInfo.TYPE_WIRED_HEADSET;
			case Choices.ROUTE_BT_SCO: return type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO;
			case Choices.ROUTE_USB:
				return type == AudioDeviceInfo.TYPE_USB_HEADSET || type == AudioDeviceInfo.TYPE_USB_DEVICE
					|| type == AudioDeviceInfo.TYPE_USB_ACCESSORY;
			default: return false;
		}
	}

	/** All currently attached input devices, one descriptive line each. */
	@NonNull
	public static List<String> listInputs(Context ctx) {
		List<String> out = new ArrayList<>();
		if (Build.VERSION.SDK_INT < 23) return out;
		AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
		if (am == null) return out;
		for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_INPUTS)) out.add(describeFull(d));
		return out;
	}

	@NonNull
	public static String describe(@Nullable AudioDeviceInfo d) {
		if (d == null || Build.VERSION.SDK_INT < 23) return "default";
		CharSequence name = d.getProductName();
		return typeName(d.getType()) + " #" + d.getId() + (name != null && name.length() > 0 ? " (" + name + ")" : "");
	}

	@NonNull
	public static String describeFull(@Nullable AudioDeviceInfo d) {
		if (d == null || Build.VERSION.SDK_INT < 23) return "default";
		StringBuilder sb = new StringBuilder(describe(d));
		int[] rates = d.getSampleRates();
		if (rates.length > 0) {
			sb.append("  rates:");
			for (int r : rates) sb.append(' ').append(r);
		}
		int[] ch = d.getChannelCounts();
		if (ch.length > 0) {
			sb.append("  ch:");
			for (int c : ch) sb.append(' ').append(c);
		}
		int[] enc = d.getEncodings();
		if (enc.length > 0) {
			sb.append("  enc:");
			for (int e : enc) sb.append(' ').append(e);
		}
		if (Build.VERSION.SDK_INT >= 28) {
			String addr = d.getAddress();
			if (addr != null && !addr.isEmpty()) sb.append("  addr:").append(addr);
		}
		return sb.toString();
	}

	@NonNull
	public static String typeName(int type) {
		switch (type) {
			case AudioDeviceInfo.TYPE_BUILTIN_MIC: return "Built-in mic";
			case AudioDeviceInfo.TYPE_WIRED_HEADSET: return "Wired headset";
			case AudioDeviceInfo.TYPE_BLUETOOTH_SCO: return "Bluetooth SCO";
			case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP: return "Bluetooth A2DP";
			case AudioDeviceInfo.TYPE_USB_DEVICE: return "USB device";
			case AudioDeviceInfo.TYPE_USB_ACCESSORY: return "USB accessory";
			case AudioDeviceInfo.TYPE_USB_HEADSET: return "USB headset";
			case AudioDeviceInfo.TYPE_TELEPHONY: return "Telephony";
			case AudioDeviceInfo.TYPE_FM_TUNER: return "FM tuner";
			case AudioDeviceInfo.TYPE_TV_TUNER: return "TV tuner";
			case AudioDeviceInfo.TYPE_LINE_ANALOG: return "Line analog";
			case AudioDeviceInfo.TYPE_LINE_DIGITAL: return "Line digital";
			case AudioDeviceInfo.TYPE_IP: return "IP";
			case AudioDeviceInfo.TYPE_BUS: return "Bus";
			case AudioDeviceInfo.TYPE_REMOTE_SUBMIX: return "Remote submix";
			case 23: return "Hearing aid";
			case 26: return "BLE headset";
			case 27: return "BLE speaker";
			case 29: return "HDMI eARC";
			case 30: return "BLE broadcast";
			default: return "Type " + type;
		}
	}
}
