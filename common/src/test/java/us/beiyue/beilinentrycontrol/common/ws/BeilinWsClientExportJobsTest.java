package us.beiyue.beilinentrycontrol.common.ws;

import us.beiyue.beilinentrycontrol.common.config.CommonConfig;
import us.beiyue.beilinentrycontrol.common.gate.EntryGateState;
import us.beiyue.beilinentrycontrol.common.http.BeilinApiClient;
import us.beiyue.beilinentrycontrol.common.http.OutboundRouteState;
import us.beiyue.beilinentrycontrol.common.log.CommonLogger;
import us.beiyue.beilinentrycontrol.common.platform.PlatformHooks;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public final class BeilinWsClientExportJobsTest {
	public static void main(String[] args) throws Exception {
		TestConfig config = new TestConfig();
		OutboundRouteState routeState = new OutboundRouteState();
		BeilinWsClient client = new BeilinWsClient(
			config,
			new TestHooks(),
			new BeilinApiClient(config, routeState),
			new EntryGateState(),
			new TestLogger(),
			routeState
		);

		List<WsExportJob> received = new ArrayList<>();
		ExportJobsListener listener = received::addAll;
		BeilinWsEvents.addExportJobsListener(listener);
		try {
			Method method = BeilinWsClient.class.getDeclaredMethod("handleTextMessage", String.class);
			method.setAccessible(true);
			method.invoke(client, "{\"action\":\"export_jobs\",\"jobs\":[{\"request_id\":11,\"minecraft_username\":\"Builder\",\"requested_at\":\"r\",\"reviewed_at\":\"v\"}]}");
		} finally {
			BeilinWsEvents.removeExportJobsListener(listener);
			client.stop();
		}

		if (received.size() != 1) {
			throw new AssertionError("expected one export job, got " + received.size());
		}
		WsExportJob job = received.get(0);
		if (job.requestId != 11L || !"Builder".equals(job.minecraftUsername) || !"r".equals(job.requestedAt) || !"v".equals(job.reviewedAt)) {
			throw new AssertionError("unexpected export job payload");
		}

		final boolean[] requested = { false };
		BeilinWsEvents.setExportJobsRequester(() -> requested[0] = true);
		try {
			if (!BeilinWsEvents.requestExportJobs() || !requested[0]) {
				throw new AssertionError("export job requester was not invoked");
			}
		} finally {
			BeilinWsEvents.setExportJobsRequester(null);
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
			return 60;
		}
	}

	private static final class TestHooks implements PlatformHooks {
		@Override
		public void runOnServerThread(Runnable task) {
			if (task != null) task.run();
		}

		@Override
		public List<String> getOnlineUsernames() {
			return List.of();
		}

		@Override
		public void kickAll(String reason) {
		}

		@Override
		public void kickByUsername(String username, String reason) {
		}
	}

	private static final class TestLogger implements CommonLogger {
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
			throw new AssertionError("unexpected error log: " + message);
		}
	}
}
