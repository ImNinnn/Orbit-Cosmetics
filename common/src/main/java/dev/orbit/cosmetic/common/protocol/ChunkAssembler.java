package dev.orbit.cosmetic.common.protocol;

import java.util.BitSet;

/** Reassembles a chunked asset transfer, rejecting inconsistent or duplicate chunks. */
public final class ChunkAssembler {
	private final AssetId asset;
	private final int totalSize;
	private final int count;
	private final int chunkSize;
	private final byte[] data;
	private final BitSet received;
	private int receivedCount;
	private long lastActivity;

	public ChunkAssembler(AssetId asset, int totalSize, int count, int chunkSize, long now) {
		this.asset = asset;
		this.totalSize = totalSize;
		this.count = count;
		this.chunkSize = chunkSize;
		this.data = new byte[totalSize];
		this.received = new BitSet(count);
		this.lastActivity = now;
	}

	public AssetId asset() {
		return asset;
	}

	public int totalSize() {
		return totalSize;
	}

	/**
	 * Stores a chunk. Callers must already have validated the chunk length against the numbering
	 * (the protocol decoder does).
	 *
	 * @return false if the chunk does not belong to this transfer or was already received
	 */
	public boolean accept(int index, int chunkCount, int size, byte[] chunk, long now) {
		if (size != totalSize || chunkCount != count || index < 0 || index >= count || received.get(index)) {
			return false;
		}
		int offset = index * chunkSize;
		if (offset + chunk.length > totalSize) {
			return false;
		}
		System.arraycopy(chunk, 0, data, offset, chunk.length);
		received.set(index);
		receivedCount++;
		lastActivity = now;
		return true;
	}

	public boolean isComplete() {
		return receivedCount == count;
	}

	public byte[] bytes() {
		return data;
	}

	public long lastActivity() {
		return lastActivity;
	}
}
