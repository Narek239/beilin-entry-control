package us.beiyue.beilinentryportability.common;

/**
 * Ephemeral world block sampled during export. This is not persisted in the
 * portability database; persisted authorship is region-level only.
 */
public final class BlockRecord {
	public final String dimension;
	public final int x;
	public final int y;
	public final int z;
	public final String blockState;

	public BlockRecord(String dimension, int x, int y, int z, String blockState) {
		this.dimension = dimension;
		this.x = x;
		this.y = y;
		this.z = z;
		this.blockState = blockState;
	}

	public BlockCoordinate coordinate() {
		return new BlockCoordinate(dimension, x, y, z);
	}
}
