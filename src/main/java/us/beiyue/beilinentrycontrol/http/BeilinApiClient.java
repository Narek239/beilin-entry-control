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
	 * POST /server/{apiKey}/player_join — 200 + {@code allowed:true} 才允许进入；
	 * 401 InvalidServerKey、400 Missing username 等见 {@link #parseJoinResponse}.
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
		JsonObject o;
		try {
			o = (body != null && !body.isBlank())
				? JsonParser.parseString(body).getAsJsonObject()
				: new JsonObject();
		} catch (Exception e) {
			return JoinResult.denied("解析失败");
		}
		String error = jsonString(o, "error");

		if (status == 401) {
			if ("InvalidServerKey".equals(error)) {
				return JoinResult.denied("服务器 API 密钥无效，请联系服主检查配置。");
			}
			return JoinResult.denied(error != null ? error : "HTTP 401");
		}
		if (status == 400) {
			if ("Missing username".equals(error)) {
				return JoinResult.denied("缺少用户名");
			}
			return JoinResult.denied(error != null ? error : "HTTP 400");
		}
		if (status < 200 || status >= 300) {
			return JoinResult.denied(error != null ? error : ("HTTP " + status));
		}

		try {
			if (!o.has("allowed")) {
				return JoinResult.denied("响应无效");
			}
			if (!o.get("allowed").getAsBoolean()) {
				String rawReason = jsonString(o, "reason");
				return JoinResult.denied(mapReason(rawReason != null ? rawReason : "拒绝进入"));
			}
			return JoinResult.allowed();
		} catch (Exception e) {
			return JoinResult.denied("解析失败");
		}
	}

	private static String jsonString(JsonObject o, String key) {
		if (!o.has(key) || !o.get(key).isJsonPrimitive()) {
			return null;
		}
		return o.get(key).getAsString();
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
			case "NoApplication" ->
				"您未注册 Beilin Entry Control。请在 beiyue.us 注册，并等待获批。";
			case "Banned" ->
				"您受到来自北约成员服的一项/多项禁令。请登录 beiyue.us 查看详情。";
			case "Restricted" ->
				"您的北约入服权在该服务器受限。请登录 beiyue.us 查看详情。";
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
