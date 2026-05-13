package us.beiyue.beilinentryportability.common;

import us.beiyue.beilinentrycontrol.common.log.CommonLogger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class BuildingIndexStoreStorageTest {
	public static void main(String[] args) throws Exception {
		Class.forName("org.sqlite.JDBC");
		Path dir = Files.createTempDirectory("beilin-entry-portability-storage");
		try {
			Path db = dir.resolve("world.db");
			createOldSchemaMarker(db);

			BuildingIndexStore store = BuildingIndexStore.open(db, new NoopLogger());
			store.recordPlaced("minecraft:overworld", 0, 64, 0, "minecraft:stone", "Alice");
			store.recordPlaced("minecraft:overworld", 1, 64, 0, "minecraft:oak_planks", "Bob");
			store.recordStateChangeWithSource("minecraft:overworld", 2, 64, 0, "minecraft:air", "minecraft:glass", "ALICE", "PLAYER_USE_ITEM_ON");
			store.recordPlaced("minecraft:overworld", 3, 64, 0, "minecraft:stone", "Alice");
			store.recordPlaced("minecraft:overworld", 4, 64, 0, "minecraft:stone", "Alice");
			store.recordPlaced("minecraft:overworld", 5, 64, 0, "minecraft:stone", "Alice");
			store.recordPlaced("minecraft:overworld", 6, 64, 0, "minecraft:stone", "Alice");
			store.recordPlaced("minecraft:overworld", 7, 64, 0, "minecraft:stone", "Alice");
			store.recordStateChangeWithSource("minecraft:overworld", 3, 64, 0, "minecraft:air", "minecraft:lantern", "SYSTEM", "SYSTEM_SET_BLOCK");
			store.recordStateChangeWithSource("minecraft:overworld", 8, 64, 0, "minecraft:air", "minecraft:wall_torch", "UNKNOWN", "SYSTEM_SET_BLOCK");
			store.flushPendingWrites();
			insertLongRailRegion(db);

			ExportBundle bundle = store.buildExportBundle(
				new ExportJob(42L, "alice", null, null),
				new FakeWorldReader(),
				1_000_000
			);
			assertEquals(1, bundle.components.size(), "long rail should be excluded and compact building should export");
			ComponentSummary summary = bundle.components.get(0).summary;
			assertEquals(8, summary.nonAirBlocks, "SYSTEM/UNKNOWN changes outside the region should not expand into terrain");
			assertEquals(8, summary.blockCount, "compat block count should map to non-air blocks");
			assertEquals(2, summary.authorCount, "SYSTEM changes should not count as authors");
			assertEquals(8750, summary.targetAuthorRatioBp, "username ratio should be normalized and author-only");
			assertEquals("mixed_authorship", summary.riskFlags, "multi-author region should be flagged");
			assertEquals("beilin-entry-portability-manifest-v2", bundle.manifest.toManifestJson().get("format").getAsString(), "manifest format");
			store.close();

			try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
				 Statement s = c.createStatement()) {
				assertEquals("wal", scalarString(s, "PRAGMA journal_mode"), "SQLite journal mode should be WAL");
				assertEquals(6, scalarInt(s, "PRAGMA user_version"), "schema version should be v6");
				assertFalse(tableExists(s, "current_blocks"), "old per-block table should be dropped");
				assertFalse(tableExists(s, "block_events"), "change log table should not exist");
				assertTrue(tableExists(s, "building_regions"), "v5 region table should exist");
				assertTrue(tableExists(s, "region_authors"), "v5 author table should exist");
				assertTrue(tableExists(s, "region_blocks"), "v6 placed block table should exist");
				assertTrue(tableExists(s, "region_chunk_index"), "v5 chunk index table should exist");
				assertEquals(2, scalarInt(s, "SELECT COUNT(DISTINCT player_name_key) FROM region_authors WHERE player_name_key IN ('alice','bob')"), "authors should be keyed by normalized username");
			}
		} finally {
			deleteTree(dir);
		}
	}

	private static void createOldSchemaMarker(Path db) throws Exception {
		try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
			 Statement s = c.createStatement()) {
			s.execute("CREATE TABLE current_blocks (dimension TEXT, x INTEGER, y INTEGER, z INTEGER)");
			s.execute("PRAGMA user_version=3");
		}
	}

	private static void insertLongRailRegion(Path db) throws Exception {
		try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
			 Statement s = c.createStatement()) {
			s.execute("""
				INSERT INTO building_regions (
					id, dimension, min_x, min_y, min_z, max_x, max_y, max_z,
					volume_blocks, status, last_touched_at, dirty, created_at, updated_at
				) VALUES (
					900, 'minecraft:overworld', 100, 64, 0, 140, 64, 0,
					41, 'active', '2026-05-13T00:00:00Z', 1, '2026-05-13T00:00:00Z', '2026-05-13T00:00:00Z'
				)
				""");
			s.execute("""
				INSERT INTO region_authors (
					region_id, player_name_key, display_name, first_place_count,
					last_modify_count, contribution_score, ratio_bp, last_touched_at
				) VALUES (
					900, 'alice', 'Alice', 1, 1, 2, 10000, '2026-05-13T00:00:00Z'
				)
				""");
			s.execute("""
				INSERT INTO region_blocks (
					region_id, player_name_key, dimension, x, y, z, first_placed_at, last_touched_at
				)
				SELECT 900, 'alice', 'minecraft:overworld', x, 64, 0,
				       '2026-05-13T00:00:00Z', '2026-05-13T00:00:00Z'
				FROM (
					SELECT 100 AS x UNION ALL SELECT 101 UNION ALL SELECT 102 UNION ALL SELECT 103 UNION ALL SELECT 104
					UNION ALL SELECT 105 UNION ALL SELECT 106 UNION ALL SELECT 107 UNION ALL SELECT 108 UNION ALL SELECT 109
					UNION ALL SELECT 110 UNION ALL SELECT 111 UNION ALL SELECT 112 UNION ALL SELECT 113 UNION ALL SELECT 114
					UNION ALL SELECT 115 UNION ALL SELECT 116 UNION ALL SELECT 117 UNION ALL SELECT 118 UNION ALL SELECT 119
					UNION ALL SELECT 120 UNION ALL SELECT 121 UNION ALL SELECT 122 UNION ALL SELECT 123 UNION ALL SELECT 124
					UNION ALL SELECT 125 UNION ALL SELECT 126 UNION ALL SELECT 127 UNION ALL SELECT 128 UNION ALL SELECT 129
					UNION ALL SELECT 130 UNION ALL SELECT 131 UNION ALL SELECT 132 UNION ALL SELECT 133 UNION ALL SELECT 134
					UNION ALL SELECT 135 UNION ALL SELECT 136 UNION ALL SELECT 137 UNION ALL SELECT 138 UNION ALL SELECT 139
					UNION ALL SELECT 140
				)
				""");
		}
	}

	private static int scalarInt(Statement s, String sql) throws Exception {
		try (ResultSet rs = s.executeQuery(sql)) {
			if (!rs.next()) throw new AssertionError("No row for " + sql);
			return rs.getInt(1);
		}
	}

	private static String scalarString(Statement s, String sql) throws Exception {
		try (ResultSet rs = s.executeQuery(sql)) {
			if (!rs.next()) throw new AssertionError("No row for " + sql);
			return rs.getString(1);
		}
	}

	private static boolean tableExists(Statement s, String table) throws Exception {
		try (ResultSet rs = s.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='" + table + "'")) {
			return rs.next();
		}
	}

	private static void assertEquals(Object expected, Object actual, String message) {
		if (!expected.equals(actual)) {
			throw new AssertionError(message + ": expected=" + expected + " actual=" + actual);
		}
	}

	private static void assertTrue(boolean value, String message) {
		if (!value) throw new AssertionError(message);
	}

	private static void assertFalse(boolean value, String message) {
		if (value) throw new AssertionError(message);
	}

	private static void deleteTree(Path dir) throws Exception {
		if (!Files.exists(dir)) return;
		try (var paths = Files.walk(dir)) {
			for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(p);
			}
		}
	}

	private static final class FakeWorldReader implements WorldBlockReader {
		@Override
		public List<BlockRecord> scan(String dimension, int minX, int minY, int minZ, int maxX, int maxY, int maxZ, int maxVolumeBlocks) {
			List<BlockRecord> blocks = new ArrayList<>();
			if (minX >= 100) {
				for (int x = 100; x <= 140; x++) {
					blocks.add(new BlockRecord(dimension, x, 64, 0, "minecraft:rail"));
				}
				return blocks;
			}
			addIfInside(blocks, dimension, 0, 64, 0, "minecraft:stone", minX, minY, minZ, maxX, maxY, maxZ);
			addIfInside(blocks, dimension, 1, 64, 0, "minecraft:oak_planks", minX, minY, minZ, maxX, maxY, maxZ);
			addIfInside(blocks, dimension, 2, 64, 0, "minecraft:glass", minX, minY, minZ, maxX, maxY, maxZ);
			addIfInside(blocks, dimension, 3, 64, 0, "minecraft:stone", minX, minY, minZ, maxX, maxY, maxZ);
			addIfInside(blocks, dimension, 4, 64, 0, "minecraft:stone", minX, minY, minZ, maxX, maxY, maxZ);
			addIfInside(blocks, dimension, 5, 64, 0, "minecraft:stone", minX, minY, minZ, maxX, maxY, maxZ);
			addIfInside(blocks, dimension, 6, 64, 0, "minecraft:stone", minX, minY, minZ, maxX, maxY, maxZ);
			addIfInside(blocks, dimension, 7, 64, 0, "minecraft:stone", minX, minY, minZ, maxX, maxY, maxZ);
			addIfInside(blocks, dimension, 8, 64, 0, "minecraft:wall_torch", minX, minY, minZ, maxX, maxY, maxZ);
			return blocks;
		}

		private static void addIfInside(
			List<BlockRecord> blocks,
			String dimension,
			int x,
			int y,
			int z,
			String blockState,
			int minX,
			int minY,
			int minZ,
			int maxX,
			int maxY,
			int maxZ
		) {
			if (x < minX || x > maxX || y < minY || y > maxY || z < minZ || z > maxZ) return;
			blocks.add(new BlockRecord(dimension, x, y, z, blockState));
		}
	}

	private static final class NoopLogger implements CommonLogger {
		@Override
		public void debug(String message, Object... args) {
		}

		@Override
		public void info(String message, Object... args) {
		}

		@Override
		public void warn(String message, Object... args) {
		}

		@Override
		public void error(String message, Object... args) {
		}
	}
}
