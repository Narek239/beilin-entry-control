package us.beiyue.beilindataportability.common;

public final class BulkPlacementShape {
	public final int sizeX;
	public final int sizeY;
	public final int sizeZ;
	public final int blockCount;

	public BulkPlacementShape(int sizeX, int sizeY, int sizeZ, int blockCount) {
		this.sizeX = Math.max(1, sizeX);
		this.sizeY = Math.max(1, sizeY);
		this.sizeZ = Math.max(1, sizeZ);
		this.blockCount = Math.max(0, blockCount);
	}

	public static BulkPlacementShape fromBounds(
		int minX,
		int minY,
		int minZ,
		int maxX,
		int maxY,
		int maxZ
	) {
		int sizeX = Math.abs(maxX - minX) + 1;
		int sizeY = Math.abs(maxY - minY) + 1;
		int sizeZ = Math.abs(maxZ - minZ) + 1;
		return new BulkPlacementShape(sizeX, sizeY, sizeZ, safeVolume(sizeX, sizeY, sizeZ));
	}

	public static BulkPlacementShape fromBounds(
		int minX,
		int minY,
		int minZ,
		int maxX,
		int maxY,
		int maxZ,
		int blockCount
	) {
		int sizeX = Math.abs(maxX - minX) + 1;
		int sizeY = Math.abs(maxY - minY) + 1;
		int sizeZ = Math.abs(maxZ - minZ) + 1;
		return new BulkPlacementShape(sizeX, sizeY, sizeZ, blockCount);
	}

	public boolean isLinearInfrastructure() {
		return PortabilityGeometryClassifier.isLinearInfrastructureBySize(sizeX, sizeY, sizeZ, blockCount);
	}

	private static int safeVolume(int x, int y, int z) {
		long volume = (long) Math.max(1, x) * Math.max(1, y) * Math.max(1, z);
		return volume > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) volume;
	}
}
