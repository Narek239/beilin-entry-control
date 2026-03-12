package us.beiyue.beilinentrycontrol;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import us.beiyue.beilinentrycontrol.config.ModConfig;
import us.beiyue.beilinentrycontrol.http.BeilinApiClient;
import us.beiyue.beilinentrycontrol.login.LoginHandler;
import us.beiyue.beilinentrycontrol.ws.BeilinWsClient;

public class BeilinEntryControl implements ModInitializer {
	public static final String MOD_ID = "beilin-entry-control";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static ModConfig config;
	private static BeilinApiClient apiClient;
	private static BeilinWsClient wsClient;

	@Override
	public void onInitialize() {
		if (FabricLoader.getInstance().getEnvironmentType() != EnvType.SERVER) {
			return;
		}
		try {
			config = ModConfig.loadOrCreate();
		} catch (Exception e) {
			LOGGER.error("Failed to load config", e);
			return;
		}
		// 与 EULA 类似：未配置 apiKey 时直接退出进程，避免在 SERVER_STARTING 里 exit 导致卡死
		if (!config.isApiKeyConfigured()) {
			LOGGER.error("Beilin Entry Control: You need to set the apiKey to run the server. Please edit {} to set it.", ModConfig.configPath());
			System.exit(1);
		}
		apiClient = new BeilinApiClient(config);

		LoginHandler.setApiClient(apiClient);
		LoginHandler.register();

		// 监听玩家断线事件，直接从 ServerPlayer 获取用户名并上报到 player_leave。
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
		// apiKey 已在 onInitialize() 中检查，未配置时进程已退出
		wsClient = new BeilinWsClient(config, server, apiClient);
		wsClient.start();
	}

	private void onServerStopping(MinecraftServer server) {
		if (wsClient != null) {
			wsClient.stop();
			wsClient = null;
		}
	}
}
