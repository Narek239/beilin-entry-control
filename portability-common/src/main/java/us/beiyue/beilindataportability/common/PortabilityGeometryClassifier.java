package us.beiyue.beilindataportability.common;

public final class PortabilityGeometryClassifier {
	public static final int LINEAR_MIN_LONG_AXIS = 32;
	public static final int LINEAR_MAX_CROSS_AXIS = 3;
	public static final int LINEAR_WIDE_MAX_CROSS = 12;
	public static final int LINEAR_WIDE_MIN_ASPECT = 16;

	private PortabilityGeometryClassifier() {
	}

	public static boolean isLinearInfrastructure(
		int minX,
		int minY,
		int minZ,
		int maxX,
		int maxY,
		int maxZ,
		int nonAir
	) {
		int x = size(minX, maxX);
		int y = size(minY, maxY);
		int z = size(minZ, maxZ);
		return isLinearInfrastructureBySize(x, y, z, nonAir);
	}

	public static boolean isLinearInfrastructureBySize(int sizeX, int sizeY, int sizeZ, int nonAir) {
		int x = Math.max(1, sizeX);
		int y = Math.max(1, sizeY);
		int z = Math.max(1, sizeZ);
		int longest = Math.max(x, Math.max(y, z));
		int shortest = Math.min(x, Math.min(y, z));
		int middle = x + y + z - longest - shortest;
		int crossMax = Math.max(middle, shortest);
		boolean horizontal = x == longest || z == longest;
		if (longest < LINEAR_MIN_LONG_AXIS) return false;
		boolean narrow = crossMax <= LINEAR_MAX_CROSS_AXIS;
		boolean wideLinear = crossMax <= LINEAR_WIDE_MAX_CROSS
			&& longest >= LINEAR_WIDE_MIN_ASPECT * shortest;
		if (!narrow && !wideLinear) return false;
		if (horizontal) return true;
		int volume = safeVolume(x, y, z);
		return volume <= 0 || nonAir <= 0 || nonAir * 10000L / volume < 4500;
	}

	private static int size(int a, int b) {
		return Math.max(a, b) - Math.min(a, b) + 1;
	}

	private static int safeVolume(int x, int y, int z) {
		long v = (long) x * (long) y * (long) z;
		return v > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(1L, v);
	}
}
