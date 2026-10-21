package com.candytek.recorder;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.MediaMetadataRetriever;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.candytek.recorder.storage.RecordingItem;
import com.candytek.recorder.storage.RecordingStore;
import com.candytek.recorder.ui.Ui;
import com.candytek.recorder.util.Fmt;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Recording history: tap to play, long-press for share / rename / info / delete. */
public class HistoryActivity extends Activity {

	private final List<RecordingItem> items = new ArrayList<>();
	private Adapter adapter;
	private ListView list;
	private TextView empty;
	private final Handler main = new Handler(Looper.getMainLooper());
	private Thread prober;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_history);
		((TextView) findViewById(R.id.headerTitle)).setText(R.string.title_history);
		findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { finish(); } });
		Button action = findViewById(R.id.btnHeaderAction);
		action.setVisibility(View.VISIBLE);
		action.setText(R.string.action_refresh);
		action.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { reload(); } });

		list = findViewById(R.id.list);
		empty = findViewById(R.id.empty);
		adapter = new Adapter();
		list.setAdapter(adapter);
		list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
			@Override public void onItemClick(AdapterView<?> p, View v, int pos, long id) { open(items.get(pos)); }
		});
		list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
			@Override public boolean onItemLongClick(AdapterView<?> p, View v, int pos, long id) { menu(items.get(pos)); return true; }
		});
		TextView folder = findViewById(R.id.folderLine);
		folder.setText(getString(R.string.history_folders, RecordingStore.appDir(this).getAbsolutePath(), RecordingStore.publicDir().getAbsolutePath()));
	}

	@Override
	protected void onResume() {
		super.onResume();
		reload();
	}

	private void reload() {
		items.clear();
		items.addAll(RecordingStore.list(this));
		adapter.notifyDataSetChanged();
		empty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
		probeDurations();
	}

	private void probeDurations() {
		final List<RecordingItem> snapshot = new ArrayList<>(items);
		prober = new Thread(new Runnable() {
			@Override public void run() {
				for (RecordingItem it : snapshot) {
					if (Thread.currentThread().isInterrupted()) return;
					if (it.durationMs >= 0) continue;
					it.durationMs = probe(it.file);
					main.post(new Runnable() { @Override public void run() { adapter.notifyDataSetChanged(); } });
				}
			}
		}, "candy-probe");
		prober.start();
	}

	static long probe(File f) {
		String n = f.getName().toLowerCase();
		if (n.endsWith(".pcm")) {
			int[] p = RecordingStore.pcmParams(f);
			if (p == null) return 0;
			long bps = (long) p[0] * p[1] * com.candytek.recorder.dsp.PcmUtil.bytesPerSample(p[2]);
			return bps > 0 ? f.length() * 1000L / bps : 0;
		}
		MediaMetadataRetriever mr = new MediaMetadataRetriever();
		try {
			mr.setDataSource(f.getAbsolutePath());
			String d = mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
			return d == null ? 0 : Long.parseLong(d);
		} catch (RuntimeException e) {
			return 0;
		} finally {
			try { mr.release(); } catch (Exception ignored) {}
		}
	}

	private void open(RecordingItem it) {
		startActivity(new Intent(this, PlayerActivity.class).putExtra(PlayerActivity.EXTRA_PATH, it.file.getAbsolutePath()));
	}

	private void menu(final RecordingItem it) {
		CharSequence[] actions = {
			getString(R.string.player_play), getString(R.string.action_share), getString(R.string.action_info),
			getString(R.string.action_rename), getString(R.string.action_delete)
		};
		new android.app.AlertDialog.Builder(this).setTitle(it.name).setItems(actions, new android.content.DialogInterface.OnClickListener() {
			@Override public void onClick(android.content.DialogInterface d, int which) {
				switch (which) {
					case 0: open(it); break;
					case 1: share(HistoryActivity.this, it.file); break;
					case 2: startActivity(new Intent(HistoryActivity.this, AudioInfoActivity.class).putExtra(AudioInfoActivity.EXTRA_PATH, it.file.getAbsolutePath())); break;
					case 3: rename(it); break;
					case 4: delete(it); break;
				}
			}
		}).show();
	}

	private void rename(final RecordingItem it) {
		String base = it.name.lastIndexOf('.') > 0 ? it.name.substring(0, it.name.lastIndexOf('.')) : it.name;
		Ui.input(this, getString(R.string.action_rename), base, new Ui.OnText() {
			@Override public void text(@NonNull String value) {
				if (RecordingStore.rename(it, value)) reload(); else Ui.toast(HistoryActivity.this, R.string.err_rename);
			}
		});
	}

	private void delete(final RecordingItem it) {
		Ui.confirm(this, getString(R.string.action_delete), getString(R.string.delete_confirm, it.name), new Runnable() {
			@Override public void run() {
				if (RecordingStore.delete(it)) reload(); else Ui.toast(HistoryActivity.this, R.string.err_delete);
			}
		});
	}

	static void share(Context ctx, File f) {
		Intent i = new Intent(Intent.ACTION_SEND);
		int dot = f.getName().lastIndexOf('.');
		i.setType(RecordingStore.mimeFor(dot >= 0 ? f.getName().substring(dot + 1).toLowerCase() : ""));
		i.putExtra(Intent.EXTRA_STREAM, RecordingStore.shareUri(ctx, f));
		i.putExtra(Intent.EXTRA_SUBJECT, f.getName());
		i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
		ctx.startActivity(Intent.createChooser(i, ctx.getString(R.string.action_share)));
	}

	@Override
	protected void onPause() {
		super.onPause();
		if (prober != null) prober.interrupt();
	}

	private final class Adapter extends BaseAdapter {
		@Override public int getCount() { return items.size(); }
		@Override public Object getItem(int i) { return items.get(i); }
		@Override public long getItemId(int i) { return i; }

		@Override
		public View getView(int pos, View convert, ViewGroup parent) {
			View v = convert != null ? convert : LayoutInflater.from(HistoryActivity.this).inflate(R.layout.item_recording, parent, false);
			RecordingItem it = items.get(pos);
			((TextView) v.findViewById(R.id.ext)).setText(it.ext.toUpperCase());
			((TextView) v.findViewById(R.id.name)).setText(it.name);
			String where = it.inPublicFolder ? getString(R.string.storage_public_short) : getString(R.string.storage_app_short);
			((TextView) v.findViewById(R.id.meta)).setText(Fmt.date(it.modified) + " · " + Fmt.bytes(it.size) + " · " + where);
			((TextView) v.findViewById(R.id.duration)).setText(it.durationMs > 0 ? Fmt.duration(it.durationMs) : "--:--");
			return v;
		}
	}
}
