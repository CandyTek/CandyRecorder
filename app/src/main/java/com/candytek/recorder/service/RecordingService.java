package com.candytek.recorder.service;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioDeviceInfo;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.candytek.recorder.MainActivity;
import com.candytek.recorder.R;
import com.candytek.recorder.core.Choices;
import com.candytek.recorder.core.Prefs;
import com.candytek.recorder.core.RecordingConfig;
import com.candytek.recorder.engine.AudioRecordEngine;
import com.candytek.recorder.engine.AudioRouter;
import com.candytek.recorder.engine.EngineStats;
import com.candytek.recorder.engine.MediaRecorderEngine;
import com.candytek.recorder.engine.MeterFrame;
import com.candytek.recorder.engine.RecorderEngine;
import com.candytek.recorder.storage.MetaFile;
import com.candytek.recorder.storage.RecordingStore;
import com.candytek.recorder.util.Fmt;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Foreground service that owns the recording session so capture survives the UI being
 * backgrounded. The activity binds to it for live telemetry.
 */
public final class RecordingService extends Service {

	public static final String ACTION_START = "com.candytek.recorder.START";
	public static final String ACTION_PAUSE = "com.candytek.recorder.PAUSE";
	public static final String ACTION_RESUME = "com.candytek.recorder.RESUME";
	public static final String ACTION_TOGGLE_PAUSE = "com.candytek.recorder.TOGGLE_PAUSE";
	public static final String ACTION_STOP = "com.candytek.recorder.STOP";
	public static final String EXTRA_PROJECTION_CODE = "projection_code";
	public static final String EXTRA_PROJECTION_DATA = "projection_data";

	public static final int STATE_IDLE = 0, STATE_PREPARING = 1, STATE_RECORDING = 2, STATE_PAUSED = 3, STATE_STOPPING = 4;

	private static final String CHANNEL_ID = "recording";
	private static final int NOTIFICATION_ID = 0x5EC;

	public interface Client {
		void onState(int state);
		void onMeter(@NonNull MeterFrame frame);
		void onError(@NonNull String message);
		void onFinished(@NonNull File file);
	}

	public final class LocalBinder extends Binder {
		public RecordingService service() { return RecordingService.this; }
	}

	private final LocalBinder binder = new LocalBinder();
	private final Handler main = new Handler(Looper.getMainLooper());
	private final ExecutorService worker = Executors.newSingleThreadExecutor();
	private final List<Client> clients = new CopyOnWriteArrayList<>();

	private int state = STATE_IDLE;
	private RecorderEngine engine;
	private RecordingConfig config;
	private File outFile;
	private AudioRouter router;
	private PowerManager.WakeLock wakeLock;
	private MediaProjection projection;
	private long startedWallMs;
	private volatile MeterFrame lastFrame;
	private volatile boolean meterPostPending;
	private String routingNote = "";

	// ---- lifecycle ---------------------------------------------------------------------------

	@Override
	public void onCreate() {
		super.onCreate();
		createChannel();
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		String action = intent == null ? null : intent.getAction();
		if (ACTION_START.equals(action)) {
			int code = intent.getIntExtra(EXTRA_PROJECTION_CODE, 0);
			Intent data = intent.getParcelableExtra(EXTRA_PROJECTION_DATA);
			startRecording(code, data);
		} else if (ACTION_PAUSE.equals(action)) {
			pause();
		} else if (ACTION_RESUME.equals(action)) {
			resume();
		} else if (ACTION_TOGGLE_PAUSE.equals(action)) {
			if (state == STATE_PAUSED) resume(); else pause();
		} else if (ACTION_STOP.equals(action)) {
			stop();
		}
		return START_NOT_STICKY;
	}

	@Nullable
	@Override
	public IBinder onBind(Intent intent) { return binder; }

	@Override
	public void onDestroy() {
		if (engine != null) {
			try { engine.stop(); } catch (Exception ignored) {}
		}
		cleanup();
		worker.shutdown();
		super.onDestroy();
	}

	// ---- public API for the bound activity ---------------------------------------------------

	public int getState() { return state; }

	@Nullable public EngineStats getStats() { return engine == null ? null : engine.stats(); }

	@Nullable public MeterFrame getLastFrame() { return lastFrame; }

	@Nullable public RecordingConfig getConfig() { return config; }

	@Nullable public File getOutFile() { return outFile; }

	public long getStartedWallMs() { return startedWallMs; }

	public void addClient(Client c) { if (!clients.contains(c)) clients.add(c); c.onState(state); }

	public void removeClient(Client c) { clients.remove(c); }

	// ---- session control ---------------------------------------------------------------------

	private void startRecording(int projectionCode, @Nullable Intent projectionData) {
		if (state != STATE_IDLE) return;
		config = new Prefs(this).buildConfig();
		startedWallMs = System.currentTimeMillis();
		setState(STATE_PREPARING);
		// Must promote to foreground promptly after startForegroundService(), even if we then bail out.
		try {
			goForeground(buildNotification());
		} catch (RuntimeException e) {
			fail(getString(R.string.err_foreground, String.valueOf(e.getMessage())));
			return;
		}
		String err = config.validate(this);
		if (err != null) { fail(err); return; }
		if (Build.VERSION.SDK_INT >= 23
			&& checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
			fail(getString(R.string.err_no_mic_permission));
			return;
		}
		outFile = RecordingStore.newFile(this, config);

		if (config.isPlaybackCapture()) {
			if (Build.VERSION.SDK_INT < 29 || projectionData == null) {
				finishWithError(getString(R.string.err_capture_denied));
				return;
			}
			MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
			try {
				projection = mpm.getMediaProjection(projectionCode, projectionData);
			} catch (RuntimeException e) {
				projection = null;
			}
			if (projection == null) { finishWithError(getString(R.string.err_capture_denied)); return; }
			projection.registerCallback(new MediaProjection.Callback() {
				@Override public void onStop() { main.post(new Runnable() { @Override public void run() { stop(); } }); }
			}, main);
		}

		PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
		if (pm != null) {
			wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CandyRecorder:capture");
			wakeLock.acquire(6 * 60 * 60 * 1000L);
		}

		router = new AudioRouter(this);
		router.prepare(config.route, new AudioRouter.Callback() {
			@Override public void onReady(@Nullable AudioDeviceInfo device, @NonNull String note) {
				routingNote = note;
				launchEngine(device);
			}
		});
	}

	private void launchEngine(@Nullable final AudioDeviceInfo device) {
		if (state != STATE_PREPARING) return;
		engine = config.engineB ? new AudioRecordEngine() : new MediaRecorderEngine();
		engine.stats().putExtra("Routing", routingNote);
		final RecorderEngine.StartParams params = new RecorderEngine.StartParams(this, config, outFile, device, projection, engineListener);
		final RecorderEngine e = engine;
		worker.execute(new Runnable() {
			@Override public void run() {
				try {
					e.start(params);
					main.post(new Runnable() {
						@Override public void run() {
							if (state != STATE_PREPARING || engine != e) return;
							setState(STATE_RECORDING);
							updateNotification();
						}
					});
				} catch (final Exception ex) {
					main.post(new Runnable() {
						@Override public void run() {
							deleteIfEmpty();
							finishWithError(String.valueOf(ex.getMessage()));
						}
					});
				}
			}
		});
	}

	private final RecorderEngine.Listener engineListener = new RecorderEngine.Listener() {
		@Override public void onMeter(@NonNull MeterFrame frame) {
			lastFrame = frame;
			if (meterPostPending || clients.isEmpty()) return;
			meterPostPending = true;
			main.post(new Runnable() {
				@Override public void run() {
					meterPostPending = false;
					MeterFrame f = lastFrame;
					if (f == null) return;
					for (Client c : clients) c.onMeter(f);
				}
			});
		}

		@Override public void onError(@NonNull final String message) {
			main.post(new Runnable() {
				@Override public void run() {
					if (state == STATE_IDLE) return;
					writeMeta();
					finishWithError(message);
				}
			});
		}
	};

	public void pause() {
		if (state != STATE_RECORDING || engine == null) return;
		engine.pause();
		if (!engine.isPaused()) { notifyError(getString(R.string.err_pause_unsupported)); return; }
		setState(STATE_PAUSED);
		updateNotification();
	}

	public void resume() {
		if (state != STATE_PAUSED || engine == null) return;
		engine.resume();
		setState(STATE_RECORDING);
		updateNotification();
	}

	public void stop() {
		if (state == STATE_PREPARING) {
			// Routing still pending: abandon without an engine.
			setState(STATE_STOPPING);
			cleanup();
			deleteIfEmpty();
			finishIdle();
			return;
		}
		if (state != STATE_RECORDING && state != STATE_PAUSED) return;
		setState(STATE_STOPPING);
		final RecorderEngine e = engine;
		worker.execute(new Runnable() {
			@Override public void run() {
				String problem = null;
				try { e.stop(); } catch (Exception ex) { problem = String.valueOf(ex.getMessage()); }
				final String p = problem;
				main.post(new Runnable() {
					@Override public void run() {
						writeMeta();
						File f = outFile;
						cleanup();
						finishIdle();
						if (p != null) notifyError(p);
						if (f != null && f.isFile() && f.length() > 0) {
							for (Client c : clients) c.onFinished(f);
						}
					}
				});
			}
		});
	}

	// ---- helpers -----------------------------------------------------------------------------

	private void fail(String msg) {
		cleanup();
		engine = null;
		outFile = null;
		setState(STATE_IDLE);
		stopForeground(true);
		stopSelf();
		notifyError(msg);
	}

	private void finishWithError(String msg) {
		cleanup();
		finishIdle();
		notifyError(msg);
	}

	private void finishIdle() {
		engine = null;
		setState(STATE_IDLE);
		stopForeground(true);
		stopSelf();
	}

	private void notifyError(String msg) {
		for (Client c : clients) c.onError(msg);
	}

	private void deleteIfEmpty() {
		if (outFile != null && outFile.isFile() && outFile.length() == 0) //noinspection ResultOfMethodCallIgnored
			outFile.delete();
	}

	private void cleanup() {
		if (router != null) { router.release(); router = null; }
		if (projection != null) { try { projection.stop(); } catch (RuntimeException ignored) {} projection = null; }
		if (wakeLock != null) { if (wakeLock.isHeld()) wakeLock.release(); wakeLock = null; }
	}

	private void setState(int s) {
		state = s;
		for (Client c : clients) c.onState(s);
	}

	private void writeMeta() {
		if (engine == null || outFile == null || !outFile.isFile()) return;
		EngineStats st = engine.stats();
		Map<String, String> m = new LinkedHashMap<>();
		m.put("Engine", st.engineName);
		m.put("Source", st.sourceName);
		m.put("Requested device", st.deviceName);
		m.put("Routed device", st.routedDevice);
		m.put("Active microphones", st.micInfo);
		m.put("Sample rate", st.sampleRate + " Hz (requested " + st.requestedSampleRate + ")");
		m.put("Channels", String.valueOf(st.channels));
		m.put("Capture encoding", st.encodingName);
		m.put("Codec", st.codecName);
		if (config != null) {
			m.put("Bitrate", config.isCompressed() ? config.bitrate + " bps" : "n/a");
			m.put("Raw mode", String.valueOf(config.rawMode));
			m.put("Silence skip", config.silenceSkip ? config.silenceDb + " dB / " + config.silenceHoldMs + " ms" : "off");
			m.put("Route", String.valueOf(config.route));
			m.put("Mic direction", config.micDirection + " / field " + config.fieldDimension);
		}
		m.put("Buffer frames", String.valueOf(st.bufferFrames));
		m.put("Started", Fmt.date(startedWallMs));
		m.put("Recorded time", Fmt.clock(st.elapsedMs()));
		m.put("Frames written", String.valueOf(st.framesRead));
		m.put("Frames skipped", String.valueOf(st.framesSkipped));
		m.put("Clipped chunks", String.valueOf(st.clipCount));
		m.put("Gate events", String.valueOf(st.gateEvents));
		m.put("Bytes", String.valueOf(st.bytesWritten));
		m.putAll(st.extrasSnapshot());
		MetaFile.write(outFile, m);
	}

	// ---- notification ------------------------------------------------------------------------

	private void createChannel() {
		if (Build.VERSION.SDK_INT < 26) return;
		NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
		if (nm == null) return;
		NotificationChannel ch = new NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW);
		ch.setDescription(getString(R.string.notif_channel_desc));
		ch.setShowBadge(false);
		nm.createNotificationChannel(ch);
	}

	private PendingIntent servicePending(String action, int req) {
		Intent i = new Intent(this, RecordingService.class).setAction(action);
		int flags = PendingIntent.FLAG_UPDATE_CURRENT;
		if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
		return PendingIntent.getService(this, req, i, flags);
	}

	private Notification buildNotification() {
		Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
		int flags = PendingIntent.FLAG_UPDATE_CURRENT;
		if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
		PendingIntent content = PendingIntent.getActivity(this, 0, open, flags);

		Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
		String title;
		if (state == STATE_PAUSED) title = getString(R.string.notif_paused);
		else if (state == STATE_PREPARING) title = getString(R.string.notif_preparing);
		else title = getString(R.string.notif_recording);
		b.setContentTitle(title)
			.setContentText(config != null ? config.summary(this) : "")
			.setSubText(outFile != null ? outFile.getName() : null)
			.setSmallIcon(R.drawable.ic_notify)
			.setOngoing(true)
			.setOnlyAlertOnce(true)
			.setContentIntent(content)
			.setVisibility(Notification.VISIBILITY_PUBLIC);
		if (state == STATE_RECORDING) {
			b.setWhen(System.currentTimeMillis() - (engine != null ? engine.stats().elapsedMs() : 0)).setUsesChronometer(true);
		}
		if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_LOW);
		if (state == STATE_RECORDING || state == STATE_PAUSED) {
			String pauseLabel = state == STATE_PAUSED ? getString(R.string.action_resume) : getString(R.string.action_pause);
			b.addAction(new Notification.Action.Builder(0, pauseLabel, servicePending(ACTION_TOGGLE_PAUSE, 1)).build());
			b.addAction(new Notification.Action.Builder(0, getString(R.string.action_stop), servicePending(ACTION_STOP, 2)).build());
		}
		return b.build();
	}

	private void goForeground(Notification n) {
		if (Build.VERSION.SDK_INT >= 29) {
			int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
			if (config != null && config.isPlaybackCapture()) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
			startForeground(NOTIFICATION_ID, n, type);
		} else {
			startForeground(NOTIFICATION_ID, n);
		}
	}

	private void updateNotification() {
		NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
		if (nm != null && state != STATE_IDLE) nm.notify(NOTIFICATION_ID, buildNotification());
	}

	/** Convenience for the activity: build the start intent. */
	public static Intent startIntent(Context ctx, int projectionCode, @Nullable Intent projectionData) {
		Intent i = new Intent(ctx, RecordingService.class).setAction(ACTION_START);
		if (projectionData != null) {
			i.putExtra(EXTRA_PROJECTION_CODE, projectionCode);
			i.putExtra(EXTRA_PROJECTION_DATA, projectionData);
		}
		return i;
	}

	public static void send(Context ctx, String action) {
		Intent i = new Intent(ctx, RecordingService.class).setAction(action);
		ctx.startService(i);
	}

	public static boolean needsStoragePermission(Context ctx, RecordingConfig cfg) {
		return cfg.storage == Choices.STORAGE_PUBLIC && Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT <= 29
			&& ctx.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED;
	}
}
