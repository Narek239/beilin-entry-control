package us.beiyue.beilinentryportability.common;

import java.util.Locale;

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
		CURRENT.set(new Actor(displayActorName(actorName), sourceName(source), null, null, null, 0));
		return new Scope(previous);
	}

	public static Scope pushNear(String actorName, String source, int x, int y, int z) {
		Actor previous = CURRENT.get();
		CURRENT.set(new Actor(displayActorName(actorName), sourceName(source), x, y, z, DEFAULT_BLOCK_ACTION_RADIUS));
		return new Scope(previous);
	}

	public static Actor system(String source) {
		return new Actor(SYSTEM_ACTOR, sourceName(source), null, null, null, 0);
	}

	public static Actor unknown(String source) {
		return new Actor(UNKNOWN_ACTOR, sourceName(source), null, null, null, 0);
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

	public static final class Scope implements AutoCloseable {
		private final Actor previous;
		private boolean closed;

		private Scope(Actor previous) {
			this.previous = previous;
		}

		@Override
		public void close() {
			if (closed) return;
			closed = true;
			if (previous == null) {
				CURRENT.remove();
			} else {
				CURRENT.set(previous);
			}
		}
	}

	public static final class Actor {
		public final String name;
		public final String nameKey;
		public final String source;
		private final Integer originX;
		private final Integer originY;
		private final Integer originZ;
		private final int radius;

		private Actor(String name, String source, Integer originX, Integer originY, Integer originZ, int radius) {
			this.name = name;
			this.nameKey = normalizeName(name);
			this.source = source;
			this.originX = originX;
			this.originY = originY;
			this.originZ = originZ;
			this.radius = Math.max(0, radius);
		}

		public boolean isSystemLike() {
			return SYSTEM_ACTOR.equals(name) || UNKNOWN_ACTOR.equals(name);
		}

		public boolean canAffectBlock(int x, int y, int z) {
			if (isSystemLike()) return true;
			if (originX == null || originY == null || originZ == null) return false;
			return Math.abs(x - originX) <= radius
				&& Math.abs(y - originY) <= radius
				&& Math.abs(z - originZ) <= radius;
		}
	}
}
