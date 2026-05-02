package us.beiyue.beilinentrycontrol.common.http;

/**
 * Outbound routing for Worker HTTP + WebSocket: system DNS to {@link us.beiyue.beilinentrycontrol.common.config.CommonConfig#wsUri()},
 * or {@link CommonConfig#wsBackupDnsHost()} resolved to literal IP while TLS SNI / {@code Host} stay logical.
 */
public enum OutboundRoute {
	PRIMARY,
	BACKUP
}
