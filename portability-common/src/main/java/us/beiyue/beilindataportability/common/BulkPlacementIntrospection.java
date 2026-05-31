package us.beiyue.beilindataportability.common;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class BulkPlacementIntrospection {
	private BulkPlacementIntrospection() {
	}

	public static BulkPlacementBounds worldEditRegionBounds(Object editSession, Object region) {
		if (region == null) return null;
		Object min = invoke(region, "getMinimumPoint");
		Object max = invoke(region, "getMaximumPoint");
		if (min == null || max == null) return null;
		Integer minX = coordinate(min, "x");
		Integer minY = coordinate(min, "y");
		Integer minZ = coordinate(min, "z");
		Integer maxX = coordinate(max, "x");
		Integer maxY = coordinate(max, "y");
		Integer maxZ = coordinate(max, "z");
		if (minX == null || minY == null || minZ == null || maxX == null || maxY == null || maxZ == null) {
			return null;
		}
		return new BulkPlacementBounds(
			worldEditDimension(editSession),
			minX,
			minY,
			minZ,
			maxX,
			maxY,
			maxZ,
			BulkPlacementShape.fromBounds(minX, minY, minZ, maxX, maxY, maxZ).blockCount
		);
	}

	public static boolean isEffortlessBulkContext(Object context) {
		if (!Boolean.TRUE.equals(invoke(context, "isBuildType"))) return false;
		String state = effortlessBuildState(context);
		return "PLACE_BLOCK".equals(state) || "PASTE_STRUCTURE".equals(state) || "BREAK_BLOCK".equals(state);
	}

	public static String effortlessBuildState(Object context) {
		Object buildState = invoke(context, "buildState");
		return buildState != null ? enumName(buildState) : null;
	}

	public static BulkPlacementBounds effortlessBounds(Object player, Object context) {
		if (context == null) return null;
		Object box = invoke(context, "getInteractionBox");
		Integer sizeX = coordinate(box, "x");
		Integer sizeY = coordinate(box, "y");
		Integer sizeZ = coordinate(box, "z");
		Integer volume = intValue(invoke(context, "getVolume"));
		if (volume == null && sizeX != null && sizeY != null && sizeZ != null) {
			volume = safeVolume(sizeX, sizeY, sizeZ);
		}
		String dimension = effortlessDimension(player, context);
		Object first = invoke(context, "getPosition", 0);
		Integer interactionCount = intValue(invoke(context, "interactionsSize"));
		Object last = interactionCount != null && interactionCount > 1 ? invoke(context, "getPosition", interactionCount - 1) : null;
		BulkPlacementBounds endpointBounds = boundsFromPositions(dimension, first, last, volume);
		if (endpointBounds != null && sameSize(endpointBounds, sizeX, sizeY, sizeZ)) return endpointBounds;
		BulkPlacementBounds anchorBounds = boundsFromAnchorAndBox(dimension, first, sizeX, sizeY, sizeZ, volume);
		return anchorBounds != null ? anchorBounds : endpointBounds;
	}

	public static BulkPlacementBounds worldEditPasteBounds(Object editSession, Object session, Object actor, boolean atOrigin) {
		Object holder = invoke(session, "getClipboard");
		Object clipboard = invoke(holder, "getClipboard");
		Object region = invoke(clipboard, "getRegion");
		Object origin = invoke(clipboard, "getOrigin");
		Object transform = invoke(holder, "getTransform");
		Object to = atOrigin ? origin : invoke(session, "getPlacementPosition", actor);
		Object min = invoke(region, "getMinimumPoint");
		Object max = invoke(region, "getMaximumPoint");
		if (min == null || max == null || origin == null || to == null) return null;
		Integer minX = coordinate(min, "x");
		Integer minY = coordinate(min, "y");
		Integer minZ = coordinate(min, "z");
		Integer maxX = coordinate(max, "x");
		Integer maxY = coordinate(max, "y");
		Integer maxZ = coordinate(max, "z");
		if (minX == null || minY == null || minZ == null || maxX == null || maxY == null || maxZ == null) return null;
		List<Object> corners = new ArrayList<>(8);
		for (int x : new int[] {minX, maxX}) {
			for (int y : new int[] {minY, maxY}) {
				for (int z : new int[] {minZ, maxZ}) {
					Object corner = vectorAt(min, x, y, z);
					Object placed = transformPasteCorner(corner, origin, to, transform);
					corners.add(placed != null ? placed : offsetCorner(corner, origin, to));
				}
			}
		}
		return boundsFromVectors(worldEditDimension(editSession), corners, BulkPlacementShape.fromBounds(minX, minY, minZ, maxX, maxY, maxZ).blockCount);
	}

	public static String actorName(Object actor) {
		Object name = invoke(actor, "getName");
		String direct = name instanceof String ? stringValue(name) : null;
		if (direct != null) return direct;
		Object displayName = invoke(actor, "getDisplayName");
		direct = displayName instanceof String ? stringValue(displayName) : null;
		if (direct != null) return direct;
		Object profile = invoke(actor, "getProfile");
		if (profile == null) profile = invoke(actor, "getGameProfile");
		direct = stringValue(invoke(profile, "getName"));
		if (direct != null) return direct;
		direct = stringValue(invoke(name, "getString"));
		return direct != null ? direct : ActorContext.UNKNOWN_ACTOR;
	}

	public static Integer blockCoordinate(Object vector, String axis) {
		return coordinate(vector, axis);
	}

	public static Object invokeNoArgs(Object target, String name) {
		return invoke(target, name);
	}

	public static Object invokeAny(Object target, String name, Object... args) {
		return invoke(target, name, args);
	}

	public static boolean isWorldEditAirPattern(Object pattern) {
		if (pattern == null) return false;
		Object block = invoke(pattern, "getBlock");
		return isWorldEditAirState(block) || isWorldEditAirState(pattern);
	}

	public static String worldEditDimension(Object editSession) {
		Object worldEditWorld = invoke(editSession, "getWorld");
		Object minecraftWorld = invoke(worldEditWorld, "getWorld");
		String dimension = dimensionString(minecraftWorld);
		if (dimension != null) return dimension;
		String id = stringValue(invoke(worldEditWorld, "getId"));
		if (id != null && id.contains(":")) return id;
		String name = stringValue(invoke(worldEditWorld, "getName"));
		if (name != null && name.contains(":")) return name;
		return "minecraft:overworld";
	}

	private static Integer coordinate(Object vector, String axis) {
		String upper = axis.toUpperCase(Locale.ROOT);
		String title = upper.substring(0, 1) + axis.substring(1);
		Integer value = intValue(invoke(vector, "getBlock" + upper));
		if (value != null) return value;
		value = intValue(invoke(vector, "block" + upper));
		if (value != null) return value;
		value = intValue(invoke(vector, "get" + title));
		if (value != null) return value;
		value = intValue(invoke(vector, axis));
		if (value != null) return value;
		return intField(vector, axis);
	}

	private static String enumName(Object value) {
		if (value instanceof Enum<?> e) return e.name();
		return String.valueOf(value).trim();
	}

	private static BulkPlacementBounds boundsFromVectors(String dimension, List<Object> vectors, int blockCount) {
		Integer minX = null;
		Integer minY = null;
		Integer minZ = null;
		Integer maxX = null;
		Integer maxY = null;
		Integer maxZ = null;
		for (Object vector : vectors) {
			Integer x = coordinate(vector, "x");
			Integer y = coordinate(vector, "y");
			Integer z = coordinate(vector, "z");
			if (x == null || y == null || z == null) continue;
			minX = minX == null ? x : Math.min(minX, x);
			minY = minY == null ? y : Math.min(minY, y);
			minZ = minZ == null ? z : Math.min(minZ, z);
			maxX = maxX == null ? x : Math.max(maxX, x);
			maxY = maxY == null ? y : Math.max(maxY, y);
			maxZ = maxZ == null ? z : Math.max(maxZ, z);
		}
		if (minX == null || minY == null || minZ == null || maxX == null || maxY == null || maxZ == null) return null;
		return new BulkPlacementBounds(dimension, minX, minY, minZ, maxX, maxY, maxZ, blockCount);
	}

	private static BulkPlacementBounds boundsFromPositions(String dimension, Object first, Object last, Integer blockCount) {
		if (first == null) return null;
		Object end = last != null ? last : first;
		Integer firstX = coordinate(first, "x");
		Integer firstY = coordinate(first, "y");
		Integer firstZ = coordinate(first, "z");
		Integer lastX = coordinate(end, "x");
		Integer lastY = coordinate(end, "y");
		Integer lastZ = coordinate(end, "z");
		if (firstX == null || firstY == null || firstZ == null || lastX == null || lastY == null || lastZ == null) return null;
		int minX = Math.min(firstX, lastX);
		int minY = Math.min(firstY, lastY);
		int minZ = Math.min(firstZ, lastZ);
		int maxX = Math.max(firstX, lastX);
		int maxY = Math.max(firstY, lastY);
		int maxZ = Math.max(firstZ, lastZ);
		return new BulkPlacementBounds(dimension, minX, minY, minZ, maxX, maxY, maxZ, blockCount != null ? blockCount : 1);
	}

	private static BulkPlacementBounds boundsFromAnchorAndBox(
		String dimension,
		Object anchor,
		Integer sizeX,
		Integer sizeY,
		Integer sizeZ,
		Integer blockCount
	) {
		if (anchor == null || sizeX == null || sizeY == null || sizeZ == null) return null;
		if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0) return null;
		Integer x = coordinate(anchor, "x");
		Integer y = coordinate(anchor, "y");
		Integer z = coordinate(anchor, "z");
		if (x == null || y == null || z == null) return null;
		int count = blockCount != null ? blockCount : safeVolume(sizeX, sizeY, sizeZ);
		return new BulkPlacementBounds(dimension, x, y, z, x + sizeX - 1, y + sizeY - 1, z + sizeZ - 1, count);
	}

	private static boolean sameSize(BulkPlacementBounds bounds, Integer sizeX, Integer sizeY, Integer sizeZ) {
		if (bounds == null || sizeX == null || sizeY == null || sizeZ == null) return false;
		BulkPlacementShape shape = bounds.shape();
		return shape.sizeX == sizeX
			&& shape.sizeY == sizeY
			&& shape.sizeZ == sizeZ;
	}

	private static int safeVolume(int sizeX, int sizeY, int sizeZ) {
		long volume = (long) Math.max(1, sizeX) * Math.max(1, sizeY) * Math.max(1, sizeZ);
		return volume > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) volume;
	}

	private static Object transformPasteCorner(Object corner, Object origin, Object to, Object transform) {
		if (corner == null || origin == null || to == null || transform == null) return null;
		Object relative = invoke(corner, "subtract", origin);
		Object relativeVector = invoke(relative, "toVector3");
		Object transformed = invoke(transform, "apply", relativeVector);
		Object toVector = invoke(to, "toVector3");
		Object placedVector = invoke(toVector, "add", transformed);
		return invoke(placedVector, "toBlockPoint");
	}

	private static Object offsetCorner(Object corner, Object origin, Object to) {
		Integer cornerX = coordinate(corner, "x");
		Integer cornerY = coordinate(corner, "y");
		Integer cornerZ = coordinate(corner, "z");
		Integer originX = coordinate(origin, "x");
		Integer originY = coordinate(origin, "y");
		Integer originZ = coordinate(origin, "z");
		Integer toX = coordinate(to, "x");
		Integer toY = coordinate(to, "y");
		Integer toZ = coordinate(to, "z");
		if (cornerX == null || cornerY == null || cornerZ == null || originX == null || originY == null || originZ == null || toX == null || toY == null || toZ == null) {
			return null;
		}
		return vectorAt(corner, cornerX + toX - originX, cornerY + toY - originY, cornerZ + toZ - originZ);
	}

	private static Object vectorAt(Object sample, int x, int y, int z) {
		if (sample == null) return null;
		try {
			Method method = sample.getClass().getMethod("at", int.class, int.class, int.class);
			method.setAccessible(true);
			return method.invoke(null, x, y, z);
		} catch (ReflectiveOperationException | RuntimeException ignored) {
			return null;
		}
	}

	private static String effortlessDimension(Object player, Object context) {
		Object extras = invoke(context, "extras");
		String dimension = stringValue(invoke(extras, "dimensionId"));
		if (dimension != null && dimension.contains(":")) return dimension;
		Object world = invoke(player, "getWorld");
		Object dimensionId = invoke(world, "getDimensionId");
		Object location = invoke(dimensionId, "location");
		dimension = stringValue(location);
		return dimension != null && dimension.contains(":") ? dimension : "minecraft:overworld";
	}

	private static String dimensionString(Object minecraftWorld) {
		Object dimensionKey = invoke(minecraftWorld, "dimension");
		Object location = invoke(dimensionKey, "location");
		String dimension = stringValue(location);
		return dimension != null && dimension.contains(":") ? dimension : null;
	}

	private static String stringValue(Object value) {
		if (value == null) return null;
		if (value instanceof String s && !s.isBlank()) return s.trim();
		String s = String.valueOf(value);
		return s == null || s.isBlank() ? null : s.trim();
	}

	private static boolean isWorldEditAirState(Object state) {
		if (state == null) return false;
		Object blockType = invoke(state, "getBlockType");
		String id = stringValue(invoke(blockType, "getId"));
		if (isAirString(id)) return true;
		String name = stringValue(invoke(blockType, "getName"));
		if (isAirString(name)) return true;
		return isAirString(stringValue(state));
	}

	private static boolean isAirString(String value) {
		if (value == null) return false;
		String s = value.trim();
		return "minecraft:air".equals(s)
			|| "air".equals(s)
			|| "Block{minecraft:air}".equals(s)
			|| s.endsWith("{minecraft:air}");
	}

	private static Integer intValue(Object value) {
		if (value instanceof Number n) return n.intValue();
		return null;
	}

	private static Integer intField(Object target, String name) {
		if (target == null) return null;
		try {
			Field field = target.getClass().getField(name);
			field.setAccessible(true);
			return intValue(field.get(target));
		} catch (ReflectiveOperationException | RuntimeException ignored) {
			return null;
		}
	}

	private static Object invoke(Object target, String name, Object... args) {
		if (target == null) return null;
		try {
			Method method = method(target.getClass(), name, args);
			if (method == null) return null;
			method.setAccessible(true);
			return method.invoke(target, args);
		} catch (ReflectiveOperationException | RuntimeException ignored) {
			return null;
		}
	}

	private static Method method(Class<?> type, String name, Object[] args) {
		for (Method method : type.getMethods()) {
			if (!method.getName().equals(name) || method.getParameterCount() != args.length) continue;
			Class<?>[] parameterTypes = method.getParameterTypes();
			boolean matches = true;
			for (int i = 0; i < parameterTypes.length; i += 1) {
				if (args[i] != null && !wrap(parameterTypes[i]).isInstance(args[i])) {
					matches = false;
					break;
				}
			}
			if (matches) return method;
		}
		return null;
	}

	private static Class<?> wrap(Class<?> type) {
		if (!type.isPrimitive()) return type;
		if (type == boolean.class) return Boolean.class;
		if (type == byte.class) return Byte.class;
		if (type == short.class) return Short.class;
		if (type == int.class) return Integer.class;
		if (type == long.class) return Long.class;
		if (type == float.class) return Float.class;
		if (type == double.class) return Double.class;
		if (type == char.class) return Character.class;
		return type;
	}
}
