package com.candytek.recorder.engine;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.projection.MediaProjection;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.candytek.recorder.core.RecordingConfig;

import java.io.File;

/** Common contract for Engine A (MediaRecorder) and Engine B (AudioRecord). */
public interface RecorderEngine {

	interface Listener {
		/** Called from the audio / polling thread, 20-60 times per second. */
		void onMeter(@NonNull MeterFrame frame);
		/** Fatal problem while recording. The engine has already stopped. */
		void onError(@NonNull String message);
	}

	final class StartParams {
		public final Context context;
		public final RecordingConfig config;
		public final File outFile;
		@Nullable public final AudioDeviceInfo preferredDevice;
		@Nullable public final MediaProjection projection;
		public final Listener listener;

		public StartParams(Context context, RecordingConfig config, File outFile,
		                   @Nullable AudioDeviceInfo preferredDevice, @Nullable MediaProjection projection,
		                   Listener listener) {
			this.context = context.getApplicationContext();
			this.config = config;
			this.outFile = outFile;
			this.preferredDevice = preferredDevice;
			this.projection = projection;
			this.listener = listener;
		}
	}

	/** Prepares and starts capturing. Throws on any failure; nothing is left running in that case. */
	void start(@NonNull StartParams params) throws Exception;

	void pause();

	void resume();

	boolean isPaused();

	/** Stops capturing and finalizes the file. Blocks until the file is complete. */
	void stop() throws Exception;

	@NonNull
	EngineStats stats();
}
