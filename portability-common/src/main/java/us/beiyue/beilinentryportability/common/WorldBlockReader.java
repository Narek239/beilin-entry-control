package us.beiyue.beilinentryportability.common;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public interface WorldBlockReader {
	List<BlockRecord> scan(
		String dimension,
		int minX,
		int minY,
		int minZ,
		int maxX,
		int maxY,
		int maxZ,
		int maxVolumeBlocks
	) throws IOException;

	default List<BlockRecord> readCoordinates(
		String dimension,
		List<BlockCoordinate> coordinates,
		int maxVolumeBlocks
	) throws IOException {
		if (coordinates == null || coordinates.isEmpty()) return List.of();
		Bounds bounds = Bounds.from(coordinates);
		List<BlockRecord> scanned = scan(
			dimension,
			bounds.minX, bounds.minY, bounds.minZ,
			bounds.maxX, bounds.maxY, bounds.maxZ,
			maxVolumeBlocks
		);
		Set<BlockCoordinate> wanted = new HashSet<>(coordinates);
		return scanned.stream()
			.filter(block -> wanted.contains(block.coordinate()))
			.toList();
	}

	final class Bounds {
		final int minX;
		final int minY;
		final int minZ;
		final int maxX;
		final int maxY;
		final int maxZ;

		private Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
			this.minX = minX;
			this.minY = minY;
			this.minZ = minZ;
			this.maxX = maxX;
			this.maxY = maxY;
			this.maxZ = maxZ;
		}

		static Bounds from(List<BlockCoordinate> coordinates) {
			BlockCoordinate first = coordinates.get(0);
			int minX = first.x, minY = first.y, minZ = first.z;
			int maxX = first.x, maxY = first.y, maxZ = first.z;
			for (BlockCoordinate c : coordinates) {
				minX = Math.min(minX, c.x);
				minY = Math.min(minY, c.y);
				minZ = Math.min(minZ, c.z);
				maxX = Math.max(maxX, c.x);
				maxY = Math.max(maxY, c.y);
				maxZ = Math.max(maxZ, c.z);
			}
			return new Bounds(minX, minY, minZ, maxX, maxY, maxZ);
		}
	}
}
