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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
				assertEquals(8, scalarInt(s, "PRAGMA user_version"), "schema version should be v8");
				assertTrue(tableExists(s, "current_blocks"), "old per-block table should be retained during non-destructive migration");
				assertFalse(tableExists(s, "block_events"), "change log table should not exist");
				assertTrue(tableExists(s, "building_regions"), "v5 region table should exist");
				assertTrue(tableExists(s, "region_authors"), "v5 author table should exist");
				assertTrue(tableExists(s, "region_blocks"), "v6 placed block table should exist");
				assertTrue(tableExists(s, "structure_audit_outbox"), "v8 structure audit outbox table should exist");
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
				assertStreamingBulkRecording(dir);
				assertHistoryAuditDoesNotWriteOwnership(dir);
				assertCompleteBoundsPlacement(dir);
			assertStructureAuditSwitch(dir);
			assertBoundsDeletion(dir);
			assertNonLinearBoundsDeletion(dir);
			assertLinearBoundsAuditCounts(dir);
			assertNonLinearWorldEditResultAudit(dir);
			assertAuditCountPriority(dir);
			assertQueuedSingleBlockWrites(dir);
			assertCompleteBoundsCaptureMode();
			assertWorldEditCuboidDetection();
			assertScopeAbortRestoresContext();
			assertDimensionNormalization();
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
					"world__minecraft:overworld",
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
				new BulkPlacementBounds("world__minecraft:overworld", 3, 64, 0, 3, 64, 0, 1),
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
			assertEquals(2, scalarInt(s, "SELECT COUNT(*) FROM structure_audit_outbox"), "bulk record/delete should enqueue two structure audit summaries");
			assertEquals(1, scalarInt(s, "SELECT COUNT(*) FROM structure_audit_outbox WHERE tool = 'WORLDEDIT' AND operation = 'SET' AND change_type = 'place' AND changed_block_count = 1"), "bulk placement audit should summarize accepted coordinates");
			assertEquals(1, scalarInt(s, "SELECT COUNT(*) FROM structure_audit_outbox WHERE tool = 'WORLDEDIT' AND operation = 'SET' AND change_type = 'delete' AND changed_block_count = 1"), "bulk delete audit without a result should use bounds volume");
			assertEquals(2, scalarInt(s, "SELECT COUNT(*) FROM structure_audit_outbox WHERE dimension = 'minecraft:overworld'"), "bulk audit dimensions should be canonicalized");
			assertEquals(0, scalarInt(s, "SELECT COUNT(*) FROM structure_audit_outbox WHERE dimension LIKE 'world%minecraft:%'"), "WorldEdit Fabric world id should not leak into audit dimensions");
		}
		BuildingIndexStore ackStore = BuildingIndexStore.open(db, new NoopLogger());
		try {
			List<StructureAuditEvent> pending = ackStore.listPendingStructureAuditEvents(10);
			assertEquals(2, pending.size(), "outbox listing should expose pending audit summaries for WebSocket flush");
			String message = StructureAuditEvent.toWsMessage(pending);
			assertTrue(message.contains("\"action\":\"structure_audit_events\""), "outbox payload should use structure audit WebSocket action");
			assertTrue(message.contains("\"changed_block_count\":1"), "outbox payload should include summary counts");
			List<String> ackIds = new ArrayList<>();
			ackIds.add(pending.get(0).eventId);
			ackStore.deleteStructureAuditOutboxEvents(ackIds);
			assertEquals(1, ackStore.listPendingStructureAuditEvents(10).size(), "ack deletion should remove acknowledged audit summaries");
		} finally {
			ackStore.close();
		}
	}

	private static void assertStreamingBulkRecording(Path dir) throws Exception {
		Path db = dir.resolve("streaming-bulk.db");
		BuildingIndexStore store = BuildingIndexStore.open(db, new NoopLogger());
		int[] batchCalls = {0};
		int[] maxBatchSize = {0};
		int[] maxBufferedChanges = {0};
		try {
			try (ActorContext.Scope scope = ActorContext.pushStreamingBulkRecord(
				"Alice",
				"WORLDEDIT_PASTE",
				(actor, changes) -> {
					batchCalls[0] += 1;
					maxBatchSize[0] = Math.max(maxBatchSize[0], changes.size());
					return store.stageBulkStateChanges(actor.bulkOperationId(), changes);
				},
				actor -> store.finishStagedBulkStateChanges(
					actor.bulkOperationId(),
					actor.name,
					actor.source,
					actor.bulkBounds(),
					actor.bulkResultCount()
				),
				actor -> store.discardStagedBulkStateChanges(actor.bulkOperationId())
			)) {
				for (int i = 0; i < 1_005; i++) {
					ActorContext.current().addBulkChange(new BulkBlockChange(
						"minecraft:overworld",
						i,
						64,
						20,
						"minecraft:air",
						"minecraft:stone",
						true,
						true
					));
					maxBufferedChanges[0] = Math.max(maxBufferedChanges[0], ActorContext.current().bulkChanges().size());
				}
			}
			try (ActorContext.Scope scope = ActorContext.pushStreamingBulkRecord(
				"Bob",
				"WORLDEDIT_PASTE",
				(actor, changes) -> store.stageBulkStateChanges(actor.bulkOperationId(), changes),
				actor -> store.finishStagedBulkStateChanges(
					actor.bulkOperationId(),
					actor.name,
					actor.source,
					actor.bulkBounds(),
					actor.bulkResultCount()
				),
				actor -> store.discardStagedBulkStateChanges(actor.bulkOperationId())
			)) {
				for (int i = 0; i < 1_005; i++) {
					ActorContext.current().addBulkChange(new BulkBlockChange(
						"minecraft:overworld",
						2_000 + i,
						64,
						20,
						"minecraft:air",
						"minecraft:stone",
						true,
						true
					));
				}
				scope.abort();
			}
			try (ActorContext.Scope scope = ActorContext.pushStreamingBulkRecord(
				"Carol",
				"WORLDEDIT_UNDO",
				(actor, changes) -> store.stageBulkStateChanges(actor.bulkOperationId(), changes),
				actor -> store.finishStagedBulkAuditOnly(
					actor.bulkOperationId(),
					actor.name,
					actor.source,
					actor.bulkBounds(),
					actor.bulkResultCount()
				),
				actor -> store.discardStagedBulkStateChanges(actor.bulkOperationId())
			)) {
				ActorContext.current().addBulkChange(new BulkBlockChange(
					"minecraft:overworld",
					4_000,
					64,
					20,
					"minecraft:air",
					"minecraft:stone",
					true,
					true
				));
				ActorContext.current().addBulkChange(new BulkBlockChange(
					"minecraft:overworld",
					4_001,
					64,
					20,
					"minecraft:stone",
					"minecraft:air",
					false,
					true
				));
			}
		} finally {
			store.close();
		}
		assertTrue(batchCalls[0] >= 1, "streaming bulk should flush at least one full batch before scope close");
		assertEquals(1_000, maxBatchSize[0], "streaming bulk should flush bounded batches");
		assertTrue(maxBufferedChanges[0] < 1_000, "streaming bulk should not retain the full operation in ActorContext");
		try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
			 Statement s = c.createStatement()) {
			assertEquals(1_005, scalarInt(s, "SELECT COUNT(*) FROM region_blocks"), "streaming bulk should preserve every staged placement");
			assertEquals(1_005, scalarInt(s, "SELECT SUM(first_place_count) FROM region_authors WHERE player_name_key = 'alice'"), "streaming bulk should preserve author placement counts");
			assertEquals(0, scalarInt(s, "SELECT COUNT(*) FROM region_authors WHERE player_name_key = 'bob'"), "aborted streaming bulk should not write author ownership");
			assertEquals(0, scalarInt(s, "SELECT COUNT(*) FROM region_blocks WHERE x >= 2000"), "aborted streaming bulk should discard staged coordinates");
			assertEquals(1, scalarInt(s, """
				SELECT COUNT(*) FROM structure_audit_outbox
				WHERE source = 'WORLDEDIT_PASTE'
				  AND actor_name = 'Alice'
				  AND changed_block_count = 1005
				  AND bounds_block_count = 1005
				"""), "streaming bulk should emit one exact audit summary at finish");
			assertEquals(0, scalarInt(s, "SELECT COUNT(*) FROM structure_audit_outbox WHERE actor_name = 'Bob'"), "aborted streaming bulk should not emit audit summaries");
			assertEquals(0, scalarInt(s, "SELECT COUNT(*) FROM region_blocks WHERE x >= 4000"), "streaming history audit should not write ownership");
			assertEquals(1, scalarInt(s, """
				SELECT COUNT(*) FROM structure_audit_outbox
				WHERE source = 'WORLDEDIT_UNDO'
				  AND actor_name = 'Carol'
				  AND change_type = 'mixed'
				  AND changed_block_count = 2
				  AND bounds_block_count = 2
				"""), "streaming history replay should emit audit-only summaries");
		}
	}

	private static void assertHistoryAuditDoesNotWriteOwnership(Path dir) throws Exception {
		Path db = dir.resolve("history-audit-only.db");
		BuildingIndexStore store = BuildingIndexStore.open(db, new NoopLogger());
		try {
			store.recordBulkStateChanges(
				List.of(
					new BulkBlockChange("minecraft:overworld", 20, 64, 0, "minecraft:air", "minecraft:stone", false, true),
					new BulkBlockChange("minecraft:overworld", 21, 64, 0, "minecraft:stone", "minecraft:air", false, true)
				),
				"Alice",
				"WORLDEDIT_UNDO"
			);
			store.recordBulkStateChanges(
				List.of(capturedPlacement(30)),
				"Alice",
				"EFFORTLESS_REDO"
			);
		} finally {
			store.close();
		}
		try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
			 Statement s = c.createStatement()) {
			assertEquals(0, scalarInt(s, "SELECT COUNT(*) FROM region_blocks"), "history replay should not create ownership rows");
			assertEquals(0, scalarInt(s, "SELECT COUNT(*) FROM region_authors"), "history replay should not create author contribution");
			assertEquals(2, scalarInt(s, "SELECT COUNT(*) FROM structure_audit_outbox"), "history replay should still produce audit summaries");
			assertEquals(1, scalarInt(s, """
				SELECT COUNT(*) FROM structure_audit_outbox
				WHERE source = 'WORLDEDIT_UNDO'
				  AND change_type = 'mixed'
				  AND changed_block_count = 2
				  AND bounds_block_count = 2
				"""), "WorldEdit undo audit should summarize replayed changes without indexing them");
			assertEquals(1, scalarInt(s, """
				SELECT COUNT(*) FROM structure_audit_outbox
				WHERE source = 'EFFORTLESS_REDO'
				  AND change_type = 'place'
				  AND changed_block_count = 1
				  AND min_x = 30 AND max_x = 30
				"""), "Effortless redo audit should summarize replayed changes without indexing them");
		}
	}

	private static void assertStructureAuditSwitch(Path dir) throws Exception {
		Path db = dir.resolve("audit-disabled.db");
		BuildingIndexStore store = BuildingIndexStore.open(db, new NoopLogger());
		try {
			store.setStructureAuditEnabled(false);
			List<BulkBlockChange> changes = new ArrayList<>();
			changes.add(new BulkBlockChange(
				"world__minecraft:overworld",
				0,
				64,
				0,
				"minecraft:stone",
				"minecraft:oak_planks",
				false,
				true
			));
			store.recordBulkStateChanges(changes, "Alice", "WORLDEDIT_SET");
			store.recordPlaced("minecraft:overworld", 1, 64, 0, "minecraft:stone", "Alice");
			store.recordPlaced("minecraft:overworld", 2, 64, 0, "minecraft:stone", "Alice");
				store.deleteIndexedBlocksInBounds(
					new BulkPlacementBounds("minecraft:overworld", 1, 64, 0, 1, 64, 0, 1),
					"Alice",
					"WORLDEDIT_CUT"
				);
				assertTrue(
					store.tryRecordCompleteBoundsPlacement(
						new BulkPlacementBounds("minecraft:overworld", 10, 64, 0, 12, 66, 2, 27),
						"Alice",
						"WORLDEDIT_SET",
						27
					),
					"disabled audit should not disable the complete bounds index fast path"
				);
				assertTrue(store.diagnosticSummary().contains("structure_audit=disabled"), "diagnostics should show disabled structure audit");
		} finally {
			store.close();
		}
		try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
			 Statement s = c.createStatement()) {
			assertEquals(1, scalarInt(s, "SELECT COUNT(*) FROM region_blocks WHERE x = 0"), "disabled audit should still record bulk placements");
				assertEquals(0, scalarInt(s, "SELECT COUNT(*) FROM region_blocks WHERE x = 1"), "disabled audit should still apply bounds deletion");
				assertEquals(1, scalarInt(s, "SELECT COUNT(*) FROM region_blocks WHERE x = 2"), "disabled audit should preserve coordinates outside bounds deletion");
				assertEquals(27, scalarInt(s, "SELECT COUNT(*) FROM region_blocks WHERE x BETWEEN 10 AND 12"), "disabled audit should still populate complete bounds coordinates");
				assertEquals(0, scalarInt(s, "SELECT COUNT(*) FROM structure_audit_outbox"), "disabled audit should not enqueue structure audit events");
		}
	}

	private static void assertCompleteBoundsPlacement(Path dir) throws Exception {
		Path db = dir.resolve("complete-bounds.db");
		BuildingIndexStore store = BuildingIndexStore.open(db, new NoopLogger());
		BulkPlacementBounds bounds = new BulkPlacementBounds(
			"world__minecraft:overworld",
			10,
			64,
			20,
			1109,
			76,
			20,
			14_300
		);
		BulkPlacementBounds partialBounds = new BulkPlacementBounds(
			"minecraft:overworld",
			1_200,
			64,
			20,
			1_219,
			64,
			39,
			400
		);
		BulkPlacementBounds zeroResultBounds = new BulkPlacementBounds(
			"minecraft:overworld",
			1_300,
			64,
			20,
			1_319,
			64,
			39,
			400
		);
		try {
			store.recordPlaced("minecraft:overworld", 10, 64, 20, "minecraft:stone", "Alice");
			assertTrue(
				store.tryRecordCompleteBoundsPlacement(bounds, "Alice", "WORLDEDIT_SET", 14_300),
				"complete non-linear //set bounds should use the set-based fast path"
			);
			assertFalse(
				store.tryRecordCompleteBoundsPlacement(partialBounds, "Alice", "WORLDEDIT_SET", 17),
				"partial //set result should not claim the full bounds"
			);
			store.recordBulkStateChanges(List.of(), "Alice", "WORLDEDIT_SET", partialBounds, 17);
			assertFalse(
				store.tryRecordCompleteBoundsPlacement(zeroResultBounds, "Alice", "WORLDEDIT_SET", 0),
				"zero-result //set should not claim the full bounds"
			);
			store.recordBulkStateChanges(List.of(), "Alice", "WORLDEDIT_SET", zeroResultBounds, 0);
		} finally {
			store.close();
		}
		try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
			 Statement s = c.createStatement()) {
			assertEquals(14_300, scalarInt(s, "SELECT COUNT(*) FROM region_blocks"), "only exact complete-bounds results should insert every coordinate");
			assertEquals(1, scalarInt(s, "SELECT COUNT(*) FROM building_regions"), "partial and zero-result bounds should not create indexed regions");
			assertEquals(14_300, scalarInt(s, "SELECT SUM(volume_blocks) FROM building_regions"), "region bounds should only retain exact complete cuboids");
			assertEquals(14_301, scalarInt(s, "SELECT SUM(first_place_count) FROM region_authors WHERE player_name_key = 'alice'"), "only exact bounds should aggregate author placement counts");
			assertEquals(14_301, scalarInt(s, "SELECT SUM(last_modify_count) FROM region_authors WHERE player_name_key = 'alice'"), "only exact bounds should aggregate author modification counts");
			assertEquals(0, scalarInt(s, """
				SELECT COUNT(*) FROM region_blocks
				WHERE x BETWEEN 1200 AND 1219
				  AND y = 64
				  AND z BETWEEN 20 AND 39
				"""), "partial bounds should not write ownership coordinates");
			assertEquals(0, scalarInt(s, """
				SELECT COUNT(*) FROM region_blocks
				WHERE x BETWEEN 1300 AND 1319
				  AND y = 64
				  AND z BETWEEN 20 AND 39
				"""), "zero-result bounds should not write ownership coordinates");
			assertEquals(1, scalarInt(s, """
				SELECT COUNT(*) FROM structure_audit_outbox
				WHERE source = 'WORLDEDIT_SET'
				  AND changed_block_count = 14300
				  AND bounds_block_count = 14300
				  AND min_x = 10 AND max_x = 1109
				  AND min_y = 64 AND max_y = 76
				  AND min_z = 20 AND max_z = 20
				"""), "complete bounds should enqueue one exact audit summary");
			assertEquals(1, scalarInt(s, """
				SELECT COUNT(*) FROM structure_audit_outbox
				WHERE source = 'WORLDEDIT_SET'
				  AND changed_block_count = 17
				  AND bounds_block_count = 400
				  AND min_x = 1200 AND max_x = 1219
				  AND min_y = 64 AND max_y = 64
				  AND min_z = 20 AND max_z = 39
				"""), "partial result should retain its exact audit count without indexing the full bounds");
			assertEquals(1, scalarInt(s, """
				SELECT COUNT(*) FROM structure_audit_outbox
				WHERE source = 'WORLDEDIT_SET'
				  AND changed_block_count = 0
				  AND bounds_block_count = 400
				  AND min_x = 1300 AND max_x = 1319
				  AND min_y = 64 AND max_y = 64
				  AND min_z = 20 AND max_z = 39
				"""), "zero result should retain its audit count without indexing the full bounds");
		}
	}

	private static void assertDimensionNormalization() {
		assertEquals("minecraft:overworld", DimensionNames.normalize("world__minecraft:overworld"), "WorldEdit Fabric overworld id should normalize");
		assertEquals("minecraft:the_nether", DimensionNames.normalize("world_minecraft:the_nether"), "WorldEdit Fabric vanilla dimension id should normalize");
		assertEquals("custom_mod:moon", DimensionNames.normalizeWorldEditId("world_custom_mod:moon", "world"), "WorldEdit id should strip the known world name without damaging custom namespaces");
		assertEquals("custom_mod:moon", BulkPlacementIntrospection.worldEditWorldDimension(new FakeWorldEditWorld("world", "world_custom_mod:moon")), "WorldEdit world dimension helper should normalize ids");
		StructureAuditEvent event = StructureAuditEvent.fromBounds(
			new BulkPlacementBounds("world__minecraft:overworld", 1, 2, 3, 1, 2, 3, 1),
			"Alice",
			"WORLDEDIT_SET",
			"place",
			1,
			SqliteUtcDatetimes.now()
		);
		assertEquals("minecraft:overworld", event.dimension, "audit events should store canonical dimensions");
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
			assertEquals(1, scalarInt(s, """
				SELECT COUNT(*) FROM structure_audit_outbox
				WHERE source = 'EFFORTLESS_BREAK'
				  AND changed_block_count = 400
				  AND bounds_block_count = 400
				"""), "delete audit without a result should use bounds volume instead of the two indexed blocks");
		}
	}

	private static void assertLinearBoundsAuditCounts(Path dir) throws Exception {
		Path db = dir.resolve("linear-bounds-audit.db");
		BuildingIndexStore store = BuildingIndexStore.open(db, new NoopLogger());
		try {
			store.deleteIndexedBlocksInBounds(
				new BulkPlacementBounds("minecraft:overworld", 0, 64, 0, 99, 64, 0, 100),
				"Alice",
				"WORLDEDIT_SET",
				"mixed",
				37
			);
			store.deleteIndexedBlocksInBounds(
				new BulkPlacementBounds("minecraft:overworld", 200, 64, 0, 299, 64, 0, 100),
				"Alice",
				"WORLDEDIT_REPLACE",
				"mixed",
				0
			);
			store.deleteIndexedBlocksInBounds(
				new BulkPlacementBounds("minecraft:overworld", 400, 64, 0, 409, 64, 0, 10),
				"Alice",
				"EFFORTLESS_BUILD",
				"mixed"
			);
		} finally {
			store.close();
		}
		try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
			 Statement s = c.createStatement()) {
			assertEquals(1, scalarInt(s, """
				SELECT COUNT(*) FROM structure_audit_outbox
				WHERE source = 'WORLDEDIT_SET' AND changed_block_count = 37 AND bounds_block_count = 100
				"""), "linear WorldEdit set audit should use the command result");
			assertEquals(1, scalarInt(s, """
				SELECT COUNT(*) FROM structure_audit_outbox
				WHERE source = 'WORLDEDIT_REPLACE' AND changed_block_count = 0 AND bounds_block_count = 100
				"""), "zero WorldEdit result should remain an exact audit count");
			assertEquals(1, scalarInt(s, """
				SELECT COUNT(*) FROM structure_audit_outbox
				WHERE source = 'EFFORTLESS_BUILD' AND changed_block_count = 10 AND bounds_block_count = 10
				"""), "bulk operations without a result should fall back to bounds volume");
		}
	}

	private static void assertNonLinearWorldEditResultAudit(Path dir) throws Exception {
		Path db = dir.resolve("non-linear-result-audit.db");
		BuildingIndexStore store = BuildingIndexStore.open(db, new NoopLogger());
		try {
			List<BulkBlockChange> capturedChanges = List.of(
				new BulkBlockChange(
					"minecraft:overworld",
					0,
					64,
					0,
					"minecraft:air",
					"minecraft:stone",
					true,
					true
				),
				new BulkBlockChange(
					"minecraft:overworld",
					1,
					64,
					0,
					"minecraft:air",
					"minecraft:stone",
					true,
					true
				)
			);
			store.recordBulkStateChanges(
				capturedChanges,
				"Alice",
				"WORLDEDIT_REPLACE",
				new BulkPlacementBounds("minecraft:overworld", 0, 64, 0, 19, 64, 19, 400),
				17
			);
			store.recordBulkStateChanges(
				List.of(),
				"Alice",
				"WORLDEDIT_SET",
				new BulkPlacementBounds("minecraft:overworld", 30, 64, 0, 49, 64, 19, 400),
				0
			);
			store.recordBulkStateChanges(
				List.of(new BulkBlockChange(
					"minecraft:overworld",
					60,
					64,
					0,
					"minecraft:air",
					"minecraft:stone",
					true,
					true
				)),
				"Alice",
				"WORLDEDIT_SET",
				null,
				9
			);
		} finally {
			store.close();
		}
		try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
			 Statement s = c.createStatement()) {
			assertEquals(3, scalarInt(s, "SELECT COUNT(*) FROM region_blocks"), "non-linear indexing should still use captured coordinates");
			assertEquals(1, scalarInt(s, """
				SELECT COUNT(*) FROM structure_audit_outbox
				WHERE source = 'WORLDEDIT_REPLACE'
				  AND changed_block_count = 17
				  AND bounds_block_count = 400
				  AND change_type = 'place'
				"""), "non-linear replace audit should use the WorldEdit result instead of captured coordinate count");
			assertEquals(1, scalarInt(s, """
				SELECT COUNT(*) FROM structure_audit_outbox
				WHERE source = 'WORLDEDIT_SET'
				  AND changed_block_count = 0
				  AND bounds_block_count = 400
				"""), "zero-result non-linear set should still enqueue an exact audit event");
			assertEquals(1, scalarInt(s, """
				SELECT COUNT(*) FROM structure_audit_outbox
				WHERE source = 'WORLDEDIT_SET'
				  AND changed_block_count = 9
				  AND bounds_block_count = 1
				  AND min_x = 60 AND max_x = 60
				"""), "a WorldEdit result should take priority over the captured count");
		}
	}

	private static void assertAuditCountPriority(Path dir) throws Exception {
		Path db = dir.resolve("audit-count-priority.db");
		BuildingIndexStore store = BuildingIndexStore.open(db, new NoopLogger());
		try {
			List<BulkBlockChange> capturedChanges = List.of(
				capturedPlacement(0),
				capturedPlacement(1)
			);
			store.recordBulkStateChanges(
				capturedChanges,
				"Alice",
				"EFFORTLESS_CAPTURED",
				new BulkPlacementBounds("minecraft:overworld", 0, 64, 0, 19, 64, 19, 400),
				-1
			);
			store.recordBulkStateChanges(
				List.of(capturedPlacement(60)),
				"Alice",
				"EFFORTLESS_CAPTURED_NO_BOUNDS",
				null,
				-1
			);
			store.recordBulkStateChanges(
				List.of(capturedPlacement(90)),
				"Alice",
				"WORLDEDIT_SET",
				new BulkPlacementBounds("minecraft:overworld", 90, 64, 0, 109, 64, 19, 400),
				7
			);
			store.recordBulkStateChanges(
				List.of(),
				"Alice",
				"EFFORTLESS_BOUNDS_ONLY",
				new BulkPlacementBounds("minecraft:overworld", 120, 64, 0, 139, 64, 19, 400),
				-1
			);
		} finally {
			store.close();
		}
		try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
			 Statement s = c.createStatement()) {
			assertEquals(4, scalarInt(s, "SELECT COUNT(*) FROM region_blocks"), "audit count selection should not discard captured index coordinates");
			assertEquals(1, scalarInt(s, """
				SELECT COUNT(*) FROM structure_audit_outbox
				WHERE source = 'EFFORTLESS_CAPTURED'
				  AND changed_block_count = 2
				  AND bounds_block_count = 400
				"""), "missing result should use the captured count with the available bounds");
			assertEquals(1, scalarInt(s, """
				SELECT COUNT(*) FROM structure_audit_outbox
				WHERE source = 'EFFORTLESS_CAPTURED_NO_BOUNDS'
				  AND changed_block_count = 1
				  AND bounds_block_count = 1
				"""), "captured coordinates should provide the count and range when bounds are unavailable");
			assertEquals(1, scalarInt(s, """
				SELECT COUNT(*) FROM structure_audit_outbox
				WHERE source = 'WORLDEDIT_SET'
				  AND changed_block_count = 7
				  AND bounds_block_count = 400
				"""), "WorldEdit result should override the captured count");
			assertEquals(1, scalarInt(s, """
				SELECT COUNT(*) FROM structure_audit_outbox
				WHERE source = 'EFFORTLESS_BOUNDS_ONLY'
				  AND changed_block_count = 400
				  AND bounds_block_count = 400
				"""), "missing result and capture should fall back to the bounds count");
		}
	}

	private static void assertQueuedSingleBlockWrites(Path dir) throws Exception {
		Path db = dir.resolve("queued-single-block-writes.db");
		BuildingIndexStore store = BuildingIndexStore.open(db, new NoopLogger());
		ExecutorService caller = Executors.newSingleThreadExecutor();
		try {
			synchronized (store) {
				Future<?> accepted = caller.submit(() -> store.recordStateChangeWithSource(
					"minecraft:overworld",
					30,
					64,
					30,
					"minecraft:air",
					"minecraft:stone",
					"Alice",
					"PLAYER_USE_ITEM_ON"
				));
				accepted.get(1, TimeUnit.SECONDS);
			}
			for (int y = 64; y < 69; y++) {
				for (int z = 0; z < 5; z++) {
					for (int x = 0; x < 5; x++) {
						store.recordStateChangeWithSource(
							"minecraft:overworld",
							x,
							y,
							z,
							"minecraft:air",
							"minecraft:stone",
							"Alice",
							"PLAYER_USE_ITEM_ON"
						);
					}
				}
			}
			store.recordStateChangeWithSource("minecraft:overworld", 20, 64, 20, "minecraft:air", "minecraft:stone", "Alice", "PLAYER_USE_ITEM_ON");
			store.recordStateChangeWithSource("minecraft:overworld", 20, 64, 20, "minecraft:stone", "minecraft:air", "Alice", "PLAYER_DESTROY_BLOCK");
			store.recordStateChangeWithSource("minecraft:overworld", 20, 64, 20, "minecraft:air", "minecraft:stone", "Alice", "PLAYER_USE_ITEM_ON");
			store.flushPendingWrites();

			try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
				 Statement s = c.createStatement()) {
				assertEquals(127, scalarInt(s, "SELECT COUNT(*) FROM region_blocks"), "queued single-block writes should be visible after the flush barrier");
				assertEquals(1, scalarInt(s, "SELECT COUNT(*) FROM region_blocks WHERE x = 20 AND y = 64 AND z = 20"), "queued mutation order should preserve the final placement");
				assertEquals(128, scalarInt(s, "SELECT SUM(first_place_count) FROM region_authors WHERE player_name_key = 'alice'"), "queued author placement counts should retain every accepted placement");
				assertEquals(129, scalarInt(s, "SELECT SUM(last_modify_count) FROM region_authors WHERE player_name_key = 'alice'"), "queued author modification counts should retain placement and deletion order");
			}
		} finally {
			caller.shutdownNow();
			store.close();
		}
	}

	private static BulkBlockChange capturedPlacement(int x) {
		return new BulkBlockChange(
			"minecraft:overworld",
			x,
			64,
			0,
			"minecraft:air",
			"minecraft:stone",
			true,
			true
		);
	}

	private static void assertCompleteBoundsCaptureMode() {
		BulkPlacementBounds bounds = new BulkPlacementBounds(
			"minecraft:overworld",
			0,
			64,
			0,
			9,
			73,
			9,
			1_000
		);
		int[] flushedChanges = {-1};
		try (ActorContext.Scope scope = ActorContext.pushBulkRecord(
			"Alice",
			"WORLDEDIT_SET",
			bounds,
			true,
			actor -> flushedChanges[0] = actor.bulkChanges().size()
		)) {
			ActorContext.Actor actor = ActorContext.current();
			assertEquals(ActorContext.RecordingMode.BULK_COMPLETE_BOUNDS, actor.recordingMode, "complete bounds mode should be selected before block updates begin");
			assertTrue(actor.shouldIgnoreBlockRecords(), "complete bounds mode should suppress per-block capture");
			assertFalse(actor.shouldForcePlacementRecords(), "complete bounds mode should not create BulkBlockChange objects");
			assertFalse(actor.isCompleteBoundsPlacement(), "unknown result count should not enable the complete bounds SQL path");
			actor.addBulkChange(capturedPlacement(0));
			assertEquals(0, actor.bulkChanges().size(), "complete bounds mode should keep the bulk buffer empty");
			actor.setBulkResultCount(1_000);
			assertTrue(actor.isCompleteBoundsPlacement(), "matching result count should enable the complete bounds SQL path");
		}
		assertEquals(0, flushedChanges[0], "complete bounds flush should not receive captured block objects");

		try (ActorContext.Scope scope = ActorContext.pushBulkRecord(
			"Alice",
			"WORLDEDIT_SET",
			bounds,
			true,
			actor -> {
			}
		)) {
			ActorContext.Actor actor = ActorContext.current();
			actor.setBulkResultCount(999);
			assertFalse(actor.isCompleteBoundsPlacement(), "partial results should not enable the complete-bounds SQL path");
			assertEquals(0, actor.bulkChanges().size(), "complete-bounds mode should remain summary-only");
		}

		try (ActorContext.Scope scope = ActorContext.pushBulkRecord(
			"Alice",
			"WORLDEDIT_SET",
			null,
			true,
			actor -> {
			}
		)) {
			assertEquals(ActorContext.RecordingMode.BULK_RECORD, ActorContext.current().recordingMode, "missing bounds should retain coordinate capture");
		}
	}

	private static void assertWorldEditCuboidDetection() {
		assertTrue(
			BulkPlacementIntrospection.isWorldEditCuboidRegion(new com.sk89q.worldedit.regions.CuboidRegion()),
			"WorldEdit CuboidRegion should be eligible for complete-bounds capture"
		);
		assertTrue(
			BulkPlacementIntrospection.isWorldEditCuboidRegion(new FakeDerivedCuboidRegion()),
			"CuboidRegion subclasses should be eligible for complete-bounds capture"
		);
		assertFalse(
			BulkPlacementIntrospection.isWorldEditCuboidRegion(new FakeWorldEditWorld("world", "world")),
			"non-cuboid WorldEdit regions should not use complete-bounds capture"
		);
		assertFalse(
			BulkPlacementIntrospection.isWorldEditCuboidRegion(null),
			"missing WorldEdit region should not use complete-bounds capture"
		);
	}

	private static void assertScopeAbortRestoresContext() {
		int[] flushCalls = {0};
		try (ActorContext.Scope outer = ActorContext.push("Outer", "TEST")) {
			ActorContext.Scope bulk = ActorContext.pushBulkRecord("Alice", "WORLDEDIT_SET", actor -> flushCalls[0]++);
			assertEquals("Alice", ActorContext.current().name, "bulk scope should install its actor");
			bulk.abort();
			assertEquals("Outer", ActorContext.current().name, "aborted bulk scope should restore the previous actor");
			assertEquals(0, flushCalls[0], "aborted bulk scope should not flush partial changes");
		}
		assertEquals(null, ActorContext.current(), "closing the outer scope should clear the ThreadLocal");
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

	private static final class FakeWorldEditWorld {
		private final String name;
		private final String id;

		FakeWorldEditWorld(String name, String id) {
			this.name = name;
			this.id = id;
		}

		public Object getWorld() {
			return null;
		}

		public String getName() {
			return name;
		}

		public String getId() {
			return id;
		}
	}

	private static final class FakeDerivedCuboidRegion extends com.sk89q.worldedit.regions.CuboidRegion {
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
