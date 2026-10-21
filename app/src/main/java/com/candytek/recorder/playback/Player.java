package com.candytek.recorder.playback;

import java.io.File;
import java.io.IOException;

/** Minimal playback abstraction shared by the MediaPlayer and the raw-PCM AudioTrack player. */
public interface Player {

	void prepare(File file) throws IOException;

	void play();

	void pause();

	boolean isPlaying();

	long durationMs();

	long positionMs();

	void seekTo(long ms);

	/** Playback speed multiplier; pitch is preserved when the platform supports it. */
	void setSpeed(float speed);

	boolean supportsSpeed();

	void setOnCompletion(Runnable r);

	void release();

	String describe();
}
