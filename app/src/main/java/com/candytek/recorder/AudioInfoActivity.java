package com.candytek.recorder;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.candytek.recorder.storage.AudioInfoReader;
import com.candytek.recorder.ui.Ui;

import java.io.File;
import java.util.List;
import java.util.Map;

/** Full dump of a recording's underlying parameters (container, track format, headers, settings). */
public class AudioInfoActivity extends Activity {

	public static final String EXTRA_PATH = "path";

	private LinearLayout root;
	private String plainText = "";

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_info);
		String path = getIntent().getStringExtra(EXTRA_PATH);
		final File file = path == null ? null : new File(path);
		if (file == null || !file.isFile()) { Ui.toast(this, R.string.err_missing_file); finish(); return; }

		((TextView) findViewById(R.id.headerTitle)).setText(R.string.title_info);
		findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { finish(); } });
		Button copy = findViewById(R.id.btnHeaderAction);
		copy.setVisibility(View.VISIBLE);
		copy.setText(R.string.action_copy);
		copy.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
			ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
			if (cm != null) { cm.setPrimaryClip(ClipData.newPlainText(file.getName(), plainText)); Ui.toast(AudioInfoActivity.this, R.string.copied); }
		} });
		root = findViewById(R.id.infoRoot);

		final TextView loading = new TextView(this);
		loading.setText(R.string.info_loading);
		loading.setTypeface(Typeface.MONOSPACE);
		loading.setTextColor(getResources().getColor(R.color.text_dim));
		loading.setPadding(0, Ui.dp(this, 20), 0, 0);
		root.addView(loading);

		final Handler main = new Handler(Looper.getMainLooper());
		new Thread(new Runnable() {
			@Override public void run() {
				final List<AudioInfoReader.Section> sections = AudioInfoReader.read(file);
				main.post(new Runnable() { @Override public void run() { if (!isFinishing()) render(sections); } });
			}
		}, "candy-info").start();
	}

	private void render(List<AudioInfoReader.Section> sections) {
		root.removeAllViews();
		StringBuilder sb = new StringBuilder();
		for (AudioInfoReader.Section s : sections) {
			TextView h = new TextView(this);
			h.setText(s.title);
			h.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
			h.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
			h.setAllCaps(true);
			h.setLetterSpacing(0.14f);
			h.setTextColor(getResources().getColor(R.color.accent));
			h.setPadding(Ui.dp(this, 4), Ui.dp(this, 16), 0, Ui.dp(this, 6));
			root.addView(h);
			sb.append("## ").append(s.title).append('\n');

			LinearLayout card = new LinearLayout(this);
			card.setOrientation(LinearLayout.VERTICAL);
			card.setBackgroundResource(R.drawable.bg_panel);
			int p = Ui.dp(this, 12);
			card.setPadding(p, Ui.dp(this, 6), p, Ui.dp(this, 6));
			for (Map.Entry<String, String> e : s.rows.entrySet()) {
				card.addView(Ui.kvRow(this, e.getKey(), e.getValue()));
				sb.append(e.getKey()).append(": ").append(e.getValue()).append('\n');
			}
			root.addView(card);
			sb.append('\n');
		}
		plainText = sb.toString();
	}
}
