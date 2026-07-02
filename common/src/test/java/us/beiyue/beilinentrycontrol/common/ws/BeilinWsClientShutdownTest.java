package us.beiyue.beilinentrycontrol.common.ws;

import okhttp3.Response;
import okhttp3.WebSocket;
import us.beiyue.beilinentrycontrol.common.config.CommonConfig;
import us.beiyue.beilinentrycontrol.common.gate.EntryGateState;
import us.beiyue.beilinentrycontrol.common.http.BeilinApiClient;
import us.beiyue.beilinentrycontrol.common.http.OutboundRoute;
import us.beiyue.beilinentrycontrol.common.http.OutboundRouteState;
import us.beiyue.beilinentrycontrol.common.log.CommonLogger;
import us.beiyue.beilinentrycontrol.common.platform.PlatformHooks;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class BeilinWsClientShutdownTest {
	public static void main(String[] args) throws Exception {
		assertWsDownKicksOnlyOnAcceptingTransition();

		TestConfig config = new TestConfig();
		OutboundRouteState routeState = new OutboundRouteState();
		BeilinApiClient apiClient = new BeilinApiClient(config, routeState);
		BeilinWsClient client = new BeilinWsClient(
			config,
			new TestHooks(),
			apiClient,
			new EntryGateState(),
			new TestLogger(),
			routeState
		);

		client.stop();
		AtomicBoolean executed = new AtomicBoolean(false);
		boolean accepted = client.runOnScheduler(() -> executed.set(true));
		if (accepted) {
			throw new AssertionError("scheduler accepted work after stop");
		}
		if (executed.get()) {
			throw new AssertionError("scheduler executed work after stop");
		}

		Object listener = listener(client);
		invoke(listener, "onFailure",
			new Class<?>[] { WebSocket.class, Throwable.class, Response.class },
			new Object[] { null, new IOException("closed during shutdown"), null });
		invoke(listener, "onClosed",
			new Class<?>[] { WebSocket.class, int.class, String.class },
			new Object[] { null, 1006, "shutdown" });
		invoke(client, "scheduleReconnect", new Class<?>[0], new Object[0]);

		if (apiClient.playerJoinAsync("Alice").get().ok) {
			throw new AssertionError("api client accepted work after websocket stop");
		}
	}

	private static void assertWsDownKicksOnlyOnAcceptingTransition() throws Exception {
		TestConfig config = new TestConfig();
		OutboundRouteState routeState = new OutboundRouteState();
		EntryGateState gateState = new EntryGateState();
		CountingHooks hooks = new CountingHooks();
		BeilinWsClient client = new BeilinWsClient(
			config,
			hooks,
			new BeilinApiClient(config, routeState),
			gateState,
			new TestLogger(),
			routeState
		);
		try {
			invoke(client, "onWsDown", new Class<?>[] { boolean.class }, new Object[] { true });
			if (hooks.kickAllCount.get() != 0) {
				throw new AssertionError("initial non-accepting ws down should not kick players");
			}

			gateState.setAcceptingPlayers(true);
			invoke(client, "onWsDown", new Class<?>[] { boolean.class }, new Object[] { true });
			if (hooks.kickAllCount.get() != 1) {
				throw new AssertionError("accepting->down transition should kick players once");
			}
			if (gateState.isAcceptingPlayers()) {
				throw new AssertionError("ws down should close the entry gate");
			}

			invoke(client, "onWsDown", new Class<?>[] { boolean.class }, new Object[] { true });
			if (hooks.kickAllCount.get() != 1) {
				throw new AssertionError("repeated ws down should not kick players again");
			}

			gateState.setAcceptingPlayers(true);
			invoke(client, "onWsDown", new Class<?>[] { boolean.class }, new Object[] { false });
			if (hooks.kickAllCount.get() != 1) {
				throw new AssertionError("silent ws down should not kick players");
			}
		} finally {
			client.stop();
		}
	}

	@SuppressWarnings("unchecked")
	private static Object listener(BeilinWsClient client) throws ReflectiveOperationException {
		Class<?> modeClass = Class.forName("us.beiyue.beilinentrycontrol.common.ws.BeilinWsClient$SilentCloseMode");
		Object normalMode = Enum.valueOf((Class<Enum>) modeClass.asSubclass(Enum.class), "NORMAL");
		Class<?> listenerClass = Class.forName("us.beiyue.beilinentrycontrol.common.ws.BeilinWsClient$Listener");
		Constructor<?> ctor = listenerClass.getDeclaredConstructor(BeilinWsClient.class, OutboundRoute.class, modeClass, java.net.InetAddress.class);
		ctor.setAccessible(true);
		return ctor.newInstance(client, OutboundRoute.PRIMARY, normalMode, null);
	}

	private static void invoke(Object target, String name, Class<?>[] types, Object[] args) throws ReflectiveOperationException {
		Method method = target.getClass().getDeclaredMethod(name, types);
		method.setAccessible(true);
		method.invoke(target, args);
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

	private static final class CountingHooks implements PlatformHooks {
		private final AtomicInteger kickAllCount = new AtomicInteger();

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
			kickAllCount.incrementAndGet();
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
