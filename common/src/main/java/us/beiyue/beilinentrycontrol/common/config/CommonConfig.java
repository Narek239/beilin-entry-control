package us.beiyue.beilinentrycontrol.common.config;

import java.net.URI;

/**
 * Minimal config interface to keep {@code common} free of FabricLoader.
 */
public interface CommonConfig {
	boolean isValid();
	String httpBase();
	String wsUri();
	boolean isApiKeyConfigured();

	/**
	 * Host name from {@link #wsUri()} authority (same as {@code baseHost} in mods); used for TLS SNI / {@code Host} on WS.
	 */
	default String baseHost() {
		try {
			URI u = URI.create(wsUri());
			String h = u.getHost();
			if (h != null && !h.isBlank()) {
				return h;
			}
		} catch (Exception ignored) {
		}
		return "beiyue.us";
	}

	/** Hostname resolved to IP for BACKUP WebSocket path only; SNI / {@code Host} still use {@link #baseHost()}. */
	String wsBackupDnsHost();

	/** While on BACKUP route, interval to probe PRIMARY reachability (seconds). */
	long wsPrimaryProbeIntervalSec();
}

