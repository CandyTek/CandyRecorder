package com.candytek.recorder.storage;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.candytek.recorder.core.Choices;
import com.candytek.recorder.core.RecordingConfig;
import com.candytek.recorder.dsp.PcmUtil;
import com.candytek.recorder.util.Fmt;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where recordings live. Two external locations are supported:
 * <ul>
 *   <li>App folder: /sdcard/Android/data/&lt;pkg&gt;/files/Recordings (no permission needed)</li>
 *   <li>Public folder: /sdcard/Music/CandyRecorder (needs legacy storage access)</li>
 * </ul>
 */
public final class RecordingStore {

	public static final String PUBLIC_DIR = "CandyRecorder";
	public static final String APP_DIR = "Recordings";
	public static final Set<String> EXTENSIONS = new HashSet<>(Arrays.asList(
		"wav", "pcm", "flac", "m4a", "mp4", "3gp", "ogg", "aac", "ts", "amr", "awb", "opus", "mp3", "webm"));

	/** Raw PCM file-name suffix: _48000Hz_2ch_s16.pcm */
	private static final Pattern PCM_NAME = Pattern.compile("_(\\d+)Hz_(\\d)ch_(u8|s16|s24|s32|f32)\\.pcm$");

	private RecordingStore() {}

	@NonNull
	public static File appDir(Context ctx) {
		File base = ctx.getExternalFilesDir(null);
		if (base == null) base = ctx.getFilesDir();
		File d = new File(base, APP_DIR);
		//noinspection ResultOfMethodCallIgnored
		d.mkdirs();
		return d;
	}

	@NonNull
	public static File publicDir() {
		return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), PUBLIC_DIR);
	}

	/** True when the app may use plain file I/O inside the public Music folder. */
	public static boolean publicWritable(Context ctx) {
		if (Build.VERSION.SDK_INT >= 29 && !Environment.isExternalStorageLegacy()) return false;
		if (Build.VERSION.SDK_INT >= 23
			&& ctx.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
			return false;
		}
		return Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState());
	}

	/** Resolves the folder for a storage preference, falling back to the app folder. */
	@NonNull
	public static File dirFor(Context ctx, int storage) {
		if (storage == Choices.STORAGE_PUBLIC && publicWritable(ctx)) {
			File d = publicDir();
			if (d.isDirectory() || d.mkdirs()) return d;
		}
		return appDir(ctx);
	}

	@NonNull
	public static File newFile(Context ctx, RecordingConfig cfg) {
		File dir = dirFor(ctx, cfg.storage);
		StringBuilder sb = new StringBuilder(cfg.filePrefix).append('_').append(Fmt.stamp(System.currentTimeMillis()));
		if (cfg.isPcmOutput()) {
			sb.append('_').append(cfg.sampleRate).append("Hz_").append(cfg.channels).append("ch_")
				.append(PcmUtil.encodingTag(cfg.pcmEncoding));
		}
		String base = sb.toString();
		File f = new File(dir, base + "." + cfg.fileExtension());
		int n = 1;
		while (f.exists()) f = new File(dir, base + "_" + (n++) + "." + cfg.fileExtension());
		return f;
	}

	/** Parses sample rate / channels / encoding out of a raw PCM file name. Null if not tagged. */
	@Nullable
	public static int[] pcmParams(File f) {
		Matcher m = PCM_NAME.matcher(f.getName());
		if (!m.find()) return null;
		return new int[] {Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), PcmUtil.encodingFromTag(m.group(3))};
	}

	@NonNull
	public static List<RecordingItem> list(Context ctx) {
		List<RecordingItem> out = new ArrayList<>();
		collect(appDir(ctx), false, out);
		File pub = publicDir();
		if (pub.isDirectory() && pub.canRead()) collect(pub, true, out);
		Collections.sort(out, new Comparator<RecordingItem>() {
			@Override public int compare(RecordingItem a, RecordingItem b) {
				return Long.compare(b.modified, a.modified);
			}
		});
		return out;
	}

	private static void collect(File dir, boolean isPublic, List<RecordingItem> out) {
		File[] files = dir.listFiles();
		if (files == null) return;
		for (File f : files) {
			if (!f.isFile()) continue;
			String n = f.getName();
			int dot = n.lastIndexOf('.');
			if (dot < 0) continue;
			String ext = n.substring(dot + 1).toLowerCase();
			if (EXTENSIONS.contains(ext)) out.add(new RecordingItem(f, isPublic));
		}
	}

	public static boolean delete(RecordingItem item) {
		File meta = MetaFile.of(item.file);
		if (meta.isFile()) //noinspection ResultOfMethodCallIgnored
			meta.delete();
		return item.file.delete();
	}

	public static boolean rename(RecordingItem item, String newBaseName) {
		String clean = newBaseName.trim().replaceAll("[\\\\/:*?\"<>|]", "");
		if (clean.isEmpty()) return false;
		File target = new File(item.file.getParentFile(), clean + "." + item.ext);
		if (target.exists()) return false;
		File meta = MetaFile.of(item.file);
		boolean ok = item.file.renameTo(target);
		if (ok && meta.isFile()) //noinspection ResultOfMethodCallIgnored
			meta.renameTo(MetaFile.of(target));
		return ok;
	}

	@NonNull
	public static Uri shareUri(Context ctx, File f) {
		return RecordingProvider.uriFor(ctx, f);
	}

	@NonNull
	public static String mimeFor(String ext) {
		switch (ext) {
			case "wav": return "audio/wav";
			case "pcm": return "application/octet-stream";
			case "flac": return "audio/flac";
			case "m4a": return "audio/mp4";
			case "mp4": return "audio/mp4";
			case "3gp": return "audio/3gpp";
			case "ogg": case "opus": return "audio/ogg";
			case "aac": return "audio/aac";
			case "ts": return "video/mp2t";
			case "amr": return "audio/amr";
			case "awb": return "audio/amr-wb";
			case "mp3": return "audio/mpeg";
			case "webm": return "audio/webm";
			default: return "application/octet-stream";
		}
	}
}
