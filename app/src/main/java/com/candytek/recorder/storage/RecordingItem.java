package com.candytek.recorder.storage;

import androidx.annotation.NonNull;

import java.io.File;

/** One entry in the history list. */
public final class RecordingItem {

	public final File file;
	public final String name;
	public final String ext;
	public final long size;
	public final long modified;
	public final boolean inPublicFolder;
	/** Milliseconds, -1 until probed. */
	public volatile long durationMs = -1;

	public RecordingItem(@NonNull File file, boolean inPublicFolder) {
		this.file = file;
		this.name = file.getName();
		this.size = file.length();
		this.modified = file.lastModified();
		this.inPublicFolder = inPublicFolder;
		int dot = name.lastIndexOf('.');
		this.ext = dot >= 0 ? name.substring(dot + 1).toLowerCase() : "";
	}
}
