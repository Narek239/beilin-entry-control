package us.beiyue.beilinentrycontrol.ws;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;
import us.beiyue.beilinentrycontrol.BeilinEntryControl;
import us.beiyue.beilinentrycontrol.config.ModConfig;
import us.beiyue.beilinentrycontrol.gate.EntryGate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * WebSocket connect/reconnect, 15s JSON ping, 30s pong timeout, kick dispatch on server thread.
 * Uses exponential backoff for reconnect to avoid socket exhaustion and retry storms.
 */
public final class BeilinWsClient {
	private static final long PING_INTERVAL_SEC = 15;
	private static final long PONG_TIMEOUT_SEC = 30;
	private static final long RECONNECT_INITIAL_SEC = 5;
	private static final long RECONNECT_MAX_SEC = 300;
	private static final double RECONNECT_BACKOFF_MULTIPLIER = 1.5;
	private static final int LOG_WARN_EVERY_N_FAILURES = 10;

	private final HttpClient httpClient = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(10))
		.build();

	private final ModConfig config;
	private final MinecraftServer server;
	private final ScheduledExecutorService scheduler;
	private final AtomicReference<WebSocket> wsRef = new AtomicReference<>();
	private final AtomicBoolean reconnectScheduled = new AtomicBoolean(false);
	private final StringBuilder textBuffer = new StringBuilder();

	private ScheduledFuture<?> pingTask;
	private ScheduledFuture<?> pongWatchdog;
	private volatile long lastPongTime;
	private volatile boolean intentionalClose;
	private volatile long nextReconnectDelaySec = RECONNECT_INITIAL_SEC;
	private int connectFailCount = 0;

	public BeilinWsClient(ModConfig config, MinecraftServer server) {
		this.config = config;
		this.server = server;
		this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "beilin-entry-control-ws");
			t.setDaemon(true);
			return t;
		});
	}

	public void start() {
		EntryGate.setAcceptingPlayers(false);
		intentionalClose = false;
		nextReconnectDelaySec = RECONNECT_INITIAL_SEC;
		connectFailCount = 0;
		connectAsync();
	}

	public void stop() {
		intentionalClose = true;
		cancelTimers();
		WebSocket w = wsRef.getAndSet(null);
		if (w != null && !w.isOutputClosed()) {
			try {
				w.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown");
			} catch (Exception ignored) {
			}
		}
		scheduler.shutdownNow();
	}

	private void connectAsync() {
		if (intentionalClose) return;
		if (!config.isValid()) {
			BeilinEntryControl.LOGGER.warn("Beilin WS: config invalid, not reconnecting");
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

		URI uri = URI.create(config.wsUri());
		httpClient.newWebSocketBuilder()
			.connectTimeout(Duration.ofSeconds(15))
			.buildAsync(uri, new Listener())
			.whenComplete((ws, err) -> {
				if (err != null) {
					connectFailCount++;
					if (connectFailCount <= 3 || connectFailCount % LOG_WARN_EVERY_N_FAILURES == 0) {
						BeilinEntryControl.LOGGER.warn("Beilin WS connect failed (attempt {}): {}", connectFailCount, err.toString());
					}
					onWsDown();
					scheduleReconnect();
				}
			});
	}

	private void scheduleReconnect() {
		if (intentionalClose) return;
		if (!reconnectScheduled.compareAndSet(false, true)) return;
		long delay = nextReconnectDelaySec;
		nextReconnectDelaySec = Math.min((long) (nextReconnectDelaySec * RECONNECT_BACKOFF_MULTIPLIER), RECONNECT_MAX_SEC);
		scheduler.schedule(() -> {
			reconnectScheduled.set(false);
			connectAsync();
		}, delay, TimeUnit.SECONDS);
	}

	private void onWsDown() {
		EntryGate.setAcceptingPlayers(false);
		server.execute(() -> EntryGate.kickAll(server, EntryGate.SYNC_MESSAGE));
	}

	private void onWsUp(WebSocket ws) {
		wsRef.set(ws);
		lastPongTime = System.currentTimeMillis();
		nextReconnectDelaySec = RECONNECT_INITIAL_SEC;
		connectFailCount = 0;
		EntryGate.setAcceptingPlayers(true);
		startHeartbeat(ws);
	}

	private void startHeartbeat(WebSocket ws) {
		cancelTimers();
		pingTask = scheduler.scheduleAtFixedRate(() -> {
			if (ws.isOutputClosed()) return;
			try {
				ws.sendText("{\"action\":\"ping\"}", true);
				// pong watchdog: if no pong within 30s after this ping, abort
				if (pongWatchdog != null) pongWatchdog.cancel(false);
				pongWatchdog = scheduler.schedule(() -> {
					if (System.currentTimeMillis() - lastPongTime > PONG_TIMEOUT_SEC * 1000L) {
						BeilinEntryControl.LOGGER.warn("Beilin WS pong timeout, aborting");
						ws.abort();
					}
				}, PONG_TIMEOUT_SEC, TimeUnit.SECONDS);
			} catch (Exception e) {
				BeilinEntryControl.LOGGER.warn("Beilin WS ping send failed: {}", e.toString());
			}
		}, PING_INTERVAL_SEC, PING_INTERVAL_SEC, TimeUnit.SECONDS);
	}

	private void cancelTimers() {
		if (pingTask != null) pingTask.cancel(false);
		if (pongWatchdog != null) pongWatchdog.cancel(false);
		pingTask = null;
		pongWatchdog = null;
	}

	private void handleTextMessage(String text) {
		try {
			BeilinEntryControl.LOGGER.debug("Beilin WS recv: {}", text);
			JsonObject o = JsonParser.parseString(text).getAsJsonObject();
			if (!o.has("action")) return;
			String action = o.get("action").getAsString();
			if ("pong".equals(action)) {
				lastPongTime = System.currentTimeMillis();
				return;
			}
			if ("kick".equals(action)) {
				// worker might use username/user/player; accept common aliases
				String username = null;
				if (o.has("username")) username = o.get("username").getAsString();
				else if (o.has("user")) username = o.get("user").getAsString();
				else if (o.has("player")) username = o.get("player").getAsString();
				if (username == null || username.isEmpty()) return;
				String reason = o.has("reason") && !o.get("reason").isJsonNull()
					? o.get("reason").getAsString()
					: "已被踢出";
				BeilinEntryControl.LOGGER.info("Beilin WS kick {} reason={}", username, reason);
				final String kickUsername = username;
				final String kickReason = reason;
				server.execute(() -> EntryGate.kickByUsername(server, kickUsername, kickReason));
			}
		} catch (Exception e) {
			BeilinEntryControl.LOGGER.warn("Beilin WS message parse failed: {}", e.toString());
		}
	}

	private class Listener implements WebSocket.Listener {
		@Override
		public void onOpen(WebSocket webSocket) {
			BeilinEntryControl.LOGGER.info("Beilin WS connected");
			onWsUp(webSocket);
			webSocket.request(Long.MAX_VALUE);
		}

		@Override
		public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
			textBuffer.append(data);
			if (last) {
				String full = textBuffer.toString();
				textBuffer.setLength(0);
				handleTextMessage(full);
			}
			webSocket.request(1);
			return null;
		}

		@Override
		public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
			// Some servers send JSON as binary frames; decode as UTF-8.
			byte[] bytes = new byte[data.remaining()];
			data.get(bytes);
			textBuffer.append(new String(bytes, StandardCharsets.UTF_8));
			if (last) {
				String full = textBuffer.toString();
				textBuffer.setLength(0);
				handleTextMessage(full);
			}
			webSocket.request(1);
			return null;
		}

		@Override
		public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
			// If peer uses protocol-level pong, treat it as heartbeat too.
			lastPongTime = System.currentTimeMillis();
			webSocket.request(1);
			return null;
		}

		@Override
		public void onError(WebSocket webSocket, Throwable error) {
			BeilinEntryControl.LOGGER.warn("Beilin WS error: {}", error.toString());
			wsRef.compareAndSet(webSocket, null);
			cancelTimers();
			onWsDown();
			if (!intentionalClose) scheduleReconnect(); // guard prevents double schedule with onClose
		}

		@Override
		public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
			BeilinEntryControl.LOGGER.info("Beilin WS closed {} {}", statusCode, reason);
			wsRef.compareAndSet(webSocket, null);
			cancelTimers();
			if (!intentionalClose) {
				onWsDown();
				scheduleReconnect(); // reconnectScheduled ensures only one pending reconnect
			}
			return null;
		}
	}
}
