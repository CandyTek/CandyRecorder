package com.candytek.recorder;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.candytek.recorder.core.Choices;
import com.candytek.recorder.core.Prefs;
import com.candytek.recorder.core.RecordingConfig;
import com.candytek.recorder.engine.EngineStats;
import com.candytek.recorder.engine.MeterFrame;
import com.candytek.recorder.service.RecordingService;
import com.candytek.recorder.ui.DashboardView;
import com.candytek.recorder.ui.Ui;
import com.candytek.recorder.util.Fmt;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Dashboard + transport controls. Capture itself lives in {@link RecordingService}. */
public class MainActivity extends Activity implements RecordingService.Client {

	private static final int REQ_PERMS = 11;
	private static final int REQ_PROJECTION = 12;
	private static final long TICK_MS = 100;

	private RecordingService service;
	private boolean bound;
	private Prefs prefs;

	private TextView clock, stateBadge, stateLine, configLine;
	private DashboardView dashboard;
	private LinearLayout statsPanel;
	private Button btnRecord, btnPause, btnStop, btnSettings, btnHistory;

	private final Handler ui = new Handler(Looper.getMainLooper());
	private final Map<String, TextView> statViews = new LinkedHashMap<>();
	private int lastState = -1;
	private RecordingConfig pendingConfig;

	private final ServiceConnection conn = new ServiceConnection() {
		@Override public void onServiceConnected(ComponentName n, IBinder b) {
			service = ((RecordingService.LocalBinder) b).service();
			service.addClient(MainActivity.this);
			MeterFrame f = service.getLastFrame();
			if (f != null && service.getState() != RecordingService.STATE_IDLE) dashboard.push(f);
			ui.removeCallbacks(ticker);
			ui.post(ticker);
		}
		@Override public void onServiceDisconnected(ComponentName n) {
			service = null;
		}
	};

	private final Runnable ticker = new Runnable() {
		@Override public void run() {
			refreshStats();
			ui.postDelayed(this, TICK_MS);
		}
	};

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);
		prefs = new Prefs(this);
		clock = findViewById(R.id.clock);
		stateBadge = findViewById(R.id.stateBadge);
		stateLine = findViewById(R.id.stateLine);
		configLine = findViewById(R.id.configLine);
		dashboard = findViewById(R.id.dashboard);
		statsPanel = findViewById(R.id.statsPanel);
		btnRecord = findViewById(R.id.btnRecord);
		btnPause = findViewById(R.id.btnPause);
		btnStop = findViewById(R.id.btnStop);
		btnSettings = findViewById(R.id.btnSettings);
		btnHistory = findViewById(R.id.btnHistory);

		btnRecord.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { onRecordPressed(); } });
		btnPause.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { onPausePressed(); } });
		btnStop.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
			if (service != null) service.stop();
		} });
		btnSettings.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
			startActivity(new Intent(MainActivity.this, SettingsActivity.class));
		} });
		btnHistory.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
			startActivity(new Intent(MainActivity.this, HistoryActivity.class));
		} });
		applyState(RecordingService.STATE_IDLE);
	}

	@Override
	protected void onStart() {
		super.onStart();
		configLine.setText(prefs.buildConfig().summary(this));
		bound = bindService(new Intent(this, RecordingService.class), conn, Context.BIND_AUTO_CREATE);
	}

	@Override
	protected void onStop() {
		super.onStop();
		ui.removeCallbacks(ticker);
		if (bound) {
			if (service != null) service.removeClient(this);
			unbindService(conn);
			bound = false;
			service = null;
		}
	}

	// ---- transport ---------------------------------------------------------------------------

	private void onRecordPressed() {
		if (service != null && service.getState() != RecordingService.STATE_IDLE) return;
		RecordingConfig cfg = prefs.buildConfig();
		String err = cfg.validate(this);
		if (err != null) { Ui.toast(this, err); return; }
		pendingConfig = cfg;
		List<String> missing = new ArrayList<>();
		if (Build.VERSION.SDK_INT >= 23) {
			if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.RECORD_AUDIO);
			if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.POST_NOTIFICATIONS);
			if (Build.VERSION.SDK_INT >= 31 && cfg.route == Choices.ROUTE_BT_SCO
				&& checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.BLUETOOTH_CONNECT);
			if (RecordingService.needsStoragePermission(this, cfg)) missing.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
		}
		if (!missing.isEmpty() && Build.VERSION.SDK_INT >= 23) {
			requestPermissions(missing.toArray(new String[0]), REQ_PERMS);
			return;
		}
		proceedStart(cfg);
	}

	private void proceedStart(RecordingConfig cfg) {
		if (cfg.isPlaybackCapture() && Build.VERSION.SDK_INT >= 29) {
			MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
			if (mpm == null) { Ui.toast(this, R.string.err_capture_denied); return; }
			startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION);
			return;
		}
		launchService(0, null);
	}

	private void launchService(int code, Intent data) {
		Intent i = RecordingService.startIntent(this, code, data);
		if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
		dashboard.reset();
	}

	private void onPausePressed() {
		if (service == null) return;
		if (service.getState() == RecordingService.STATE_PAUSED) service.resume();
		else service.pause();
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
		if (requestCode == REQ_PROJECTION) {
			if (resultCode == RESULT_OK && data != null) launchService(resultCode, data);
			else Ui.toast(this, R.string.err_capture_denied);
		}
	}

	@Override
	public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
		super.onRequestPermissionsResult(requestCode, permissions, grantResults);
		if (requestCode != REQ_PERMS) return;
		boolean mic = Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
		if (!mic) { Ui.toast(this, R.string.err_no_mic_permission); return; }
		RecordingConfig cfg = pendingConfig != null ? pendingConfig : prefs.buildConfig();
		if (RecordingService.needsStoragePermission(this, cfg)) Ui.toast(this, R.string.warn_public_fallback);
		proceedStart(cfg);
	}

	// ---- service callbacks -------------------------------------------------------------------

	@Override
	public void onState(int state) { applyState(state); }

	@Override
	public void onMeter(@NonNull MeterFrame frame) { dashboard.push(frame); }

	@Override
	public void onError(@NonNull String message) {
		new AlertDialog.Builder(this)
			.setTitle(R.string.err_title)
			.setMessage(message)
			.setPositiveButton(android.R.string.ok, null)
			.show();
	}

	@Override
	public void onFinished(@NonNull File file) {
		Ui.toast(this, getString(R.string.saved_as, file.getName(), Fmt.bytes(file.length())));
	}

	private void applyState(int state) {
		if (state == lastState) return;
		lastState = state;
		boolean idle = state == RecordingService.STATE_IDLE;
		boolean active = state == RecordingService.STATE_RECORDING || state == RecordingService.STATE_PAUSED;
		btnRecord.setEnabled(idle);
		btnRecord.setAlpha(idle ? 1f : 0.35f);
		btnPause.setEnabled(active);
		btnPause.setAlpha(active ? 1f : 0.4f);
		btnStop.setEnabled(active || state == RecordingService.STATE_PREPARING);
		btnStop.setAlpha(btnStop.isEnabled() ? 1f : 0.4f);
		btnSettings.setEnabled(idle);
		btnSettings.setAlpha(idle ? 1f : 0.4f);
		btnPause.setText(state == RecordingService.STATE_PAUSED ? R.string.action_resume : R.string.action_pause);

		int badge;
		int color;
		switch (state) {
			case RecordingService.STATE_PREPARING: badge = R.string.state_preparing; color = R.color.amber; break;
			case RecordingService.STATE_RECORDING: badge = R.string.state_recording; color = R.color.red; break;
			case RecordingService.STATE_PAUSED: badge = R.string.state_paused; color = R.color.amber; break;
			case RecordingService.STATE_STOPPING: badge = R.string.state_stopping; color = R.color.amber; break;
			default: badge = R.string.state_standby; color = R.color.text_dim; break;
		}
		stateBadge.setText(badge);
		stateBadge.setTextColor(getResources().getColor(color));
		stateBadge.setBackgroundResource(state == RecordingService.STATE_RECORDING ? R.drawable.badge : R.drawable.badge_dim);

		boolean keepOn = active && prefs.keepScreenOn();
		if (keepOn) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

		if (idle) {
			stateLine.setText("");
			configLine.setText(prefs.buildConfig().summary(this));
			dashboard.reset();
			renderStats(idleStats());
			clock.setText(R.string.clock_zero);
		} else if (service != null) {
			File f = service.getOutFile();
			stateLine.setText(f != null ? f.getName() : "");
			RecordingConfig c = service.getConfig();
			if (c != null) configLine.setText(c.summary(this));
		}
	}

	// ---- stats panel -------------------------------------------------------------------------

	private Map<String, String> idleStats() {
		Map<String, String> m = new LinkedHashMap<>();
		RecordingConfig c = prefs.buildConfig();
		m.put(getString(R.string.st_engine), c.engineB ? getString(R.string.engine_b) : getString(R.string.engine_a));
		Choices.Opt src = Choices.find(Choices.SOURCES, c.audioSource);
		m.put(getString(R.string.st_source), src != null ? getString(src.label) : String.valueOf(c.audioSource));
		m.put(getString(R.string.st_sample_rate), c.sampleRate + " Hz");
		m.put(getString(R.string.st_channels), String.valueOf(c.channels));
		if (c.engineB) {
			Choices.Opt pcm = Choices.find(Choices.PCM_ENCODINGS, c.pcmEncoding);
			m.put(getString(R.string.st_encoding), pcm != null ? getString(pcm.label) : "?");
			m.put(getString(R.string.st_codec), getString(c.codecB.label));
		} else {
			Choices.Opt enc = Choices.find(Choices.ENCODERS_A, c.encoderA);
			m.put(getString(R.string.st_codec), (enc != null ? getString(enc.label) : "?") + " / " + getString(c.formatA.label));
		}
		if (c.isCompressed()) m.put(getString(R.string.st_bitrate), c.bitrate / 1000 + " kbps");
		Choices.Opt route = Choices.find(Choices.ROUTES, c.route);
		m.put(getString(R.string.st_route), route != null ? getString(route.label) : "?");
		m.put(getString(R.string.st_silence), c.silenceSkip ? c.silenceDb + " dB / " + c.silenceHoldMs + " ms" : getString(R.string.off));
		m.put(getString(R.string.st_raw_mode), c.rawMode ? getString(R.string.on) : getString(R.string.off));
		m.put(getString(R.string.st_api), "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
		m.put(getString(R.string.st_unprocessed), RecordingConfig.supportsUnprocessed(this) ? getString(R.string.yes) : getString(R.string.no));
		return m;
	}

	private void refreshStats() {
		if (service == null) return;
		int state = service.getState();
		if (state == RecordingService.STATE_IDLE) return;
		EngineStats s = service.getStats();
		if (s == null) return;
		clock.setText(Fmt.clock(s.elapsedMs()));
		Map<String, String> m = new LinkedHashMap<>();
		m.put(getString(R.string.st_engine), s.engineName);
		m.put(getString(R.string.st_source), s.sourceName);
		m.put(getString(R.string.st_device), s.deviceName);
		m.put(getString(R.string.st_routed), s.routedDevice.isEmpty() ? "—" : s.routedDevice);
		if (!s.micInfo.isEmpty()) m.put(getString(R.string.st_mics), s.micInfo);
		m.put(getString(R.string.st_sample_rate), s.sampleRate + " Hz" + (s.sampleRate != s.requestedSampleRate ? " (req " + s.requestedSampleRate + ")" : ""));
		m.put(getString(R.string.st_channels), String.valueOf(s.channels));
		m.put(getString(R.string.st_encoding), s.encodingName);
		m.put(getString(R.string.st_codec), s.codecName);
		if (s.bufferFrames > 0) {
			m.put(getString(R.string.st_buffer), s.bufferFrames + " fr / " + Fmt.fixed(s.bufferLatencyMs, 2) + " ms");
			m.put(getString(R.string.st_chunk), s.chunkFrames + " fr / " + Fmt.fixed(s.chunkFrames * 1000.0 / Math.max(1, s.sampleRate), 2) + " ms");
			m.put(getString(R.string.st_read_time), s.readAvgUs + " µs avg / " + s.readMaxUs + " µs max");
			m.put(getString(R.string.st_drift), s.driftFrames + " fr");
		}
		m.put(getString(R.string.st_file_size), Fmt.bytes(s.bytesWritten));
		m.put(getString(R.string.st_bitrate), Fmt.kbps(s.measuredBitrate()));
		m.put(getString(R.string.st_frames), String.valueOf(s.framesRead));
		m.put(getString(R.string.st_skipped), Fmt.clock(s.framesSkipped * 1000L / Math.max(1, s.sampleRate)) + " (" + s.gateEvents + ")");
		m.put(getString(R.string.st_gate), s.gateClosed ? getString(R.string.gate_closed) : getString(R.string.gate_open));
		m.put(getString(R.string.st_peak), Fmt.db(s.peakDbL) + " / " + Fmt.db(s.peakDbR) + " dBFS");
		if (s.rmsDbL > -140f) m.put(getString(R.string.st_rms), Fmt.db(s.rmsDbL) + " / " + Fmt.db(s.rmsDbR) + " dBFS");
		m.put(getString(R.string.st_clips), String.valueOf(s.clipCount));
		if (s.audioSessionId >= 0) m.put(getString(R.string.st_session), String.valueOf(s.audioSessionId));
		m.putAll(s.extrasSnapshot());
		m.put(getString(R.string.st_path), s.filePath);
		renderStats(m);
	}

	private void renderStats(Map<String, String> values) {
		if (!statViews.keySet().equals(values.keySet())) {
			statsPanel.removeAllViews();
			statViews.clear();
			for (Map.Entry<String, String> e : values.entrySet()) {
				View row = Ui.kvRow(this, e.getKey(), e.getValue());
				statsPanel.addView(row);
				statViews.put(e.getKey(), (TextView) ((LinearLayout) row).getChildAt(1));
			}
			return;
		}
		for (Map.Entry<String, String> e : values.entrySet()) {
			TextView tv = statViews.get(e.getKey());
			if (tv != null && !e.getValue().contentEquals(tv.getText())) tv.setText(e.getValue());
		}
	}
}
