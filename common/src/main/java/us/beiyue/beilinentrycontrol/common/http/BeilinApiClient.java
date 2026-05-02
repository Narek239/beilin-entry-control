package us.beiyue.beilinentrycontrol.common.http;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import us.beiyue.beilinentrycontrol.common.config.CommonConfig;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Async HTTP to Beilin Worker API. Network error or non-allowed => deny.
 * When {@link OutboundRouteState} is BACKUP (same as WebSocket egress), HTTPS uses literal backup IP + {@code Host}.
 */
public final class BeilinApiClient {

	/**
	 * BACKUP literal-IP HTTPS needs {@code Host}; must run before the first {@link HttpClient} in the JVM.
	 */
	static {
		String key = "jdk.httpclient.allowRestrictedHeaders";
		String cur = System.getProperty(key, "").trim();
		if (cur.isEmpty()) {
			System.setProperty(key, "host");
		} else {
			boolean hasHost = false;
			for (String part : cur.split(",")) {
				if ("host".equalsIgnoreCase(part.trim())) {
					hasHost = true;
					break;
				}
			}
			if (!hasHost) {
				System.setProperty(key, cur + ",host");
			}
		}
	}

	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
	private static final int BACKUP_INET_MAX_PER_DNS = 1;

	private final HttpClient primaryHttpClient = HttpClient.newBuilder()
		.connectTimeout(CONNECT_TIMEOUT)
		.build();
	private final HttpClient backupHttpClient;

	private final CommonConfig config;
	private final OutboundRouteState outboundRouteState;

	public BeilinApiClient(CommonConfig config, OutboundRouteState outboundRouteState) {
		this.config = Objects.requireNonNull(config, "config");
		this.outboundRouteState = Objects.requireNonNull(outboundRouteState, "outboundRouteState");
		try {
			SSLParameters sslParameters = SSLContext.getDefault().getDefaultSSLParameters();
			sslParameters.setServerNames(List.of(new SNIHostName(config.baseHost())));
			sslParameters.setEndpointIdentificationAlgorithm("HTTPS");
			this.backupHttpClient = HttpClient.newBuilder()
				.sslParameters(sslParameters)
				.connectTimeout(CONNECT_TIMEOUT)
				.build();
		} catch (Exception e) {
			throw new IllegalStateException("Beilin API: cannot init backup HttpClient SSL", e);
		}
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
			.thenApply(r -> parseJoinResponse(r.statusCode(), r.body()))
			.exceptionally(ex -> JoinResult.denied("网络异常"));
	}

	/**
	 * POST player_leave — fire and forget; failures logged only.
	 */
	public CompletableFuture<Boolean> playerLeaveAsync(String username) {
		return postJson("/player_leave", "{\"username\":\"" + escapeJson(username) + "\"}")
			.thenApply(r -> r.statusCode() >= 200 && r.statusCode() < 300)
			.exceptionally(ex -> false);
	}

	private CompletableFuture<HttpResponse<String>> postJson(String pathSuffix, String jsonBody) {
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

			HttpClient client;
			HttpRequest.Builder rb = HttpRequest.newBuilder()
				.timeout(REQUEST_TIMEOUT)
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));

			if (route == OutboundRoute.PRIMARY) {
				client = primaryHttpClient;
				URI uri = new URI(
					logicalBase.getScheme(),
					null,
					logicalBase.getHost(),
					port,
					fullPath,
					logicalBase.getRawQuery(),
					null
				);
				rb.uri(uri);
			} else {
				client = backupHttpClient;
				InetAddress inet = outboundRouteState.getStickyBackupInet();
				if (inet == null) {
					inet = resolveBackupInetFresh();
				}
				String hostLit = InetAddressFormatting.hostLiteral(inet);
				URI uri = new URI(logicalBase.getScheme(), null, hostLit, port, fullPath, logicalBase.getRawQuery(), null);
				rb.uri(uri);
				rb.header("Host", hostHeaderForAuthority(config.baseHost(), port, logicalBase.getScheme()));
				/*
				 * JDK negotiates HTTP/2 by default; :authority follows the literal IP from the URI while SNI/Host use
				 * the logical hostname — CDNs respond with HTTP 421 (Misdirected Request). WS uses HTTP/1.1 Upgrade;
				 * keep REST literal-IP on HTTP/1.1 as well.
				 */
				rb.version(HttpClient.Version.HTTP_1_1);
			}

			return client.sendAsync(rb.build(), HttpResponse.BodyHandlers.ofString());
		} catch (Exception e) {
			return CompletableFuture.failedFuture(e);
		}
	}

	/** Join {@code httpBase()} path ({@code /server/{key}}) with suffix ({@code /player_join}). */
	private static String hostHeaderForAuthority(String host, int port, String scheme) {
		boolean implicitPort =
			("https".equalsIgnoreCase(scheme) && port == 443)
				|| ("http".equalsIgnoreCase(scheme) && port == 80);
		return implicitPort ? host : host + ":" + port;
	}

	private static String concatHttpPaths(String baseRawPath, String pathSuffix) {
		String base = baseRawPath == null || baseRawPath.isEmpty() ? "/" : baseRawPath;
		String suffix = pathSuffix.startsWith("/") ? pathSuffix : "/" + pathSuffix;
		if (base.endsWith("/")) {
			return base + suffix.substring(1);
		}
		return base + suffix;
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
