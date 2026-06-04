package us.beiyue.beilindataportability.common;

public final class BulkPlacementBounds {
	public final String dimension;
	public final int minX;
	public final int minY;
	public final int minZ;
	public final int maxX;
	public final int maxY;
	public final int maxZ;
	public final int blockCount;

	public BulkPlacementBounds(
		String dimension,
		int minX,
		int minY,
		int minZ,
		int maxX,
		int maxY,
		int maxZ,
		int blockCount
	) {
		this.dimension = DimensionNames.normalize(dimension);
		this.minX = Math.min(minX, maxX);
		this.minY = Math.min(minY, maxY);
		this.minZ = Math.min(minZ, maxZ);
		this.maxX = Math.max(minX, maxX);
		this.maxY = Math.max(minY, maxY);
		this.maxZ = Math.max(minZ, maxZ);
		this.blockCount = Math.max(0, blockCount);
	}

	public BulkPlacementShape shape() {
		return BulkPlacementShape.fromBounds(minX, minY, minZ, maxX, maxY, maxZ, blockCount);
	}

	public boolean isLinearInfrastructure() {
		return shape().isLinearInfrastructure();
	}
}
