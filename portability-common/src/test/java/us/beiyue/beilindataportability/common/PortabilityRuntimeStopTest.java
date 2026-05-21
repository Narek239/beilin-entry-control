package us.beiyue.beilindataportability.common;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import us.beiyue.beilinentrycontrol.common.config.CommonConfig;
import us.beiyue.beilinentrycontrol.common.log.CommonLogger;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class PortabilityRuntimeStopTest {
	public static void main(String[] args) throws Exception {
		CountDownLatch uploadStarted = new CountDownLatch(1);
		CountDownLatch failSeen = new CountDownLatch(1);
		List<String> calls = new CopyOnWriteArrayList<>();
		ExecutorService serverExecutor = Executors.newCachedThreadPool();
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.setExecutor(serverExecutor);
		server.createContext("/", exchange -> handle(exchange, calls, uploadStarted, failSeen));
		server.start();

		Path dir = Files.createTempDirectory("beilin-data-portability-runtime-stop");
		BuildingIndexStore store = null;
		PortabilityRuntime runtime = null;
		try {
			int port = server.getAddress().getPort();
			store = BuildingIndexStore.open(dir.resolve("index.db"), new NoopLogger());
			for (int x = 0; x < 8; x++) {
				store.recordPlaced("minecraft:overworld", x, 64, 0, "minecraft:stone", "Alice");
			}
			runtime = new PortabilityRuntime(
				new PortabilityApiClient(new TestConfig(port)),
				new NoopLogger(),
				store,
				new ExactReader(),
				dir.resolve("artifacts"),
				1_000_000
			);
			runtime.start();
			assertEquals(1, PortabilityBridge.listenerCount(), "runtime should register one bridge listener");
			PortabilityBridge.acceptExportJobsJson("{\"action\":\"export_jobs\",\"jobs\":[{\"request_id\":11,\"minecraft_username\":\"Alice\"}]}");
			if (!uploadStarted.await(10, TimeUnit.SECONDS)) {
				throw new AssertionError("upload did not start; calls=" + calls);
			}

			long start = System.nanoTime();
			runtime.stop();
			runtime = null;
			long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
			if (elapsedMs > 3_000L) {
				throw new AssertionError("runtime stop took too long: " + elapsedMs + "ms");
			}
			assertEquals(0, PortabilityBridge.listenerCount(), "runtime should remove bridge listener on stop");
			if (!failSeen.await(1, TimeUnit.SECONDS)) {
				throw new AssertionError("shutdown did not report export failure; calls=" + calls);
			}
			Thread.sleep(200L);
			if (calls.stream().anyMatch(call -> call.equals("POST /server/key/exports/11/complete"))) {
				throw new AssertionError("job complete should not be called after stop: " + calls);
			}
		} finally {
			if (runtime != null) runtime.stop();
			if (store != null) store.close();
			server.stop(0);
			serverExecutor.shutdownNow();
			deleteTree(dir);
		}
	}

	private static void handle(
		HttpExchange exchange,
		List<String> calls,
		CountDownLatch uploadStarted,
		CountDownLatch failSeen
	) throws IOException {
		String method = exchange.getRequestMethod();
		String path = exchange.getRequestURI().getPath();
		String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
		String call = method + " " + path;
		calls.add(call);
		if (method.equals("POST") && path.equals("/server/key/exports/11/claim")) {
			json(exchange, 200, "{\"ok\":true}");
			return;
		}
		if (method.equals("POST") && path.equals("/server/key/exports/11/manifest")) {
			json(exchange, 200, "{\"ok\":true}");
			return;
		}
		if (method.equals("POST") && path.equals("/server/key/exports/11/artifact/uploads")) {
			json(exchange, 200, "{\"ok\":true,\"upload_id\":99,\"object_key\":\"exports/request-11/test.zip\",\"part_size_bytes\":4}");
			return;
		}
		if (method.equals("PUT") && path.startsWith("/server/key/exports/11/artifact/uploads/99/parts/")) {
			uploadStarted.countDown();
			try {
				Thread.sleep(10_000L);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			json(exchange, 200, "{\"ok\":true}");
			return;
		}
		if (method.equals("POST") && path.equals("/server/key/exports/11/fail")) {
			if (!body.contains("ExportShutdown")) {
				throw new AssertionError("shutdown failure reason was not sent: " + body);
			}
			failSeen.countDown();
			json(exchange, 200, "{\"ok\":true}");
			return;
		}
		if (method.equals("POST") && path.equals("/server/key/exports/11/complete")) {
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

	private static void assertEquals(Object expected, Object actual, String message) {
		if (!expected.equals(actual)) {
			throw new AssertionError(message + ": expected=" + expected + " actual=" + actual);
		}
	}

	private static void deleteTree(Path dir) throws Exception {
		if (!Files.exists(dir)) return;
		try (var paths = Files.walk(dir)) {
			for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(p);
			}
		}
	}

	private static final class ExactReader implements WorldBlockReader {
		@Override
		public List<BlockRecord> scan(String dimension, int minX, int minY, int minZ, int maxX, int maxY, int maxZ, int maxVolumeBlocks) {
			throw new AssertionError("runtime export should use exact coordinate reads");
		}

		@Override
		public List<BlockRecord> readCoordinates(String dimension, List<BlockCoordinate> coordinates, int maxExportVolumeBlocks) {
			List<BlockRecord> out = new ArrayList<>();
			for (BlockCoordinate coordinate : coordinates) {
				out.add(new BlockRecord(dimension, coordinate.x, coordinate.y, coordinate.z, "minecraft:stone"));
			}
			return out;
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

	private static final class NoopLogger implements CommonLogger {
		@Override
		public void debug(String message, Object... args) {
		}

		@Override
		public void info(String message, Object... args) {
		}

		@Override
		public void warn(String message, Object... args) {
		}

		@Override
		public void error(String message, Object... args) {
		}
	}
}
