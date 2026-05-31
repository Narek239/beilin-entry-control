package us.beiyue.beilindataportability.common;

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
import java.util.regex.Pattern;

public final class BuildingIndexStoreStorageTest {
	private static final Pattern SQLITE_UTC_DATETIME = Pattern.compile("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");

	public static void main(String[] args) throws Exception {
		Class.forName("org.sqlite.JDBC");
		Path dir = Files.createTempDirectory("beilin-data-portability-storage");
		BuildingIndexStore store = null;
		try {
			Path db = dir.resolve("world.db");
			createOldSchemaMarker(db);

			store = BuildingIndexStore.open(db, new NoopLogger());
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
			store.close();
			store = null;

			insertLongRailRegion(db);
			store = BuildingIndexStore.open(db, new NoopLogger());
			FakeWorldReader reader = new FakeWorldReader();

			ExportBundle bundle = store.buildExportBundle(
				new ExportJob(42L, "alice", null, null),
				reader,
				1_000_000
			);
				assertEquals(2, bundle.components.size(), "long rail should export once indexed; linear filtering happens only at bulk write time");
				ComponentSummary summary = findComponent(bundle, 0);
				ComponentSummary rail = findComponent(bundle, 100);
			assertEquals(8, summary.nonAirBlocks, "SYSTEM/UNKNOWN changes outside the region should not expand into terrain");
			assertEquals(8, summary.blockCount, "compat block count should map to non-air blocks");
			assertEquals(2, summary.authorCount, "SYSTEM changes should not count as authors");
				assertEquals(8750, summary.targetAuthorRatioBp, "username ratio should be normalized and author-only");
				assertEquals("mixed_authorship", summary.riskFlags, "multi-author region should be flagged");
				assertEquals(41, rail.nonAirBlocks, "indexed long rail should no longer be skipped at export time");
				assertEquals(null, rail.riskFlags, "manifest should not claim linear components were excluded");
			assertEquals("beilin-data-portability-manifest-v2", bundle.manifest.toManifestJson().get("format").getAsString(), "manifest format");
			assertEquals(0, reader.scanCalls, "export should use exact coordinate reads instead of cuboid scans");
			assertTrue(reader.coordinateReadCalls > 0, "exact coordinate reader should be used");
			assertSqliteUtcDatetime(bundle.manifest.generatedAt, "manifest generated_at should use SQLite UTC datetime");
			assertSqliteUtcDatetime(summary.targetLastTouchedAt, "component target_last_touched_at should use SQLite UTC datetime");
			store.close();
			store = null;

			try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
				 Statement s = c.createStatement()) {
				assertEquals("wal", scalarString(s, "PRAGMA journal_mode"), "SQLite journal mode should be WAL");
				assertEquals(7, scalarInt(s, "PRAGMA user_version"), "schema version should be v7");
				assertTrue(tableExists(s, "current_blocks"), "old per-block table should be retained during non-destructive migration");
				assertFalse(tableExists(s, "block_events"), "change log table should not exist");
				assertTrue(tableExists(s, "building_regions"), "v5 region table should exist");
				assertTrue(tableExists(s, "region_authors"), "v5 author table should exist");
				assertTrue(tableExists(s, "region_blocks"), "v6 placed block table should exist");
				assertFalse(tableExists(s, "region_chunk_index"), "unused chunk index table should not be created for new schemas");
				assertEquals(2, scalarInt(s, "SELECT COUNT(DISTINCT player_name_key) FROM region_authors WHERE player_name_key IN ('alice','bob')"), "authors should be keyed by normalized username");
				assertEquals(0, scalarInt(s, """
					SELECT COUNT(*) FROM building_regions
					WHERE created_at LIKE '%T%' OR updated_at LIKE '%T%' OR last_touched_at LIKE '%T%'
					"""), "building region datetimes should be normalized");
				assertEquals(0, scalarInt(s, "SELECT COUNT(*) FROM region_authors WHERE last_touched_at LIKE '%T%'"), "author datetimes should be normalized");
				assertEquals(0, scalarInt(s, """
					SELECT COUNT(*) FROM region_blocks
					WHERE first_placed_at LIKE '%T%' OR last_touched_at LIKE '%T%'
					"""), "placed block datetimes should be normalized");
				}
				assertRecordingSemantics(dir);
				assertBoundsDeletion(dir);
				assertNonLinearBoundsDeletion(dir);
				assertGeometryClassifier();
		} finally {
			if (store != null) {
				store.close();
			}
			deleteTree(dir);
		}
	}

	private static void assertRecordingSemantics(Path dir) throws Exception {
		Path db = dir.resolve("recording.db");
		BuildingIndexStore store = BuildingIndexStore.open(db, new NoopLogger());
		try {
			store.recordStateChangeWithSource("minecraft:overworld", 0, 64, 0, "minecraft:grass", true, "minecraft:oak_planks", "Alice", "PLAYER_USE_ITEM_ON");
			store.recordStateChangeWithSource("minecraft:overworld", 1, 64, 0, "minecraft:stone", false, "minecraft:oak_planks", "Alice", "PLAYER_USE_ITEM_ON");
			try (ActorContext.Scope scope = ActorContext.pushBulkRecord("Alice", "WORLDEDIT_SET", actor ->
				store.recordBulkStateChanges(actor.bulkChanges(), actor.name, actor.source)
			)) {
				ActorContext.current().addBulkChange(new BulkBlockChange(
					"minecraft:overworld",
					2,
					64,
					0,
					"minecraft:stone",
					"minecraft:oak_planks",
					false,
					true
				));
			}
			store.recordPlaced("minecraft:overworld", 3, 64, 0, "minecraft:stone", "Alice");
			store.recordPlaced("minecraft:overworld", 4, 64, 0, "minecraft:stone", "Alice");
			try (ActorContext.Scope scope = ActorContext.pushBulkDeleteBounds(
				"Alice",
				"WORLDEDIT_SET",
				new BulkPlacementBounds("minecraft:overworld", 3, 64, 0, 3, 64, 0, 1),
				actor -> store.deleteIndexedBlocksInBounds(actor.bulkBounds(), actor.name, actor.source)
			)) {
				ActorContext.current().addBulkChange(new BulkBlockChange(
					"minecraft:overworld",
					5,
					64,
					0,
					"minecraft:air",
					"minecraft:oak_planks",
					false,
					true
				));
			}
		} finally {
			store.close();
		}
		try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
			 Statement s = c.createStatement()) {
			assertEquals(3, scalarInt(s, "SELECT COUNT(*) FROM region_blocks"), "replaceable and bulk forced placements should be recorded, with delete-bounds applied");
			assertEquals(1, scalarInt(s, "SELECT COUNT(*) FROM region_blocks WHERE x = 0"), "replaceable non-air should count as first placement");
			assertEquals(0, scalarInt(s, "SELECT COUNT(*) FROM region_blocks WHERE x = 1"), "non-replaceable non-air replacement should not count as first placement");
			assertEquals(1, scalarInt(s, "SELECT COUNT(*) FROM region_blocks WHERE x = 2"), "bulk RECORD should flush collected coordinates");
			assertEquals(0, scalarInt(s, "SELECT COUNT(*) FROM region_blocks WHERE x = 3"), "bulk DELETE_BOUNDS should remove indexed coordinates in range");
			assertEquals(1, scalarInt(s, "SELECT COUNT(*) FROM region_blocks WHERE x = 4"), "bulk DELETE_BOUNDS should keep coordinates outside range");
			assertEquals(0, scalarInt(s, "SELECT COUNT(*) FROM region_blocks WHERE x = 5"), "bulk DELETE_BOUNDS should not collect coordinates");
		}
	}

	private static void assertBoundsDeletion(Path dir) throws Exception {
		Path db = dir.resolve("bounds-delete.db");
		BuildingIndexStore store = BuildingIndexStore.open(db, new NoopLogger());
		try {
			store.recordPlaced("minecraft:overworld", 10, 64, 0, "minecraft:stone", "Alice");
			store.recordPlaced("minecraft:overworld", 11, 64, 0, "minecraft:stone", "Alice");
			store.recordPlaced("minecraft:overworld", 12, 64, 0, "minecraft:stone", "Alice");
		} finally {
			store.close();
		}
		try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
			 Statement s = c.createStatement()) {
			s.executeUpdate("UPDATE building_regions SET dirty = 0");
		}
		store = BuildingIndexStore.open(db, new NoopLogger());
		try {
			store.deleteIndexedBlocksInBounds(
				new BulkPlacementBounds("minecraft:overworld", 10, 64, 0, 11, 64, 0, 2),
				"Alice",
				"WORLDEDIT_CUT"
			);
		} finally {
			store.close();
		}
		try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
			 Statement s = c.createStatement()) {
			assertEquals(0, scalarInt(s, "SELECT COUNT(*) FROM region_blocks WHERE x BETWEEN 10 AND 11"), "bounds delete should remove indexed blocks inside range");
			assertEquals(1, scalarInt(s, "SELECT COUNT(*) FROM region_blocks WHERE x = 12"), "bounds delete should preserve indexed blocks outside range");
			assertEquals(1, scalarInt(s, "SELECT COUNT(*) FROM building_regions WHERE dirty = 1"), "bounds delete should mark affected region dirty");
		}
	}

	private static void assertNonLinearBoundsDeletion(Path dir) throws Exception {
		Path db = dir.resolve("non-linear-bounds-delete.db");
		BuildingIndexStore store = BuildingIndexStore.open(db, new NoopLogger());
		try {
			store.recordPlaced("minecraft:overworld", 20, 64, 0, "minecraft:stone", "Alice");
			store.recordPlaced("minecraft:overworld", 39, 64, 19, "minecraft:stone", "Alice");
			store.recordPlaced("minecraft:overworld", 40, 64, 20, "minecraft:stone", "Alice");
			store.deleteIndexedBlocksInBounds(
				new BulkPlacementBounds("minecraft:overworld", 20, 64, 0, 39, 64, 19, 400),
				"Alice",
				"EFFORTLESS_BREAK"
			);
		} finally {
			store.close();
		}
		try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
			 Statement s = c.createStatement()) {
			assertEquals(0, scalarInt(s, "SELECT COUNT(*) FROM region_blocks WHERE x BETWEEN 20 AND 39 AND z BETWEEN 0 AND 19"), "non-linear bounds delete should not depend on linear geometry");
			assertEquals(1, scalarInt(s, "SELECT COUNT(*) FROM region_blocks WHERE x = 40 AND z = 20"), "non-linear bounds delete should preserve indexed blocks outside range");
		}
	}

	private static void assertGeometryClassifier() {
		assertTrue(PortabilityGeometryClassifier.isLinearInfrastructureBySize(1000, 1, 1, 1000), "1000x1x1 should be linear");
		assertTrue(PortabilityGeometryClassifier.isLinearInfrastructureBySize(1000, 2, 2, 4000), "1000x2x2 should be linear");
		assertTrue(PortabilityGeometryClassifier.isLinearInfrastructureBySize(32, 1, 12, 384), "32x1x12 should be linear");
		assertFalse(PortabilityGeometryClassifier.isLinearInfrastructureBySize(20, 20, 1, 400), "20x20x1 should be non-linear");
		assertFalse(PortabilityGeometryClassifier.isLinearInfrastructureBySize(16, 16, 4, 1024), "16x16x4 should be non-linear");
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

	private static ComponentSummary findComponent(ExportBundle bundle, int minX) {
		for (ComponentExport component : bundle.components) {
			if (component.summary.minX == minX) return component.summary;
		}
		throw new AssertionError("No component with minX=" + minX);
	}

	private static void assertEquals(Object expected, Object actual, String message) {
		if (expected == null ? actual != null : !expected.equals(actual)) {
			throw new AssertionError(message + ": expected=" + expected + " actual=" + actual);
		}
	}

	private static void assertTrue(boolean value, String message) {
		if (!value) throw new AssertionError(message);
	}

	private static void assertFalse(boolean value, String message) {
		if (value) throw new AssertionError(message);
	}

	private static void assertSqliteUtcDatetime(String value, String message) {
		if (value == null || !SQLITE_UTC_DATETIME.matcher(value).matches()) {
			throw new AssertionError(message + ": " + value);
		}
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
		int scanCalls;
		int coordinateReadCalls;

		@Override
		public List<BlockRecord> scan(String dimension, int minX, int minY, int minZ, int maxX, int maxY, int maxZ, int maxVolumeBlocks) {
			scanCalls++;
			throw new AssertionError("unexpected cuboid scan");
		}

		@Override
		public List<BlockRecord> readCoordinates(String dimension, List<BlockCoordinate> coordinates, int maxExportVolumeBlocks) {
			coordinateReadCalls++;
			List<BlockRecord> blocks = new ArrayList<>();
			for (BlockCoordinate coordinate : coordinates) {
				if (coordinate.x >= 100 && coordinate.x <= 140 && coordinate.y == 64 && coordinate.z == 0) {
					blocks.add(new BlockRecord(dimension, coordinate.x, coordinate.y, coordinate.z, "minecraft:rail"));
					continue;
				}
				addKnownBlock(blocks, dimension, coordinate);
			}
			blocks.sort(Comparator
				.comparingInt((BlockRecord b) -> b.y)
				.thenComparingInt(b -> b.z)
				.thenComparingInt(b -> b.x));
			return blocks;
		}

		private static void addKnownBlock(
			List<BlockRecord> blocks,
			String dimension,
			BlockCoordinate coordinate
		) {
			if (coordinate.y != 64 || coordinate.z != 0) return;
			switch (coordinate.x) {
				case 0 -> blocks.add(new BlockRecord(dimension, 0, 64, 0, "minecraft:stone"));
				case 1 -> blocks.add(new BlockRecord(dimension, 1, 64, 0, "minecraft:oak_planks"));
				case 2 -> blocks.add(new BlockRecord(dimension, 2, 64, 0, "minecraft:glass"));
				case 3, 4, 5, 6, 7 -> blocks.add(new BlockRecord(dimension, coordinate.x, 64, 0, "minecraft:stone"));
				case 8 -> blocks.add(new BlockRecord(dimension, 8, 64, 0, "minecraft:wall_torch"));
				default -> {
				}
			}
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
