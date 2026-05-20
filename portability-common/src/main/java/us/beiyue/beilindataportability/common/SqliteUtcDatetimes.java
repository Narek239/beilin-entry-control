package us.beiyue.beilindataportability.common;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

public final class SqliteUtcDatetimes {
	private static final Pattern SQLITE_UTC_DATETIME = Pattern.compile("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
	private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
		.withZone(ZoneOffset.UTC);
	private static final DateTimeFormatter LOCAL_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	private SqliteUtcDatetimes() {
	}

	public static String now() {
		return format(Instant.now());
	}

	public static String format(Instant instant) {
		return FORMATTER.format(instant);
	}

	public static String normalize(String value) {
		if (value == null) return null;
		String trimmed = value.trim();
		if (trimmed.isEmpty()) return trimmed;
		if (SQLITE_UTC_DATETIME.matcher(trimmed).matches()) return trimmed;
		try {
			return format(Instant.parse(trimmed));
		} catch (DateTimeParseException ignored) {
		}
		try {
			return format(LocalDateTime.parse(trimmed, LOCAL_FORMATTER).toInstant(ZoneOffset.UTC));
		} catch (DateTimeParseException ignored) {
		}
		return trimmed;
	}
}
