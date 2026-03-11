package us.beiyue.beilinentrycontrol.http;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import us.beiyue.beilinentrycontrol.config.ModConfig;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Async HTTP to Beilin Worker API. Network error or non-allowed => deny.
 */
public final class BeilinApiClient {
	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

	private final HttpClient client = HttpClient.newBuilder()
		.connectTimeout(CONNECT_TIMEOUT)
		.build();

	private final ModConfig config;

	public BeilinApiClient(ModConfig config) {
		this.config = config;
	}

	/**
	 * POST player_join — must respond with ok==true before player enters.
	 */
	public CompletableFuture<JoinResult> playerJoinAsync(String username) {
		if (!config.isValid()) {
			return CompletableFuture.completedFuture(JoinResult.denied("配置无效"));
		}
		String json = "{\"username\":\"" + escapeJson(username) + "\"}";
		try {
			String url = config.httpBase() + "/player_join";
			HttpRequest req = HttpRequest.newBuilder()
				.uri(URI.create(url))
				.timeout(REQUEST_TIMEOUT)
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
				.build();
			return client.sendAsync(req, HttpResponse.BodyHandlers.ofString())
				.thenApply(r -> parseJoinResponse(r.statusCode(), r.body()))
				.exceptionally(ex -> JoinResult.denied("网络异常"));
		} catch (Exception e) {
			return CompletableFuture.completedFuture(JoinResult.denied("网络异常"));
		}
	}

	/**
	 * POST player_leave — fire and forget; failures logged only.
	 */
	public CompletableFuture<Boolean> playerLeaveAsync(String username) {
		return postUsername(config.httpBase() + "/player_leave", username);
	}

	private CompletableFuture<Boolean> postUsername(String url, String username) {
		if (!config.isValid()) {
			return CompletableFuture.completedFuture(false);
		}
		String json = "{\"username\":\"" + escapeJson(username) + "\"}";
		try {
			HttpRequest req = HttpRequest.newBuilder()
				.uri(URI.create(url))
				.timeout(REQUEST_TIMEOUT)
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
				.build();
			return client.sendAsync(req, HttpResponse.BodyHandlers.ofString())
				.thenApply(r -> r.statusCode() >= 200 && r.statusCode() < 300)
				.exceptionally(ex -> false);
		} catch (Exception e) {
			return CompletableFuture.completedFuture(false);
		}
	}

	private static JoinResult parseJoinResponse(int status, String body) {
		if (status < 200 || status >= 300) {
			return JoinResult.denied("HTTP " + status);
		}
		try {
			JsonObject o = JsonParser.parseString(body).getAsJsonObject();
			if (!o.has("ok")) {
				return JoinResult.denied("响应无效");
			}
			boolean ok = o.get("ok").getAsBoolean();
			if (ok) {
				return JoinResult.allowed();
			}
			String rawReason = o.has("reason") && o.get("reason").isJsonPrimitive()
				? o.get("reason").getAsString()
				: "拒绝进入";
			return JoinResult.denied(mapReason(rawReason));
		} catch (Exception e) {
			return JoinResult.denied("解析失败");
		}
	}

	private static String escapeJson(String s) {
		if (s == null) return "";
		return s.replace("\\", "\\\\").replace("\"", "\\\"");
	}

	private static String mapReason(String reason) {
		if (reason == null) {
			return "拒绝进入";
		}
		return switch (reason) {
			case "BannedBySouth" ->
				"您有一项来自 South Beilin 的禁令。该问题解决前，您无法进入服务器。";
			case "BannedByNorth" ->
				"您有一项来自 North Beilin 的禁令。该问题解决前，您无法进入服务器。";
			case "BannedByBoth" ->
				"您同时被 South Beilin 与 North Beilin 施加了禁令。该问题解决前，您无法进入服务器。";
			case "NoApplication" ->
				"您未注册 Beilin Entry Control。请在 beiyue.us 注册，并等待获批。";
			case "NotApproved" ->
				"您尚未获批北约入服权。请留意您的邮箱，并配合 South Beilin / North Beilin 进行额外审查（如有）。";
			default -> reason;
		};
	}

	public static final class JoinResult {
		public final boolean ok;
		public final String reason;

		private JoinResult(boolean ok, String reason) {
			this.ok = ok;
			this.reason = reason;
		}

		public static JoinResult allowed() {
			return new JoinResult(true, null);
		}

		public static JoinResult denied(String reason) {
			return new JoinResult(false, reason != null ? reason : "拒绝进入");
		}
	}
}
