package us.beiyue.beilinentryportability.common;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import us.beiyue.beilinentrycontrol.common.config.CommonConfig;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public final class PortabilityApiClient {
	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

	private final CommonConfig config;
	private final HttpClient client = HttpClient.newBuilder()
		.connectTimeout(CONNECT_TIMEOUT)
		.build();

	public PortabilityApiClient(CommonConfig config) {
		this.config = Objects.requireNonNull(config, "config");
	}

	public CompletableFuture<Boolean> claimJobAsync(long requestId) {
		return postJson("/exports/" + requestId + "/claim", "{}")
			.thenApply(r -> r.statusCode >= 200 && r.statusCode < 300)
			.exceptionally(ex -> false);
	}

	public CompletableFuture<Boolean> submitManifestAsync(long requestId, String manifestJson) {
		String body = "{\"manifest\":" + (manifestJson == null || manifestJson.isBlank() ? "{}" : manifestJson) + ",\"components\":[]}";
		return postJson("/exports/" + requestId + "/manifest", body)
			.thenApply(r -> r.statusCode >= 200 && r.statusCode < 300)
			.exceptionally(ex -> false);
	}

	public CompletableFuture<Boolean> submitManifestAsync(ExportManifest manifest) {
		JsonObject body = new JsonObject();
		body.add("manifest", manifest.toManifestJson());
		JsonArray components = new JsonArray();
		for (ComponentSummary c : manifest.components) {
			components.add(c.toApiJson());
		}
		body.add("components", components);
		return postJson("/exports/" + manifest.requestId + "/manifest", body.toString())
			.thenApply(r -> r.statusCode >= 200 && r.statusCode < 300)
			.exceptionally(ex -> false);
	}

	public CompletableFuture<Boolean> failJobAsync(long requestId, String reason) {
		String body = "{\"reason\":\"" + escapeJson(reason == null ? "ExportFailed" : reason) + "\"}";
		return postJson("/exports/" + requestId + "/fail", body)
			.thenApply(r -> r.statusCode >= 200 && r.statusCode < 300)
			.exceptionally(ex -> false);
	}

	public CompletableFuture<ExportUploadSession> uploadArtifactAsync(long requestId, ExportArtifact artifact) {
		return beginArtifactUploadAsync(requestId, artifact)
			.thenCompose(session -> CompletableFuture.supplyAsync(() -> {
				boolean completed = false;
				try {
					uploadArtifactPartsBlocking(requestId, session, artifact);
					completeArtifactUploadBlocking(requestId, session, artifact);
					completed = true;
					return session;
				} catch (Exception e) {
					throw new CompletionException(e);
				} finally {
					if (!completed) {
						try {
							abortArtifactUploadBlocking(requestId, session);
						} catch (Exception ignored) {
						}
					}
				}
			}));
	}

	public CompletableFuture<Boolean> completeJobAsync(long requestId, ExportArtifact artifact, ExportUploadSession upload) {
		JsonObject body = new JsonObject();
		body.addProperty("upload_id", upload.uploadId);
		body.addProperty("sha256", artifact.sha256);
		body.addProperty("bytes", artifact.bytes);
		return postJson("/exports/" + requestId + "/complete", body.toString())
			.thenApply(r -> r.statusCode >= 200 && r.statusCode < 300)
			.exceptionally(ex -> false);
	}

	private CompletableFuture<ExportUploadSession> beginArtifactUploadAsync(long requestId, ExportArtifact artifact) {
		JsonObject body = new JsonObject();
		body.addProperty("bytes", artifact.bytes);
		body.addProperty("sha256", artifact.sha256);
		return postJson("/exports/" + requestId + "/artifact/uploads", body.toString())
			.thenApply(response -> {
				if (response.statusCode < 200 || response.statusCode >= 300) {
					throw new CompletionException(new IOException("artifact upload init failed: HTTP " + response.statusCode));
				}
				JsonObject root = JsonParser.parseString(response.body).getAsJsonObject();
				long uploadId = root.has("upload_id") ? root.get("upload_id").getAsLong() : 0L;
				String objectKey = stringOrNull(root, "object_key");
				int partSize = root.has("part_size_bytes") ? root.get("part_size_bytes").getAsInt() : 0;
				if (uploadId <= 0 || objectKey == null || objectKey.isBlank() || partSize <= 0) {
					throw new CompletionException(new IOException("artifact upload init returned invalid payload"));
				}
				return new ExportUploadSession(uploadId, objectKey, partSize);
			});
	}

	private void uploadArtifactPartsBlocking(long requestId, ExportUploadSession session, ExportArtifact artifact) throws IOException, InterruptedException {
		byte[] buffer = new byte[session.partSizeBytes];
		int partNumber = 1;
		try (InputStream in = Files.newInputStream(artifact.path)) {
			while (true) {
				int read = readPart(in, buffer);
				if (read < 0) {
					break;
				}
				uploadArtifactPartWithRetry(requestId, session.uploadId, partNumber, buffer, read);
				partNumber += 1;
			}
		}
	}

	private void uploadArtifactPartWithRetry(long requestId, long uploadId, int partNumber, byte[] buffer, int length) throws IOException, InterruptedException {
		IOException last = null;
		for (int attempt = 1; attempt <= 3; attempt += 1) {
			try {
				ApiResponse response = putBytesBlocking(
					"/exports/" + requestId + "/artifact/uploads/" + uploadId + "/parts/" + partNumber,
					buffer,
					length
				);
				if (response.statusCode >= 200 && response.statusCode < 300) {
					return;
				}
				last = new IOException("artifact upload part " + partNumber + " failed: HTTP " + response.statusCode);
			} catch (IOException e) {
				last = e;
			}
			try {
				Thread.sleep(250L * attempt);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw e;
			}
		}
		throw last != null ? last : new IOException("artifact upload part " + partNumber + " failed");
	}

	private void completeArtifactUploadBlocking(long requestId, ExportUploadSession session, ExportArtifact artifact) throws IOException, InterruptedException {
		JsonObject body = new JsonObject();
		body.addProperty("sha256", artifact.sha256);
		body.addProperty("bytes", artifact.bytes);
		ApiResponse response = postJsonBlocking(
			"/exports/" + requestId + "/artifact/uploads/" + session.uploadId + "/complete",
			body.toString()
		);
		if (response.statusCode < 200 || response.statusCode >= 300) {
			throw new IOException("artifact upload complete failed: HTTP " + response.statusCode);
		}
	}

	private void abortArtifactUploadBlocking(long requestId, ExportUploadSession session) throws IOException, InterruptedException {
		ApiResponse response = postJsonBlocking(
			"/exports/" + requestId + "/artifact/uploads/" + session.uploadId + "/abort",
			"{}"
		);
		if (response.statusCode < 200 || response.statusCode >= 300) {
			throw new IOException("artifact upload abort failed: HTTP " + response.statusCode);
		}
	}

	private CompletableFuture<ApiResponse> postJson(String pathSuffix, String body) {
		if (!config.isValid()) {
			return CompletableFuture.failedFuture(new IllegalStateException("config invalid"));
		}
		try {
			URI uri = buildUri(pathSuffix);
			HttpRequest request = HttpRequest.newBuilder(uri)
				.timeout(REQUEST_TIMEOUT)
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body == null ? "{}" : body, StandardCharsets.UTF_8))
				.build();
			return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
				.thenApply(r -> new ApiResponse(r.statusCode(), r.body()));
		} catch (Exception e) {
			return CompletableFuture.failedFuture(e);
		}
	}

	private ApiResponse postJsonBlocking(String pathSuffix, String body) throws IOException, InterruptedException {
		if (!config.isValid()) {
			throw new IOException("config invalid");
		}
		try {
			HttpRequest request = HttpRequest.newBuilder(buildUri(pathSuffix))
				.timeout(REQUEST_TIMEOUT)
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body == null ? "{}" : body, StandardCharsets.UTF_8))
				.build();
			HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
			return new ApiResponse(response.statusCode(), response.body());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw e;
		} catch (IOException e) {
			throw e;
		} catch (Exception e) {
			throw new IOException(e);
		}
	}

	private ApiResponse putBytesBlocking(String pathSuffix, byte[] buffer, int length) throws IOException, InterruptedException {
		if (!config.isValid()) {
			throw new IOException("config invalid");
		}
		try {
			HttpRequest request = HttpRequest.newBuilder(buildUri(pathSuffix))
				.timeout(REQUEST_TIMEOUT)
				.header("Content-Type", "application/octet-stream")
				.PUT(HttpRequest.BodyPublishers.ofByteArray(buffer, 0, length))
				.build();
			HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
			return new ApiResponse(response.statusCode(), response.body());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw e;
		} catch (IOException e) {
			throw e;
		} catch (Exception e) {
			throw new IOException(e);
		}
	}

	private URI buildUri(String pathSuffix) throws Exception {
		URI base = URI.create(config.httpBase());
		String fullPath = concatHttpPaths(base.getRawPath(), pathSuffix);
		int port = base.getPort();
		if (port < 0) {
			port = "https".equalsIgnoreCase(base.getScheme()) ? 443 : 80;
		}
		return new URI(base.getScheme(), null, base.getHost(), port, fullPath, base.getRawQuery(), null);
	}

	private static String stringOrNull(JsonObject o, String key) {
		if (!o.has(key) || o.get(key).isJsonNull() || !o.get(key).isJsonPrimitive()) {
			return null;
		}
		return o.get(key).getAsString();
	}

	private static String concatHttpPaths(String baseRawPath, String pathSuffix) {
		String base = baseRawPath == null || baseRawPath.isEmpty() ? "/" : baseRawPath;
		String suffix = pathSuffix.startsWith("/") ? pathSuffix : "/" + pathSuffix;
		if (base.endsWith("/")) {
			return base + suffix.substring(1);
		}
		return base + suffix;
	}

	private static int readPart(InputStream in, byte[] buffer) throws IOException {
		int offset = 0;
		while (offset < buffer.length) {
			int read = in.read(buffer, offset, buffer.length - offset);
			if (read < 0) {
				return offset == 0 ? -1 : offset;
			}
			offset += read;
		}
		return offset;
	}

	private static String escapeJson(String s) {
		return s.replace("\\", "\\\\").replace("\"", "\\\"");
	}

	private static final class ApiResponse {
		final int statusCode;
		final String body;

		ApiResponse(int statusCode, String body) {
			this.statusCode = statusCode;
			this.body = body != null ? body : "";
		}
	}
}
