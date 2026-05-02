package us.beiyue.beilinentrycontrol.platform.fabric1201;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import us.beiyue.beilinentrycontrol.common.gate.EntryGateState;
import us.beiyue.beilinentrycontrol.common.http.BeilinApiClient;
import us.beiyue.beilinentrycontrol.common.http.OutboundRouteState;
import us.beiyue.beilinentrycontrol.common.ws.BeilinWsClient;

public final class BeilinEntryControl1201 implements ModInitializer {
	public static final String MOD_ID = "beilin-entry-control";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static ModConfig1201 config;
	private static OutboundRouteState outboundRouteState;
	private static BeilinApiClient apiClient;
	private static EntryGateState gateState;
	private static BeilinWsClient wsClient;

	@Override
	public void onInitialize() {
		if (FabricLoader.getInstance().getEnvironmentType() != EnvType.SERVER) {
			return;
		}
		try {
			config = ModConfig1201.loadOrCreate();
		} catch (Exception e) {
			LOGGER.error("Failed to load config", e);
			return;
		}
		if (!config.isApiKeyConfigured()) {
			LOGGER.error("Beilin Entry Control: You need to set the apiKey to run the server. Please edit {} to set it.", ModConfig1201.configPath());
			System.exit(1);
		}

		outboundRouteState = new OutboundRouteState();
		apiClient = new BeilinApiClient(config, outboundRouteState);
		gateState = new EntryGateState();

		LoginHandler1201.setApiClient(apiClient);
		LoginHandler1201.setGateState(gateState);
		LoginHandler1201.register();

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			if (apiClient == null || config == null || !config.isValid()) return;
			ServerPlayer player = handler.player;
			if (player == null) return;
			String username = player.getGameProfile().getName();
			if (username == null || username.isEmpty()) return;
			onPlayerLeft(username);
		});

		ServerLifecycleEvents.SERVER_STARTING.register(this::onServerStarting);
		ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);
	}

	private static void onPlayerLeft(String username) {
		if (apiClient == null || config == null || !config.isValid()) return;
		if (username == null || username.isEmpty()) return;
		apiClient.playerLeaveAsync(username).exceptionally(ex -> {
			LOGGER.debug("player_leave failed for {}", username, ex);
			return false;
		});
	}

	private void onServerStarting(MinecraftServer server) {
		if (!server.isDedicatedServer()) return;

		PlatformHooks1201 hooks = new PlatformHooks1201(server);
		wsClient = new BeilinWsClient(
			config,
			hooks,
			apiClient,
			gateState,
			new Slf4jCommonLogger(LOGGER),
			outboundRouteState
		);
		wsClient.start();
	}

	private void onServerStopping(MinecraftServer server) {
		if (wsClient != null) {
			wsClient.stop();
			wsClient = null;
		}
	}
}

