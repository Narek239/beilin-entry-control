package us.beiyue.beilinentrycontrol.common.http;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import us.beiyue.beilinentrycontrol.common.config.CommonConfig;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Async HTTP to Beilin Worker API. Network error or non-allowed => deny.
 * When {@link OutboundRouteState} is BACKUP, OkHttp keeps the logical URL host and routes DNS to the selected backup IP.
 */
public final class BeilinApiClient {

	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
	private static final int BACKUP_INET_MAX_PER_DNS = 1;
	private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

	private final HttpClient primaryHttpClient = HttpClient.newBuilder()
		.connectTimeout(CONNECT_TIMEOUT)
		.build();
	private final OkHttpClient backupHttpClient;

	private final CommonConfig config;
	private final OutboundRouteState outboundRouteState;

	public BeilinApiClient(CommonConfig config, OutboundRouteState outboundRouteState) {
		this.config = Objects.requireNonNull(config, "config");
		this.outboundRouteState = Objects.requireNonNull(outboundRouteState, "outboundRouteState");
		this.backupHttpClient = new OkHttpClient.Builder()
			.connectTimeout(CONNECT_TIMEOUT)
			.callTimeout(REQUEST_TIMEOUT)
			.dns(new FixedHostDns(config.baseHost(), this::resolveStickyOrFreshBackupInet))
			.build();
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
		return postJson("/player_join", json)
			.thenApply(r -> parseJoinResponse(r.statusCode, r.body))
			.exceptionally(ex -> JoinResult.denied("网络异常"));
	}

	/**
	 * POST player_leave — fire and forget; failures logged only.
	 */
	public CompletableFuture<Boolean> playerLeaveAsync(String username) {
		return postJson("/player_leave", "{\"username\":\"" + escapeJson(username) + "\"}")
			.thenApply(r -> r.statusCode >= 200 && r.statusCode < 300)
			.exceptionally(ex -> false);
	}

	private CompletableFuture<ApiResponse> postJson(String pathSuffix, String jsonBody) {
		if (!config.isValid()) {
			return CompletableFuture.failedFuture(new IllegalStateException("config invalid"));
		}
		try {
			OutboundRoute route = outboundRouteState.getOutboundRoute();
			URI logicalBase = URI.create(config.httpBase());
			String fullPath = concatHttpPaths(logicalBase.getRawPath(), pathSuffix);
			if (!fullPath.startsWith("/")) {
				fullPath = "/" + fullPath;
			}
			int port = logicalBase.getPort();
			if (port < 0) {
				port = "https".equalsIgnoreCase(logicalBase.getScheme()) ? 443 : 80;
			}

			if (route == OutboundRoute.PRIMARY) {
				URI uri = new URI(
					logicalBase.getScheme(),
					null,
					logicalBase.getHost(),
					port,
					fullPath,
					logicalBase.getRawQuery(),
					null
				);
				HttpRequest request = HttpRequest.newBuilder()
					.timeout(REQUEST_TIMEOUT)
					.header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
					.uri(uri)
					.build();
				return primaryHttpClient.sendAsync(request, java.net.http.HttpResponse.BodyHandlers.ofString())
					.thenApply(r -> new ApiResponse(r.statusCode(), r.body()));
			}

			URI uri = new URI(
				logicalBase.getScheme(),
				null,
				logicalBase.getHost(),
				port,
				fullPath,
				logicalBase.getRawQuery(),
				null
			);
			return postJsonBackup(uri, jsonBody);
		} catch (Exception e) {
			return CompletableFuture.failedFuture(e);
		}
	}

	private CompletableFuture<ApiResponse> postJsonBackup(URI uri, String jsonBody) {
		CompletableFuture<ApiResponse> future = new CompletableFuture<>();
		Request request = new Request.Builder()
			.url(uri.toASCIIString())
			.post(RequestBody.create(JSON, jsonBody))
			.build();
		backupHttpClient.newCall(request).enqueue(new Callback() {
			@Override
			public void onFailure(Call call, IOException e) {
				future.completeExceptionally(e);
			}

			@Override
			public void onResponse(Call call, Response response) {
				try (Response r = response) {
					ResponseBody body = r.body();
					future.complete(new ApiResponse(r.code(), body != null ? body.string() : ""));
				} catch (IOException e) {
					future.completeExceptionally(e);
				}
			}
		});
		return future;
	}

	private static String concatHttpPaths(String baseRawPath, String pathSuffix) {
		String base = baseRawPath == null || baseRawPath.isEmpty() ? "/" : baseRawPath;
		String suffix = pathSuffix.startsWith("/") ? pathSuffix : "/" + pathSuffix;
		if (base.endsWith("/")) {
			return base + suffix.substring(1);
		}
		return base + suffix;
	}

	private InetAddress resolveStickyOrFreshBackupInet() throws UnknownHostException {
		InetAddress inet = outboundRouteState.getStickyBackupInet();
		return inet != null ? inet : resolveBackupInetFresh();
	}

	private InetAddress resolveBackupInetFresh() throws UnknownHostException {
		InetAddress[] raw = InetAddress.getAllByName(config.wsBackupDnsHost());
		InetAddress[] sorted = preferInet4First(raw);
		if (sorted.length > BACKUP_INET_MAX_PER_DNS) {
			sorted = Arrays.copyOf(sorted, BACKUP_INET_MAX_PER_DNS);
		}
		if (sorted.length == 0) {
			throw new UnknownHostException(config.wsBackupDnsHost());
		}
		return sorted[0];
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

	private static JoinResult parseJoinResponse(int status, String body) {
		JsonObject o;
		try {
			o = !body.isBlank()
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
			case "EmailConfirmRequired" ->
				"您正在尝试进入一个新的北约成员服，请查看邮件中的指引完成确认。";
			default -> reason;
		};
	}

	private static final class ApiResponse {
		final int statusCode;
		final String body;

		ApiResponse(int statusCode, String body) {
			this.statusCode = statusCode;
			this.body = body != null ? body : "";
		}
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
