package com.candytek.recorder.engine;

/**
 * Hysteresis-free silence detector with a hold time: the gate closes once the level has stayed
 * below the threshold for {@code holdMs}, and reopens instantly on the first loud chunk.
 */
public final class SilenceGate {

	private final float thresholdDb;
	private final long holdNs;
	private long quietSinceNs = -1;
	private boolean closed;
	private int events;

	public SilenceGate(int thresholdDb, int holdMs) {
		this.thresholdDb = thresholdDb;
		this.holdNs = holdMs * 1_000_000L;
	}

	/** @return true when the chunk should be dropped. */
	public boolean update(float levelDb, long nowNs) {
		if (levelDb < thresholdDb) {
			if (quietSinceNs < 0) quietSinceNs = nowNs;
			if (!closed && nowNs - quietSinceNs >= holdNs) {
				closed = true;
				events++;
			}
		} else {
			quietSinceNs = -1;
			closed = false;
		}
		return closed;
	}

	public boolean isClosed() { return closed; }

	public int events() { return events; }
}
