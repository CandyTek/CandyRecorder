package com.candytek.recorder.playback;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.PlaybackParams;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import com.candytek.recorder.dsp.PcmUtil;
import com.candytek.recorder.storage.RecordingStore;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Streams headerless PCM (layout parsed from the file name) through AudioTrack. */
public final class PcmTrackPlayer implements Player {

	private RandomAccessFile raf;
	private AudioTrack track;
	private Thread thread;
	private int sampleRate, channels, encoding, frameBytes, byteRate;
	private long totalBytes;
	private volatile boolean playing, released;
	private volatile long pendingSeek = -1;
	private volatile long filePos;
	private float speed = 1f;
	private Runnable onCompletion;
	private final Handler main = new Handler(Looper.getMainLooper());
	private final Object lock = new Object();

	@Override
	public void prepare(File file) throws IOException {
		int[] p = RecordingStore.pcmParams(file);
		if (p == null) { sampleRate = 44100; channels = 1; encoding = AudioFormat.ENCODING_PCM_16BIT; }
		else { sampleRate = p[0]; channels = p[1]; encoding = p[2]; }
		frameBytes = PcmUtil.bytesPerSample(encoding) * channels;
		byteRate = sampleRate * frameBytes;
		raf = new RandomAccessFile(file, "r");
		totalBytes = raf.length() - raf.length() % frameBytes;
		int mask = channels == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
		int min = AudioTrack.getMinBufferSize(sampleRate, mask, encoding);
		if (min <= 0) throw new IOException("AudioTrack rejects " + PcmUtil.encodingName(encoding) + " @ " + sampleRate);
		int buf = Math.max(min * 2, byteRate / 5);
		try {
			if (Build.VERSION.SDK_INT >= 23) {
				track = new AudioTrack.Builder()
					.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
						.setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
					.setAudioFormat(new AudioFormat.Builder().setEncoding(encoding).setSampleRate(sampleRate).setChannelMask(mask).build())
					.setBufferSizeInBytes(buf)
					.setTransferMode(AudioTrack.MODE_STREAM)
					.build();
			} else {
				track = new AudioTrack(AudioManager.STREAM_MUSIC, sampleRate, mask, encoding, buf, AudioTrack.MODE_STREAM);
			}
		} catch (RuntimeException e) {
			throw new IOException("AudioTrack init failed: " + e.getMessage(), e);
		}
		if (track.getState() != AudioTrack.STATE_INITIALIZED) throw new IOException("AudioTrack not initialized");
		thread = new Thread(new Runnable() { @Override public void run() { loop(); } }, "candy-pcm-play");
		thread.start();
	}

	private void loop() {
		ByteBuffer buf = ByteBuffer.allocateDirect(Math.max(frameBytes, byteRate / 20)).order(ByteOrder.nativeOrder());
		byte[] arr = new byte[buf.capacity()];
		while (!released) {
			if (!playing) {
				synchronized (lock) { try { lock.wait(50); } catch (InterruptedException ignored) {} }
				continue;
			}
			try {
				long seek = pendingSeek;
				if (seek >= 0) {
					pendingSeek = -1;
					track.pause();
					track.flush();
					filePos = Math.min(totalBytes, seek - seek % frameBytes);
					raf.seek(filePos);
					track.play();
				}
				if (filePos >= totalBytes) {
					playing = false;
					try { track.stop(); } catch (RuntimeException ignored) {}
					filePos = 0;
					raf.seek(0);
					main.post(new Runnable() { @Override public void run() { if (onCompletion != null) onCompletion.run(); } });
					continue;
				}
				int n = raf.read(arr, 0, (int) Math.min(arr.length, totalBytes - filePos));
				if (n <= 0) { filePos = totalBytes; continue; }
				n -= n % frameBytes;
				buf.clear();
				buf.put(arr, 0, n);
				buf.flip();
				int off = 0;
				while (off < n && playing && !released) {
					int w = track.write(buf, n - off, AudioTrack.WRITE_BLOCKING);
					if (w < 0) { playing = false; break; }
					off += w;
				}
				filePos += off;
			} catch (IOException | RuntimeException e) {
				playing = false;
			}
		}
	}

	@Override
	public void play() {
		if (track == null) return;
		if (filePos >= totalBytes) { filePos = 0; pendingSeek = 0; }
		try { track.play(); } catch (RuntimeException ignored) {}
		applySpeed();
		playing = true;
		synchronized (lock) { lock.notifyAll(); }
	}

	@Override
	public void pause() {
		playing = false;
		if (track != null) try { track.pause(); } catch (RuntimeException ignored) {}
	}

	@Override
	public boolean isPlaying() { return playing; }

	@Override
	public long durationMs() { return byteRate == 0 ? 0 : totalBytes * 1000L / byteRate; }

	@Override
	public long positionMs() { return byteRate == 0 ? 0 : filePos * 1000L / byteRate; }

	@Override
	public void seekTo(long ms) {
		long bytes = ms * byteRate / 1000L;
		if (playing) pendingSeek = bytes;
		else {
			filePos = Math.min(totalBytes, bytes - bytes % frameBytes);
			try { raf.seek(filePos); if (track != null) track.flush(); } catch (IOException | RuntimeException ignored) {}
		}
	}

	private void applySpeed() {
		if (track == null) return;
		try {
			if (Build.VERSION.SDK_INT >= 23) {
				track.setPlaybackParams(new PlaybackParams().setSpeed(speed).setPitch(1f)
					.setAudioFallbackMode(PlaybackParams.AUDIO_FALLBACK_MODE_DEFAULT));
			} else {
				track.setPlaybackRate(Math.max(4000, Math.round(sampleRate * speed)));
			}
		} catch (RuntimeException ignored) {
		}
	}

	@Override
	public void setSpeed(float s) { speed = s; applySpeed(); }

	@Override
	public boolean supportsSpeed() { return true; }

	@Override
	public void setOnCompletion(Runnable r) { onCompletion = r; }

	@Override
	public void release() {
		released = true;
		playing = false;
		synchronized (lock) { lock.notifyAll(); }
		if (thread != null) { try { thread.join(1000); } catch (InterruptedException ignored) {} }
		if (track != null) { try { track.stop(); } catch (RuntimeException ignored) {} track.release(); track = null; }
		if (raf != null) { try { raf.close(); } catch (IOException ignored) {} raf = null; }
	}

	@Override
	public String describe() {
		return "AudioTrack " + PcmUtil.encodingName(encoding) + " " + sampleRate + " Hz " + channels + "ch";
	}
}
