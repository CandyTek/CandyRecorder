package com.candytek.recorder.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

import com.candytek.recorder.R;

/** Small dialog / widget helpers so the activities stay readable without AppCompat. */
public final class Ui {

	private Ui() {}

	public interface OnPick { void pick(int index); }

	public interface OnText { void text(@NonNull String value); }

	public static void toast(Context c, String msg) { Toast.makeText(c, msg, Toast.LENGTH_SHORT).show(); }

	public static void toast(Context c, @StringRes int res) { Toast.makeText(c, res, Toast.LENGTH_SHORT).show(); }

	public static void choice(Context c, CharSequence title, CharSequence[] labels, int checked, final OnPick cb) {
		new AlertDialog.Builder(c)
			.setTitle(title)
			.setSingleChoiceItems(labels, checked, new DialogInterface.OnClickListener() {
				@Override public void onClick(DialogInterface d, int which) {
					d.dismiss();
					cb.pick(which);
				}
			})
			.setNegativeButton(android.R.string.cancel, null)
			.show();
	}

	public static void confirm(Context c, CharSequence title, CharSequence msg, final Runnable yes) {
		new AlertDialog.Builder(c)
			.setTitle(title)
			.setMessage(msg)
			.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
				@Override public void onClick(DialogInterface d, int which) { yes.run(); }
			})
			.setNegativeButton(android.R.string.cancel, null)
			.show();
	}

	public static void input(Context c, CharSequence title, String initial, final OnText cb) {
		final EditText et = new EditText(c);
		et.setInputType(InputType.TYPE_CLASS_TEXT);
		et.setText(initial);
		et.setSelection(initial.length());
		et.setTypeface(Typeface.MONOSPACE);
		FrameLayout wrap = new FrameLayout(c);
		int pad = dp(c, 20);
		wrap.setPadding(pad, dp(c, 8), pad, 0);
		wrap.addView(et, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
		new AlertDialog.Builder(c)
			.setTitle(title)
			.setView(wrap)
			.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
				@Override public void onClick(DialogInterface d, int which) { cb.text(et.getText().toString()); }
			})
			.setNegativeButton(android.R.string.cancel, null)
			.show();
	}

	public static void message(Context c, CharSequence title, CharSequence body) {
		TextView tv = new TextView(c);
		tv.setTypeface(Typeface.MONOSPACE);
		tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
		tv.setTextColor(c.getResources().getColor(R.color.text));
		tv.setText(body);
		tv.setTextIsSelectable(true);
		int pad = dp(c, 20);
		tv.setPadding(pad, dp(c, 12), pad, dp(c, 12));
		android.widget.ScrollView sv = new android.widget.ScrollView(c);
		sv.addView(tv);
		new AlertDialog.Builder(c).setTitle(title).setView(sv).setPositiveButton(android.R.string.ok, null).show();
	}

	public static int dp(Context c, int dp) {
		return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, c.getResources().getDisplayMetrics()));
	}

	/** A "LABEL …… value" row used by the stats panel and the info screens. */
	public static View kvRow(Context c, CharSequence key, CharSequence value) {
		LinearLayout row = new LinearLayout(c);
		row.setOrientation(LinearLayout.HORIZONTAL);
		row.setGravity(Gravity.TOP);
		int px = dp(c, 2);
		row.setPadding(0, px, 0, px);
		TextView k = new TextView(c);
		k.setTypeface(Typeface.MONOSPACE);
		k.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
		k.setTextColor(c.getResources().getColor(R.color.text_dim));
		k.setText(key);
		k.setAllCaps(true);
		k.setLetterSpacing(0.06f);
		TextView v = new TextView(c);
		v.setTypeface(Typeface.MONOSPACE);
		v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
		v.setTextColor(c.getResources().getColor(R.color.text));
		v.setText(value);
		v.setGravity(Gravity.END);
		v.setTextIsSelectable(false);
		LinearLayout.LayoutParams kp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.42f);
		LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.58f);
		row.addView(k, kp);
		row.addView(v, vp);
		return row;
	}
}
