package us.beiyue.beilinentrycontrol.common.ws;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import us.beiyue.beilinentrycontrol.common.config.CommonConfig;
import us.beiyue.beilinentrycontrol.common.gate.EntryGateState;
import us.beiyue.beilinentrycontrol.common.http.BeilinApiClient;
import us.beiyue.beilinentrycontrol.common.http.BeilinApiClient.JoinResult;
import us.beiyue.beilinentrycontrol.common.http.FixedHostDns;
import us.beiyue.beilinentrycontrol.common.log.CommonLogger;
import us.beiyue.beilinentrycontrol.common.platform.PlatformHooks;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import us.beiyue.beilinentrycontrol.common.http.InetAddressFormatting;
import us.beiyue.beilinentrycontrol.common.http.OutboundRoute;
import us.beiyue.beilinentrycontrol.common.http.OutboundRouteState;

/**
 * WebSocket connect/reconnect with OkHttp's 15s protocol-level ping/pong heartbeat.
 * PRIMARY uses system DNS; BACKUP resolves {@link CommonConfig#wsBackupDnsHost()} to an IP while
 * TLS SNI / {@code Host} use {@link CommonConfig#baseHost()} (same authority as {@link CommonConfig#wsUri()}).
 * Each successful WS session triggers {@link BeilinApiClient#playerJoinAsync} for all in-world players (auth clears online state on new connections).
 */
public final class BeilinWsClient {
	private static final int NORMAL_CLOSURE = 1000;
	private static final long PING_INTERVAL_SEC = 15;
	private static final long RECONNECT_INITIAL_SEC = 5;
	private static final long RECONNECT_MAX_SEC = 300;
	private static final double RECONNECT_BACKOFF_MULTIPLIER = 1.5;
	private static final int LOG_WARN_EVERY_N_FAILURES = 10;
	private static final long BACKUP_DNS_CACHE_MS = 60_000L;
	/** Caps backup-host rotation when DNS (e.g. Cloudflare anycast) returns many addresses; avoids long PRIMARY backoff delay. */
	private static final int BACKUP_INET_MAX_PER_DNS = 1;
	private static final int PRIMARY_PROBE_CONNECT_MS = 5_000;

	/**
	 * How the next close/failure for <em>this</em> WebSocket should be interpreted for 1006 silent logic.
	 */
	private enum SilentCloseMode {
		NORMAL,
		/** After first 1006 on PRIMARY: this socket is the immediate PRIMARY retry. */
		SILENT_PRIMARY_RETRY,
		/** After PRIMARY retry was 1006: this socket is the one BACKUP attempt. */
		SILENT_BACKUP_TRY,
		/** After first 1006 on BACKUP: this socket is the immediate BACKUP retry. */
		SILENT_BACKUP_RETRY,
		/** After BACKUP retry was 1006: this socket is the one PRIMARY attempt. */
		SILENT_PRIMARY_AFTER_BACKUP
	}

	private final OkHttpClient primaryWsClient = new OkHttpClient.Builder()
		.connectTimeout(Duration.ofSeconds(10))
		.pingInterval(Duration.ofSeconds(PING_INTERVAL_SEC))
		.build();
	private final OkHttpClient backupWsClient = new OkHttpClient.Builder()
		.connectTimeout(Duration.ofSeconds(10))
		.pingInterval(Duration.ofSeconds(PING_INTERVAL_SEC))
		.build();

	private final CommonConfig config;
	private final PlatformHooks hooks;
	private final BeilinApiClient apiClient;
	private final EntryGateState gateState;
	private final CommonLogger log;
	private final ScheduledExecutorService scheduler;
	private final OutboundRouteState routeState;

	private final AtomicReference<WebSocket> wsRef = new AtomicReference<>();
	private final AtomicBoolean reconnectScheduled = new AtomicBoolean(false);
	private final AtomicBoolean stopped = new AtomicBoolean(false);

	private ScheduledFuture<?> primaryProbeTask;
	private volatile boolean intentionalClose;
	private volatile long nextReconnectDelaySec = RECONNECT_INITIAL_SEC;
	private int connectFailCount = 0;

	/** After silent chain exhaust or non-1006 in chain, 1006 closes kick like other codes. */
	private volatile boolean loud1006Disconnects;
	private int loudAltOrdinal;
	/** Set by {@link #scheduleReconnect()} for the next delayed connect (scheduler thread only). */
	private OutboundRoute pendingScheduleRoute;
	/** Next listener's {@link SilentCloseMode} (scheduler thread only). */
	private SilentCloseMode pendingSilentCloseMode = SilentCloseMode.NORMAL;

	/** Cached {@link CommonConfig#wsBackupDnsHost()} answers; prefer IPv4; rotate cursor on handshake failure (scheduler thread). */
	private InetAddress[] backupInetCache;
	private long backupInetCacheExpiryMs;
	private int backupInetCursor;

	public BeilinWsClient(
		CommonConfig config,
		PlatformHooks hooks,
		BeilinApiClient apiClient,
		EntryGateState gateState,
		CommonLogger log,
		OutboundRouteState routeState
	) {
		this.config = config;
		this.hooks = hooks;
		this.apiClient = apiClient;
		this.gateState = gateState;
		this.log = log;
		this.routeState = Objects.requireNonNull(routeState, "routeState");
		this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "beilin-entry-control-ws");
			t.setDaemon(true);
			return t;
		});
	}

	public void start() {
		gateState.setAcceptingPlayers(false);
		if (scheduler.isShutdown()) {
			log.warn("Beilin WS: cannot start after scheduler shutdown");
			return;
		}
		stopped.set(false);
		intentionalClose = false;
		nextReconnectDelaySec = RECONNECT_INITIAL_SEC;
		connectFailCount = 0;
		loud1006Disconnects = false;
		routeState.setOutboundRoute(OutboundRoute.PRIMARY);
		loudAltOrdinal = 0;
		pendingScheduleRoute = null;
		pendingSilentCloseMode = SilentCloseMode.NORMAL;
		backupInetCache = null;
		backupInetCacheExpiryMs = 0L;
		backupInetCursor = 0;
		reconnectScheduled.set(false);
		BeilinWsEvents.setStructureAuditSender(this::sendStructureAuditEventsJson);
		connectAsync();
	}

	public void stop() {
		stopped.set(true);
		intentionalClose = true;
		reconnectScheduled.set(false);
		cancelPrimaryProbe();
		WebSocket w = wsRef.getAndSet(null);
		if (w != null) {
			try {
				w.close(NORMAL_CLOSURE, "shutdown");
			} catch (Exception ignored) {
			}
			try {
				w.cancel();
			} catch (Exception ignored) {
			}
		}
		scheduler.shutdownNow();
		apiClient.shutdownNow();
		shutdownOkHttp(primaryWsClient);
		shutdownOkHttp(backupWsClient);
		BeilinWsEvents.setStructureAuditSender(null);
	}

	boolean runOnScheduler(Runnable r) {
		if (r == null || isStopped()) return false;
		try {
			scheduler.execute(() -> {
				if (!isStopped()) r.run();
			});
			return true;
		} catch (RejectedExecutionException ignored) {
			return false;
		}
	}

	private ScheduledFuture<?> scheduleOnScheduler(Runnable r, long delay, TimeUnit unit) {
		if (r == null || isStopped()) return null;
		try {
			return scheduler.schedule(() -> {
				if (!isStopped()) r.run();
			}, delay, unit);
		} catch (RejectedExecutionException ignored) {
			return null;
		}
	}

	private ScheduledFuture<?> scheduleAtFixedRateOnScheduler(Runnable r, long initialDelay, long period, TimeUnit unit) {
		if (r == null || isStopped()) return null;
		try {
			return scheduler.scheduleAtFixedRate(() -> {
				if (!isStopped()) r.run();
			}, initialDelay, period, unit);
		} catch (RejectedExecutionException ignored) {
			return null;
		}
	}

	private boolean isStopped() {
		return stopped.get() || scheduler.isShutdown() || scheduler.isTerminated();
	}

	private void connectAsync() {
		if (intentionalClose || isStopped()) return;
		if (!config.isValid()) {
			log.warn("Beilin WS: config invalid, not reconnecting");
			return;
		}
		WebSocket prev = wsRef.getAndSet(null);
		if (prev != null) {
			try {
				prev.close(NORMAL_CLOSURE, "reconnect");
			} catch (Exception ignored) {
			}
		}

		OutboundRoute route;
		if (pendingScheduleRoute != null) {
			route = pendingScheduleRoute;
			pendingScheduleRoute = null;
		} else {
			route = resolveConnectRoute();
		}

		SilentCloseMode closeMode = pendingSilentCloseMode;
		pendingSilentCloseMode = SilentCloseMode.NORMAL;

		Request request;
		OkHttpClient client;
		InetAddress resolvedBackupIp = null;
		try {
			if (route == OutboundRoute.PRIMARY) {
				routeState.clearStickyBackupInet();
				request = new Request.Builder()
					.url(URI.create(config.wsUri()).toASCIIString())
					.build();
				client = primaryWsClient;
			} else {
				resolvedBackupIp = resolveBackupAddress();
				routeState.setStickyBackupInet(resolvedBackupIp);
				InetAddress backupIpForDns = resolvedBackupIp;
				request = new Request.Builder()
					.url(URI.create(config.wsUri()).toASCIIString())
					.build();
				client = backupWsClient.newBuilder()
					.dns(new FixedHostDns(config.baseHost(), () -> backupIpForDns))
					.build();
			}
		} catch (Exception e) {
			log.warn("Beilin WS: cannot build URI ({}): {}", route, e.toString());
			InetAddress backupIpUsed = resolvedBackupIp;
			runOnScheduler(() -> onConnectFailed(e, route, backupIpUsed, closeMode));
			return;
		}

		final OutboundRoute connectRoute = route;
		final InetAddress backupIpUsed = resolvedBackupIp;
		client.newWebSocket(request, new Listener(connectRoute, closeMode, backupIpUsed));
	}

	private void connectImmediateRun() {
		reconnectScheduled.set(false);
		pendingScheduleRoute = null;
		connectAsync();
	}

	private void onConnectFailed(Throwable err, OutboundRoute failedRoute) {
		onConnectFailed(err, failedRoute, null, SilentCloseMode.NORMAL);
	}

	private void onConnectFailed(Throwable err, OutboundRoute failedRoute, InetAddress backupIpTried) {
		onConnectFailed(err, failedRoute, backupIpTried, SilentCloseMode.NORMAL);
	}

	private void onConnectFailed(
		Throwable err,
		OutboundRoute failedRoute,
		InetAddress backupIpTried,
		SilentCloseMode closeMode
	) {
		if (isStopped()) return;
		connectFailCount++;
		boolean logDetail = connectFailCount <= 5 || connectFailCount % LOG_WARN_EVERY_N_FAILURES == 0;
		if (logDetail) {
			if (failedRoute == OutboundRoute.BACKUP && backupIpTried != null) {
				log.warn("Beilin WS connect failed (attempt {}, route=BACKUP, ip={}): {}",
					connectFailCount, InetAddressFormatting.hostLiteral(backupIpTried), err != null ? err.toString() : "(null)");
			} else {
				log.warn("Beilin WS connect failed (attempt {}, route={}): {}",
					connectFailCount, failedRoute, err != null ? err.toString() : "(null)");
			}
		}
		if (handleSilentConnectFailed(closeMode)) {
			return;
		}
		loud1006Disconnects = true;
		pendingSilentCloseMode = SilentCloseMode.NORMAL;
		onWsDown(true);
		scheduleHandshakeFailureReconnect(failedRoute);
	}

	/**
	 * Extends the 1006 silent chain across handshake failures:
	 * PRIMARY retry failure -> BACKUP try; BACKUP retry failure -> PRIMARY try; chain exhaustion -> loud mode.
	 */
	private boolean handleSilentConnectFailed(SilentCloseMode closeMode) {
		if (isStopped()) return true;
		switch (closeMode) {
			case SILENT_PRIMARY_RETRY:
				routeState.setOutboundRoute(OutboundRoute.BACKUP);
				pendingSilentCloseMode = SilentCloseMode.SILENT_BACKUP_TRY;
				onWsDown(false);
				connectImmediateRun();
				return true;
			case SILENT_BACKUP_RETRY:
				routeState.setOutboundRoute(OutboundRoute.PRIMARY);
				pendingSilentCloseMode = SilentCloseMode.SILENT_PRIMARY_AFTER_BACKUP;
				onWsDown(false);
				connectImmediateRun();
				return true;
			case SILENT_BACKUP_TRY:
			case SILENT_PRIMARY_AFTER_BACKUP:
			case NORMAL:
			default:
				return false;
		}
	}

	/**
	 * WebSocket handshake never completes (distinct from closed 1006).
	 * PRIMARY failure → BACKUP immediately. BACKUP failure → try another backup IP when present, else PRIMARY + backoff.
	 */
	private void scheduleHandshakeFailureReconnect(OutboundRoute failedRoute) {
		if (intentionalClose || isStopped()) return;
		if (!reconnectScheduled.compareAndSet(false, true)) return;

		long delaySec;
		if (failedRoute == OutboundRoute.PRIMARY) {
			delaySec = 0;
			pendingScheduleRoute = OutboundRoute.BACKUP;
			routeState.setOutboundRoute(OutboundRoute.BACKUP);
			log.info("Beilin WS: PRIMARY unreachable, failing over to BACKUP");
		} else if (advanceBackupInetCursorTryNext()) {
			delaySec = 0;
			pendingScheduleRoute = OutboundRoute.BACKUP;
			routeState.setOutboundRoute(OutboundRoute.BACKUP);
			try {
				InetAddress next = resolveBackupAddress();
				log.info("Beilin WS: BACKUP endpoint failed; retrying next ip ({})", InetAddressFormatting.hostLiteral(next));
			} catch (Exception ex) {
				log.info("Beilin WS: BACKUP endpoint failed; retrying alternate ip (resolver: {})",
					ex.toString());
			}
		} else {
			delaySec = nextReconnectDelaySec;
			nextReconnectDelaySec = Math.min((long) (nextReconnectDelaySec * RECONNECT_BACKOFF_MULTIPLIER), RECONNECT_MAX_SEC);
			pendingScheduleRoute = OutboundRoute.PRIMARY;
		}

		ScheduledFuture<?> scheduled = scheduleOnScheduler(() -> {
			reconnectScheduled.set(false);
			connectAsync();
		}, delaySec, TimeUnit.SECONDS);
		if (scheduled == null) reconnectScheduled.set(false);
	}

	/** @return {@code true} if another BACKUP IP is available this cycle (cursor advanced); {@code false} to give up BACKUP sweep. */
	private boolean advanceBackupInetCursorTryNext() {
		if (backupInetCache == null || backupInetCache.length <= 1) {
			invalidateBackupInetCache();
			return false;
		}
		if (backupInetCursor + 1 >= backupInetCache.length) {
			invalidateBackupInetCache();
			return false;
		}
		backupInetCursor++;
		return true;
	}

	private void invalidateBackupInetCache() {
		backupInetCache = null;
		backupInetCacheExpiryMs = 0L;
		backupInetCursor = 0;
	}

	private OutboundRoute resolveConnectRoute() {
		if (loud1006Disconnects) {
			return (loudAltOrdinal % 2 == 0) ? OutboundRoute.PRIMARY : OutboundRoute.BACKUP;
		}
		return routeState.getOutboundRoute();
	}

	private InetAddress resolveBackupAddress() throws UnknownHostException {
		long now = System.currentTimeMillis();
		boolean needLookup = backupInetCache == null
			|| now >= backupInetCacheExpiryMs
			|| backupInetCache.length == 0;
		if (needLookup) {
			InetAddress[] raw = InetAddress.getAllByName(config.wsBackupDnsHost());
			InetAddress[] sorted = preferInet4First(raw);
			if (sorted.length > BACKUP_INET_MAX_PER_DNS) {
				sorted = Arrays.copyOf(sorted, BACKUP_INET_MAX_PER_DNS);
			}
			backupInetCache = sorted;
			backupInetCursor = 0;
			backupInetCacheExpiryMs = now + BACKUP_DNS_CACHE_MS;
		}
		if (backupInetCache == null || backupInetCache.length == 0) {
			throw new UnknownHostException(config.wsBackupDnsHost());
		}
		if (backupInetCursor >= backupInetCache.length || backupInetCursor < 0) {
			backupInetCursor = 0;
		}
		return backupInetCache[backupInetCursor];
	}

	private static InetAddress[] preferInet4First(InetAddress[] raw) {
		if (raw == null || raw.length == 0) return new InetAddress[0];
		List<InetAddress> v4 = new ArrayList<>();
		List<InetAddress> rest = new ArrayList<>();
		for (InetAddress a : raw) {
			if (a instanceof Inet4Address) v4.add(a);
			else rest.add(a);
		}
		v4.addAll(rest);
		return v4.toArray(new InetAddress[0]);
	}

	private void scheduleReconnect() {
		if (intentionalClose || isStopped()) return;
		if (!reconnectScheduled.compareAndSet(false, true)) return;
		long delay = nextReconnectDelaySec;
		nextReconnectDelaySec = Math.min((long) (nextReconnectDelaySec * RECONNECT_BACKOFF_MULTIPLIER), RECONNECT_MAX_SEC);

		if (loud1006Disconnects) {
			pendingScheduleRoute = (loudAltOrdinal % 2 == 0) ? OutboundRoute.PRIMARY : OutboundRoute.BACKUP;
			loudAltOrdinal++;
		} else {
			pendingScheduleRoute = null;
		}

		ScheduledFuture<?> scheduled = scheduleOnScheduler(() -> {
			reconnectScheduled.set(false);
			connectAsync();
		}, delay, TimeUnit.SECONDS);
		if (scheduled == null) reconnectScheduled.set(false);
	}

	private void onWsDown(boolean kickPlayers) {
		if (isStopped()) return;
		gateState.setAcceptingPlayers(false);
		if (kickPlayers) {
			hooks.runOnServerThread(() -> hooks.kickAll(EntryGateState.SYNC_MESSAGE));
		}
	}

	private void onWsUp(WebSocket ws) {
		if (isStopped()) return;
		wsRef.set(ws);
		nextReconnectDelaySec = RECONNECT_INITIAL_SEC;
		connectFailCount = 0;
		gateState.setAcceptingPlayers(true);
		// Auth server clears online players on each new WebSocket; re-register everyone in-world.
		syncOnlinePlayersAfterWsConnect();
		requestExportJobs(ws);
	}

	private void requestExportJobs(WebSocket ws) {
		try {
			if (ws != null && wsRef.get() == ws && ws.send("{\"action\":\"export_jobs_request\"}")) {
				log.debug("Beilin WS requested export jobs");
			}
		} catch (Exception e) {
			log.warn("Beilin WS export job request failed: {}", e.toString());
		}
	}

	private boolean sendStructureAuditEventsJson(String text) {
		try {
			WebSocket ws = wsRef.get();
			if (ws == null || text == null || text.isBlank()) return false;
			boolean ok = ws.send(text);
			if (!ok) {
				log.warn("Beilin WS structure audit send failed: output queue closed");
			}
			return ok;
		} catch (Exception e) {
			log.warn("Beilin WS structure audit send failed: {}", e.toString());
			return false;
		}
	}

	private void syncOnlinePlayersAfterWsConnect() {
		if (isStopped()) return;
		hooks.runOnServerThread(() -> {
			if (isStopped()) return;
			List<String> players = hooks.getOnlineUsernames();
			if (players == null || players.isEmpty()) return;
			for (String username : players) {
				if (username == null || username.isEmpty()) continue;
				String u = username;
				apiClient.playerJoinAsync(u)
					.whenComplete((JoinResult r, Throwable ex) -> {
						if (r != null && !r.ok) {
							hooks.runOnServerThread(() -> hooks.kickByUsername(u, "AccessRevoked"));
						}
					});
			}
		});
	}

	private void cancelPrimaryProbe() {
		if (primaryProbeTask != null) {
			primaryProbeTask.cancel(false);
			primaryProbeTask = null;
		}
	}

	private void ensurePrimaryProbe() {
		cancelPrimaryProbe();
		if (intentionalClose || isStopped() || loud1006Disconnects) return;
		if (routeState.getOutboundRoute() != OutboundRoute.BACKUP) return;
		long sec = config.wsPrimaryProbeIntervalSec();
		primaryProbeTask = scheduleAtFixedRateOnScheduler(this::runPrimaryProbe, sec, sec, TimeUnit.SECONDS);
	}

	private void runPrimaryProbe() {
		if (intentionalClose || isStopped() || loud1006Disconnects) return;
		if (routeState.getOutboundRoute() != OutboundRoute.BACKUP) return;
		if (pendingSilentCloseMode != SilentCloseMode.NORMAL) return;
		try {
			if (tryPrimaryTlsHandshake()) {
				log.info("Beilin WS: PRIMARY route reachable, switching from BACKUP");
				routeState.setOutboundRoute(OutboundRoute.PRIMARY);
				loudAltOrdinal = 0;
				cancelPrimaryProbe();
				WebSocket w = wsRef.getAndSet(null);
				if (w != null) {
					onWsDown(false);
					try {
						w.close(NORMAL_CLOSURE, "switch_to_primary");
					} catch (Exception ignored) {
					}
				}
				connectImmediateRun();
			}
		} catch (Exception e) {
			log.debug("Beilin WS PRIMARY probe failed: {}", e.toString());
		}
	}

	private boolean tryPrimaryTlsHandshake() throws IOException {
		String sni = config.baseHost();
		InetAddress[] targets = InetAddress.getAllByName(sni);
		InetAddress target = null;
		for (InetAddress a : targets) {
			if (a instanceof Inet4Address) {
				target = a;
				break;
			}
		}
		if (target == null && targets.length > 0) {
			target = targets[0];
		}
		if (target == null) {
			return false;
		}
		try (Socket raw = new Socket()) {
			raw.connect(new InetSocketAddress(target, 443), PRIMARY_PROBE_CONNECT_MS);
			SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
			try (SSLSocket ssl = (SSLSocket) factory.createSocket(raw, sni, 443, true)) {
				SSLParameters p = ssl.getSSLParameters();
				p.setServerNames(List.of(new SNIHostName(sni)));
				p.setEndpointIdentificationAlgorithm("HTTPS");
				ssl.setSSLParameters(p);
				ssl.setSoTimeout(PRIMARY_PROBE_CONNECT_MS);
				ssl.startHandshake();
			}
		}
		return true;
	}

	private void handleTextMessage(String text) {
		if (isStopped()) return;
		try {
			log.debug("Beilin WS recv: {}", text);
			JsonObject o = JsonParser.parseString(text).getAsJsonObject();
			if (!o.has("action")) return;
			String action = o.get("action").getAsString();
			if ("structure_audit_ack".equals(action)) {
				List<String> eventIds = parseStringArray(o, "event_ids");
				if (!eventIds.isEmpty()) {
					BeilinWsEvents.dispatchStructureAuditAck(eventIds);
				}
				return;
			}
			if ("kick".equals(action)) {
				String username = null;
				if (o.has("username")) username = o.get("username").getAsString();
				else if (o.has("user")) username = o.get("user").getAsString();
				else if (o.has("player")) username = o.get("player").getAsString();
				if (username == null || username.isEmpty()) return;
				String reason = o.has("reason") && !o.get("reason").isJsonNull()
					? o.get("reason").getAsString()
					: "已被踢出";
				log.info("Beilin WS kick {} reason={}", username, reason);
				final String kickUsername = username;
				final String kickReason = reason;
				hooks.runOnServerThread(() -> hooks.kickByUsername(kickUsername, kickReason));
				return;
			}
			if ("export_jobs".equals(action)) {
				List<WsExportJob> jobs = parseExportJobs(o);
				BeilinWsEvents.dispatchExportJobs(jobs);
				if (!jobs.isEmpty()) {
					log.info("Beilin WS export queue has {} pending job(s)", jobs.size());
				}
			}
		} catch (Exception e) {
			log.warn("Beilin WS message parse failed: {}", e.toString());
		}
	}

	private static List<WsExportJob> parseExportJobs(JsonObject root) {
		JsonArray jobs = root.has("jobs") && root.get("jobs").isJsonArray()
			? root.getAsJsonArray("jobs")
			: new JsonArray();
		List<WsExportJob> out = new ArrayList<>();
		for (JsonElement e : jobs) {
			if (!e.isJsonObject()) continue;
			JsonObject o = e.getAsJsonObject();
			long id = o.has("request_id") ? o.get("request_id").getAsLong() : 0L;
			String username = stringOrNull(o, "minecraft_username");
			if (id <= 0 || username == null || username.isBlank()) continue;
			out.add(new WsExportJob(
				id,
				username,
				stringOrNull(o, "requested_at"),
				stringOrNull(o, "reviewed_at")
			));
		}
		return out;
	}

	private static List<String> parseStringArray(JsonObject root, String key) {
		JsonArray values = root.has(key) && root.get(key).isJsonArray()
			? root.getAsJsonArray(key)
			: new JsonArray();
		List<String> out = new ArrayList<>();
		for (JsonElement e : values) {
			if (!e.isJsonPrimitive()) continue;
			String value = e.getAsString();
			if (value != null && !value.isBlank()) out.add(value);
		}
		return out;
	}

	private static String stringOrNull(JsonObject o, String key) {
		if (!o.has(key) || o.get(key).isJsonNull() || !o.get(key).isJsonPrimitive()) {
			return null;
		}
		return o.get(key).getAsString();
	}

	private static void shutdownOkHttp(OkHttpClient client) {
		try {
			client.dispatcher().cancelAll();
		} catch (Exception ignored) {
		}
		try {
			client.dispatcher().executorService().shutdownNow();
		} catch (Exception ignored) {
		}
		try {
			client.connectionPool().evictAll();
		} catch (Exception ignored) {
		}
	}

	private void handleOpen(WebSocket webSocket, OutboundRoute connectRoute, SilentCloseMode closeMode) {
		if (isStopped()) return;
		if (closeMode == SilentCloseMode.NORMAL) {
			log.info("Beilin WS connected");
		} else {
			log.debug("Beilin WS connected (silent 1006 retry path, closeMode={})", closeMode);
		}
		routeState.setOutboundRoute(connectRoute);
		loud1006Disconnects = false;
		loudAltOrdinal = 0;
		onWsUp(webSocket);
		if (connectRoute == OutboundRoute.BACKUP) {
			ensurePrimaryProbe();
		} else {
			cancelPrimaryProbe();
		}
	}

	private void handleClose(WebSocket webSocket, int statusCode, String reason, OutboundRoute connectRoute, SilentCloseMode closeMode) {
		if (isStopped()) return;
		boolean is1006 = (statusCode == 1006);
		if (is1006) {
			log.debug("Beilin WS closed 1006, reconnecting without kick (until loud mode)");
		} else {
			log.info("Beilin WS closed {} {}", statusCode, reason);
		}
		boolean wasActive = wsRef.compareAndSet(webSocket, null);
		if (intentionalClose || !wasActive) {
			return;
		}
		if (statusCode == NORMAL_CLOSURE && "switch_to_primary".equals(reason)) {
			onWsDown(false);
			connectImmediateRun();
			return;
		}

		boolean kickOnThisDown = loud1006Disconnects || !is1006;
		boolean handled = false;

		if (is1006) {
			switch (closeMode) {
				case SILENT_PRIMARY_RETRY:
					routeState.setOutboundRoute(OutboundRoute.BACKUP);
					pendingSilentCloseMode = SilentCloseMode.SILENT_BACKUP_TRY;
					onWsDown(false);
					connectImmediateRun();
					handled = true;
					break;
				case SILENT_BACKUP_TRY:
					loud1006Disconnects = true;
					pendingSilentCloseMode = SilentCloseMode.NORMAL;
					loudAltOrdinal = 0;
					onWsDown(true);
					scheduleReconnect();
					handled = true;
					break;
				case SILENT_BACKUP_RETRY:
					routeState.setOutboundRoute(OutboundRoute.PRIMARY);
					pendingSilentCloseMode = SilentCloseMode.SILENT_PRIMARY_AFTER_BACKUP;
					onWsDown(false);
					connectImmediateRun();
					handled = true;
					break;
				case SILENT_PRIMARY_AFTER_BACKUP:
					loud1006Disconnects = true;
					pendingSilentCloseMode = SilentCloseMode.NORMAL;
					loudAltOrdinal = 0;
					onWsDown(true);
					scheduleReconnect();
					handled = true;
					break;
				case NORMAL:
				default:
					break;
			}
		}

		if (!handled) {
			if (!loud1006Disconnects && is1006) {
				if (connectRoute == OutboundRoute.PRIMARY) {
					pendingSilentCloseMode = SilentCloseMode.SILENT_PRIMARY_RETRY;
					onWsDown(false);
					connectImmediateRun();
					return;
				}
				if (connectRoute == OutboundRoute.BACKUP) {
					pendingSilentCloseMode = SilentCloseMode.SILENT_BACKUP_RETRY;
					onWsDown(false);
					connectImmediateRun();
					return;
				}
			}
			onWsDown(kickOnThisDown);
			scheduleReconnect();
		}
	}

	private void handleFailure(
		WebSocket webSocket,
		Throwable error,
		OutboundRoute connectRoute,
		SilentCloseMode closeMode,
		InetAddress backupIpTried,
		boolean opened
	) {
		if (isStopped()) return;
		if (wsRef.get() == webSocket) {
			handleClose(webSocket, 1006, error != null ? error.toString() : "", connectRoute, closeMode);
			return;
		}
		if (opened) {
			return;
		}
		onConnectFailed(error, connectRoute, backupIpTried, closeMode);
	}

	private final class Listener extends WebSocketListener {
		private final OutboundRoute connectRoute;
		private final SilentCloseMode closeMode;
		private final InetAddress backupIpTried;
		private volatile boolean opened;

		Listener(OutboundRoute connectRoute, SilentCloseMode closeMode, InetAddress backupIpTried) {
			this.connectRoute = connectRoute;
			this.closeMode = closeMode;
			this.backupIpTried = backupIpTried;
		}

		@Override
		public void onOpen(WebSocket webSocket, Response response) {
			opened = true;
			runOnScheduler(() -> handleOpen(webSocket, connectRoute, closeMode));
		}

		@Override
		public void onMessage(WebSocket webSocket, String text) {
			runOnScheduler(() -> handleTextMessage(text));
		}

		@Override
		public void onMessage(WebSocket webSocket, ByteString bytes) {
			runOnScheduler(() -> handleTextMessage(bytes.utf8()));
		}

		@Override
		public void onClosing(WebSocket webSocket, int code, String reason) {
			webSocket.close(code, reason);
		}

		@Override
		public void onClosed(WebSocket webSocket, int code, String reason) {
			runOnScheduler(() -> handleClose(webSocket, code, reason, connectRoute, closeMode));
		}

		@Override
		public void onFailure(WebSocket webSocket, Throwable t, Response response) {
			boolean wasOpened = opened;
			runOnScheduler(() -> handleFailure(webSocket, t, connectRoute, closeMode, backupIpTried, wasOpened));
		}
	}
}
