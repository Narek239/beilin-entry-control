package us.beiyue.beilindataportability.common;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import us.beiyue.beilinentrycontrol.common.config.CommonConfig;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class PortabilityApiClientUploadFlowTest {
	private static final String SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

	public static void main(String[] args) throws Exception {
		List<String> calls = new ArrayList<>();
		AtomicInteger partTwoAttempts = new AtomicInteger();
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> handle(exchange, calls, partTwoAttempts));
		server.start();
		Path artifactPath = null;
		PortabilityApiClient client = null;
		try {
			int port = server.getAddress().getPort();
			client = new PortabilityApiClient(new TestConfig(port));
			artifactPath = Files.createTempFile("beilin-data-portability-upload", ".zip");
			Files.writeString(artifactPath, "hello-world", StandardCharsets.UTF_8);
			ExportArtifact artifact = new ExportArtifact(artifactPath, Files.size(artifactPath), SHA);

			ExportUploadSession session = client.uploadArtifactAsync(11L, artifact)
				.get(10, TimeUnit.SECONDS);
			if (session.uploadId != 99L) {
				throw new AssertionError("unexpected upload id: " + session.uploadId);
			}
			boolean completed = client.completeJobAsync(11L, artifact, session)
				.get(10, TimeUnit.SECONDS);
			if (!completed) {
				throw new AssertionError("job complete returned false");
			}

			assertContainsInOrder(calls,
				"POST /server/key/exports/11/artifact/uploads",
				"PUT /server/key/exports/11/artifact/uploads/99/parts/1 bytes=4",
				"PUT /server/key/exports/11/artifact/uploads/99/parts/2 bytes=4",
				"PUT /server/key/exports/11/artifact/uploads/99/parts/2 bytes=4",
				"PUT /server/key/exports/11/artifact/uploads/99/parts/3 bytes=3",
				"POST /server/key/exports/11/artifact/uploads/99/complete",
				"POST /server/key/exports/11/complete"
			);
			if (partTwoAttempts.get() != 2) {
				throw new AssertionError("expected part 2 to be retried once, got " + partTwoAttempts.get());
			}
		} finally {
			if (artifactPath != null) {
				Files.deleteIfExists(artifactPath);
			}
			if (client != null) {
				client.shutdownNow();
			}
			server.stop(0);
		}
	}

	private static void handle(
		HttpExchange exchange,
		List<String> calls,
		AtomicInteger partTwoAttempts
	) throws IOException {
		String method = exchange.getRequestMethod();
		String path = exchange.getRequestURI().getPath();
		byte[] body = exchange.getRequestBody().readAllBytes();
		String call = method + " " + path + (method.equals("PUT") ? " bytes=" + body.length : "");
		calls.add(call);

		if (method.equals("POST") && path.equals("/server/key/exports/11/artifact/uploads")) {
			json(exchange, 200, "{\"ok\":true,\"upload_id\":99,\"object_key\":\"exports/request-11/test.zip\",\"part_size_bytes\":4}");
			return;
		}
		if (method.equals("PUT") && path.equals("/server/key/exports/11/artifact/uploads/99/parts/2")) {
			if (partTwoAttempts.incrementAndGet() == 1) {
				json(exchange, 500, "{\"error\":\"try again\"}");
				return;
			}
			json(exchange, 200, "{\"ok\":true,\"part\":{\"partNumber\":2,\"etag\":\"etag-2\"}}");
			return;
		}
		if (method.equals("PUT") && path.startsWith("/server/key/exports/11/artifact/uploads/99/parts/")) {
			String partNumber = path.substring(path.lastIndexOf('/') + 1);
			json(exchange, 200, "{\"ok\":true,\"part\":{\"partNumber\":" + partNumber + ",\"etag\":\"etag-" + partNumber + "\"}}");
			return;
		}
		if (method.equals("POST") && path.equals("/server/key/exports/11/artifact/uploads/99/complete")) {
			json(exchange, 200, "{\"ok\":true,\"artifact_key\":\"exports/request-11/test.zip\"}");
			return;
		}
		if (method.equals("POST") && path.equals("/server/key/exports/11/complete")) {
			json(exchange, 200, "{\"ok\":true}");
			return;
		}
		if (method.equals("POST") && path.equals("/server/key/exports/11/artifact/uploads/99/abort")) {
			json(exchange, 200, "{\"ok\":true}");
			return;
		}
		json(exchange, 404, "{\"error\":\"not found\"}");
	}

	private static void json(HttpExchange exchange, int status, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, bytes.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}

	private static void assertContainsInOrder(List<String> actual, String... expected) {
		int offset = 0;
		for (String expectedCall : expected) {
			int found = -1;
			for (int i = offset; i < actual.size(); i++) {
				if (expectedCall.equals(actual.get(i))) {
					found = i;
					break;
				}
			}
			if (found < 0) {
				throw new AssertionError("missing call " + expectedCall + " in " + actual);
			}
			offset = found + 1;
		}
	}

	private static final class TestConfig implements CommonConfig {
		private final int port;

		private TestConfig(int port) {
			this.port = port;
		}

		@Override
		public boolean isValid() {
			return true;
		}

		@Override
		public String httpBase() {
			return "http://127.0.0.1:" + port + "/server/key";
		}

		@Override
		public String wsUri() {
			return "ws://127.0.0.1:" + port + "/server/key/ws";
		}

		@Override
		public boolean isApiKeyConfigured() {
			return true;
		}

		@Override
		public String wsBackupDnsHost() {
			return "127.0.0.1";
		}

		@Override
		public long wsPrimaryProbeIntervalSec() {
			return 60L;
		}
	}
}
