package us.beiyue.beilinentryportability.common;

import java.io.IOException;
import java.util.List;

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
}
