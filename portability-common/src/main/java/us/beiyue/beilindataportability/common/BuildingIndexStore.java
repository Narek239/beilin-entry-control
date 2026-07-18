package us.beiyue.beilindataportability.common;

import us.beiyue.beilinentrycontrol.common.log.CommonLogger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public final class BuildingIndexStore {
	private static final int SCHEMA_VERSION = 8;
	private static final int REGION_MERGE_GAP_BLOCKS = 2;
	private static final int MIN_EXPORT_COMPONENT_BLOCKS = 8;
	private static final int WRITE_BATCH_SIZE = 1_000;
	private static final long WRITE_BATCH_WAIT_MILLIS = 5L;
	private static final long WRITER_STOP_TIMEOUT_SECONDS = 30L;
	private static final int[][] NEIGHBORS = {
		{1, 0, 0}, {-1, 0, 0},
		{0, 1, 0}, {0, -1, 0},
		{0, 0, 1}, {0, 0, -1}
	};

	private final Path dbPath;
	private final CommonLogger log;
	private final Connection connection;
	private final BlockingQueue<WriteCommand> writeQueue = new LinkedBlockingQueue<>();
	private final Object writerStateLock = new Object();
	private final Thread writerThread;
	private boolean acceptingWrites = true;
	private boolean writerStarted = false;
	private volatile boolean closed = false;
	private volatile boolean structureAuditEnabled = true;

	private BuildingIndexStore(Path dbPath, CommonLogger log, Connection connection) {
		this.dbPath = dbPath;
		this.log = log;
		this.connection = connection;
		this.writerThread = new Thread(this::runWriter, "beilin-portability-sqlite-writer");
		this.writerThread.setDaemon(true);
	}

	public static BuildingIndexStore open(Path dbPath, CommonLogger log) throws IOException {
		try {
			Class.forName("org.sqlite.JDBC");
			Path parent = dbPath.getParent();
			if (parent != null) Files.createDirectories(parent);
			Connection connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
			BuildingIndexStore store = new BuildingIndexStore(dbPath, log, connection);
			store.initializeSchema();
			store.startWriter();
			log.info("Beilin Data Portability opened cuboid region index {} with {} region(s)", dbPath, store.size());
			return store;
		} catch (ReflectiveOperationException | SQLException e) {
			throw new IOException("Failed to open portability SQLite index " + dbPath, e);
		}
	}

	public int size() {
		flushPendingWrites();
		synchronized (this) {
			return countRegions();
		}
	}

	public void close() {
		synchronized (writerStateLock) {
			if (!acceptingWrites) return;
			acceptingWrites = false;
			putWriterCommand(StopCommand.INSTANCE);
		}
		if (Thread.currentThread() != writerThread) {
			try {
				writerThread.join(TimeUnit.SECONDS.toMillis(WRITER_STOP_TIMEOUT_SECONDS));
				if (writerThread.isAlive()) {
					writerThread.interrupt();
					log.warn("Timed out while draining Beilin portability SQLite writes during close");
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				writerThread.interrupt();
			}
		}
		synchronized (this) {
			closed = true;
			try {
				connection.close();
			} catch (SQLException ignored) {
			}
		}
	}

	public void flushPendingWrites() {
		if (Thread.currentThread() == writerThread) return;
		BarrierCommand barrier = new BarrierCommand();
		synchronized (writerStateLock) {
			if (!acceptingWrites || !writerStarted) return;
			putWriterCommand(barrier);
		}
		try {
			barrier.await();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	public void checkpoint() throws SQLException {
		flushPendingWrites();
		synchronized (this) {
			try (Statement stmt = connection.createStatement()) {
				stmt.execute("PRAGMA wal_checkpoint(TRUNCATE)");
			}
		}
	}

	public void compact() throws SQLException {
		flushPendingWrites();
		synchronized (this) {
			try (Statement stmt = connection.createStatement()) {
				stmt.execute("VACUUM");
			}
		}
	}

	public String diagnosticSummary() {
		flushPendingWrites();
		synchronized (this) {
			return "db=" + dbPath
				+ ", indexed_regions=" + countRegions()
				+ ", structure_audit=" + (structureAuditEnabled ? "enabled" : "disabled")
				+ ", pending_structure_audit=" + countPendingStructureAuditEvents()
				+ ", schema=v" + SCHEMA_VERSION;
		}
	}

	public synchronized void setStructureAuditEnabled(boolean enabled) {
		structureAuditEnabled = enabled;
	}

	private void startWriter() {
		synchronized (writerStateLock) {
			if (writerStarted) return;
			writerStarted = true;
			writerThread.start();
		}
	}

	private void enqueueStateChange(PendingChange change) {
		synchronized (writerStateLock) {
			if (!acceptingWrites || closed) return;
			writeQueue.offer(change);
		}
	}

	private void enqueueStagedBulkFinish(StagedBulkFinishCommand command) {
		if (command == null || command.operationId == null || command.operationId.isBlank()) return;
		synchronized (writerStateLock) {
			if (!acceptingWrites || closed) {
				deleteStagedBulkStateChangesNow(command.operationId);
				return;
			}
			writeQueue.offer(command);
		}
	}

	private void putWriterCommand(WriteCommand command) {
		boolean interrupted = false;
		while (true) {
			try {
				writeQueue.put(command);
				break;
			} catch (InterruptedException e) {
				interrupted = true;
			}
		}
		if (interrupted) Thread.currentThread().interrupt();
	}

	private void runWriter() {
		List<PendingChange> batch = new ArrayList<>(WRITE_BATCH_SIZE);
		WriteCommand deferred = null;
		try {
			while (true) {
				WriteCommand command = deferred != null ? deferred : writeQueue.take();
				deferred = null;
				if (command instanceof PendingChange first) {
					batch.clear();
					batch.add(first);
					long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WRITE_BATCH_WAIT_MILLIS);
					while (batch.size() < WRITE_BATCH_SIZE) {
						long remaining = deadline - System.nanoTime();
						if (remaining <= 0L) break;
						WriteCommand next = writeQueue.poll(remaining, TimeUnit.NANOSECONDS);
						if (next == null) break;
						if (next instanceof PendingChange change) {
							batch.add(change);
						} else {
							deferred = next;
							break;
						}
						}
						applyQueuedStateChanges(batch);
					} else if (command instanceof StagedBulkFinishCommand bulkFinish) {
						applyStagedBulkFinishCommand(bulkFinish);
					} else if (command instanceof BarrierCommand barrier) {
						barrier.complete();
					} else if (command == StopCommand.INSTANCE) {
					return;
				}
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} finally {
			WriteCommand remaining;
			while ((remaining = writeQueue.poll()) != null) {
				if (remaining instanceof BarrierCommand barrier) barrier.complete();
			}
		}
	}

	private void applyQueuedStateChanges(List<PendingChange> changes) {
		if (changes == null || changes.isEmpty()) return;
		synchronized (this) {
			if (closed) return;
			boolean originalAutoCommit = true;
			try {
				originalAutoCommit = connection.getAutoCommit();
				connection.setAutoCommit(false);
				String now = SqliteUtcDatetimes.now();
				Set<Long> affectedRegionIds = new HashSet<>();
				BulkMutationBuffer mutationBuffer = new BulkMutationBuffer();
				for (PendingChange change : changes) {
					long affectedRegionId = applyChange(change, now, mutationBuffer);
					if (affectedRegionId >= 0) affectedRegionIds.add(affectedRegionId);
				}
				flushBulkMutations(mutationBuffer, now);
				finishRegionMaintenance(affectedRegionIds);
				connection.commit();
			} catch (SQLException e) {
				try {
					connection.rollback();
				} catch (SQLException ignored) {
				}
				log.warn("Failed to apply {} queued portability block change(s): {}", changes.size(), e.toString());
			} finally {
				try {
					connection.setAutoCommit(originalAutoCommit);
				} catch (SQLException ignored) {
				}
			}
		}
	}

	private void applyStagedBulkFinishCommand(StagedBulkFinishCommand command) {
		synchronized (this) {
			if (closed) {
				deleteStagedBulkStateChangesNow(command.operationId);
				return;
			}
			if (command.auditOnly) {
				finishStagedBulkAuditOnlyNow(
					command.operationId,
					command.actorName,
					command.source,
					command.auditBounds,
					command.resultChangedBlockCount
				);
			} else {
				finishStagedBulkStateChangesNow(
					command.operationId,
					command.actorName,
					command.source,
					command.auditBounds,
					command.resultChangedBlockCount
				);
			}
		}
	}

	public void recordPlaced(
		String dimension,
		int x,
		int y,
		int z,
		String blockState,
		String actorName
	) {
		recordStateChangeWithSource(dimension, x, y, z, "minecraft:air", blockState, actorName, "PLAYER_EVENT");
	}

	public void recordRemoved(
		String dimension,
		int x,
		int y,
		int z,
		String actorName
	) {
		recordStateChangeWithSource(dimension, x, y, z, null, "minecraft:air", actorName, "PLAYER_EVENT");
	}

	public void recordStateChangeWithSource(
		String dimension,
		int x,
		int y,
		int z,
		String oldBlockState,
		String newBlockState,
		String actorName,
		String source
	) {
		recordStateChangeWithSource(
			dimension,
			x,
			y,
			z,
			oldBlockState,
			false,
			newBlockState,
			actorName,
			source
		);
	}

	public void recordStateChangeWithSource(
		String dimension,
		int x,
		int y,
		int z,
		String oldBlockState,
		boolean oldBlockReplaceable,
		String newBlockState,
		String actorName,
		String source
	) {
		if (newBlockState == null || newBlockState.isBlank()) return;
		String actor = displayActorName(actorName);
		if (!isPlayerActor(actor)) return;
		PendingChange change = new PendingChange(
			dimensionName(dimension),
			x,
			y,
			z,
			oldBlockState,
			newBlockState,
			actor,
			oldBlockReplaceable,
			false
		);
		if (!shouldApplyChange(change)) return;
		enqueueStateChange(change);
	}

	public void recordBulkStateChanges(List<BulkBlockChange> changes, String actorName, String source) {
		recordBulkStateChanges(changes, actorName, source, null, -1);
	}

	public void recordBulkAuditOnly(List<BulkBlockChange> changes, String actorName, String source) {
		recordBulkAuditOnly(changes, actorName, source, null, -1);
	}

	public void recordBulkAuditOnly(
		List<BulkBlockChange> changes,
		String actorName,
		String source,
		BulkPlacementBounds auditBounds,
		int resultChangedBlockCount
	) {
		flushPendingWrites();
		synchronized (this) {
			recordBulkAuditOnlyNow(changes, actorName, source, auditBounds, resultChangedBlockCount);
		}
	}

	public boolean stageBulkStateChanges(String operationId, List<BulkBlockChange> changes) {
		synchronized (this) {
			return stageBulkStateChangesNow(operationId, changes);
		}
	}

	public void discardStagedBulkStateChanges(String operationId) {
		synchronized (this) {
			deleteStagedBulkStateChangesNow(operationId);
		}
	}

	public void finishStagedBulkStateChanges(
		String operationId,
		String actorName,
		String source,
		BulkPlacementBounds auditBounds,
		int resultChangedBlockCount
	) {
		enqueueStagedBulkFinish(new StagedBulkFinishCommand(
			operationId,
			actorName,
			source,
			auditBounds,
			resultChangedBlockCount,
			isHistorySource(source)
		));
	}

	public void finishStagedBulkAuditOnly(
		String operationId,
		String actorName,
		String source,
		BulkPlacementBounds auditBounds,
		int resultChangedBlockCount
	) {
		enqueueStagedBulkFinish(new StagedBulkFinishCommand(
			operationId,
			actorName,
			source,
			auditBounds,
			resultChangedBlockCount,
			true
		));
	}

	public void recordBulkStateChanges(
		List<BulkBlockChange> changes,
		String actorName,
		String source,
		BulkPlacementBounds auditBounds,
		int resultChangedBlockCount
	) {
		flushPendingWrites();
		synchronized (this) {
			recordBulkStateChangesNow(changes, actorName, source, auditBounds, resultChangedBlockCount);
		}
	}

	private void recordBulkStateChangesNow(
		List<BulkBlockChange> changes,
		String actorName,
		String source,
		BulkPlacementBounds auditBounds,
		int resultChangedBlockCount
	) {
		if (closed) return;
		List<BulkBlockChange> sourceChanges = changes != null ? changes : List.of();
		if (sourceChanges.isEmpty() && auditBounds == null) return;
		String actor = displayActorName(actorName);
		if (!isPlayerActor(actor)) return;
		if (isHistorySource(source)) {
			recordBulkAuditOnlyNow(sourceChanges, actor, source, auditBounds, resultChangedBlockCount);
			return;
		}
		List<PendingChange> pending = new ArrayList<>();
		StructureAuditEvent.Accumulator capturedAudit = structureAuditEnabled && auditBounds == null
			? StructureAuditEvent.accumulator(actor, source)
			: null;
		Set<BlockCoordinate> seenCoordinates = new HashSet<>();
		for (BulkBlockChange change : sourceChanges) {
			if (change == null || change.newBlockState == null || change.newBlockState.isBlank()) continue;
			String dimension = dimensionName(change.dimension);
			if (!seenCoordinates.add(new BlockCoordinate(dimension, change.x, change.y, change.z))) continue;
			PendingChange pendingChange = new PendingChange(
				dimension,
				change.x,
				change.y,
				change.z,
				change.oldBlockState,
				change.newBlockState,
				actor,
				change.oldBlockReplaceable,
				change.forcePlacement
			);
			if (shouldApplyChange(pendingChange)) {
				pending.add(pendingChange);
				if (capturedAudit != null) {
					capturedAudit.include(dimension, change.x, change.y, change.z, change.newBlockState);
				}
			}
		}
		boolean originalAutoCommit = true;
		String now = SqliteUtcDatetimes.now();
		List<StructureAuditEvent> auditEvents = List.of();
		if (structureAuditEnabled && auditBounds != null) {
			int changedBlockCount = resultChangedBlockCount >= 0
				? resultChangedBlockCount
				: (pending.isEmpty() ? -1 : pending.size());
			StructureAuditEvent event = StructureAuditEvent.fromBounds(
				auditBounds,
				actor,
				source,
				auditChangeType(sourceChanges),
				changedBlockCount,
				now
			);
			auditEvents = event != null ? List.of(event) : List.of();
		} else if (capturedAudit != null) {
			auditEvents = capturedAudit.toEvents(now, resultChangedBlockCount);
		}
		if (pending.isEmpty() && auditEvents.isEmpty()) return;
		try {
			originalAutoCommit = connection.getAutoCommit();
			connection.setAutoCommit(false);
			Set<Long> affectedRegionIds = new HashSet<>();
			BulkMutationBuffer mutationBuffer = new BulkMutationBuffer();
			for (PendingChange change : pending) {
				long affectedRegionId = applyChange(change, now, mutationBuffer);
				if (affectedRegionId >= 0) affectedRegionIds.add(affectedRegionId);
			}
			flushBulkMutations(mutationBuffer, now);
			finishRegionMaintenance(affectedRegionIds);
			for (StructureAuditEvent event : auditEvents) {
				insertStructureAuditEvent(event);
			}
			connection.commit();
		} catch (SQLException e) {
			try {
				connection.rollback();
			} catch (SQLException ignored) {
			}
			log.warn("Failed to apply portability bulk block changes from {}: {}", source, e.toString());
		} finally {
			try {
				connection.setAutoCommit(originalAutoCommit);
			} catch (SQLException ignored) {
			}
		}
	}

	private boolean stageBulkStateChangesNow(String operationId, List<BulkBlockChange> changes) {
		if (closed || operationId == null || operationId.isBlank()) return false;
		if (changes == null || changes.isEmpty()) return true;
		boolean originalAutoCommit = true;
		try {
			ensureBulkStageTable();
			originalAutoCommit = connection.getAutoCommit();
			connection.setAutoCommit(false);
			try (PreparedStatement ps = connection.prepareStatement("""
				INSERT OR IGNORE INTO bulk_change_stage (
					operation_id, dimension, x, y, z,
					old_block_state, new_block_state, old_block_replaceable, force_placement
				) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
				""")) {
				int batched = 0;
				for (BulkBlockChange change : changes) {
					if (change == null || change.newBlockState == null || change.newBlockState.isBlank()) continue;
					ps.setString(1, operationId);
					ps.setString(2, dimensionName(change.dimension));
					ps.setInt(3, change.x);
					ps.setInt(4, change.y);
					ps.setInt(5, change.z);
					ps.setString(6, change.oldBlockState);
					ps.setString(7, change.newBlockState);
					ps.setInt(8, change.oldBlockReplaceable ? 1 : 0);
					ps.setInt(9, change.forcePlacement ? 1 : 0);
					ps.addBatch();
					if (++batched >= WRITE_BATCH_SIZE) {
						ps.executeBatch();
						ps.clearBatch();
						batched = 0;
					}
				}
				if (batched > 0) ps.executeBatch();
			}
			connection.commit();
			return true;
		} catch (SQLException e) {
			try {
				connection.rollback();
			} catch (SQLException ignored) {
			}
			log.warn("Failed to stage portability bulk block changes: {}", e.toString());
			return false;
		} finally {
			try {
				connection.setAutoCommit(originalAutoCommit);
			} catch (SQLException ignored) {
			}
		}
	}

	private void finishStagedBulkStateChangesNow(
		String operationId,
		String actorName,
		String source,
		BulkPlacementBounds auditBounds,
		int resultChangedBlockCount
	) {
		if (closed || operationId == null || operationId.isBlank()) return;
		String actor = displayActorName(actorName);
		if (!isPlayerActor(actor)) {
			deleteStagedBulkStateChangesNow(operationId);
			return;
		}
		boolean originalAutoCommit = true;
		String now = SqliteUtcDatetimes.now();
		try {
			ensureBulkStageTable();
			originalAutoCommit = connection.getAutoCommit();
			connection.setAutoCommit(false);
			long lastRowId = 0L;
			int appliedCount = 0;
			BulkAuditTally auditTally = new BulkAuditTally();
			StructureAuditEvent.Accumulator capturedAudit = structureAuditEnabled && auditBounds == null
				? StructureAuditEvent.accumulator(actor, source)
				: null;
			while (true) {
				List<StagedBulkChange> page = stagedBulkChangesPage(operationId, lastRowId);
				if (page.isEmpty()) break;
				Set<Long> affectedRegionIds = new HashSet<>();
					BulkMutationBuffer mutationBuffer = new BulkMutationBuffer();
					for (StagedBulkChange staged : page) {
						lastRowId = staged.rowId;
						auditTally.include(staged.newBlockState);
					PendingChange pendingChange = staged.toPendingChange(actor);
					if (shouldApplyChange(pendingChange)) {
						appliedCount += 1;
						if (capturedAudit != null) {
							capturedAudit.include(
								pendingChange.dimension,
								pendingChange.x,
								pendingChange.y,
								pendingChange.z,
								pendingChange.newBlockState
							);
						}
						long affectedRegionId = applyChange(pendingChange, now, mutationBuffer);
						if (affectedRegionId >= 0) affectedRegionIds.add(affectedRegionId);
					}
				}
				flushBulkMutations(mutationBuffer, now);
				finishRegionMaintenance(affectedRegionIds);
			}
			List<StructureAuditEvent> auditEvents = List.of();
			if (structureAuditEnabled && auditBounds != null) {
				int changedBlockCount = resultChangedBlockCount >= 0
					? resultChangedBlockCount
					: (appliedCount == 0 ? -1 : appliedCount);
				StructureAuditEvent event = StructureAuditEvent.fromBounds(
					auditBounds,
					actor,
					source,
					auditTally.changeType(),
					changedBlockCount,
					now
				);
				auditEvents = event != null ? List.of(event) : List.of();
			} else if (capturedAudit != null) {
				auditEvents = capturedAudit.toEvents(now, resultChangedBlockCount);
			}
			for (StructureAuditEvent event : auditEvents) {
				insertStructureAuditEvent(event);
			}
			deleteStagedBulkStateChangesInTransaction(operationId);
			connection.commit();
		} catch (SQLException e) {
			try {
				connection.rollback();
			} catch (SQLException ignored) {
			}
			log.warn("Failed to apply staged portability bulk block changes from {}: {}", source, e.toString());
		} finally {
			try {
				connection.setAutoCommit(originalAutoCommit);
			} catch (SQLException ignored) {
			}
		}
	}

	private void finishStagedBulkAuditOnlyNow(
		String operationId,
		String actorName,
		String source,
		BulkPlacementBounds auditBounds,
		int resultChangedBlockCount
	) {
		if (operationId == null || operationId.isBlank()) return;
		if (closed || !structureAuditEnabled) {
			deleteStagedBulkStateChangesNow(operationId);
			return;
		}
		String actor = displayActorName(actorName);
		if (!isPlayerActor(actor)) {
			deleteStagedBulkStateChangesNow(operationId);
			return;
		}
		boolean originalAutoCommit = true;
		String now = SqliteUtcDatetimes.now();
		try {
			ensureBulkStageTable();
			originalAutoCommit = connection.getAutoCommit();
			connection.setAutoCommit(false);
			long lastRowId = 0L;
			int stagedCount = 0;
			BulkAuditTally auditTally = new BulkAuditTally();
			StructureAuditEvent.Accumulator accumulator = auditBounds == null
				? StructureAuditEvent.accumulator(actor, source)
				: null;
			while (true) {
				List<StagedBulkChange> page = stagedBulkChangesPage(operationId, lastRowId);
				if (page.isEmpty()) break;
				for (StagedBulkChange staged : page) {
					lastRowId = staged.rowId;
					stagedCount += 1;
					auditTally.include(staged.newBlockState);
					if (accumulator != null) {
						accumulator.include(staged.dimension, staged.x, staged.y, staged.z, staged.newBlockState);
					}
				}
			}
			List<StructureAuditEvent> auditEvents;
			if (auditBounds != null) {
				int changedBlockCount = resultChangedBlockCount >= 0
					? resultChangedBlockCount
					: (stagedCount == 0 ? -1 : stagedCount);
				StructureAuditEvent event = StructureAuditEvent.fromBounds(
					auditBounds,
					actor,
					source,
					auditTally.changeType(),
					changedBlockCount,
					now
				);
				auditEvents = event != null ? List.of(event) : List.of();
			} else {
				auditEvents = accumulator != null ? accumulator.toEvents(now, resultChangedBlockCount) : List.of();
			}
			for (StructureAuditEvent event : auditEvents) {
				insertStructureAuditEvent(event);
			}
			deleteStagedBulkStateChangesInTransaction(operationId);
			connection.commit();
		} catch (SQLException e) {
			try {
				connection.rollback();
			} catch (SQLException ignored) {
			}
			log.warn("Failed to record staged portability bulk audit from {}: {}", source, e.toString());
		} finally {
			try {
				connection.setAutoCommit(originalAutoCommit);
			} catch (SQLException ignored) {
			}
		}
	}

	private void recordBulkAuditOnlyNow(
		List<BulkBlockChange> changes,
		String actorName,
		String source,
		BulkPlacementBounds auditBounds,
		int resultChangedBlockCount
	) {
		if (closed || !structureAuditEnabled) return;
		List<BulkBlockChange> sourceChanges = changes != null ? changes : List.of();
		if (sourceChanges.isEmpty() && auditBounds == null) return;
		String actor = displayActorName(actorName);
		if (!isPlayerActor(actor)) return;
		String now = SqliteUtcDatetimes.now();
		List<StructureAuditEvent> auditEvents = List.of();
		if (auditBounds != null) {
			int changedBlockCount = resultChangedBlockCount >= 0
				? resultChangedBlockCount
				: (sourceChanges.isEmpty() ? -1 : sourceChanges.size());
			StructureAuditEvent event = StructureAuditEvent.fromBounds(
				auditBounds,
				actor,
				source,
				auditChangeType(sourceChanges),
				changedBlockCount,
				now
			);
			auditEvents = event != null ? List.of(event) : List.of();
		} else {
			StructureAuditEvent.Accumulator accumulator = StructureAuditEvent.accumulator(actor, source);
			Set<BlockCoordinate> seenCoordinates = new HashSet<>();
			for (BulkBlockChange change : sourceChanges) {
				if (change == null || change.newBlockState == null || change.newBlockState.isBlank()) continue;
				String dimension = dimensionName(change.dimension);
				if (!seenCoordinates.add(new BlockCoordinate(dimension, change.x, change.y, change.z))) continue;
				accumulator.include(dimension, change.x, change.y, change.z, change.newBlockState);
			}
			auditEvents = accumulator.toEvents(now, resultChangedBlockCount);
		}
		if (auditEvents.isEmpty()) return;
		boolean originalAutoCommit = true;
		try {
			originalAutoCommit = connection.getAutoCommit();
			connection.setAutoCommit(false);
			for (StructureAuditEvent event : auditEvents) {
				insertStructureAuditEvent(event);
			}
			connection.commit();
		} catch (SQLException e) {
			try {
				connection.rollback();
			} catch (SQLException ignored) {
			}
			log.warn("Failed to record portability bulk audit from {}: {}", source, e.toString());
		} finally {
			try {
				connection.setAutoCommit(originalAutoCommit);
			} catch (SQLException ignored) {
			}
		}
	}

	private static String auditChangeType(List<BulkBlockChange> changes) {
		boolean hasPlace = false;
		boolean hasDelete = false;
		if (changes != null) {
			for (BulkBlockChange change : changes) {
				if (change == null || change.newBlockState == null || change.newBlockState.isBlank()) continue;
				if (isAirState(change.newBlockState)) hasDelete = true;
				else hasPlace = true;
				if (hasPlace && hasDelete) return "mixed";
			}
		}
		if (hasDelete) return "delete";
		if (hasPlace) return "place";
		return "mixed";
	}

	public boolean tryRecordCompleteBoundsPlacement(
		BulkPlacementBounds bounds,
		String actorName,
		String source,
		int changedBlockCount
	) {
		flushPendingWrites();
		synchronized (this) {
			return tryRecordCompleteBoundsPlacementNow(bounds, actorName, source, changedBlockCount);
		}
	}

	private boolean tryRecordCompleteBoundsPlacementNow(
		BulkPlacementBounds bounds,
		String actorName,
		String source,
		int changedBlockCount
	) {
		if (closed || bounds == null) return false;
		String actor = displayActorName(actorName);
		if (!isPlayerActor(actor)) return false;
		int volume = bounds.volumeBlockCount();
		if (volume <= 0) return false;
		if (changedBlockCount != volume) return false;
		Bounds placedBounds = new Bounds(bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ);
		if (isLinearInfrastructure(placedBounds, volume)) return false;

		boolean originalAutoCommit = true;
		String dimension = dimensionName(bounds.dimension);
		String now = SqliteUtcDatetimes.now();
		try {
			originalAutoCommit = connection.getAutoCommit();
			List<Region> overlapping = regionsOverlappingBounds(dimension, placedBounds, 0);
			Bounds merged = placedBounds;
			int estimatedNonAir = volume;
			for (Region region : overlapping) {
				merged = merged.merge(region);
				estimatedNonAir = saturatingAdd(estimatedNonAir, Math.max(0, region.nonAirCount));
			}
			if (isLinearInfrastructure(merged, estimatedNonAir)) {
				return false;
			}

			connection.setAutoCommit(false);
			long targetRegionId;
			if (overlapping.isEmpty()) {
				targetRegionId = insertRegion(dimension, merged, now);
			} else {
				targetRegionId = overlapping.get(0).id;
				updateRegionBounds(targetRegionId, merged, now);
				for (int i = 1; i < overlapping.size(); i++) {
					Region sourceRegion = overlapping.get(i);
					if (sourceRegion.id != targetRegionId) {
						mergeRegionInto(targetRegionId, sourceRegion.id, merged);
					}
				}
			}

			insertCompleteBoundsBlocks(targetRegionId, actor, dimension, placedBounds, now);
			upsertAuthor(targetRegionId, actor, volume, volume, now);
			mergeCompatibleRegions(targetRegionId);
			refreshAuthorRatios(targetRegionId);
			refreshRiskFlags(targetRegionId);
			if (structureAuditEnabled) {
				StructureAuditEvent event = StructureAuditEvent.fromBounds(
					bounds,
					actor,
					source,
					"place",
					changedBlockCount,
					now
				);
				if (event != null) insertStructureAuditEvent(event);
			}
			connection.commit();
			return true;
		} catch (SQLException e) {
			try {
				connection.rollback();
			} catch (SQLException ignored) {
			}
			log.warn("Failed to apply complete bounds placement from {}: {}", source, e.toString());
			return false;
		} finally {
			try {
				connection.setAutoCommit(originalAutoCommit);
			} catch (SQLException ignored) {
			}
		}
	}

	public void deleteIndexedBlocksInBounds(BulkPlacementBounds bounds, String actorName, String source) {
		deleteIndexedBlocksInBounds(bounds, actorName, source, "delete");
	}

	public void deleteIndexedBlocksInBounds(BulkPlacementBounds bounds, String actorName, String source, String changeType) {
		deleteIndexedBlocksInBounds(bounds, actorName, source, changeType, -1);
	}

	public void deleteIndexedBlocksInBounds(
		BulkPlacementBounds bounds,
		String actorName,
		String source,
		String changeType,
		int auditChangedBlockCount
	) {
		flushPendingWrites();
		synchronized (this) {
			deleteIndexedBlocksInBoundsNow(bounds, actorName, source, changeType, auditChangedBlockCount);
		}
	}

	private void deleteIndexedBlocksInBoundsNow(
		BulkPlacementBounds bounds,
		String actorName,
		String source,
		String changeType,
		int auditChangedBlockCount
	) {
		if (closed || bounds == null) return;
		String actor = displayActorName(actorName);
		if (!isPlayerActor(actor)) return;
		boolean originalAutoCommit = true;
		String dimension = dimensionName(bounds.dimension);
		String now = SqliteUtcDatetimes.now();
		try {
			originalAutoCommit = connection.getAutoCommit();
			connection.setAutoCommit(false);
			Map<Long, Integer> affected = indexedBlockCountsInBounds(bounds, dimension);
			if (!affected.isEmpty()) {
				deletePlacedBlocksInBounds(bounds, dimension);
				for (Map.Entry<Long, Integer> entry : affected.entrySet()) {
					long regionId = entry.getKey();
					int deletedBlocks = Math.max(1, entry.getValue());
					upsertAuthor(regionId, actor, 0, deletedBlocks, now);
					touchRegion(regionId, now);
					refreshAuthorRatios(regionId);
					refreshRiskFlags(regionId);
				}
			}
			if (structureAuditEnabled) {
				int reportedChangedBlocks = auditChangedBlockCount >= 0
					? auditChangedBlockCount
					: -1;
				StructureAuditEvent auditEvent = StructureAuditEvent.fromBounds(
					bounds,
					actor,
					source,
					changeType,
					reportedChangedBlocks,
					now
				);
				if (auditEvent != null) {
					insertStructureAuditEvent(auditEvent);
				}
			}
			connection.commit();
		} catch (SQLException e) {
			try {
				connection.rollback();
			} catch (SQLException ignored) {
			}
			log.warn("Failed to delete portability indexed block bounds from {}: {}", source, e.toString());
		} finally {
			try {
				connection.setAutoCommit(originalAutoCommit);
			} catch (SQLException ignored) {
			}
		}
	}

	public ExportManifest buildManifest(ExportJob job, WorldBlockReader reader, int maxExportVolumeBlocks) throws IOException {
		return buildExportBundle(job, reader, maxExportVolumeBlocks).manifest;
	}

	public List<StructureAuditEvent> listPendingStructureAuditEvents(int limit) {
		flushPendingWrites();
		synchronized (this) {
			return listPendingStructureAuditEventsNow(limit);
		}
	}

	private List<StructureAuditEvent> listPendingStructureAuditEventsNow(int limit) {
		if (closed) return List.of();
		int safeLimit = Math.max(1, Math.min(200, limit));
		try (PreparedStatement ps = connection.prepareStatement("""
			SELECT * FROM structure_audit_outbox
			ORDER BY created_at ASC, event_id ASC
			LIMIT ?
			""")) {
			ps.setInt(1, safeLimit);
			try (ResultSet rs = ps.executeQuery()) {
				List<StructureAuditEvent> out = new ArrayList<>();
				while (rs.next()) out.add(StructureAuditEvent.fromResultSet(rs));
				return out;
			}
		} catch (SQLException e) {
			log.warn("Failed to list pending structure audit events: {}", e.toString());
			return List.of();
		}
	}

	public void deleteStructureAuditOutboxEvents(List<String> eventIds) {
		flushPendingWrites();
		synchronized (this) {
			deleteStructureAuditOutboxEventsNow(eventIds);
		}
	}

	private void deleteStructureAuditOutboxEventsNow(List<String> eventIds) {
		if (closed || eventIds == null || eventIds.isEmpty()) return;
		try (PreparedStatement ps = connection.prepareStatement(
			"DELETE FROM structure_audit_outbox WHERE event_id = ?"
		)) {
			for (String id : eventIds) {
				if (id == null || id.isBlank()) continue;
				ps.setString(1, id.trim());
				ps.addBatch();
			}
			ps.executeBatch();
		} catch (SQLException e) {
			log.warn("Failed to delete acked structure audit events: {}", e.toString());
		}
	}

	public ExportBundle buildExportBundle(ExportJob job, WorldBlockReader reader, int maxExportVolumeBlocks) throws IOException {
		flushPendingWrites();
		String target = normalizeName(job.minecraftUsername);
		List<RegionExportPlan> plans;
		int indexedRegionCount;
		synchronized (this) {
			try {
				List<RegionExportPlan> regions = regionsForAuthor(target);
				plans = new ArrayList<>(regions.size());
				plans.addAll(regions);
				indexedRegionCount = countRegions();
			} catch (SQLException e) {
				throw new IOException("Failed to read portability export regions", e);
			}
		}
		List<ComponentExport> exports = new ArrayList<>();
		int componentIndex = 1;
		for (RegionExportPlan region : plans) {
			ensureNotInterrupted();
			List<BlockCoordinate> placedCoordinates;
			synchronized (this) {
				try {
					placedCoordinates = placedBlocksForRegion(region.id);
				} catch (SQLException e) {
					throw new IOException("Failed to read portability placed blocks", e);
				}
			}
			if (placedCoordinates.isEmpty()) {
				synchronized (this) {
					markRegionScanned(region.id, 0, safeVolume(region), "low_density");
				}
				continue;
			}
			int regionVolume = safeVolume(region);
			if (regionVolume > maxExportVolumeBlocks) {
				throw new IOException("export cuboid is too large: " + regionVolume + " > " + maxExportVolumeBlocks);
			}
			ensureNotInterrupted();
			List<BlockRecord> ownedBlocks = reader.readCoordinates(region.dimension, placedCoordinates, maxExportVolumeBlocks);
			List<List<BlockRecord>> groups = compactGroups(ownedBlocks, connectedGroups(ownedBlocks));
			if (groups.isEmpty()) {
				synchronized (this) {
					markRegionScanned(region.id, 0, safeVolume(region), "low_density");
				}
				continue;
			}
			int regionNonAir = 0;
			for (List<BlockRecord> group : groups) {
				ensureNotInterrupted();
				Bounds bounds = Bounds.from(group);
				List<BlockRecord> cuboidBlocks = blocksWithin(ownedBlocks, bounds);
				int nonAir = cuboidBlocks.size();
				regionNonAir += nonAir;
				if (nonAir < MIN_EXPORT_COMPONENT_BLOCKS) {
					continue;
				}
				String risks = riskFlags(bounds, nonAir, region.authorCount, region.targetRatioBp);
				String filename = String.format(
					Locale.ROOT,
					"%03d_%s_x%d_y%d_z%d.litematic",
					componentIndex,
					safeDimension(region.dimension),
					bounds.minX,
					bounds.minY,
					bounds.minZ
				);
				ComponentSummary summary = new ComponentSummary(
					componentIndex,
					region.id,
					region.dimension,
					bounds.minX, bounds.minY, bounds.minZ,
					bounds.maxX, bounds.maxY, bounds.maxZ,
					safeVolume(bounds),
					nonAir,
					region.targetRatioBp,
					region.targetLastTouchedAt,
					region.authorCount,
					risks,
					filename
				);
				exports.add(new ComponentExport(summary, cuboidBlocks));
				componentIndex += 1;
			}
			synchronized (this) {
				markRegionScanned(region.id, regionNonAir, safeVolume(region), null);
			}
		}
		ExportManifest manifest = new ExportManifest(
			job.requestId,
			job.minecraftUsername,
			SqliteUtcDatetimes.now(),
			indexedRegionCount,
			exports.stream().map(e -> e.summary).toList()
		);
		return new ExportBundle(manifest, exports);
	}

	private long applyChange(PendingChange change, String now, BulkMutationBuffer mutationBuffer) throws SQLException {
		String actorName = displayActorName(change.actorName);
		boolean playerActor = isPlayerActor(actorName);
		boolean newAir = isAirState(change.newBlockState);
		boolean firstPlacement = isFirstPlacement(change, newAir);
		boolean playerCanExpandRegion = playerActor && firstPlacement;
		Region region = findCandidateRegion(change.dimension, change.x, change.y, change.z, playerCanExpandRegion);

		if (region == null && firstPlacement && playerActor) {
			long id = insertRegion(change.dimension, change.x, change.y, change.z, now);
			region = findRegion(id);
		}

		if (region == null) return -1L;

		Bounds next = playerCanExpandRegion ? region.include(change.x, change.y, change.z) : region;
		if (!newAir && !next.equalsBounds(region)) {
			updateRegionBounds(region.id, next, now);
			region = findRegion(region.id);
		} else {
			touchRegion(region.id, now);
		}

		if (playerActor) {
			if (mutationBuffer != null) {
				mutationBuffer.addAuthor(region.id, actorName, firstPlacement ? 1 : 0, 1);
				if (firstPlacement) {
					mutationBuffer.addPlacement(region.id, actorName, change.dimension, change.x, change.y, change.z);
				} else if (newAir) {
					mutationBuffer.addDeletion(region.id, actorName, change.dimension, change.x, change.y, change.z);
				}
			} else {
				upsertAuthor(region.id, actorName, firstPlacement ? 1 : 0, 1, now);
				if (firstPlacement) {
					upsertPlacedBlock(region.id, actorName, change.dimension, change.x, change.y, change.z, now);
				} else if (newAir) {
					deletePlacedBlock(region.id, change.dimension, change.x, change.y, change.z);
				}
			}
		}

		return region.id;
	}

	private void finishRegionMaintenance(Set<Long> affectedRegionIds) throws SQLException {
		if (affectedRegionIds == null || affectedRegionIds.isEmpty()) return;
		Set<Long> survivingRegionIds = new HashSet<>();
		for (Long regionId : affectedRegionIds) {
			if (regionId == null || findRegion(regionId) == null) continue;
			mergeCompatibleRegions(regionId);
			if (findRegion(regionId) != null) survivingRegionIds.add(regionId);
		}
		for (Long regionId : survivingRegionIds) {
			refreshAuthorRatios(regionId);
			refreshRiskFlags(regionId);
		}
	}

	private void flushBulkMutations(BulkMutationBuffer buffer, String now) throws SQLException {
		if (buffer == null) return;
		if (!buffer.authorDeltas.isEmpty()) {
			try (PreparedStatement ps = connection.prepareStatement("""
				INSERT INTO region_authors (
					region_id, player_name_key, display_name,
					first_place_count, last_modify_count, contribution_score,
					ratio_bp, last_touched_at
				) VALUES (?, ?, ?, ?, ?, ?, 0, ?)
				ON CONFLICT(region_id, player_name_key) DO UPDATE SET
					display_name = excluded.display_name,
					first_place_count = first_place_count + excluded.first_place_count,
					last_modify_count = last_modify_count + excluded.last_modify_count,
					contribution_score = contribution_score + excluded.contribution_score,
					last_touched_at = excluded.last_touched_at
				""")) {
				int batched = 0;
				for (Map.Entry<AuthorMutationKey, AuthorDelta> entry : buffer.authorDeltas.entrySet()) {
					AuthorMutationKey key = entry.getKey();
					AuthorDelta delta = entry.getValue();
					ps.setLong(1, key.regionId);
					ps.setString(2, key.playerNameKey);
					ps.setString(3, delta.displayName);
					ps.setInt(4, Math.max(0, delta.firstPlaceCount));
					ps.setInt(5, Math.max(0, delta.lastModifyCount));
					ps.setInt(6, Math.max(0, delta.firstPlaceCount) + Math.max(0, delta.lastModifyCount));
					ps.setString(7, now);
					ps.addBatch();
					if (++batched >= WRITE_BATCH_SIZE) {
						ps.executeBatch();
						ps.clearBatch();
						batched = 0;
					}
				}
				if (batched > 0) ps.executeBatch();
			}
		}
		if (!buffer.mutations.isEmpty()) {
			try (
				PreparedStatement placement = connection.prepareStatement("""
					INSERT INTO region_blocks (
						region_id, player_name_key, dimension, x, y, z, first_placed_at, last_touched_at
					) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
					ON CONFLICT(region_id, player_name_key, dimension, x, y, z) DO UPDATE SET
						last_touched_at = excluded.last_touched_at
					""");
				PreparedStatement deletion = connection.prepareStatement("""
					DELETE FROM region_blocks
					WHERE region_id = ? AND dimension = ? AND x = ? AND y = ? AND z = ?
					""")
			) {
				Boolean placementRun = null;
				int batched = 0;
				for (BlockMutation mutation : buffer.mutations) {
					if (placementRun == null || placementRun != mutation.placement || batched >= WRITE_BATCH_SIZE) {
						if (placementRun != null && batched > 0) {
							PreparedStatement current = placementRun ? placement : deletion;
							current.executeBatch();
							current.clearBatch();
						}
						placementRun = mutation.placement;
						batched = 0;
					}
					if (mutation.placement) {
						placement.setLong(1, mutation.regionId);
						placement.setString(2, normalizeName(mutation.actorName));
						placement.setString(3, dimensionName(mutation.dimension));
						placement.setInt(4, mutation.x);
						placement.setInt(5, mutation.y);
						placement.setInt(6, mutation.z);
						placement.setString(7, now);
						placement.setString(8, now);
						placement.addBatch();
					} else {
						deletion.setLong(1, mutation.regionId);
						deletion.setString(2, dimensionName(mutation.dimension));
						deletion.setInt(3, mutation.x);
						deletion.setInt(4, mutation.y);
						deletion.setInt(5, mutation.z);
						deletion.addBatch();
					}
					batched += 1;
				}
				if (placementRun != null && batched > 0) {
					(placementRun ? placement : deletion).executeBatch();
				}
			}
		}
	}

	private static boolean shouldApplyChange(PendingChange change) {
		boolean newAir = isAirState(change.newBlockState);
		return newAir || isFirstPlacement(change, false);
	}

	private static boolean isFirstPlacement(PendingChange change, boolean newAir) {
		return !newAir
			&& (isAirState(change.oldBlockState) || change.oldBlockReplaceable || change.forcePlacement);
	}

	private void initializeSchema() throws SQLException {
		try (Statement stmt = connection.createStatement()) {
			stmt.execute("PRAGMA journal_mode=WAL");
			stmt.execute("PRAGMA synchronous=NORMAL");
			stmt.execute("PRAGMA foreign_keys=ON");
		}
		createSchema();
		normalizeStoredDatetimes();
		try (Statement stmt = connection.createStatement()) {
			stmt.execute("PRAGMA user_version=" + SCHEMA_VERSION);
		}
	}

	private void createSchema() throws SQLException {
		try (Statement stmt = connection.createStatement()) {
			stmt.execute("""
				CREATE TABLE IF NOT EXISTS building_regions (
					id INTEGER PRIMARY KEY AUTOINCREMENT,
					dimension TEXT NOT NULL,
					min_x INTEGER NOT NULL,
					min_y INTEGER NOT NULL,
					min_z INTEGER NOT NULL,
					max_x INTEGER NOT NULL,
					max_y INTEGER NOT NULL,
					max_z INTEGER NOT NULL,
					volume_blocks INTEGER NOT NULL DEFAULT 1,
					non_air_count INTEGER NOT NULL DEFAULT 0,
					density_bp INTEGER NOT NULL DEFAULT 0,
					status TEXT NOT NULL DEFAULT 'active',
					risk_flags TEXT,
					last_touched_at TEXT,
					last_scanned_at TEXT,
					dirty INTEGER NOT NULL DEFAULT 1,
					created_at TEXT NOT NULL,
					updated_at TEXT NOT NULL
				)
				""");
			stmt.execute("CREATE INDEX IF NOT EXISTS idx_building_regions_dimension_box ON building_regions(dimension, min_x, max_x, min_z, max_z)");
			stmt.execute("CREATE INDEX IF NOT EXISTS idx_building_regions_status ON building_regions(status, dirty)");
			stmt.execute("""
				CREATE TABLE IF NOT EXISTS region_authors (
					region_id INTEGER NOT NULL REFERENCES building_regions(id) ON DELETE CASCADE,
					player_name_key TEXT NOT NULL,
					display_name TEXT NOT NULL,
					first_place_count INTEGER NOT NULL DEFAULT 0,
					last_modify_count INTEGER NOT NULL DEFAULT 0,
					contribution_score INTEGER NOT NULL DEFAULT 0,
					ratio_bp INTEGER NOT NULL DEFAULT 0,
					last_touched_at TEXT,
					PRIMARY KEY (region_id, player_name_key)
				)
				""");
			stmt.execute("CREATE INDEX IF NOT EXISTS idx_region_authors_player ON region_authors(player_name_key, last_touched_at)");
			stmt.execute("""
				CREATE TABLE IF NOT EXISTS region_blocks (
					region_id INTEGER NOT NULL REFERENCES building_regions(id) ON DELETE CASCADE,
					player_name_key TEXT NOT NULL,
					dimension TEXT NOT NULL,
					x INTEGER NOT NULL,
					y INTEGER NOT NULL,
					z INTEGER NOT NULL,
					first_placed_at TEXT NOT NULL,
					last_touched_at TEXT NOT NULL,
					PRIMARY KEY (region_id, player_name_key, dimension, x, y, z)
				)
				""");
			stmt.execute("CREATE INDEX IF NOT EXISTS idx_region_blocks_region ON region_blocks(region_id)");
			stmt.execute("CREATE INDEX IF NOT EXISTS idx_region_blocks_dimension_xyz_region ON region_blocks(dimension, x, y, z, region_id)");
			stmt.execute("""
				CREATE TABLE IF NOT EXISTS structure_audit_outbox (
					event_id TEXT PRIMARY KEY,
					actor_name TEXT NOT NULL,
					tool TEXT NOT NULL,
					operation TEXT NOT NULL,
					source TEXT NOT NULL,
					change_type TEXT NOT NULL,
					dimension TEXT NOT NULL,
					min_x INTEGER NOT NULL,
					min_y INTEGER NOT NULL,
					min_z INTEGER NOT NULL,
					max_x INTEGER NOT NULL,
					max_y INTEGER NOT NULL,
					max_z INTEGER NOT NULL,
					changed_block_count INTEGER NOT NULL,
					bounds_block_count INTEGER NOT NULL,
					recorded_at TEXT NOT NULL,
					created_at TEXT NOT NULL
				)
				""");
			stmt.execute("CREATE INDEX IF NOT EXISTS idx_structure_audit_outbox_created ON structure_audit_outbox(created_at)");
		}
	}

	private void normalizeStoredDatetimes() throws SQLException {
		normalizeDatetimeColumn("building_regions", "last_touched_at");
		normalizeDatetimeColumn("building_regions", "last_scanned_at");
		normalizeDatetimeColumn("building_regions", "created_at");
		normalizeDatetimeColumn("building_regions", "updated_at");
		normalizeDatetimeColumn("region_authors", "last_touched_at");
		normalizeDatetimeColumn("region_blocks", "first_placed_at");
		normalizeDatetimeColumn("region_blocks", "last_touched_at");
		normalizeDatetimeColumn("structure_audit_outbox", "recorded_at");
		normalizeDatetimeColumn("structure_audit_outbox", "created_at");
	}

	private void insertStructureAuditEvent(StructureAuditEvent event) throws SQLException {
		if (event == null || "VANILLA_FILL".equalsIgnoreCase(event.source)) return;
		try (PreparedStatement ps = connection.prepareStatement("""
			INSERT OR IGNORE INTO structure_audit_outbox (
				event_id, actor_name, tool, operation, source, change_type, dimension,
				min_x, min_y, min_z, max_x, max_y, max_z,
				changed_block_count, bounds_block_count, recorded_at, created_at
			) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
			""")) {
			ps.setString(1, event.eventId);
			ps.setString(2, event.actorName);
			ps.setString(3, event.tool);
			ps.setString(4, event.operation);
			ps.setString(5, event.source);
			ps.setString(6, event.changeType);
			ps.setString(7, event.dimension);
			ps.setInt(8, event.minX);
			ps.setInt(9, event.minY);
			ps.setInt(10, event.minZ);
			ps.setInt(11, event.maxX);
			ps.setInt(12, event.maxY);
			ps.setInt(13, event.maxZ);
			ps.setInt(14, event.changedBlockCount);
			ps.setInt(15, event.boundsBlockCount);
			ps.setString(16, event.recordedAt);
			ps.setString(17, SqliteUtcDatetimes.now());
			ps.executeUpdate();
		}
	}

	private void normalizeDatetimeColumn(String table, String column) throws SQLException {
		if (!tableHasColumn(table, column)) return;
		try (Statement stmt = connection.createStatement()) {
			stmt.executeUpdate(
				"UPDATE " + table +
					" SET " + column + " = datetime(" + column + ")" +
					" WHERE " + column + " LIKE '%T%' AND datetime(" + column + ") IS NOT NULL"
			);
		}
	}

	private boolean tableHasColumn(String table, String column) throws SQLException {
		try (Statement stmt = connection.createStatement();
			 ResultSet rs = stmt.executeQuery("PRAGMA table_info(" + table + ")")) {
			while (rs.next()) {
				if (column.equals(rs.getString("name"))) return true;
			}
		}
		return false;
	}

	private long insertRegion(String dimension, int x, int y, int z, String now) throws SQLException {
		return insertRegion(dimension, new Bounds(x, y, z, x, y, z), now);
	}

	private long insertRegion(String dimension, Bounds bounds, String now) throws SQLException {
		try (PreparedStatement ps = connection.prepareStatement("""
			INSERT INTO building_regions (
				dimension, min_x, min_y, min_z, max_x, max_y, max_z,
				volume_blocks, status, last_touched_at, dirty, created_at, updated_at
			) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'active', ?, 1, ?, ?)
			""")) {
			ps.setString(1, dimension);
			ps.setInt(2, bounds.minX);
			ps.setInt(3, bounds.minY);
			ps.setInt(4, bounds.minZ);
			ps.setInt(5, bounds.maxX);
			ps.setInt(6, bounds.maxY);
			ps.setInt(7, bounds.maxZ);
			ps.setInt(8, safeVolume(bounds));
			ps.setString(9, now);
			ps.setString(10, now);
			ps.setString(11, now);
			ps.executeUpdate();
		}
		try (Statement stmt = connection.createStatement();
			 ResultSet rs = stmt.executeQuery("SELECT last_insert_rowid()")) {
			if (rs.next()) {
				long id = rs.getLong(1);
				return id;
			}
		}
		throw new SQLException("last_insert_rowid() returned no rows");
	}

	private void updateRegionBounds(long regionId, Bounds bounds, String now) throws SQLException {
		try (PreparedStatement ps = connection.prepareStatement("""
			UPDATE building_regions
			SET min_x = ?, min_y = ?, min_z = ?, max_x = ?, max_y = ?, max_z = ?,
			    volume_blocks = ?, last_touched_at = ?, updated_at = ?, dirty = 1
			WHERE id = ?
			""")) {
			ps.setInt(1, bounds.minX);
			ps.setInt(2, bounds.minY);
			ps.setInt(3, bounds.minZ);
			ps.setInt(4, bounds.maxX);
			ps.setInt(5, bounds.maxY);
			ps.setInt(6, bounds.maxZ);
			ps.setInt(7, safeVolume(bounds));
			ps.setString(8, now);
			ps.setString(9, now);
			ps.setLong(10, regionId);
			ps.executeUpdate();
		}
	}

	private void touchRegion(long regionId, String now) throws SQLException {
		try (PreparedStatement ps = connection.prepareStatement(
			"UPDATE building_regions SET last_touched_at = ?, updated_at = ?, dirty = 1 WHERE id = ?"
		)) {
			ps.setString(1, now);
			ps.setString(2, now);
			ps.setLong(3, regionId);
			ps.executeUpdate();
		}
	}

	private void upsertAuthor(long regionId, String actorName, int firstPlaceDelta, int lastModifyDelta, String now) throws SQLException {
		String key = normalizeName(actorName);
		if (key.isBlank() || !isPlayerActor(actorName)) return;
		try (PreparedStatement ps = connection.prepareStatement("""
			INSERT INTO region_authors (
				region_id, player_name_key, display_name,
				first_place_count, last_modify_count, contribution_score,
				ratio_bp, last_touched_at
			) VALUES (?, ?, ?, ?, ?, ?, 0, ?)
			ON CONFLICT(region_id, player_name_key) DO UPDATE SET
				display_name = excluded.display_name,
				first_place_count = first_place_count + excluded.first_place_count,
				last_modify_count = last_modify_count + excluded.last_modify_count,
				contribution_score = contribution_score + excluded.contribution_score,
				last_touched_at = excluded.last_touched_at
			""")) {
			ps.setLong(1, regionId);
			ps.setString(2, key);
			ps.setString(3, actorName.trim());
			ps.setInt(4, Math.max(0, firstPlaceDelta));
			ps.setInt(5, Math.max(0, lastModifyDelta));
			ps.setInt(6, Math.max(0, firstPlaceDelta) + Math.max(0, lastModifyDelta));
			ps.setString(7, now);
			ps.executeUpdate();
		}
	}

	private void upsertPlacedBlock(long regionId, String actorName, String dimension, int x, int y, int z, String now) throws SQLException {
		String key = normalizeName(actorName);
		if (key.isBlank()) return;
		try (PreparedStatement ps = connection.prepareStatement("""
			INSERT INTO region_blocks (
				region_id, player_name_key, dimension, x, y, z, first_placed_at, last_touched_at
			) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
			ON CONFLICT(region_id, player_name_key, dimension, x, y, z) DO UPDATE SET
				last_touched_at = excluded.last_touched_at
			""")) {
			ps.setLong(1, regionId);
			ps.setString(2, key);
			ps.setString(3, dimensionName(dimension));
			ps.setInt(4, x);
			ps.setInt(5, y);
			ps.setInt(6, z);
			ps.setString(7, now);
			ps.setString(8, now);
			ps.executeUpdate();
		}
	}

	private void insertCompleteBoundsBlocks(
		long regionId,
		String actorName,
		String dimension,
		Bounds bounds,
		String now
	) throws SQLException {
		String key = normalizeName(actorName);
		if (key.isBlank()) return;
		try (PreparedStatement ps = connection.prepareStatement("""
			WITH RECURSIVE
			x(v) AS (VALUES (?) UNION ALL SELECT v + 1 FROM x WHERE v < ?),
			y(v) AS (VALUES (?) UNION ALL SELECT v + 1 FROM y WHERE v < ?),
			z(v) AS (VALUES (?) UNION ALL SELECT v + 1 FROM z WHERE v < ?)
			INSERT INTO region_blocks (
				region_id, player_name_key, dimension, x, y, z, first_placed_at, last_touched_at
			)
			SELECT ?, ?, ?, x.v, y.v, z.v, ?, ?
			FROM x CROSS JOIN y CROSS JOIN z
			WHERE 1
			ON CONFLICT(region_id, player_name_key, dimension, x, y, z) DO UPDATE SET
				last_touched_at = excluded.last_touched_at
			""")) {
			ps.setInt(1, bounds.minX);
			ps.setInt(2, bounds.maxX);
			ps.setInt(3, bounds.minY);
			ps.setInt(4, bounds.maxY);
			ps.setInt(5, bounds.minZ);
			ps.setInt(6, bounds.maxZ);
			ps.setLong(7, regionId);
			ps.setString(8, key);
			ps.setString(9, dimensionName(dimension));
			ps.setString(10, now);
			ps.setString(11, now);
			ps.executeUpdate();
		}
	}

	private void deletePlacedBlock(long regionId, String dimension, int x, int y, int z) throws SQLException {
		try (PreparedStatement ps = connection.prepareStatement("""
			DELETE FROM region_blocks
			WHERE region_id = ? AND dimension = ? AND x = ? AND y = ? AND z = ?
			""")) {
			ps.setLong(1, regionId);
			ps.setString(2, dimensionName(dimension));
			ps.setInt(3, x);
			ps.setInt(4, y);
			ps.setInt(5, z);
			ps.executeUpdate();
		}
	}

	private Map<Long, Integer> indexedBlockCountsInBounds(BulkPlacementBounds bounds, String dimension) throws SQLException {
		Map<Long, Integer> affected = new HashMap<>();
		try (PreparedStatement ps = connection.prepareStatement("""
			SELECT region_id, COUNT(*) AS deleted_count
			FROM region_blocks
			WHERE dimension = ?
			  AND x BETWEEN ? AND ?
			  AND y BETWEEN ? AND ?
			  AND z BETWEEN ? AND ?
			GROUP BY region_id
			""")) {
			bindBounds(ps, dimension, bounds);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					affected.put(rs.getLong("region_id"), rs.getInt("deleted_count"));
				}
			}
		}
		return affected;
	}

	private void deletePlacedBlocksInBounds(BulkPlacementBounds bounds, String dimension) throws SQLException {
		try (PreparedStatement ps = connection.prepareStatement("""
			DELETE FROM region_blocks
			WHERE dimension = ?
			  AND x BETWEEN ? AND ?
			  AND y BETWEEN ? AND ?
			  AND z BETWEEN ? AND ?
			""")) {
			bindBounds(ps, dimension, bounds);
			ps.executeUpdate();
		}
	}

	private static void bindBounds(PreparedStatement ps, String dimension, BulkPlacementBounds bounds) throws SQLException {
		ps.setString(1, dimension);
		ps.setInt(2, bounds.minX);
		ps.setInt(3, bounds.maxX);
		ps.setInt(4, bounds.minY);
		ps.setInt(5, bounds.maxY);
		ps.setInt(6, bounds.minZ);
		ps.setInt(7, bounds.maxZ);
	}

	private Region findCandidateRegion(String dimension, int x, int y, int z, boolean expanding) throws SQLException {
		List<Region> candidates = regionsNear(dimension, x, y, z, expanding ? REGION_MERGE_GAP_BLOCKS : 0);
		candidates.sort(Comparator.comparingInt(r -> r.distanceTo(x, y, z)));
		for (Region region : candidates) {
			if (!expanding) return region;
			Bounds expanded = region.include(x, y, z);
			if (isLinearInfrastructure(expanded, Math.max(1, region.nonAirCount))) continue;
			return region;
		}
		return null;
	}

	private List<Region> regionsNear(String dimension, int x, int y, int z, int gap) throws SQLException {
		try (PreparedStatement ps = connection.prepareStatement("""
			SELECT * FROM building_regions
			WHERE dimension = ? AND status = 'active'
			  AND min_x <= ? AND max_x >= ?
			  AND min_y <= ? AND max_y >= ?
			  AND min_z <= ? AND max_z >= ?
			""")) {
			ps.setString(1, dimension);
			ps.setInt(2, x + gap);
			ps.setInt(3, x - gap);
			ps.setInt(4, y + gap);
			ps.setInt(5, y - gap);
			ps.setInt(6, z + gap);
			ps.setInt(7, z - gap);
			try (ResultSet rs = ps.executeQuery()) {
				List<Region> out = new ArrayList<>();
				while (rs.next()) out.add(region(rs));
				return out;
			}
		}
	}

	private List<Region> regionsOverlappingBounds(String dimension, Bounds bounds, int gap) throws SQLException {
		try (PreparedStatement ps = connection.prepareStatement("""
			SELECT * FROM building_regions
			WHERE dimension = ? AND status = 'active'
			  AND min_x <= ? AND max_x >= ?
			  AND min_y <= ? AND max_y >= ?
			  AND min_z <= ? AND max_z >= ?
			ORDER BY id ASC
			""")) {
			ps.setString(1, dimension);
			ps.setInt(2, bounds.maxX + gap);
			ps.setInt(3, bounds.minX - gap);
			ps.setInt(4, bounds.maxY + gap);
			ps.setInt(5, bounds.minY - gap);
			ps.setInt(6, bounds.maxZ + gap);
			ps.setInt(7, bounds.minZ - gap);
			try (ResultSet rs = ps.executeQuery()) {
				List<Region> out = new ArrayList<>();
				while (rs.next()) out.add(region(rs));
				return out;
			}
		}
	}

	private void mergeCompatibleRegions(long regionId) throws SQLException {
		Region base = findRegion(regionId);
		if (base == null) return;
		List<Region> others = overlappingRegions(base, REGION_MERGE_GAP_BLOCKS);
		for (Region other : others) {
			if (other.id == base.id) continue;
			Bounds merged = base.merge(other);
			if (isLinearInfrastructure(merged, base.nonAirCount + other.nonAirCount)) continue;
			mergeRegionInto(base.id, other.id, merged);
			base = findRegion(base.id);
			if (base == null) return;
		}
	}

	private List<Region> overlappingRegions(Region region, int gap) throws SQLException {
		try (PreparedStatement ps = connection.prepareStatement("""
			SELECT * FROM building_regions
			WHERE dimension = ? AND status = 'active'
			  AND min_x <= ? AND max_x >= ?
			  AND min_y <= ? AND max_y >= ?
			  AND min_z <= ? AND max_z >= ?
			""")) {
			ps.setString(1, region.dimension);
			ps.setInt(2, region.maxX + gap);
			ps.setInt(3, region.minX - gap);
			ps.setInt(4, region.maxY + gap);
			ps.setInt(5, region.minY - gap);
			ps.setInt(6, region.maxZ + gap);
			ps.setInt(7, region.minZ - gap);
			try (ResultSet rs = ps.executeQuery()) {
				List<Region> out = new ArrayList<>();
				while (rs.next()) out.add(region(rs));
				return out;
			}
		}
	}

	private void mergeRegionInto(long targetId, long sourceId, Bounds merged) throws SQLException {
		String now = SqliteUtcDatetimes.now();
		try (PreparedStatement ps = connection.prepareStatement("""
			UPDATE building_regions
			SET min_x = ?, min_y = ?, min_z = ?, max_x = ?, max_y = ?, max_z = ?,
			    volume_blocks = ?, dirty = 1, updated_at = ?
			WHERE id = ?
			""")) {
			ps.setInt(1, merged.minX);
			ps.setInt(2, merged.minY);
			ps.setInt(3, merged.minZ);
			ps.setInt(4, merged.maxX);
			ps.setInt(5, merged.maxY);
			ps.setInt(6, merged.maxZ);
			ps.setInt(7, safeVolume(merged));
			ps.setString(8, now);
			ps.setLong(9, targetId);
			ps.executeUpdate();
		}
		try (PreparedStatement ps = connection.prepareStatement("""
			INSERT INTO region_authors (
				region_id, player_name_key, display_name,
				first_place_count, last_modify_count, contribution_score,
				ratio_bp, last_touched_at
			)
			SELECT ?, player_name_key, display_name, first_place_count, last_modify_count,
			       contribution_score, ratio_bp, last_touched_at
			FROM region_authors WHERE region_id = ?
			ON CONFLICT(region_id, player_name_key) DO UPDATE SET
				first_place_count = first_place_count + excluded.first_place_count,
				last_modify_count = last_modify_count + excluded.last_modify_count,
				contribution_score = contribution_score + excluded.contribution_score,
				last_touched_at = CASE
					WHEN excluded.last_touched_at > last_touched_at THEN excluded.last_touched_at
					ELSE last_touched_at
				END
			""")) {
			ps.setLong(1, targetId);
			ps.setLong(2, sourceId);
			ps.executeUpdate();
		}
		try (PreparedStatement ps = connection.prepareStatement("""
			INSERT OR IGNORE INTO region_blocks (
				region_id, player_name_key, dimension, x, y, z, first_placed_at, last_touched_at
			)
			SELECT ?, player_name_key, dimension, x, y, z, first_placed_at, last_touched_at
			FROM region_blocks WHERE region_id = ?
			""")) {
			ps.setLong(1, targetId);
			ps.setLong(2, sourceId);
			ps.executeUpdate();
		}
		try (PreparedStatement ps = connection.prepareStatement("DELETE FROM region_blocks WHERE region_id = ?")) {
			ps.setLong(1, sourceId);
			ps.executeUpdate();
		}
		try (PreparedStatement ps = connection.prepareStatement("DELETE FROM building_regions WHERE id = ?")) {
			ps.setLong(1, sourceId);
			ps.executeUpdate();
		}
	}

	private void refreshAuthorRatios(long regionId) throws SQLException {
		int total = 0;
		try (PreparedStatement ps = connection.prepareStatement("SELECT COALESCE(SUM(contribution_score), 0) FROM region_authors WHERE region_id = ?")) {
			ps.setLong(1, regionId);
			try (ResultSet rs = ps.executeQuery()) {
				if (rs.next()) total = rs.getInt(1);
			}
		}
		try (PreparedStatement ps = connection.prepareStatement("""
			UPDATE region_authors
			SET ratio_bp = CASE WHEN ? > 0 THEN CAST(ROUND(contribution_score * 10000.0 / ?) AS INTEGER) ELSE 0 END
			WHERE region_id = ?
			""")) {
			ps.setInt(1, total);
			ps.setInt(2, total);
			ps.setLong(3, regionId);
			ps.executeUpdate();
		}
	}

	private void refreshRiskFlags(long regionId) throws SQLException {
		Region region = findRegion(regionId);
		if (region == null) return;
		int authors = authorCount(regionId);
		String risks = riskFlags(region, region.nonAirCount, authors, 10000);
		try (PreparedStatement ps = connection.prepareStatement("UPDATE building_regions SET risk_flags = ? WHERE id = ?")) {
			ps.setString(1, risks);
			ps.setLong(2, regionId);
			ps.executeUpdate();
		}
	}

	private void markRegionScanned(long regionId, int nonAir, int volume, String extraRisk) {
		String now = SqliteUtcDatetimes.now();
		int density = volume > 0 ? (int) Math.round(nonAir * 10000.0D / volume) : 0;
		try (PreparedStatement ps = connection.prepareStatement("""
			UPDATE building_regions
			SET non_air_count = ?, density_bp = ?, last_scanned_at = ?, dirty = 0,
			    risk_flags = COALESCE(?, risk_flags), updated_at = ?
			WHERE id = ?
			""")) {
			ps.setInt(1, Math.max(0, nonAir));
			ps.setInt(2, Math.max(0, density));
			ps.setString(3, now);
			ps.setString(4, extraRisk);
			ps.setString(5, now);
			ps.setLong(6, regionId);
			ps.executeUpdate();
		} catch (SQLException e) {
			log.warn("Failed to mark region {} scanned: {}", regionId, e.toString());
		}
	}

	private List<RegionExportPlan> regionsForAuthor(String playerNameKey) throws SQLException {
		try (PreparedStatement ps = connection.prepareStatement("""
			SELECT br.*,
			       ra.ratio_bp AS target_ratio_bp,
			       ra.last_touched_at AS target_last_touched_at,
			       (
			           SELECT COUNT(*)
			           FROM region_authors ra2
			           WHERE ra2.region_id = br.id AND ra2.contribution_score > 0
			       ) AS author_count
			FROM building_regions br
			JOIN region_authors ra ON ra.region_id = br.id
			WHERE br.status = 'active' AND ra.player_name_key = ? AND ra.contribution_score > 0
			ORDER BY COALESCE(ra.last_touched_at, br.last_touched_at, br.updated_at) DESC, br.id DESC
			""")) {
			ps.setString(1, playerNameKey);
			try (ResultSet rs = ps.executeQuery()) {
				List<RegionExportPlan> out = new ArrayList<>();
				while (rs.next()) out.add(regionExportPlan(rs));
				return out;
			}
		}
	}

	private Region findRegion(long id) throws SQLException {
		try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM building_regions WHERE id = ?")) {
			ps.setLong(1, id);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? region(rs) : null;
			}
		}
	}

	private int authorCount(long regionId) throws SQLException {
		try (PreparedStatement ps = connection.prepareStatement("SELECT COUNT(*) FROM region_authors WHERE region_id = ? AND contribution_score > 0")) {
			ps.setLong(1, regionId);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? rs.getInt(1) : 0;
			}
		}
	}

	private List<BlockCoordinate> placedBlocksForRegion(long regionId) throws SQLException {
		try (PreparedStatement ps = connection.prepareStatement("""
			SELECT dimension, x, y, z
			FROM region_blocks
			WHERE region_id = ?
			""")) {
			ps.setLong(1, regionId);
			try (ResultSet rs = ps.executeQuery()) {
				List<BlockCoordinate> out = new ArrayList<>();
				while (rs.next()) {
					out.add(new BlockCoordinate(
						rs.getString("dimension"),
						rs.getInt("x"),
						rs.getInt("y"),
						rs.getInt("z")
					));
				}
				return out;
			}
		}
	}

	private void ensureBulkStageTable() throws SQLException {
		try (Statement stmt = connection.createStatement()) {
			stmt.execute("""
				CREATE TEMP TABLE IF NOT EXISTS bulk_change_stage (
					row_id INTEGER PRIMARY KEY,
					operation_id TEXT NOT NULL,
					dimension TEXT NOT NULL,
					x INTEGER NOT NULL,
					y INTEGER NOT NULL,
					z INTEGER NOT NULL,
					old_block_state TEXT,
					new_block_state TEXT NOT NULL,
					old_block_replaceable INTEGER NOT NULL DEFAULT 0,
					force_placement INTEGER NOT NULL DEFAULT 0,
					UNIQUE(operation_id, dimension, x, y, z)
				)
				""");
			stmt.execute("CREATE INDEX IF NOT EXISTS temp.idx_bulk_change_stage_operation_row ON bulk_change_stage(operation_id, row_id)");
		}
	}

	private List<StagedBulkChange> stagedBulkChangesPage(String operationId, long afterRowId) throws SQLException {
		try (PreparedStatement ps = connection.prepareStatement("""
			SELECT row_id, dimension, x, y, z,
			       old_block_state, new_block_state,
			       old_block_replaceable, force_placement
			FROM bulk_change_stage
			WHERE operation_id = ? AND row_id > ?
			ORDER BY row_id
			LIMIT ?
			""")) {
			ps.setString(1, operationId);
			ps.setLong(2, afterRowId);
			ps.setInt(3, WRITE_BATCH_SIZE);
			try (ResultSet rs = ps.executeQuery()) {
				List<StagedBulkChange> out = new ArrayList<>(WRITE_BATCH_SIZE);
				while (rs.next()) {
					out.add(new StagedBulkChange(
						rs.getLong("row_id"),
						rs.getString("dimension"),
						rs.getInt("x"),
						rs.getInt("y"),
						rs.getInt("z"),
						rs.getString("old_block_state"),
						rs.getString("new_block_state"),
						rs.getInt("old_block_replaceable") != 0,
						rs.getInt("force_placement") != 0
					));
				}
				return out;
			}
		}
	}

	private void deleteStagedBulkStateChangesNow(String operationId) {
		if (operationId == null || operationId.isBlank()) return;
		try {
			ensureBulkStageTable();
			deleteStagedBulkStateChangesInTransaction(operationId);
		} catch (SQLException e) {
			log.warn("Failed to discard staged portability bulk changes: {}", e.toString());
		}
	}

	private void deleteStagedBulkStateChangesInTransaction(String operationId) throws SQLException {
		try (PreparedStatement ps = connection.prepareStatement(
			"DELETE FROM bulk_change_stage WHERE operation_id = ?"
		)) {
			ps.setString(1, operationId);
			ps.executeUpdate();
		}
	}

	private int countRegions() {
		try (Statement stmt = connection.createStatement();
			 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM building_regions WHERE status = 'active'")) {
			return rs.next() ? rs.getInt(1) : 0;
		} catch (SQLException e) {
			log.warn("Failed to count portability building regions: {}", e.toString());
			return 0;
		}
	}

	private int countPendingStructureAuditEvents() {
		try (Statement stmt = connection.createStatement();
			 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM structure_audit_outbox")) {
			return rs.next() ? rs.getInt(1) : 0;
		} catch (SQLException e) {
			log.warn("Failed to count pending structure audit events: {}", e.toString());
			return 0;
		}
	}

	private static List<List<BlockRecord>> connectedGroups(List<BlockRecord> blocks) throws IOException {
		Map<BlockCoordinate, BlockRecord> byCoordinate = new HashMap<>();
		for (BlockRecord block : blocks) {
			ensureNotInterrupted();
			byCoordinate.put(block.coordinate(), block);
		}
		List<List<BlockRecord>> out = new ArrayList<>();
		Set<BlockCoordinate> visited = new HashSet<>();
		for (BlockCoordinate seed : byCoordinate.keySet()) {
			ensureNotInterrupted();
			if (visited.contains(seed)) continue;
			List<BlockRecord> group = new ArrayList<>();
			ArrayDeque<BlockCoordinate> queue = new ArrayDeque<>();
			queue.add(seed);
			visited.add(seed);
			while (!queue.isEmpty()) {
				ensureNotInterrupted();
				BlockCoordinate key = queue.removeFirst();
				BlockRecord record = byCoordinate.get(key);
				if (record == null) continue;
				group.add(record);
				BlockCoordinate coord = record.coordinate();
				for (int[] n : NEIGHBORS) {
					BlockCoordinate next = coord.offset(n[0], n[1], n[2]);
					if (visited.contains(next) || !byCoordinate.containsKey(next)) continue;
					visited.add(next);
					queue.add(next);
				}
			}
			out.add(group);
		}
		out.sort(Comparator.comparingInt(List<BlockRecord>::size).reversed());
		return out;
	}

	private static List<List<BlockRecord>> compactGroups(List<BlockRecord> scanned, List<List<BlockRecord>> groups) throws IOException {
		List<MutableGroup> work = new ArrayList<>();
		for (List<BlockRecord> group : groups) {
			if (!group.isEmpty()) work.add(new MutableGroup(group));
		}
		boolean changed;
		do {
			ensureNotInterrupted();
			changed = false;
			work.sort(Comparator.comparingInt(MutableGroup::size).reversed());
			outer:
			for (int i = 0; i < work.size(); i++) {
				ensureNotInterrupted();
				for (int j = i + 1; j < work.size(); j++) {
					MutableGroup a = work.get(i);
					MutableGroup b = work.get(j);
					Bounds merged = a.bounds.merge(b.bounds);
					int mergedNonAir = countBlocksWithin(scanned, merged);
					if (!shouldMergeGroups(a, b, merged, mergedNonAir)) continue;
					a.merge(b);
					work.remove(j);
					changed = true;
					break outer;
				}
			}
		} while (changed);
		work.sort(Comparator.comparingInt(MutableGroup::size).reversed());
		List<List<BlockRecord>> out = new ArrayList<>();
		for (MutableGroup group : work) out.add(group.blocks);
		return out;
	}

	private static int countBlocksWithin(List<BlockRecord> blocks, Bounds bounds) {
		int count = 0;
		for (BlockRecord block : blocks) {
			if (bounds.contains(block.x, block.y, block.z)) count += 1;
		}
		return count;
	}

	private static void ensureNotInterrupted() throws IOException {
		if (Thread.currentThread().isInterrupted()) {
			throw new IOException("Interrupted while preparing portability export");
		}
	}

	private static boolean shouldMergeGroups(MutableGroup a, MutableGroup b, Bounds merged, int mergedNonAir) {
		if (isLinearInfrastructure(merged, mergedNonAir)) return false;
		int gap = a.bounds.distanceTo(b.bounds);
		if (gap <= 1) return true;
		int smaller = Math.min(a.size(), b.size());
		int larger = Math.max(a.size(), b.size());
		return smaller <= 16 && larger >= 3 && gap <= 4;
	}

	private static List<BlockRecord> blocksWithin(List<BlockRecord> blocks, Bounds bounds) {
		List<BlockRecord> out = new ArrayList<>();
		for (BlockRecord block : blocks) {
			if (bounds.contains(block.x, block.y, block.z)) out.add(block);
		}
		out.sort(Comparator
			.comparingInt((BlockRecord b) -> b.y)
			.thenComparingInt(b -> b.z)
			.thenComparingInt(b -> b.x));
		return out;
	}

	private static Region region(ResultSet rs) throws SQLException {
		return new Region(
			rs.getLong("id"),
			rs.getString("dimension"),
			rs.getInt("min_x"),
			rs.getInt("min_y"),
			rs.getInt("min_z"),
			rs.getInt("max_x"),
			rs.getInt("max_y"),
			rs.getInt("max_z"),
			rs.getInt("non_air_count")
		);
	}

	private static RegionExportPlan regionExportPlan(ResultSet rs) throws SQLException {
		return new RegionExportPlan(
			rs.getLong("id"),
			rs.getString("dimension"),
			rs.getInt("min_x"),
			rs.getInt("min_y"),
			rs.getInt("min_z"),
			rs.getInt("max_x"),
			rs.getInt("max_y"),
			rs.getInt("max_z"),
			rs.getInt("target_ratio_bp"),
			rs.getString("target_last_touched_at"),
			rs.getInt("author_count")
		);
	}

	private static String riskFlags(Bounds bounds, int nonAir, int authorCount, int targetRatio) {
		List<String> risks = new ArrayList<>();
		int volume = safeVolume(bounds);
		if (authorCount > 1) risks.add("mixed_authorship");
		if (targetRatio > 0 && targetRatio < 5000) risks.add("low_target_authorship");
		if (volume > 500_000) risks.add("large_cuboid");
		if (volume > 0 && nonAir > 0 && nonAir * 10000L / volume < 250) risks.add("low_density");
		return risks.isEmpty() ? null : String.join(",", risks);
	}

	private static boolean isLinearInfrastructure(Bounds bounds, int nonAir) {
		return PortabilityGeometryClassifier.isLinearInfrastructure(
			bounds.minX,
			bounds.minY,
			bounds.minZ,
			bounds.maxX,
			bounds.maxY,
			bounds.maxZ,
			nonAir
		);
	}

	private static int safeVolume(Bounds bounds) {
		long v = (long) bounds.sizeX() * (long) bounds.sizeY() * (long) bounds.sizeZ();
		return v > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(1L, v);
	}

	private static int saturatingAdd(int a, int b) {
		long sum = (long) Math.max(0, a) + Math.max(0, b);
		return sum > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sum;
	}

	private static String dimensionName(String raw) {
		return DimensionNames.normalize(raw);
	}

	private static String displayActorName(String actorName) {
		return actorName == null || actorName.isBlank() ? ActorContext.UNKNOWN_ACTOR : actorName.trim();
	}

	private static boolean isPlayerActor(String actorName) {
		String key = normalizeName(actorName);
		return !key.isBlank()
			&& !key.equals(normalizeName(ActorContext.SYSTEM_ACTOR))
			&& !key.equals(normalizeName(ActorContext.UNKNOWN_ACTOR));
	}

	private static String normalizeName(String s) {
		return ActorContext.normalizeName(s);
	}

	private static String safeDimension(String s) {
		return (s == null ? "world" : s).replace(':', '_').replace('/', '_');
	}

	private static boolean isHistorySource(String source) {
		if (source == null) return false;
		String s = source.trim().toUpperCase(Locale.ROOT);
		return "WORLDEDIT_UNDO".equals(s)
			|| "WORLDEDIT_REDO".equals(s)
			|| "EFFORTLESS_UNDO".equals(s)
			|| "EFFORTLESS_REDO".equals(s);
	}

	private static boolean isAirState(String state) {
		if (state == null || state.isBlank()) return true;
		String s = state.trim();
		return "minecraft:air".equals(s)
			|| "air".equals(s)
			|| "Block{minecraft:air}".equals(s)
			|| s.endsWith("{minecraft:air}");
	}

	private interface WriteCommand {
	}

	private static final class StagedBulkFinishCommand implements WriteCommand {
		final String operationId;
		final String actorName;
		final String source;
		final BulkPlacementBounds auditBounds;
		final int resultChangedBlockCount;
		final boolean auditOnly;

		StagedBulkFinishCommand(
			String operationId,
			String actorName,
			String source,
			BulkPlacementBounds auditBounds,
			int resultChangedBlockCount,
			boolean auditOnly
		) {
			this.operationId = operationId;
			this.actorName = actorName;
			this.source = source;
			this.auditBounds = auditBounds;
			this.resultChangedBlockCount = resultChangedBlockCount;
			this.auditOnly = auditOnly;
		}
	}

	private static final class BarrierCommand implements WriteCommand {
		private final CountDownLatch completed = new CountDownLatch(1);

		void complete() {
			completed.countDown();
		}

		void await() throws InterruptedException {
			completed.await();
		}
	}

	private enum StopCommand implements WriteCommand {
		INSTANCE
	}

	private static final class BulkMutationBuffer {
		final Map<AuthorMutationKey, AuthorDelta> authorDeltas = new HashMap<>();
		final List<BlockMutation> mutations = new ArrayList<>();

		void addAuthor(long regionId, String actorName, int firstPlaceCount, int lastModifyCount) {
			AuthorMutationKey key = new AuthorMutationKey(regionId, normalizeName(actorName));
			AuthorDelta delta = authorDeltas.computeIfAbsent(key, ignored -> new AuthorDelta());
			delta.displayName = actorName.trim();
			delta.firstPlaceCount = saturatingAdd(delta.firstPlaceCount, firstPlaceCount);
			delta.lastModifyCount = saturatingAdd(delta.lastModifyCount, lastModifyCount);
		}

		void addPlacement(long regionId, String actorName, String dimension, int x, int y, int z) {
			mutations.add(new BlockMutation(regionId, actorName, dimension, x, y, z, true));
		}

		void addDeletion(long regionId, String actorName, String dimension, int x, int y, int z) {
			mutations.add(new BlockMutation(regionId, actorName, dimension, x, y, z, false));
		}
	}

	private record AuthorMutationKey(long regionId, String playerNameKey) {
	}

	private static final class AuthorDelta {
		String displayName;
		int firstPlaceCount;
		int lastModifyCount;
	}

	private static final class BlockMutation {
		final long regionId;
		final String actorName;
		final String dimension;
		final int x;
		final int y;
		final int z;
		final boolean placement;

		BlockMutation(long regionId, String actorName, String dimension, int x, int y, int z, boolean placement) {
			this.regionId = regionId;
			this.actorName = actorName;
			this.dimension = dimension;
			this.x = x;
			this.y = y;
			this.z = z;
			this.placement = placement;
		}
	}

	private static final class BulkAuditTally {
		private boolean hasPlace;
		private boolean hasDelete;

		void include(String newBlockState) {
			if (newBlockState == null || newBlockState.isBlank()) return;
			if (isAirState(newBlockState)) hasDelete = true;
			else hasPlace = true;
		}

		String changeType() {
			if (hasPlace && hasDelete) return "mixed";
			if (hasDelete) return "delete";
			if (hasPlace) return "place";
			return "mixed";
		}
	}

	private static final class StagedBulkChange {
		final long rowId;
		final String dimension;
		final int x;
		final int y;
		final int z;
		final String oldBlockState;
		final String newBlockState;
		final boolean oldBlockReplaceable;
		final boolean forcePlacement;

		StagedBulkChange(
			long rowId,
			String dimension,
			int x,
			int y,
			int z,
			String oldBlockState,
			String newBlockState,
			boolean oldBlockReplaceable,
			boolean forcePlacement
		) {
			this.rowId = rowId;
			this.dimension = dimension;
			this.x = x;
			this.y = y;
			this.z = z;
			this.oldBlockState = oldBlockState;
			this.newBlockState = newBlockState;
			this.oldBlockReplaceable = oldBlockReplaceable;
			this.forcePlacement = forcePlacement;
		}

		PendingChange toPendingChange(String actorName) {
			return new PendingChange(
				dimension,
				x,
				y,
				z,
				oldBlockState,
				newBlockState,
				actorName,
				oldBlockReplaceable,
				forcePlacement
			);
		}
	}

	private static final class PendingChange implements WriteCommand {
		final String dimension;
		final int x;
		final int y;
		final int z;
		final String oldBlockState;
		final String newBlockState;
		final String actorName;
		final boolean oldBlockReplaceable;
		final boolean forcePlacement;

		PendingChange(
			String dimension,
			int x,
			int y,
			int z,
			String oldBlockState,
			String newBlockState,
			String actorName,
			boolean oldBlockReplaceable,
			boolean forcePlacement
		) {
			this.dimension = dimension;
			this.x = x;
			this.y = y;
			this.z = z;
			this.oldBlockState = oldBlockState;
			this.newBlockState = newBlockState;
			this.actorName = actorName;
			this.oldBlockReplaceable = oldBlockReplaceable;
			this.forcePlacement = forcePlacement;
		}
	}

	private static class Bounds {
		final int minX;
		final int minY;
		final int minZ;
		final int maxX;
		final int maxY;
		final int maxZ;

		Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
			this.minX = Math.min(minX, maxX);
			this.minY = Math.min(minY, maxY);
			this.minZ = Math.min(minZ, maxZ);
			this.maxX = Math.max(minX, maxX);
			this.maxY = Math.max(minY, maxY);
			this.maxZ = Math.max(minZ, maxZ);
		}

		static Bounds from(List<BlockRecord> blocks) {
			BlockRecord first = blocks.get(0);
			int minX = first.x, minY = first.y, minZ = first.z;
			int maxX = first.x, maxY = first.y, maxZ = first.z;
			for (BlockRecord b : blocks) {
				minX = Math.min(minX, b.x);
				minY = Math.min(minY, b.y);
				minZ = Math.min(minZ, b.z);
				maxX = Math.max(maxX, b.x);
				maxY = Math.max(maxY, b.y);
				maxZ = Math.max(maxZ, b.z);
			}
			return new Bounds(minX, minY, minZ, maxX, maxY, maxZ);
		}

		Bounds include(int x, int y, int z) {
			return new Bounds(
				Math.min(minX, x),
				Math.min(minY, y),
				Math.min(minZ, z),
				Math.max(maxX, x),
				Math.max(maxY, y),
				Math.max(maxZ, z)
			);
		}

		Bounds merge(Bounds other) {
			return new Bounds(
				Math.min(minX, other.minX),
				Math.min(minY, other.minY),
				Math.min(minZ, other.minZ),
				Math.max(maxX, other.maxX),
				Math.max(maxY, other.maxY),
				Math.max(maxZ, other.maxZ)
			);
		}

		int sizeX() {
			return maxX - minX + 1;
		}

		int sizeY() {
			return maxY - minY + 1;
		}

		int sizeZ() {
			return maxZ - minZ + 1;
		}

		boolean equalsBounds(Bounds other) {
			return other != null
				&& minX == other.minX && minY == other.minY && minZ == other.minZ
				&& maxX == other.maxX && maxY == other.maxY && maxZ == other.maxZ;
		}

		boolean contains(int x, int y, int z) {
			return x >= minX && x <= maxX
				&& y >= minY && y <= maxY
				&& z >= minZ && z <= maxZ;
		}

		int distanceTo(Bounds other) {
			int dx = axisGap(minX, maxX, other.minX, other.maxX);
			int dy = axisGap(minY, maxY, other.minY, other.maxY);
			int dz = axisGap(minZ, maxZ, other.minZ, other.maxZ);
			return dx + dy + dz;
		}

		private static int axisGap(int minA, int maxA, int minB, int maxB) {
			if (maxA < minB) return minB - maxA - 1;
			if (maxB < minA) return minA - maxB - 1;
			return 0;
		}
	}

	private static final class MutableGroup {
		final List<BlockRecord> blocks = new ArrayList<>();
		Bounds bounds;

		MutableGroup(List<BlockRecord> source) {
			blocks.addAll(source);
			bounds = Bounds.from(blocks);
		}

		int size() {
			return blocks.size();
		}

		void merge(MutableGroup other) {
			blocks.addAll(other.blocks);
			bounds = bounds.merge(other.bounds);
		}
	}

	private static final class Region extends Bounds {
		final long id;
		final String dimension;
		final int nonAirCount;

		Region(long id, String dimension, int minX, int minY, int minZ, int maxX, int maxY, int maxZ, int nonAirCount) {
			super(minX, minY, minZ, maxX, maxY, maxZ);
			this.id = id;
			this.dimension = dimension;
			this.nonAirCount = nonAirCount;
		}

		int distanceTo(int x, int y, int z) {
			int dx = x < minX ? minX - x : Math.max(0, x - maxX);
			int dy = y < minY ? minY - y : Math.max(0, y - maxY);
			int dz = z < minZ ? minZ - z : Math.max(0, z - maxZ);
			return dx + dy + dz;
		}
	}

	private static final class RegionExportPlan extends Bounds {
		final long id;
		final String dimension;
		final int targetRatioBp;
		final String targetLastTouchedAt;
		final int authorCount;

		RegionExportPlan(
			long id,
			String dimension,
			int minX,
			int minY,
			int minZ,
			int maxX,
			int maxY,
			int maxZ,
			int targetRatioBp,
			String targetLastTouchedAt,
			int authorCount
		) {
			super(minX, minY, minZ, maxX, maxY, maxZ);
			this.id = id;
			this.dimension = dimension;
			this.targetRatioBp = targetRatioBp;
			this.targetLastTouchedAt = targetLastTouchedAt;
			this.authorCount = authorCount;
		}
	}
}
