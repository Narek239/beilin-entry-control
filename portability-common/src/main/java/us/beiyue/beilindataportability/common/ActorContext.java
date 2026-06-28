package us.beiyue.beilindataportability.common;

import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class ActorContext {
	public static final String SYSTEM_ACTOR = "SYSTEM";
	public static final String UNKNOWN_ACTOR = "UNKNOWN";
	private static final int DEFAULT_BLOCK_ACTION_RADIUS = 3;

	private static final ThreadLocal<Actor> CURRENT = new ThreadLocal<>();

	private ActorContext() {
	}

	public static Actor current() {
		return CURRENT.get();
	}

	public static Scope push(String actorName, String source) {
		Actor previous = CURRENT.get();
		CURRENT.set(new Actor(displayActorName(actorName), sourceName(source), null, null, null, 0, RecordingMode.NORMAL, null, null, false, null));
		return new Scope(previous);
	}

	public static Scope pushNear(String actorName, String source, int x, int y, int z) {
		Actor previous = CURRENT.get();
		CURRENT.set(new Actor(displayActorName(actorName), sourceName(source), x, y, z, DEFAULT_BLOCK_ACTION_RADIUS, RecordingMode.NORMAL, null, null, false, null));
		return new Scope(previous);
	}

	public static Scope pushBulkRecord(String actorName, String source, Consumer<Actor> flush) {
		return pushBulkRecord(actorName, source, null, false, flush);
	}

	public static Scope pushBulkRecord(
		String actorName,
		String source,
		BulkPlacementBounds bounds,
		boolean completeBoundsCandidate,
		Consumer<Actor> flush
	) {
		Actor previous = CURRENT.get();
		RecordingMode recordingMode = completeBoundsCandidate && bounds != null
			? RecordingMode.BULK_COMPLETE_BOUNDS
			: RecordingMode.BULK_RECORD;
		CURRENT.set(new Actor(
			displayActorName(actorName),
			sourceName(source),
			null,
			null,
			null,
			0,
			recordingMode,
			bounds,
			null,
			completeBoundsCandidate,
			flush
		));
		return new Scope(previous);
	}

	public static Scope pushBulkDeleteBounds(String actorName, String source, BulkPlacementBounds bounds, Consumer<Actor> flush) {
		return pushBulkDeleteBounds(actorName, source, bounds, "delete", flush);
	}

	public static Scope pushBulkDeleteBounds(String actorName, String source, BulkPlacementBounds bounds, String changeType, Consumer<Actor> flush) {
		Actor previous = CURRENT.get();
		CURRENT.set(new Actor(displayActorName(actorName), sourceName(source), null, null, null, 0, RecordingMode.BULK_DELETE_BOUNDS, bounds, changeTypeName(changeType), false, flush));
		return new Scope(previous);
	}

	public static Scope pushBulkIgnore(String actorName, String source) {
		Actor previous = CURRENT.get();
		CURRENT.set(new Actor(displayActorName(actorName), sourceName(source), null, null, null, 0, RecordingMode.BULK_IGNORE, null, null, false, null));
		return new Scope(previous);
	}

	public static Actor system(String source) {
		return new Actor(SYSTEM_ACTOR, sourceName(source), null, null, null, 0, RecordingMode.NORMAL, null, null, false, null);
	}

	public static Actor unknown(String source) {
		return new Actor(UNKNOWN_ACTOR, sourceName(source), null, null, null, 0, RecordingMode.NORMAL, null, null, false, null);
	}

	public static String normalizeName(String name) {
		return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
	}

	private static String displayActorName(String name) {
		if (name == null || name.isBlank()) return UNKNOWN_ACTOR;
		return name.trim();
	}

	private static String sourceName(String source) {
		if (source == null || source.isBlank()) return "UNKNOWN";
		return source.trim();
	}

	private static String changeTypeName(String changeType) {
		if (changeType == null || changeType.isBlank()) return "mixed";
		String value = changeType.trim().toLowerCase(Locale.ROOT);
		if ("place".equals(value) || "delete".equals(value) || "mixed".equals(value)) return value;
		return "mixed";
	}

	public static final class Scope implements AutoCloseable {
		private final Actor previous;
		private boolean closed;

		private Scope(Actor previous) {
			this.previous = previous;
		}

		@Override
		public void close() {
			finish(true);
		}

		public void abort() {
			finish(false);
		}

		private void finish(boolean flush) {
			if (closed) return;
			closed = true;
			Actor current = CURRENT.get();
			try {
				if (flush && current != null && current.bulkFlush != null) {
					current.bulkFlush.accept(current);
				}
			} finally {
				if (previous == null) {
					CURRENT.remove();
				} else {
					CURRENT.set(previous);
				}
			}
		}
	}

	public enum RecordingMode {
		NORMAL,
		BULK_RECORD,
		BULK_COMPLETE_BOUNDS,
		BULK_DELETE_BOUNDS,
		BULK_IGNORE
	}

	public static final class Actor {
		public final String name;
		public final String nameKey;
		public final String source;
		public final RecordingMode recordingMode;
		private final Integer originX;
		private final Integer originY;
		private final Integer originZ;
		private final int radius;
		private final BulkPlacementBounds bulkBounds;
		private final String bulkChangeType;
		private final boolean completeBoundsCandidate;
		private final Consumer<Actor> bulkFlush;
		private final List<BulkBlockChange> bulkChanges = new ArrayList<>();
		private int bulkResultCount = -1;

		private Actor(
			String name,
			String source,
			Integer originX,
			Integer originY,
			Integer originZ,
			int radius,
			RecordingMode recordingMode,
			BulkPlacementBounds bulkBounds,
			String bulkChangeType,
			boolean completeBoundsCandidate,
			Consumer<Actor> bulkFlush
		) {
			this.name = name;
			this.nameKey = normalizeName(name);
			this.source = source;
			this.originX = originX;
			this.originY = originY;
			this.originZ = originZ;
			this.radius = Math.max(0, radius);
			this.recordingMode = recordingMode != null ? recordingMode : RecordingMode.NORMAL;
			this.bulkBounds = bulkBounds;
			this.bulkChangeType = changeTypeName(bulkChangeType);
			this.completeBoundsCandidate = completeBoundsCandidate;
			this.bulkFlush = bulkFlush;
		}

		public boolean isSystemLike() {
			return SYSTEM_ACTOR.equals(name) || UNKNOWN_ACTOR.equals(name);
		}

		public boolean canAffectBlock(int x, int y, int z) {
			if (recordingMode != RecordingMode.NORMAL) return true;
			if (isSystemLike()) return true;
			if (originX == null || originY == null || originZ == null) return false;
			return Math.abs(x - originX) <= radius
				&& Math.abs(y - originY) <= radius
				&& Math.abs(z - originZ) <= radius;
		}

		public boolean shouldIgnoreBlockRecords() {
			return recordingMode == RecordingMode.BULK_IGNORE
				|| recordingMode == RecordingMode.BULK_COMPLETE_BOUNDS
				|| recordingMode == RecordingMode.BULK_DELETE_BOUNDS;
		}

		public boolean shouldForcePlacementRecords() {
			return recordingMode == RecordingMode.BULK_RECORD;
		}

		public void addBulkChange(BulkBlockChange change) {
			if (recordingMode != RecordingMode.BULK_RECORD || change == null) return;
			bulkChanges.add(change);
		}

		public BulkPlacementBounds bulkBounds() {
			return bulkBounds;
		}

		public String bulkChangeType() {
			return bulkChangeType;
		}

		public List<BulkBlockChange> bulkChanges() {
			return List.copyOf(bulkChanges);
		}

		public void setBulkResultCount(int count) {
			if (recordingMode == RecordingMode.BULK_RECORD
				|| recordingMode == RecordingMode.BULK_COMPLETE_BOUNDS
				|| recordingMode == RecordingMode.BULK_DELETE_BOUNDS) {
				bulkResultCount = Math.max(0, count);
			}
		}

		public int bulkResultCount() {
			return bulkResultCount;
		}

		public boolean isCompleteBoundsPlacement() {
			return recordingMode == RecordingMode.BULK_COMPLETE_BOUNDS
				&& completeBoundsCandidate
				&& bulkBounds != null
				&& bulkResultCount == bulkBounds.volumeBlockCount();
		}
	}
}
