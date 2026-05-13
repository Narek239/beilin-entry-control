package us.beiyue.beilinentryportability.common;

import java.util.Objects;

public final class BlockCoordinate {
	public final String dimension;
	public final int x;
	public final int y;
	public final int z;

	public BlockCoordinate(String dimension, int x, int y, int z) {
		this.dimension = dimension != null && !dimension.isBlank() ? dimension : "minecraft:overworld";
		this.x = x;
		this.y = y;
		this.z = z;
	}

	public String key() {
		return dimension + "|" + x + "|" + y + "|" + z;
	}

	public BlockCoordinate offset(int dx, int dy, int dz) {
		return new BlockCoordinate(dimension, x + dx, y + dy, z + dz);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof BlockCoordinate that)) return false;
		return x == that.x && y == that.y && z == that.z && dimension.equals(that.dimension);
	}

	@Override
	public int hashCode() {
		return Objects.hash(dimension, x, y, z);
	}
}
