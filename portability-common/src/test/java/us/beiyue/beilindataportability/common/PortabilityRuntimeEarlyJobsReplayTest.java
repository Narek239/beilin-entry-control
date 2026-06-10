package us.beiyue.beilindataportability.common;

import us.beiyue.beilinentrycontrol.common.config.CommonConfig;
import us.beiyue.beilinentrycontrol.common.log.CommonLogger;
import us.beiyue.beilinentrycontrol.common.ws.BeilinWsEvents;
import us.beiyue.beilinentrycontrol.common.ws.WsExportJob;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class PortabilityRuntimeEarlyJobsReplayTest {
	public static void main(String[] args) throws Exception {
		dispatchExportJobs(List.of(new WsExportJob(11L, "Alice", "requested", "reviewed")));

		ReplayLogger logger = new ReplayLogger();
		PortabilityRuntime runtime = new PortabilityRuntime(
			new PortabilityApiClient(new TestConfig()),
			logger,
			null,
			null,
			Path.of("unused"),
			1
		);
		try {
			runtime.start();
			if (!logger.replayed.await(5, TimeUnit.SECONDS)) {
				throw new AssertionError("early export jobs were not replayed when the runtime started");
			}
			if (logger.pendingJobCount.get() != 1) {
				throw new AssertionError("expected one replayed export job, got " + logger.pendingJobCount.get());
			}
		} finally {
			runtime.stop();
		}
	}

	private static void dispatchExportJobs(List<WsExportJob> jobs) throws Exception {
		Method dispatch = BeilinWsEvents.class.getDeclaredMethod("dispatchExportJobs", List.class);
		dispatch.setAccessible(true);
		dispatch.invoke(null, jobs);
	}

	private static final class ReplayLogger implements CommonLogger {
		private final CountDownLatch replayed = new CountDownLatch(1);
		private final AtomicInteger pendingJobCount = new AtomicInteger();

		@Override
		public void debug(String message, Object... args) {
		}

		@Override
		public void info(String message, Object... args) {
			if (message.startsWith("Portability export queue has") && args.length > 0 && args[0] instanceof Number count) {
				pendingJobCount.set(count.intValue());
				replayed.countDown();
			}
		}

		@Override
		public void warn(String message, Object... args) {
		}

		@Override
		public void error(String message, Object... args) {
			throw new AssertionError("unexpected error log: " + message);
		}
	}

	private static final class TestConfig implements CommonConfig {
		@Override
		public boolean isValid() {
			return true;
		}

		@Override
		public String httpBase() {
			return "https://example.invalid/server/key";
		}

		@Override
		public String wsUri() {
			return "wss://example.invalid/server/key/ws";
		}

		@Override
		public boolean isApiKeyConfigured() {
			return true;
		}

		@Override
		public String wsBackupDnsHost() {
			return "backup.example.invalid";
		}

		@Override
		public long wsPrimaryProbeIntervalSec() {
			return 60L;
		}
	}
}
