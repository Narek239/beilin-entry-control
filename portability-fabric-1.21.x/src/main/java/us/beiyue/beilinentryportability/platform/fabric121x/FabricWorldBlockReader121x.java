package us.beiyue.beilinentryportability.platform.fabric121x;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import us.beiyue.beilinentryportability.common.BlockCoordinate;
import us.beiyue.beilinentryportability.common.BlockRecord;
import us.beiyue.beilinentryportability.common.WorldBlockReader;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

public final class FabricWorldBlockReader121x implements WorldBlockReader {
	private final MinecraftServer server;
	private final int scanChunksPerBatch;

	public FabricWorldBlockReader121x(MinecraftServer server, int scanChunksPerBatch) {
		this.server = server;
		this.scanChunksPerBatch = Math.max(1, scanChunksPerBatch);
	}

	@Override
	public List<BlockRecord> readCoordinates(
		String dimension,
		List<BlockCoordinate> coordinates,
		int maxVolumeBlocks
	) throws IOException {
		if (coordinates == null || coordinates.isEmpty()) return List.of();
		if (server.isSameThread()) {
			return readCoordinatesOnServerThread(dimension, coordinates);
		}
		CompletableFuture<List<BlockRecord>> future = new CompletableFuture<>();
		server.execute(() -> {
			if (future.isCancelled()) return;
			try {
				future.complete(readCoordinatesOnServerThread(dimension, coordinates));
			} catch (Throwable t) {
				future.completeExceptionally(t);
			}
		});
		try {
			return future.get();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			future.cancel(true);
			throw new IOException("Interrupted while reading export coordinates", e);
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			if (cause instanceof IOException io) throw io;
			throw new IOException("Failed to read export coordinates", cause);
		}
	}

	@Override
	public List<BlockRecord> scan(
		String dimension,
		int minX,
		int minY,
		int minZ,
		int maxX,
		int maxY,
		int maxZ,
		int maxVolumeBlocks
	) throws IOException {
		long volume = (long) (maxX - minX + 1) * (long) (maxY - minY + 1) * (long) (maxZ - minZ + 1);
		if (volume > maxVolumeBlocks) {
			throw new IOException("export cuboid is too large: " + volume + " > " + maxVolumeBlocks);
		}
		if (server.isSameThread()) {
			return scanOnServerThread(dimension, minX, minY, minZ, maxX, maxY, maxZ);
		}
		CompletableFuture<List<BlockRecord>> future = new CompletableFuture<>();
		server.execute(() -> {
			if (future.isCancelled()) return;
			try {
				future.complete(scanOnServerThread(dimension, minX, minY, minZ, maxX, maxY, maxZ));
			} catch (Throwable t) {
				future.completeExceptionally(t);
			}
		});
		try {
			return future.get();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			future.cancel(true);
			throw new IOException("Interrupted while scanning export cuboid", e);
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			if (cause instanceof IOException io) throw io;
			throw new IOException("Failed to scan export cuboid", cause);
		}
	}

	private List<BlockRecord> scanOnServerThread(String dimension, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
		ServerLevel level = level(dimension);
		if (level == null) return List.of();
		int clampedMinY = Math.max(minY, level.getMinBuildHeight());
		int clampedMaxY = Math.min(maxY, level.getMaxBuildHeight() - 1);
		if (clampedMinY > clampedMaxY) return List.of();

		List<BlockRecord> out = new ArrayList<>();
		int chunkCount = 0;
		for (int chunkX = Math.floorDiv(minX, 16); chunkX <= Math.floorDiv(maxX, 16); chunkX++) {
			for (int chunkZ = Math.floorDiv(minZ, 16); chunkZ <= Math.floorDiv(maxZ, 16); chunkZ++) {
				LevelChunk chunk = level.getChunk(chunkX, chunkZ);
				int startX = Math.max(minX, chunkX << 4);
				int endX = Math.min(maxX, (chunkX << 4) + 15);
				int startZ = Math.max(minZ, chunkZ << 4);
				int endZ = Math.min(maxZ, (chunkZ << 4) + 15);
				for (int x = startX; x <= endX; x++) {
					for (int z = startZ; z <= endZ; z++) {
						for (int y = clampedMinY; y <= clampedMaxY; y++) {
							BlockPos pos = new BlockPos(x, y, z);
							BlockState state = chunk.getBlockState(pos);
							if (!state.isAir()) {
								out.add(new BlockRecord(dimension, x, y, z, state.toString()));
							}
						}
					}
				}
				chunkCount += 1;
				if (chunkCount % scanChunksPerBatch == 0) Thread.yield();
			}
		}
		return out;
	}

	private List<BlockRecord> readCoordinatesOnServerThread(String dimension, List<BlockCoordinate> coordinates) {
		ServerLevel level = level(dimension);
		if (level == null) return List.of();
		int minBuildY = level.getMinBuildHeight();
		int maxBuildY = level.getMaxBuildHeight() - 1;
		Map<Long, List<BlockCoordinate>> byChunk = new HashMap<>();
		Set<BlockCoordinate> seen = new HashSet<>();
		for (BlockCoordinate coordinate : coordinates) {
			if (coordinate == null || !seen.add(coordinate)) continue;
			if (!dimension.equals(coordinate.dimension)) continue;
			if (coordinate.y < minBuildY || coordinate.y > maxBuildY) continue;
			int chunkX = Math.floorDiv(coordinate.x, 16);
			int chunkZ = Math.floorDiv(coordinate.z, 16);
			byChunk.computeIfAbsent(chunkKey(chunkX, chunkZ), ignored -> new ArrayList<>()).add(coordinate);
		}
		List<BlockRecord> out = new ArrayList<>();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (Map.Entry<Long, List<BlockCoordinate>> entry : byChunk.entrySet()) {
			int chunkX = chunkX(entry.getKey());
			int chunkZ = chunkZ(entry.getKey());
			LevelChunk chunk = level.getChunk(chunkX, chunkZ);
			for (BlockCoordinate coordinate : entry.getValue()) {
				pos.set(coordinate.x, coordinate.y, coordinate.z);
				BlockState state = chunk.getBlockState(pos);
				if (!state.isAir()) out.add(new BlockRecord(dimension, coordinate.x, coordinate.y, coordinate.z, state.toString()));
			}
		}
		out.sort(Comparator
			.comparingInt((BlockRecord b) -> b.y)
			.thenComparingInt(b -> b.z)
			.thenComparingInt(b -> b.x));
		return out;
	}

	private ServerLevel level(String dimension) {
		for (ServerLevel level : server.getAllLevels()) {
			if (level.dimension().location().toString().equals(dimension)) return level;
		}
		return null;
	}

	private static long chunkKey(int chunkX, int chunkZ) {
		return ((long) chunkX << 32) ^ (chunkZ & 0xffffffffL);
	}

	private static int chunkX(long key) {
		return (int) (key >> 32);
	}

	private static int chunkZ(long key) {
		return (int) key;
	}
}
