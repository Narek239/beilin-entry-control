package us.beiyue.beilindataportability.common;

public final class DimensionNames {
	private static final String DEFAULT_DIMENSION = "minecraft:overworld";

	private DimensionNames() {
	}

	public static String normalize(String raw) {
		if (raw == null || raw.isBlank()) return DEFAULT_DIMENSION;
		String value = raw.trim();
		int minecraftIndex = value.indexOf("minecraft:");
		if (minecraftIndex > 0) return value.substring(minecraftIndex);
		return value;
	}

	public static String normalizeWorldEditId(String rawId, String worldName) {
		if (rawId == null || rawId.isBlank()) return DEFAULT_DIMENSION;
		String id = rawId.trim();
		String name = worldName == null ? null : worldName.trim();
		if (name != null && !name.isBlank()) {
			String prefix = name + "_";
			if (id.startsWith(prefix)) {
				String dimension = id.substring(prefix.length()).trim();
				if (isResourceLocation(dimension)) return dimension;
			}
		}
		return normalize(id);
	}

	private static boolean isResourceLocation(String value) {
		if (value == null || value.isBlank()) return false;
		int colon = value.indexOf(':');
		return colon > 0 && colon + 1 < value.length();
	}
}
