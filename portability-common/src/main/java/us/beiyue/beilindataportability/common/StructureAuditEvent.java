package us.beiyue.beilindataportability.common;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class StructureAuditEvent {
	public final String eventId;
	public final String actorName;
	public final String tool;
	public final String operation;
	public final String source;
	public final String changeType;
	public final String dimension;
	public final int minX;
	public final int minY;
	public final int minZ;
	public final int maxX;
	public final int maxY;
	public final int maxZ;
	public final int changedBlockCount;
	public final int boundsBlockCount;
	public final String recordedAt;

	public StructureAuditEvent(
		String eventId,
		String actorName,
		String tool,
		String operation,
		String source,
		String changeType,
		String dimension,
		int minX,
		int minY,
		int minZ,
		int maxX,
		int maxY,
		int maxZ,
		int changedBlockCount,
		int boundsBlockCount,
		String recordedAt
	) {
		this.eventId = safeText(eventId, UUID.randomUUID().toString());
		this.actorName = safeText(actorName, ActorContext.UNKNOWN_ACTOR);
		this.tool = safeText(tool, "UNKNOWN");
		this.operation = safeText(operation, "UNKNOWN");
		this.source = safeText(source, "UNKNOWN");
		this.changeType = safeChangeType(changeType);
		this.dimension = DimensionNames.normalize(dimension);
		this.minX = Math.min(minX, maxX);
		this.minY = Math.min(minY, maxY);
		this.minZ = Math.min(minZ, maxZ);
		this.maxX = Math.max(minX, maxX);
		this.maxY = Math.max(minY, maxY);
		this.maxZ = Math.max(minZ, maxZ);
		this.changedBlockCount = Math.max(0, changedBlockCount);
		this.boundsBlockCount = Math.max(0, boundsBlockCount);
		this.recordedAt = safeText(recordedAt, SqliteUtcDatetimes.now());
	}

	public static List<StructureAuditEvent> fromBulkChanges(
		List<BulkBlockChange> changes,
		String actorName,
		String source,
		String recordedAt
	) {
		if (changes == null || changes.isEmpty()) return List.of();
		Map<String, MutableSummary> summaries = new LinkedHashMap<>();
		for (BulkBlockChange change : changes) {
			if (change == null) continue;
			String dimension = DimensionNames.normalize(change.dimension);
			MutableSummary summary = summaries.computeIfAbsent(dimension, MutableSummary::new);
			summary.include(change);
		}
		List<StructureAuditEvent> out = new ArrayList<>();
		ToolOperation parsed = parseSource(source);
		for (MutableSummary summary : summaries.values()) {
			if (summary.count <= 0) continue;
			out.add(new StructureAuditEvent(
				UUID.randomUUID().toString(),
				actorName,
				parsed.tool,
				parsed.operation,
				source,
				summary.changeType(),
				summary.dimension,
				summary.minX,
				summary.minY,
				summary.minZ,
				summary.maxX,
				summary.maxY,
				summary.maxZ,
				summary.count,
				summary.volume(),
				recordedAt
			));
		}
		return out;
	}

	public static StructureAuditEvent fromBounds(
		BulkPlacementBounds bounds,
		String actorName,
		String source,
		String changeType,
		int changedBlockCount,
		String recordedAt
	) {
		if (bounds == null) return null;
		ToolOperation parsed = parseSource(source);
		int volume = bounds.blockCount > 0
			? bounds.blockCount
			: safeVolume(bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ);
		return new StructureAuditEvent(
			UUID.randomUUID().toString(),
			actorName,
			parsed.tool,
			parsed.operation,
			source,
			changeType,
			bounds.dimension,
			bounds.minX,
			bounds.minY,
			bounds.minZ,
			bounds.maxX,
			bounds.maxY,
			bounds.maxZ,
			changedBlockCount > 0 ? changedBlockCount : volume,
			volume,
			recordedAt
		);
	}

	public static StructureAuditEvent fromResultSet(ResultSet rs) throws SQLException {
		return new StructureAuditEvent(
			rs.getString("event_id"),
			rs.getString("actor_name"),
			rs.getString("tool"),
			rs.getString("operation"),
			rs.getString("source"),
			rs.getString("change_type"),
			rs.getString("dimension"),
			rs.getInt("min_x"),
			rs.getInt("min_y"),
			rs.getInt("min_z"),
			rs.getInt("max_x"),
			rs.getInt("max_y"),
			rs.getInt("max_z"),
			rs.getInt("changed_block_count"),
			rs.getInt("bounds_block_count"),
			rs.getString("recorded_at")
		);
	}

	public static String toWsMessage(List<StructureAuditEvent> events) {
		JsonObject root = new JsonObject();
		root.addProperty("action", "structure_audit_events");
		JsonArray items = new JsonArray();
		if (events != null) {
			for (StructureAuditEvent event : events) {
				if (event != null) items.add(event.toJson());
			}
		}
		root.add("events", items);
		return root.toString();
	}

	public JsonObject toJson() {
		JsonObject o = new JsonObject();
		o.addProperty("event_id", eventId);
		o.addProperty("actor_name", actorName);
		o.addProperty("tool", tool);
		o.addProperty("operation", operation);
		o.addProperty("source", source);
		o.addProperty("change_type", changeType);
		o.addProperty("dimension", dimension);
		o.addProperty("min_x", minX);
		o.addProperty("min_y", minY);
		o.addProperty("min_z", minZ);
		o.addProperty("max_x", maxX);
		o.addProperty("max_y", maxY);
		o.addProperty("max_z", maxZ);
		o.addProperty("changed_block_count", changedBlockCount);
		o.addProperty("bounds_block_count", boundsBlockCount);
		o.addProperty("recorded_at", recordedAt);
		return o;
	}

	private static ToolOperation parseSource(String source) {
		String value = safeText(source, "UNKNOWN").trim().toUpperCase(Locale.ROOT);
		int split = value.indexOf('_');
		if (split <= 0 || split + 1 >= value.length()) {
			return new ToolOperation(value, "UNKNOWN");
		}
		return new ToolOperation(value.substring(0, split), value.substring(split + 1));
	}

	private static String safeText(String value, String fallback) {
		return value == null || value.isBlank() ? fallback : value.trim();
	}

	private static String safeChangeType(String value) {
		String s = safeText(value, "mixed").toLowerCase(Locale.ROOT);
		if ("place".equals(s) || "delete".equals(s) || "mixed".equals(s)) return s;
		return "mixed";
	}

	private static int safeVolume(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
		long x = (long) Math.abs(maxX - minX) + 1L;
		long y = (long) Math.abs(maxY - minY) + 1L;
		long z = (long) Math.abs(maxZ - minZ) + 1L;
		long v = Math.max(1L, x) * Math.max(1L, y) * Math.max(1L, z);
		return v > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) v;
	}

	private static final class ToolOperation {
		final String tool;
		final String operation;

		ToolOperation(String tool, String operation) {
			this.tool = safeText(tool, "UNKNOWN");
			this.operation = safeText(operation, "UNKNOWN");
		}
	}

	private static final class MutableSummary {
		final String dimension;
		int minX = Integer.MAX_VALUE;
		int minY = Integer.MAX_VALUE;
		int minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxY = Integer.MIN_VALUE;
		int maxZ = Integer.MIN_VALUE;
		int count;
		boolean hasPlace;
		boolean hasDelete;

		MutableSummary(String dimension) {
			this.dimension = dimension;
		}

		void include(BulkBlockChange change) {
			minX = Math.min(minX, change.x);
			minY = Math.min(minY, change.y);
			minZ = Math.min(minZ, change.z);
			maxX = Math.max(maxX, change.x);
			maxY = Math.max(maxY, change.y);
			maxZ = Math.max(maxZ, change.z);
			count += 1;
			if (isAirState(change.newBlockState)) hasDelete = true;
			else hasPlace = true;
		}

		String changeType() {
			if (hasPlace && hasDelete) return "mixed";
			if (hasDelete) return "delete";
			return "place";
		}

		int volume() {
			return safeVolume(minX, minY, minZ, maxX, maxY, maxZ);
		}
	}

	private static boolean isAirState(String state) {
		if (state == null || state.isBlank()) return true;
		String s = state.trim();
		return "minecraft:air".equals(s)
			|| "air".equals(s)
			|| "Block{minecraft:air}".equals(s)
			|| s.endsWith("{minecraft:air}");
	}
}
