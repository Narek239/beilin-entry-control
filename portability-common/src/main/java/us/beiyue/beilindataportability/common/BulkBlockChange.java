package us.beiyue.beilindataportability.common;

public final class BulkBlockChange {
	public final String dimension;
	public final int x;
	public final int y;
	public final int z;
	public final String oldBlockState;
	public final String newBlockState;
	public final boolean oldBlockReplaceable;
	public final boolean forcePlacement;

	public BulkBlockChange(
		String dimension,
		int x,
		int y,
		int z,
		String oldBlockState,
		String newBlockState,
		boolean oldBlockReplaceable,
		boolean forcePlacement
	) {
		this.dimension = DimensionNames.normalize(dimension);
		this.x = x;
		this.y = y;
		this.z = z;
		this.oldBlockState = oldBlockState;
		this.newBlockState = newBlockState;
		this.oldBlockReplaceable = oldBlockReplaceable;
		this.forcePlacement = forcePlacement;
	}
}
