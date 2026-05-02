package us.beiyue.beilinentrycontrol.common.ws;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import us.beiyue.beilinentrycontrol.common.config.CommonConfig;
import us.beiyue.beilinentrycontrol.common.gate.EntryGateState;
import us.beiyue.beilinentrycontrol.common.http.BeilinApiClient;
import us.beiyue.beilinentrycontrol.common.http.BeilinApiClient.JoinResult;
import us.beiyue.beilinentrycontrol.common.log.CommonLogger;
import us.beiyue.beilinentrycontrol.common.platform.PlatformHooks;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
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
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
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
 * WebSocket connect/reconnect, 15s JSON ping, 30s pong timeout.
 * PRIMARY uses system DNS; BACKUP resolves {@link CommonConfig#wsBackupDnsHost()} to an IP while
 * TLS SNI / {@code Host} use {@link CommonConfig#baseHost()} (same authority as {@link CommonConfig#wsUri()}).
 * Each successful WS session triggers {@link BeilinApiClient#playerJoinAsync} for all in-world players (auth clears online state on new connections).
 */
public final class BeilinWsClient {
	private static final long PING_INTERVAL_SEC = 15;
	private static final long PONG_TIMEOUT_SEC = 30;
	private static final long RECONNECT_INITIAL_SEC = 5;
	private static final long RECONNECT_MAX_SEC = 300;
	private static final double RECONNECT_BACKOFF_MULTIPLIER = 1.5;
	private static final int LOG_WARN_EVERY_N_FAILURES = 10;
	private static final long BACKUP_DNS_CACHE_MS = 60_000L;
	/** Caps backup-host rotation when DNS (e.g. Cloudflare anycast) returns many addresses; avoids long PRIMARY backoff delay. */
	private static final int BACKUP_INET_MAX_PER_DNS = 1;
	private static final int PRIMARY_PROBE_CONNECT_MS = 5_000;

	/**
	 * How the next {@link Listener#onClose} for <em>this</em> WebSocket should be interpreted for 1006 silent logic.
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

	/** {@link BeilinApiClient} static init sets {@code jdk.httpclient.allowRestrictedHeaders=host} before first {@link HttpClient}. */
	private final HttpClient primaryHttpClient = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(10))
		.build();
	private final HttpClient backupHttpClient;

	private final CommonConfig config;
	private final PlatformHooks hooks;
	private final BeilinApiClient apiClient;
	private final EntryGateState gateState;
	private final CommonLogger log;
	private final ScheduledExecutorService scheduler;
	private final OutboundRouteState routeState;

	private final AtomicReference<WebSocket> wsRef = new AtomicReference<>();
	private final AtomicBoolean reconnectScheduled = new AtomicBoolean(false);

	private ScheduledFuture<?> pingTask;
	private ScheduledFuture<?> pongWatchdog;
	private ScheduledFuture<?> primaryProbeTask;
	private volatile long lastPongTime;
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

	/** Last outbound route attempted in {@link #connectAsync} (scheduler thread). */
	private OutboundRoute lastConnectRouteAttempted = OutboundRoute.PRIMARY;

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
		try {
			SSLParameters sslParameters = SSLContext.getDefault().getDefaultSSLParameters();
			sslParameters.setServerNames(List.of(new SNIHostName(config.baseHost())));
			sslParameters.setEndpointIdentificationAlgorithm("HTTPS");
			this.backupHttpClient = HttpClient.newBuilder()
				.sslParameters(sslParameters)
				.connectTimeout(Duration.ofSeconds(10))
				.build();
		} catch (Exception e) {
			throw new IllegalStateException("Beilin WS: cannot init backup HttpClient SSL", e);
		}
	}

	public void start() {
		gateState.setAcceptingPlayers(false);
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
		connectAsync();
	}

	public void stop() {
		intentionalClose = true;
		cancelTimers();
		cancelPrimaryProbe();
		WebSocket w = wsRef.getAndSet(null);
		if (w != null && !w.isOutputClosed()) {
			try {
				w.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown");
			} catch (Exception ignored) {
			}
		}
		scheduler.shutdownNow();
	}

	private void runOnScheduler(Runnable r) {
		scheduler.execute(r);
	}

	private void connectAsync() {
		if (intentionalClose) return;
		if (!config.isValid()) {
			log.warn("Beilin WS: config invalid, not reconnecting");
			return;
		}
		WebSocket prev = wsRef.getAndSet(null);
		if (prev != null && !prev.isOutputClosed()) {
			try {
				prev.sendClose(WebSocket.NORMAL_CLOSURE, "reconnect");
			} catch (Exception ignored) {
			}
		}
		cancelTimers();

		OutboundRoute route;
		if (pendingScheduleRoute != null) {
			route = pendingScheduleRoute;
			pendingScheduleRoute = null;
		} else {
			route = resolveConnectRoute();
		}
		lastConnectRouteAttempted = route;

		SilentCloseMode closeMode = pendingSilentCloseMode;
		pendingSilentCloseMode = SilentCloseMode.NORMAL;

		URI uri;
		HttpClient client;
		InetAddress resolvedBackupIp = null;
		try {
			if (route == OutboundRoute.PRIMARY) {
				routeState.clearStickyBackupInet();
				uri = URI.create(config.wsUri());
				client = primaryHttpClient;
			} else {
				resolvedBackupIp = resolveBackupAddress();
				routeState.setStickyBackupInet(resolvedBackupIp);
				uri = backupWsUri(resolvedBackupIp);
				client = backupHttpClient;
			}
		} catch (Exception e) {
			log.warn("Beilin WS: cannot build URI ({}): {}", route, e.toString());
			runOnScheduler(() -> onConnectFailed(e, route));
			return;
		}

		WebSocket.Builder builder = client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(15));
		if (route == OutboundRoute.BACKUP) {
			URI logical = URI.create(config.wsUri());
			int port = logical.getPort();
			if (port < 0) {
				port = "wss".equalsIgnoreCase(logical.getScheme()) ? 443 : 80;
			}
			builder.header("Host", config.baseHost() + ":" + port);
		}

		final OutboundRoute connectRoute = route;
		final InetAddress backupIpUsed = resolvedBackupIp;
		builder.buildAsync(uri, new Listener(connectRoute, closeMode))
			.whenComplete((ws, err) -> {
				if (err != null) {
					runOnScheduler(() -> onConnectFailed(err, connectRoute, backupIpUsed));
				}
			});
	}

	private void connectImmediateRun() {
		reconnectScheduled.set(false);
		pendingScheduleRoute = null;
		connectAsync();
	}

	private void onConnectFailed(Throwable err, OutboundRoute failedRoute) {
		onConnectFailed(err, failedRoute, null);
	}

	private void onConnectFailed(Throwable err, OutboundRoute failedRoute, InetAddress backupIpTried) {
		lastConnectRouteAttempted = failedRoute;
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
		loud1006Disconnects = true;
		pendingSilentCloseMode = SilentCloseMode.NORMAL;
		onWsDown(true);
		scheduleHandshakeFailureReconnect(failedRoute);
	}

	/**
	 * WebSocket handshake never completes (distinct from closed 1006).
	 * PRIMARY failure → BACKUP immediately. BACKUP failure → try another backup IP when present, else PRIMARY + backoff.
	 */
	private void scheduleHandshakeFailureReconnect(OutboundRoute failedRoute) {
		if (intentionalClose) return;
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

		scheduler.schedule(() -> {
			reconnectScheduled.set(false);
			connectAsync();
		}, delaySec, TimeUnit.SECONDS);
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

	private URI backupWsUri(InetAddress addr) throws Exception {
		URI logical = URI.create(config.wsUri());
		String scheme = logical.getScheme();
		int port = logical.getPort();
		if (port < 0) {
			port = "wss".equalsIgnoreCase(scheme) ? 443 : 80;
		}
		String path = logical.getRawPath();
		if (path == null || path.isEmpty()) {
			path = "/";
		}
		String q = logical.getRawQuery();
		if (q != null && !q.isEmpty()) {
			path = path + "?" + q;
		}
		return new URI(scheme, null, InetAddressFormatting.hostLiteral(addr), port, path, null, null);
	}

	private void scheduleReconnect() {
		if (intentionalClose) return;
		if (!reconnectScheduled.compareAndSet(false, true)) return;
		long delay = nextReconnectDelaySec;
		nextReconnectDelaySec = Math.min((long) (nextReconnectDelaySec * RECONNECT_BACKOFF_MULTIPLIER), RECONNECT_MAX_SEC);

		if (loud1006Disconnects) {
			pendingScheduleRoute = (loudAltOrdinal % 2 == 0) ? OutboundRoute.PRIMARY : OutboundRoute.BACKUP;
			loudAltOrdinal++;
		} else {
			pendingScheduleRoute = null;
		}

		scheduler.schedule(() -> {
			reconnectScheduled.set(false);
			connectAsync();
		}, delay, TimeUnit.SECONDS);
	}

	private void onWsDown(boolean kickPlayers) {
		gateState.setAcceptingPlayers(false);
		if (kickPlayers) {
			hooks.runOnServerThread(() -> hooks.kickAll(EntryGateState.SYNC_MESSAGE));
		}
	}

	private void onWsUp(WebSocket ws) {
		wsRef.set(ws);
		lastPongTime = System.currentTimeMillis();
		nextReconnectDelaySec = RECONNECT_INITIAL_SEC;
		connectFailCount = 0;
		gateState.setAcceptingPlayers(true);
		startHeartbeat(ws);
		// Auth server clears online players on each new WebSocket; re-register everyone in-world.
		syncOnlinePlayersAfterWsConnect();
	}

	private void syncOnlinePlayersAfterWsConnect() {
		hooks.runOnServerThread(() -> {
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

	private void startHeartbeat(WebSocket ws) {
		cancelTimers();
		pingTask = scheduler.scheduleAtFixedRate(() -> {
			if (ws.isOutputClosed()) return;
			try {
				ws.sendText("{\"action\":\"ping\"}", true);
				if (pongWatchdog != null) pongWatchdog.cancel(false);
				pongWatchdog = scheduler.schedule(() -> {
					if (System.currentTimeMillis() - lastPongTime > PONG_TIMEOUT_SEC * 1000L) {
						log.warn("Beilin WS pong timeout, aborting");
						ws.abort();
					}
				}, PONG_TIMEOUT_SEC, TimeUnit.SECONDS);
			} catch (Exception e) {
				log.warn("Beilin WS ping send failed: {}", e.toString());
			}
		}, PING_INTERVAL_SEC, PING_INTERVAL_SEC, TimeUnit.SECONDS);
	}

	private void cancelTimers() {
		if (pingTask != null) pingTask.cancel(false);
		if (pongWatchdog != null) pongWatchdog.cancel(false);
		pingTask = null;
		pongWatchdog = null;
	}

	private void cancelPrimaryProbe() {
		if (primaryProbeTask != null) {
			primaryProbeTask.cancel(false);
			primaryProbeTask = null;
		}
	}

	private void ensurePrimaryProbe() {
		cancelPrimaryProbe();
		if (intentionalClose || loud1006Disconnects) return;
		if (routeState.getOutboundRoute() != OutboundRoute.BACKUP) return;
		long sec = config.wsPrimaryProbeIntervalSec();
		primaryProbeTask = scheduler.scheduleAtFixedRate(this::runPrimaryProbe, sec, sec, TimeUnit.SECONDS);
	}

	private void runPrimaryProbe() {
		if (intentionalClose || loud1006Disconnects) return;
		if (routeState.getOutboundRoute() != OutboundRoute.BACKUP) return;
		if (pendingSilentCloseMode != SilentCloseMode.NORMAL) return;
		try {
			if (tryPrimaryTlsHandshake()) {
				log.info("Beilin WS: PRIMARY route reachable, switching from BACKUP");
				routeState.setOutboundRoute(OutboundRoute.PRIMARY);
				loudAltOrdinal = 0;
				cancelPrimaryProbe();
				WebSocket w = wsRef.get();
				if (w != null && !w.isOutputClosed()) {
					try {
						w.sendClose(WebSocket.NORMAL_CLOSURE, "switch_to_primary");
					} catch (Exception ignored) {
					}
				} else {
					connectImmediateRun();
				}
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
		try {
			log.debug("Beilin WS recv: {}", text);
			JsonObject o = JsonParser.parseString(text).getAsJsonObject();
			if (!o.has("action")) return;
			String action = o.get("action").getAsString();
			if ("pong".equals(action)) {
				lastPongTime = System.currentTimeMillis();
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
			}
		} catch (Exception e) {
			log.warn("Beilin WS message parse failed: {}", e.toString());
		}
	}

	private void handleOpen(WebSocket webSocket, OutboundRoute connectRoute, SilentCloseMode closeMode) {
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
		webSocket.request(Long.MAX_VALUE);
	}

	private void handleClose(WebSocket webSocket, int statusCode, String reason, OutboundRoute connectRoute, SilentCloseMode closeMode) {
		boolean is1006 = (statusCode == 1006);
		if (is1006) {
			log.debug("Beilin WS closed 1006, reconnecting without kick (until loud mode)");
		} else {
			log.info("Beilin WS closed {} {}", statusCode, reason);
		}
		boolean wasActive = wsRef.compareAndSet(webSocket, null);
		cancelTimers();
		if (intentionalClose || !wasActive) {
			return;
		}

		if (statusCode == WebSocket.NORMAL_CLOSURE && "switch_to_primary".equals(reason)) {
			onWsDown(false);
			connectImmediateRun();
			return;
		}

		boolean kickOnThisDown = loud1006Disconnects || !is1006;
		boolean handled = false;

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

	private void handleError(WebSocket webSocket, Throwable error) {
		log.warn("Beilin WS error: {}", error.toString());
		boolean wasActive = wsRef.compareAndSet(webSocket, null);
		cancelTimers();
		if (!intentionalClose && wasActive) {
			loud1006Disconnects = true;
			pendingSilentCloseMode = SilentCloseMode.NORMAL;
			onWsDown(true);
			scheduleReconnect();
		}
	}

	private final class Listener implements WebSocket.Listener {
		private final OutboundRoute connectRoute;
		private final SilentCloseMode closeMode;
		private final StringBuilder textBuffer = new StringBuilder();

		Listener(OutboundRoute connectRoute, SilentCloseMode closeMode) {
			this.connectRoute = connectRoute;
			this.closeMode = closeMode;
		}

		@Override
		public void onOpen(WebSocket webSocket) {
			runOnScheduler(() -> handleOpen(webSocket, connectRoute, closeMode));
		}

		@Override
		public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
			textBuffer.append(data);
			if (last) {
				String full = textBuffer.toString();
				textBuffer.setLength(0);
				runOnScheduler(() -> handleTextMessage(full));
			}
			webSocket.request(1);
			return null;
		}

		@Override
		public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
			byte[] bytes = new byte[data.remaining()];
			data.get(bytes);
			textBuffer.append(new String(bytes, StandardCharsets.UTF_8));
			if (last) {
				String full = textBuffer.toString();
				textBuffer.setLength(0);
				runOnScheduler(() -> handleTextMessage(full));
			}
			webSocket.request(1);
			return null;
		}

		@Override
		public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
			lastPongTime = System.currentTimeMillis();
			webSocket.request(1);
			return null;
		}

		@Override
		public void onError(WebSocket webSocket, Throwable error) {
			runOnScheduler(() -> handleError(webSocket, error));
		}

		@Override
		public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
			runOnScheduler(() -> handleClose(webSocket, statusCode, reason, connectRoute, closeMode));
			return null;
		}
	}
}
