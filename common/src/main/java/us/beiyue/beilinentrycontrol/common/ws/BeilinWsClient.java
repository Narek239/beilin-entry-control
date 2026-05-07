package us.beiyue.beilinentrycontrol.common.ws;

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
	private static final int NORMAL_CLOSURE = 1000;
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
		.build();
	private final OkHttpClient backupWsClient = new OkHttpClient.Builder()
		.connectTimeout(Duration.ofSeconds(10))
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
		if (w != null) {
			try {
				w.close(NORMAL_CLOSURE, "shutdown");
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
		if (prev != null) {
			try {
				prev.close(NORMAL_CLOSURE, "reconnect");
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
			runOnScheduler(() -> onConnectFailed(e, route));
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
		onConnectFailed(err, failedRoute, null);
	}

	private void onConnectFailed(Throwable err, OutboundRoute failedRoute, InetAddress backupIpTried) {
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
			try {
				if (!ws.send("{\"action\":\"ping\"}")) {
					log.warn("Beilin WS ping send failed: output queue closed");
					return;
				}
				if (pongWatchdog != null) pongWatchdog.cancel(false);
				pongWatchdog = scheduler.schedule(() -> {
					if (System.currentTimeMillis() - lastPongTime > PONG_TIMEOUT_SEC * 1000L) {
						log.warn("Beilin WS pong timeout, aborting");
						ws.cancel();
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
				if (w != null) {
					try {
						w.close(NORMAL_CLOSURE, "switch_to_primary");
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

		if (statusCode == NORMAL_CLOSURE && "switch_to_primary".equals(reason)) {
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

	private void handleFailure(
		WebSocket webSocket,
		Throwable error,
		OutboundRoute connectRoute,
		SilentCloseMode closeMode,
		InetAddress backupIpTried
	) {
		if (wsRef.get() == webSocket) {
			handleClose(webSocket, 1006, error != null ? error.toString() : "", connectRoute, closeMode);
			return;
		}
		onConnectFailed(error, connectRoute, backupIpTried);
	}

	private final class Listener extends WebSocketListener {
		private final OutboundRoute connectRoute;
		private final SilentCloseMode closeMode;
		private final InetAddress backupIpTried;

		Listener(OutboundRoute connectRoute, SilentCloseMode closeMode, InetAddress backupIpTried) {
			this.connectRoute = connectRoute;
			this.closeMode = closeMode;
			this.backupIpTried = backupIpTried;
		}

		@Override
		public void onOpen(WebSocket webSocket, Response response) {
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
			runOnScheduler(() -> handleFailure(webSocket, t, connectRoute, closeMode, backupIpTried));
		}
	}
}
