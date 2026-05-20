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

public final class BuildingIndexStore {
	private static final int SCHEMA_VERSION = 7;
	private static final int REGION_MERGE_GAP_BLOCKS = 2;
	private static final int MAX_AUTO_REGION_VOLUME = 2_000_000;
	private static final int MIN_EXPORT_COMPONENT_BLOCKS = 8;
	private static final int LINEAR_MIN_LONG_AXIS = 32;
	private static final int LINEAR_MAX_CROSS_AXIS = 3;
	private static final int LINEAR_WIDE_MAX_CROSS = 12;
	private static final int LINEAR_WIDE_MIN_ASPECT = 16;
	private static final int[][] NEIGHBORS = {
		{1, 0, 0}, {-1, 0, 0},
		{0, 1, 0}, {0, -1, 0},
		{0, 0, 1}, {0, 0, -1}
	};

	private final Path dbPath;
	private final CommonLogger log;
	private final Connection connection;
	private volatile boolean closed = false;

	private BuildingIndexStore(Path dbPath, CommonLogger log, Connection connection) {
		this.dbPath = dbPath;
		this.log = log;
		this.connection = connection;
	}

	public static BuildingIndexStore open(Path dbPath, CommonLogger log) throws IOException {
		try {
			Class.forName("org.sqlite.JDBC");
			Path parent = dbPath.getParent();
			if (parent != null) Files.createDirectories(parent);
			Connection connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
			BuildingIndexStore store = new BuildingIndexStore(dbPath, log, connection);
			store.initializeSchema();
			log.info("Beilin Data Portability opened cuboid region index {} with {} region(s)", dbPath, store.size());
			return store;
		} catch (ReflectiveOperationException | SQLException e) {
			throw new IOException("Failed to open portability SQLite index " + dbPath, e);
		}
	}

	public synchronized int size() {
		return countRegions();
	}

	public synchronized void close() {
		closed = true;
		try {
			connection.close();
		} catch (SQLException ignored) {
		}
	}

	/** @deprecated No-op. Retained for API compatibility with Mixin and platform callers. */
	@Deprecated
	public synchronized void flushPendingWrites() {
	}

	public synchronized void checkpoint() throws SQLException {
		try (Statement stmt = connection.createStatement()) {
			stmt.execute("PRAGMA wal_checkpoint(TRUNCATE)");
		}
	}

	public synchronized void compact() throws SQLException {
		try (Statement stmt = connection.createStatement()) {
			stmt.execute("VACUUM");
		}
	}

	public synchronized String diagnosticSummary() {
		return "db=" + dbPath
			+ ", indexed_regions=" + countRegions()
			+ ", schema=v" + SCHEMA_VERSION;
	}

	public synchronized void recordPlaced(
		String dimension,
		int x,
		int y,
		int z,
		String blockState,
		String actorName
	) {
		recordStateChangeWithSource(dimension, x, y, z, "minecraft:air", blockState, actorName, "PLAYER_EVENT");
	}

	public synchronized void recordRemoved(
		String dimension,
		int x,
		int y,
		int z,
		String actorName
	) {
		recordStateChangeWithSource(dimension, x, y, z, null, "minecraft:air", actorName, "PLAYER_EVENT");
	}

	public synchronized void recordStateChangeWithSource(
		String dimension,
		int x,
		int y,
		int z,
		String oldBlockState,
		String newBlockState,
		String actorName,
		String source
	) {
		if (closed) return;
		if (newBlockState == null || newBlockState.isBlank()) return;
		String actor = displayActorName(actorName);
		if (!isPlayerActor(actor)) return;
		boolean newAir = isAirState(newBlockState);
		boolean firstPlacement = !newAir && isAirState(oldBlockState);
		if (!newAir && !firstPlacement) return;
		boolean originalAutoCommit = true;
		try {
			originalAutoCommit = connection.getAutoCommit();
			connection.setAutoCommit(false);
			applyChange(new PendingChange(dimensionName(dimension), x, y, z, oldBlockState, newBlockState, actor));
			connection.commit();
		} catch (SQLException e) {
			try {
				connection.rollback();
			} catch (SQLException ignored) {
			}
			log.warn("Failed to apply portability block change at {},{},{}: {}", x, y, z, e.toString());
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

	public ExportBundle buildExportBundle(ExportJob job, WorldBlockReader reader, int maxExportVolumeBlocks) throws IOException {
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
				if (isLinearInfrastructure(bounds, nonAir)) {
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

	private void applyChange(PendingChange change) throws SQLException {
		String actorName = displayActorName(change.actorName);
		boolean playerActor = isPlayerActor(actorName);
		boolean newAir = isAirState(change.newBlockState);
		boolean firstPlacement = !newAir && isAirState(change.oldBlockState);
		boolean playerCanExpandRegion = playerActor && firstPlacement;
		Region region = findCandidateRegion(change.dimension, change.x, change.y, change.z, playerCanExpandRegion);
		String now = SqliteUtcDatetimes.now();

		if (region == null && firstPlacement && playerActor) {
			long id = insertRegion(change.dimension, change.x, change.y, change.z, now);
			region = findRegion(id);
		}

		if (region == null) return;

		Bounds next = playerCanExpandRegion ? region.include(change.x, change.y, change.z) : region;
		if (!newAir && !next.equalsBounds(region)) {
			updateRegionBounds(region.id, next, now);
			region = findRegion(region.id);
		} else {
			touchRegion(region.id, now);
		}

		if (playerActor) {
			upsertAuthor(region.id, actorName, firstPlacement ? 1 : 0, 1, now);
			if (firstPlacement) {
				upsertPlacedBlock(region.id, actorName, change.dimension, change.x, change.y, change.z, now);
			} else if (newAir) {
				deletePlacedBlock(region.id, change.dimension, change.x, change.y, change.z);
			}
		}

		markDirty(region.id, now);
		if (region != null) {
			refreshAuthorRatios(region.id);
			refreshRiskFlags(region.id);
			mergeCompatibleRegions(region.id);
		}
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
		try (PreparedStatement ps = connection.prepareStatement("""
			INSERT INTO building_regions (
				dimension, min_x, min_y, min_z, max_x, max_y, max_z,
				volume_blocks, status, last_touched_at, dirty, created_at, updated_at
			) VALUES (?, ?, ?, ?, ?, ?, ?, 1, 'active', ?, 1, ?, ?)
			""")) {
			ps.setString(1, dimension);
			ps.setInt(2, x);
			ps.setInt(3, y);
			ps.setInt(4, z);
			ps.setInt(5, x);
			ps.setInt(6, y);
			ps.setInt(7, z);
			ps.setString(8, now);
			ps.setString(9, now);
			ps.setString(10, now);
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
			"UPDATE building_regions SET last_touched_at = ?, updated_at = ? WHERE id = ?"
		)) {
			ps.setString(1, now);
			ps.setString(2, now);
			ps.setLong(3, regionId);
			ps.executeUpdate();
		}
	}

	private void markDirty(long regionId, String now) throws SQLException {
		try (PreparedStatement ps = connection.prepareStatement(
			"UPDATE building_regions SET dirty = 1, updated_at = ? WHERE id = ?"
		)) {
			ps.setString(1, now);
			ps.setLong(2, regionId);
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

	private Region findCandidateRegion(String dimension, int x, int y, int z, boolean expanding) throws SQLException {
		List<Region> candidates = regionsNear(dimension, x, y, z, expanding ? REGION_MERGE_GAP_BLOCKS : 0);
		candidates.sort(Comparator.comparingInt(r -> r.distanceTo(x, y, z)));
		for (Region region : candidates) {
			if (!expanding) return region;
			Bounds expanded = region.include(x, y, z);
			if (safeVolume(expanded) > MAX_AUTO_REGION_VOLUME) continue;
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

	private void mergeCompatibleRegions(long regionId) throws SQLException {
		Region base = findRegion(regionId);
		if (base == null) return;
		List<Region> others = overlappingRegions(base, REGION_MERGE_GAP_BLOCKS);
		for (Region other : others) {
			if (other.id == base.id) continue;
			Bounds merged = base.merge(other);
			if (safeVolume(merged) > MAX_AUTO_REGION_VOLUME) continue;
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
		refreshAuthorRatios(targetId);
		refreshRiskFlags(targetId);
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

	private int countRegions() {
		try (Statement stmt = connection.createStatement();
			 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM building_regions WHERE status = 'active'")) {
			return rs.next() ? rs.getInt(1) : 0;
		} catch (SQLException e) {
			log.warn("Failed to count portability building regions: {}", e.toString());
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
		if (safeVolume(merged) > MAX_AUTO_REGION_VOLUME) return false;
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
		if (isLinearInfrastructure(bounds, nonAir)) risks.add("linear_infrastructure_excluded");
		return risks.isEmpty() ? null : String.join(",", risks);
	}

	private static boolean isLinearInfrastructure(Bounds bounds, int nonAir) {
		int x = bounds.sizeX();
		int y = bounds.sizeY();
		int z = bounds.sizeZ();
		int longest = Math.max(x, Math.max(y, z));
		int shortest = Math.min(x, Math.min(y, z));
		int middle = x + y + z - longest - shortest;
		int crossMax = Math.max(middle, shortest);
		boolean horizontal = x == longest || z == longest;
		if (longest < LINEAR_MIN_LONG_AXIS) return false;
		boolean narrow = crossMax <= LINEAR_MAX_CROSS_AXIS;
		boolean wideLinear = crossMax <= LINEAR_WIDE_MAX_CROSS
			&& longest >= LINEAR_WIDE_MIN_ASPECT * crossMax;
		if (!narrow && !wideLinear) return false;
		if (horizontal) return true;
		int volume = safeVolume(bounds);
		return volume <= 0 || nonAir <= 0 || nonAir * 10000L / volume < 4500;
	}

	private static int safeVolume(Bounds bounds) {
		long v = (long) bounds.sizeX() * (long) bounds.sizeY() * (long) bounds.sizeZ();
		return v > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(1L, v);
	}

	private static String dimensionName(String raw) {
		return raw == null || raw.isBlank() ? "minecraft:overworld" : raw.trim();
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

	private static boolean isAirState(String state) {
		if (state == null || state.isBlank()) return true;
		String s = state.trim();
		return "minecraft:air".equals(s)
			|| "air".equals(s)
			|| "Block{minecraft:air}".equals(s)
			|| s.endsWith("{minecraft:air}");
	}

	private static final class PendingChange {
		final String dimension;
		final int x;
		final int y;
		final int z;
		final String oldBlockState;
		final String newBlockState;
		final String actorName;

		PendingChange(String dimension, int x, int y, int z, String oldBlockState, String newBlockState, String actorName) {
			this.dimension = dimension;
			this.x = x;
			this.y = y;
			this.z = z;
			this.oldBlockState = oldBlockState;
			this.newBlockState = newBlockState;
			this.actorName = actorName;
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
