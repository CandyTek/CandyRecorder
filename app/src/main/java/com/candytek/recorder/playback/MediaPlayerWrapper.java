package com.candytek.recorder.playback;

import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.os.Build;

import java.io.File;
import java.io.IOException;

/** Container formats the platform can decode (WAV, M4A, 3GP, Ogg, FLAC, AMR, …). */
public final class MediaPlayerWrapper implements Player {

	private MediaPlayer mp;
	private float speed = 1f;
	private Runnable onCompletion;
	private boolean completed;

	@Override
	public void prepare(File file) throws IOException {
		mp = new MediaPlayer();
		mp.setAudioAttributes(new AudioAttributes.Builder()
			.setUsage(AudioAttributes.USAGE_MEDIA)
			.setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
			.build());
		mp.setDataSource(file.getAbsolutePath());
		mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
			@Override public void onCompletion(MediaPlayer p) {
				completed = true;
				if (onCompletion != null) onCompletion.run();
			}
		});
		mp.prepare();
	}

	@Override
	public void play() {
		if (mp == null) return;
		if (completed) { mp.seekTo(0); completed = false; }
		mp.start();
		applySpeed();
	}

	private void applySpeed() {
		if (Build.VERSION.SDK_INT >= 23 && mp != null && mp.isPlaying()) {
			try {
				mp.setPlaybackParams(new PlaybackParams().setSpeed(speed).setPitch(1f)
					.setAudioFallbackMode(PlaybackParams.AUDIO_FALLBACK_MODE_DEFAULT));
			} catch (RuntimeException ignored) {
			}
		}
	}

	@Override
	public void pause() { if (mp != null && mp.isPlaying()) mp.pause(); }

	@Override
	public boolean isPlaying() { return mp != null && mp.isPlaying(); }

	@Override
	public long durationMs() { return mp == null ? 0 : mp.getDuration(); }

	@Override
	public long positionMs() { return mp == null ? 0 : mp.getCurrentPosition(); }

	@Override
	public void seekTo(long ms) {
		if (mp == null) return;
		completed = false;
		if (Build.VERSION.SDK_INT >= 26) mp.seekTo(ms, MediaPlayer.SEEK_CLOSEST);
		else mp.seekTo((int) ms);
	}

	@Override
	public void setSpeed(float s) { speed = s; applySpeed(); }

	@Override
	public boolean supportsSpeed() { return Build.VERSION.SDK_INT >= 23; }

	@Override
	public void setOnCompletion(Runnable r) { onCompletion = r; }

	@Override
	public void release() {
		if (mp != null) { try { mp.release(); } catch (RuntimeException ignored) {} mp = null; }
	}

	@Override
	public String describe() { return "MediaPlayer"; }
}
