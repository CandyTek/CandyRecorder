package com.candytek.recorder;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.StringRes;

import com.candytek.recorder.core.Choices;
import com.candytek.recorder.core.Prefs;
import com.candytek.recorder.core.RecordingConfig;
import com.candytek.recorder.engine.AudioRouter;
import com.candytek.recorder.storage.RecordingStore;
import com.candytek.recorder.ui.Ui;

import java.util.List;

/** Every tunable parameter, grouped by section. Rows are generated so the screen stays in sync with Choices. */
public class SettingsActivity extends Activity {

	private Prefs prefs;
	private LinearLayout root;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_settings);
		prefs = new Prefs(this);
		root = findViewById(R.id.settingsRoot);
		((TextView) findViewById(R.id.headerTitle)).setText(R.string.title_settings);
		findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { finish(); } });
		Button action = findViewById(R.id.btnHeaderAction);
		action.setVisibility(View.VISIBLE);
		action.setText(R.string.action_reset);
		action.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
			Ui.confirm(SettingsActivity.this, getString(R.string.action_reset), getString(R.string.reset_confirm), new Runnable() {
				@Override public void run() { prefs.resetAll(); build(); }
			});
		} });
		build();
	}

	// ---- builders ----------------------------------------------------------------------------

	private void build() {
		root.removeAllViews();
		final boolean engineB = prefs.engineB();
		final RecordingConfig cfg = prefs.buildConfig();

		header(R.string.sec_engine);
		choiceRow(R.string.set_engine, R.string.set_engine_desc,
			new CharSequence[] {getString(R.string.engine_a), getString(R.string.engine_b)}, engineB ? 1 : 0,
			new Ui.OnPick() { @Override public void pick(int i) { prefs.put(Prefs.ENGINE, i == 1 ? "B" : "A"); build(); } });

		header(R.string.sec_capture);
		optRow(R.string.set_source, R.string.set_source_desc, Choices.SOURCES, prefs.source(), Prefs.SOURCE);
		optRow(R.string.set_channels, R.string.set_channels_desc, Choices.CHANNELS, prefs.channels(), Prefs.CHANNELS);
		intRow(R.string.set_sample_rate, R.string.set_sample_rate_desc, Choices.SAMPLE_RATES, prefs.sampleRate(), Prefs.SAMPLE_RATE, R.string.unit_hz);
		optRow(R.string.set_route, R.string.set_route_desc, Choices.ROUTES, prefs.route(), Prefs.ROUTE);
		optRow(R.string.set_direction, R.string.set_direction_desc, Choices.MIC_DIRECTIONS, prefs.micDirection(), Prefs.MIC_DIRECTION);
		fieldRow();
		toggleRow(R.string.set_privacy, R.string.set_privacy_desc, prefs.privacySensitive(), Prefs.PRIVACY_SENSITIVE);

		header(R.string.sec_format);
		if (engineB) {
			codecRow();
			optRow(R.string.set_pcm, R.string.set_pcm_desc, Choices.PCM_ENCODINGS, prefs.pcmEncoding(), Prefs.PCM_ENCODING);
			if (cfg.codecB.compressed && !"flac".equals(cfg.codecB.id)) {
				intRow(R.string.set_bitrate, R.string.set_bitrate_desc, Choices.BITRATES, prefs.bitrate(), Prefs.BITRATE, R.string.unit_bps);
				optRow(R.string.set_bitrate_mode, R.string.set_bitrate_mode_desc, Choices.BITRATE_MODES, prefs.bitrateMode(), Prefs.BITRATE_MODE);
			}
			if ("flac".equals(cfg.codecB.id)) {
				intRow(R.string.set_flac_level, R.string.set_flac_level_desc, Choices.FLAC_LEVELS, prefs.flacLevel(), Prefs.FLAC_LEVEL, 0);
			}
			intRow(R.string.set_buffer, R.string.set_buffer_desc, Choices.BUFFER_MULTIPLIERS, prefs.bufferMultiplier(), Prefs.BUFFER_MULT, R.string.unit_x);
		} else {
			formatRow();
			encoderRow();
			intRow(R.string.set_bitrate, R.string.set_bitrate_desc_a, Choices.BITRATES, prefs.bitrate(), Prefs.BITRATE, R.string.unit_bps);
		}

		header(R.string.sec_processing);
		toggleRow(R.string.set_raw, R.string.set_raw_desc, prefs.rawMode(), Prefs.RAW_MODE);
		if (engineB) {
			optRow(R.string.set_ns, R.string.set_ns_desc, Choices.FX_MODES, prefs.ns(), Prefs.NS);
			optRow(R.string.set_aec, R.string.set_aec_desc, Choices.FX_MODES, prefs.aec(), Prefs.AEC);
			optRow(R.string.set_agc, R.string.set_agc_desc, Choices.FX_MODES, prefs.agc(), Prefs.AGC);
		} else {
			infoRow(R.string.set_fx_engine_a, getString(R.string.set_fx_engine_a_desc), null);
		}

		header(R.string.sec_silence);
		toggleRow(R.string.set_silence, R.string.set_silence_desc, prefs.silenceSkip(), Prefs.SILENCE_SKIP);
		intRow(R.string.set_silence_db, R.string.set_silence_db_desc, Choices.SILENCE_DB, prefs.silenceDb(), Prefs.SILENCE_DB, R.string.unit_dbfs);
		intRow(R.string.set_silence_hold, R.string.set_silence_hold_desc, Choices.SILENCE_HOLD_MS, prefs.silenceHoldMs(), Prefs.SILENCE_HOLD, R.string.unit_ms);

		header(R.string.sec_storage);
		optRow(R.string.set_storage, R.string.set_storage_desc, Choices.STORAGES, prefs.storage(), Prefs.STORAGE);
		textRow(R.string.set_prefix, R.string.set_prefix_desc, prefs.filePrefix(), Prefs.FILE_PREFIX);
		infoRow(R.string.set_folder, RecordingStore.dirFor(this, prefs.storage()).getAbsolutePath(), null);

		header(R.string.sec_dashboard);
		intRow(R.string.set_fft, R.string.set_fft_desc, Choices.FFT_SIZES, prefs.fftSize(), Prefs.FFT_SIZE, R.string.unit_points);
		optRow(R.string.set_meter_speed, R.string.set_meter_speed_desc, Choices.METER_SPEEDS, prefs.meterSpeed(), Prefs.METER_SPEED);
		toggleRow(R.string.set_keep_screen, R.string.set_keep_screen_desc, prefs.keepScreenOn(), Prefs.KEEP_SCREEN_ON);

		header(R.string.sec_device);
		infoRow(R.string.info_api, "Android " + Build.VERSION.RELEASE + " · API " + Build.VERSION.SDK_INT + " · " + Build.MANUFACTURER + " " + Build.MODEL, null);
		infoRow(R.string.info_unprocessed, RecordingConfig.supportsUnprocessed(this) ? getString(R.string.yes) : getString(R.string.no), null);
		infoRow(R.string.info_public_storage, RecordingStore.publicWritable(this) ? getString(R.string.yes) : getString(R.string.no), null);
		infoRow(R.string.info_inputs, getString(R.string.info_inputs_tap), new Runnable() {
			@Override public void run() {
				List<String> devs = AudioRouter.listInputs(SettingsActivity.this);
				StringBuilder sb = new StringBuilder();
				if (devs.isEmpty()) sb.append(getString(R.string.info_inputs_none));
				for (String d : devs) sb.append("• ").append(d).append("\n\n");
				Ui.message(SettingsActivity.this, getString(R.string.info_inputs), sb.toString().trim());
			}
		});
	}

	private void header(@StringRes int title) {
		TextView tv = new TextView(this);
		tv.setText(title);
		tv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
		tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
		tv.setAllCaps(true);
		tv.setLetterSpacing(0.16f);
		tv.setTextColor(getResources().getColor(R.color.accent));
		tv.setPadding(Ui.dp(this, 4), Ui.dp(this, 18), 0, Ui.dp(this, 6));
		root.addView(tv);
	}

	private LinearLayout rowShell(@StringRes int title, CharSequence desc, CharSequence value) {
		LinearLayout row = new LinearLayout(this);
		row.setOrientation(LinearLayout.VERTICAL);
		row.setBackgroundResource(R.drawable.bg_panel);
		int p = Ui.dp(this, 12);
		row.setPadding(p, Ui.dp(this, 10), p, Ui.dp(this, 10));
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
		lp.bottomMargin = Ui.dp(this, 6);
		row.setLayoutParams(lp);

		LinearLayout top = new LinearLayout(this);
		top.setOrientation(LinearLayout.HORIZONTAL);
		TextView t = new TextView(this);
		t.setText(title);
		t.setTypeface(Typeface.MONOSPACE);
		t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
		t.setTextColor(getResources().getColor(R.color.text));
		top.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
		if (value != null) {
			TextView v = new TextView(this);
			v.setText(value);
			v.setTypeface(Typeface.MONOSPACE);
			v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
			v.setTextColor(getResources().getColor(R.color.accent));
			v.setGravity(android.view.Gravity.END);
			v.setPadding(Ui.dp(this, 8), 0, 0, 0);
			top.addView(v, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
		}
		row.addView(top);
		if (desc != null && desc.length() > 0) {
			TextView d = new TextView(this);
			d.setText(desc);
			d.setTypeface(Typeface.MONOSPACE);
			d.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
			d.setTextColor(getResources().getColor(R.color.text_dim));
			d.setPadding(0, Ui.dp(this, 3), 0, 0);
			row.addView(d);
		}
		root.addView(row);
		return row;
	}

	private void choiceRow(@StringRes final int title, @StringRes int desc, final CharSequence[] labels, final int checked, final Ui.OnPick cb) {
		CharSequence current = checked >= 0 && checked < labels.length ? labels[checked] : "—";
		LinearLayout row = rowShell(title, getString(desc), current);
		row.setOnClickListener(new View.OnClickListener() {
			@Override public void onClick(View v) { Ui.choice(SettingsActivity.this, getString(title), labels, checked, cb); }
		});
	}

	private void optRow(@StringRes int title, @StringRes int desc, Choices.Opt[] all, int current, final String key) {
		final List<Choices.Opt> opts = Choices.available(all);
		CharSequence[] labels = new CharSequence[opts.size()];
		int checked = -1;
		for (int i = 0; i < opts.size(); i++) {
			Choices.Opt o = opts.get(i);
			labels[i] = getString(o.label) + (o.privileged ? getString(R.string.suffix_privileged) : "");
			if (o.value == current) checked = i;
		}
		choiceRow(title, desc, labels, checked, new Ui.OnPick() {
			@Override public void pick(int i) { prefs.put(key, opts.get(i).value); build(); }
		});
	}

	private void intRow(@StringRes int title, @StringRes int desc, final int[] values, int current, final String key, @StringRes int unit) {
		CharSequence[] labels = new CharSequence[values.length];
		for (int i = 0; i < values.length; i++) {
			if (unit == R.string.unit_bps) labels[i] = com.candytek.recorder.util.Fmt.kbps(values[i]);
			else labels[i] = unit == 0 ? String.valueOf(values[i]) : getString(unit, values[i]);
		}
		int checked = Choices.indexOf(values, current);
		choiceRow(title, desc, labels, checked, new Ui.OnPick() {
			@Override public void pick(int i) { prefs.put(key, values[i]); build(); }
		});
	}

	private void toggleRow(@StringRes int title, @StringRes int desc, boolean value, final String key) {
		LinearLayout row = rowShell(title, getString(desc), null);
		LinearLayout top = (LinearLayout) row.getChildAt(0);
		final Switch sw = new Switch(this);
		sw.setChecked(value);
		sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
			@Override public void onCheckedChanged(CompoundButton b, boolean checked) { prefs.put(key, checked); build(); }
		});
		top.addView(sw, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
		row.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { sw.toggle(); } });
	}

	private void textRow(@StringRes final int title, @StringRes int desc, final String value, final String key) {
		LinearLayout row = rowShell(title, getString(desc), value);
		row.setOnClickListener(new View.OnClickListener() {
			@Override public void onClick(View v) {
				Ui.input(SettingsActivity.this, getString(title), value, new Ui.OnText() {
					@Override public void text(String t) { prefs.put(key, t); build(); }
				});
			}
		});
	}

	private void infoRow(@StringRes int title, String value, final Runnable onClick) {
		LinearLayout row = rowShell(title, value, null);
		if (onClick != null) row.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { onClick.run(); } });
	}

	// ---- rows with cross-dependencies ----------------------------------------------------------

	private void formatRow() {
		final java.util.ArrayList<Choices.FormatA> fmts = new java.util.ArrayList<>();
		for (Choices.FormatA f : Choices.FORMATS_A) if (f.available()) fmts.add(f);
		CharSequence[] labels = new CharSequence[fmts.size()];
		int checked = -1;
		for (int i = 0; i < fmts.size(); i++) {
			labels[i] = getString(fmts.get(i).label);
			if (fmts.get(i).id.equals(prefs.formatA())) checked = i;
		}
		choiceRow(R.string.set_format_a, R.string.set_format_a_desc, labels, checked, new Ui.OnPick() {
			@Override public void pick(int i) {
				Choices.FormatA f = fmts.get(i);
				prefs.put(Prefs.FORMAT_A, f.id);
				if (!f.supports(prefs.encoderA())) prefs.put(Prefs.ENCODER_A, f.encoders[0]);
				build();
			}
		});
	}

	private void encoderRow() {
		final Choices.FormatA fmt = Choices.formatA(prefs.formatA());
		final java.util.ArrayList<Choices.Opt> encs = new java.util.ArrayList<>();
		for (Choices.Opt o : Choices.ENCODERS_A) if (o.available() && fmt.supports(o.value)) encs.add(o);
		CharSequence[] labels = new CharSequence[encs.size()];
		int checked = -1;
		for (int i = 0; i < encs.size(); i++) {
			labels[i] = getString(encs.get(i).label);
			if (encs.get(i).value == prefs.encoderA()) checked = i;
		}
		choiceRow(R.string.set_encoder_a, R.string.set_encoder_a_desc, labels, checked, new Ui.OnPick() {
			@Override public void pick(int i) { prefs.put(Prefs.ENCODER_A, encs.get(i).value); build(); }
		});
	}

	private void codecRow() {
		final java.util.ArrayList<Choices.CodecB> codecs = new java.util.ArrayList<>();
		for (Choices.CodecB c : Choices.CODECS_B) if (c.available()) codecs.add(c);
		CharSequence[] labels = new CharSequence[codecs.size()];
		int checked = -1;
		for (int i = 0; i < codecs.size(); i++) {
			labels[i] = getString(codecs.get(i).label);
			if (codecs.get(i).id.equals(prefs.codecB())) checked = i;
		}
		choiceRow(R.string.set_codec_b, R.string.set_codec_b_desc, labels, checked, new Ui.OnPick() {
			@Override public void pick(int i) { prefs.put(Prefs.CODEC_B, codecs.get(i).id); build(); }
		});
	}

	private void fieldRow() {
		CharSequence[] labels = new CharSequence[Choices.FIELD_KEYS.length];
		for (int i = 0; i < labels.length; i++) labels[i] = getString(Choices.FIELD_LABELS[i]);
		int checked = Choices.indexOf(Choices.FIELD_KEYS, prefs.fieldDimension());
		choiceRow(R.string.set_field, R.string.set_field_desc, labels, checked, new Ui.OnPick() {
			@Override public void pick(int i) { prefs.put(Prefs.FIELD_DIMENSION, Choices.FIELD_KEYS[i]); build(); }
		});
	}
}
