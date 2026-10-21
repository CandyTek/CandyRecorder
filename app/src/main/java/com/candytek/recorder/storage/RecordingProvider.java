package com.candytek.recorder.storage;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;

/**
 * Minimal read-only content provider so recordings can be shared with other apps through
 * content:// URIs (no androidx.core FileProvider dependency).
 * URI layout: content://&lt;pkg&gt;.files/app/&lt;name&gt; or /public/&lt;name&gt;
 */
public final class RecordingProvider extends ContentProvider {

	private static final String SEG_APP = "app";
	private static final String SEG_PUBLIC = "public";

	public static String authority(Context ctx) { return ctx.getPackageName() + ".files"; }

	@NonNull
	public static Uri uriFor(Context ctx, File f) {
		boolean isPublic;
		try {
			isPublic = f.getCanonicalPath().startsWith(RecordingStore.publicDir().getCanonicalPath() + File.separator);
		} catch (IOException e) {
			isPublic = false;
		}
		return new Uri.Builder().scheme("content").authority(authority(ctx))
			.appendPath(isPublic ? SEG_PUBLIC : SEG_APP).appendPath(f.getName()).build();
	}

	@Nullable
	private File resolve(Uri uri) {
		Context ctx = getContext();
		if (ctx == null) return null;
		List<String> seg = uri.getPathSegments();
		if (seg.size() != 2) return null;
		File dir = SEG_PUBLIC.equals(seg.get(0)) ? RecordingStore.publicDir() : RecordingStore.appDir(ctx);
		File f = new File(dir, seg.get(1));
		try {
			if (!f.getCanonicalPath().startsWith(dir.getCanonicalPath() + File.separator)) return null;
		} catch (IOException e) {
			return null;
		}
		return f.isFile() ? f : null;
	}

	@Override
	public boolean onCreate() { return true; }

	@Nullable
	@Override
	public Cursor query(@NonNull Uri uri, @Nullable String[] projection, @Nullable String selection,
	                    @Nullable String[] selectionArgs, @Nullable String sortOrder) {
		File f = resolve(uri);
		if (f == null) return null;
		if (projection == null) projection = new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
		MatrixCursor c = new MatrixCursor(projection, 1);
		Object[] row = new Object[projection.length];
		for (int i = 0; i < projection.length; i++) {
			if (OpenableColumns.DISPLAY_NAME.equals(projection[i])) row[i] = f.getName();
			else if (OpenableColumns.SIZE.equals(projection[i])) row[i] = f.length();
			else if ("_data".equals(projection[i])) row[i] = f.getAbsolutePath();
			else if ("mime_type".equals(projection[i])) row[i] = getType(uri);
			else row[i] = null;
		}
		c.addRow(row);
		return c;
	}

	@Nullable
	@Override
	public String getType(@NonNull Uri uri) {
		List<String> seg = uri.getPathSegments();
		if (seg.isEmpty()) return null;
		String name = seg.get(seg.size() - 1);
		int dot = name.lastIndexOf('.');
		return RecordingStore.mimeFor(dot >= 0 ? name.substring(dot + 1).toLowerCase() : "");
	}

	@Nullable
	@Override
	public ParcelFileDescriptor openFile(@NonNull Uri uri, @NonNull String mode) throws FileNotFoundException {
		File f = resolve(uri);
		if (f == null) throw new FileNotFoundException(uri.toString());
		if (!"r".equals(mode)) throw new FileNotFoundException("read-only");
		return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
	}

	@Nullable
	@Override
	public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) { return null; }

	@Override
	public int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) { return 0; }

	@Override
	public int update(@NonNull Uri uri, @Nullable ContentValues values, @Nullable String selection, @Nullable String[] selectionArgs) { return 0; }
}
