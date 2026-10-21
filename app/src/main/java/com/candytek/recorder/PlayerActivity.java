package com.candytek.recorder;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import com.candytek.recorder.playback.MediaPlayerWrapper;
import com.candytek.recorder.playback.PcmTrackPlayer;
import com.candytek.recorder.playback.Player;
import com.candytek.recorder.storage.RecordingItem;
import com.candytek.recorder.storage.RecordingStore;
import com.candytek.recorder.ui.Ui;
import com.candytek.recorder.util.Fmt;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

/** Playback with variable speed (pitch preserved on API 23+), share, info and delete. */
public class PlayerActivity extends Activity {

	public static final String EXTRA_PATH = "path";
	private static final float[] SPEEDS = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f};

	private File file;
	private Player player;
	private SeekBar seek;
	private TextView position, duration;
	private Button btnPlay;
	private final Button[] speedButtons = new Button[SPEEDS.length];
	private float speed = 1f;
	private boolean dragging;
	private final Handler ui = new Handler(Looper.getMainLooper());
	private final Runnable tick = new Runnable() {
		@Override public void run() {
			if (player != null) {
				long pos = player.positionMs();
				long dur = player.durationMs();
				position.setText(Fmt.clock(pos));
				if (!dragging && dur > 0) seek.setProgress((int) (pos * 1000 / dur));
				btnPlay.setText(player.isPlaying() ? R.string.player_pause : R.string.player_play);
			}
			ui.postDelayed(this, 100);
		}
	};

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_player);
		String path = getIntent().getStringExtra(EXTRA_PATH);
		file = path == null ? null : new File(path);
		if (file == null || !file.isFile()) { Ui.toast(this, R.string.err_missing_file); finish(); return; }

		((TextView) findViewById(R.id.headerTitle)).setText(R.string.title_player);
		findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { finish(); } });
		((TextView) findViewById(R.id.fileName)).setText(file.getName());
		seek = findViewById(R.id.seek);
		position = findViewById(R.id.position);
		duration = findViewById(R.id.duration);
		btnPlay = findViewById(R.id.btnPlay);

		player = file.getName().toLowerCase().endsWith(".pcm") ? new PcmTrackPlayer() : new MediaPlayerWrapper();
		try {
			player.prepare(file);
		} catch (IOException | RuntimeException e) {
			Ui.toast(this, getString(R.string.err_player, String.valueOf(e.getMessage())));
			player.release();
			player = null;
		}
		String meta = Fmt.bytes(file.length()) + " · " + Fmt.date(file.lastModified())
			+ (player != null ? " · " + player.describe() : "");
		((TextView) findViewById(R.id.fileMeta)).setText(meta);
		duration.setText(player != null ? Fmt.clock(player.durationMs()) : "--");
		if (player != null) player.setOnCompletion(new Runnable() { @Override public void run() { btnPlay.setText(R.string.player_play); } });

		seek.setMax(1000);
		seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
			@Override public void onProgressChanged(SeekBar s, int p, boolean user) {
				if (user && player != null) position.setText(Fmt.clock(player.durationMs() * p / 1000));
			}
			@Override public void onStartTrackingTouch(SeekBar s) { dragging = true; }
			@Override public void onStopTrackingTouch(SeekBar s) {
				dragging = false;
				if (player != null) player.seekTo(player.durationMs() * s.getProgress() / 1000);
			}
		});

		btnPlay.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
			if (player == null) return;
			if (player.isPlaying()) player.pause(); else player.play();
		} });
		findViewById(R.id.btnBack10).setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { skip(-10000); } });
		findViewById(R.id.btnFwd10).setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { skip(10000); } });

		LinearLayout speedRow = findViewById(R.id.speedRow);
		for (int i = 0; i < SPEEDS.length; i++) {
			final float s = SPEEDS[i];
			Button b = new Button(this, null, 0, R.style.Btn);
			b.setText(String.format(Locale.US, s == (int) s ? "%.0fx" : "%.2fx", s).replace(".50x", ".5x"));
			b.setMinHeight(Ui.dp(this, 38));
			b.setPadding(0, 0, 0, 0);
			LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
			if (i > 0) lp.leftMargin = Ui.dp(this, 4);
			b.setLayoutParams(lp);
			b.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { setSpeed(s); } });
			speedButtons[i] = b;
			speedRow.addView(b);
		}
		if (player != null && !player.supportsSpeed()) {
			TextView note = findViewById(R.id.speedNote);
			note.setVisibility(View.VISIBLE);
			note.setText(R.string.player_speed_unsupported);
		}
		setSpeed(1f);

		findViewById(R.id.btnShare).setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { HistoryActivity.share(PlayerActivity.this, file); } });
		findViewById(R.id.btnInfo).setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
			startActivity(new Intent(PlayerActivity.this, AudioInfoActivity.class).putExtra(AudioInfoActivity.EXTRA_PATH, file.getAbsolutePath()));
		} });
		findViewById(R.id.btnDelete).setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
			Ui.confirm(PlayerActivity.this, getString(R.string.action_delete), getString(R.string.delete_confirm, file.getName()), new Runnable() {
				@Override public void run() {
					if (player != null) { player.release(); player = null; }
					if (RecordingStore.delete(new RecordingItem(file, false))) finish();
					else Ui.toast(PlayerActivity.this, R.string.err_delete);
				}
			});
		} });
	}

	private void skip(long deltaMs) {
		if (player == null) return;
		long target = Math.max(0, Math.min(player.durationMs(), player.positionMs() + deltaMs));
		player.seekTo(target);
	}

	private void setSpeed(float s) {
		speed = s;
		if (player != null) player.setSpeed(s);
		for (int i = 0; i < SPEEDS.length; i++) {
			boolean sel = SPEEDS[i] == s;
			speedButtons[i].setBackgroundResource(sel ? R.drawable.btn_accent : R.drawable.btn_ghost);
			speedButtons[i].setTextColor(getResources().getColor(sel ? R.color.bg : R.color.text));
		}
	}

	@Override
	protected void onResume() {
		super.onResume();
		ui.post(tick);
	}

	@Override
	protected void onPause() {
		super.onPause();
		ui.removeCallbacks(tick);
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		if (player != null) { player.release(); player = null; }
	}
}
