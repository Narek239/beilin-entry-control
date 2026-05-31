package us.beiyue.beilindataportability.platform.fabric1192;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import us.beiyue.beilindataportability.common.BuildingIndexStore;
import us.beiyue.beilindataportability.common.PortabilityApiClient;
import us.beiyue.beilindataportability.common.PortabilityRuntime;

import java.nio.file.Path;

public final class BeilinDataPortability1192 implements ModInitializer {
	public static final String MOD_ID = "beilin-data-portability";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	private static volatile BuildingIndexStore activeStore;

	private PortabilityRuntime runtime;
	private BuildingIndexStore indexStore;
	private PortabilityAddonConfig1192 addonConfig;
	private SharedControlConfig1192 controlConfig;

	@Override
	public void onInitialize() {
		if (FabricLoader.getInstance().getEnvironmentType() != EnvType.SERVER) {
			return;
		}
		try {
			addonConfig = PortabilityAddonConfig1192.loadOrCreate();
			controlConfig = SharedControlConfig1192.load();
		} catch (Exception e) {
			LOGGER.error("Failed to load Beilin Data Portability config", e);
			return;
		}
		BlockChangeRecorder1192.register(
			() -> addonConfig != null && addonConfig.recordingEnabled ? indexStore : null,
			LOGGER
		);
		BlockChangeRecorder1192.configureBulkOptions(
			addonConfig.recordWorldEditBulkPlacements,
			addonConfig.recordEffortlessBulkPlacements,
			addonConfig.discardLinearBulkPlacements
		);
		if (!addonConfig.exportProcessingEnabled) {
			LOGGER.info("Beilin Data Portability is installed. Recording={}, export processing disabled. Edit {} to enable WebSocket export processing.", addonConfig.recordingEnabled, PortabilityAddonConfig1192.configPath());
		}
		if (addonConfig.exportProcessingEnabled && !controlConfig.isApiKeyConfigured()) {
			LOGGER.warn("Beilin Data Portability cannot start because {} has no valid apiKey.", SharedControlConfig1192.configPath());
		}

		ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
		ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);
		registerCommands();
	}

	private void onServerStarted(MinecraftServer server) {
		if (!server.isDedicatedServer()) return;
		try {
			Path dbPath = resolveWorldDbPath(server);
			Path artifactDir = resolveArtifactDir(addonConfig.artifactDirectory);
			indexStore = BuildingIndexStore.open(dbPath, new Slf4jCommonLogger(LOGGER));
			activeStore = indexStore;
			if (addonConfig.exportProcessingEnabled && controlConfig.isApiKeyConfigured()) {
				PortabilityApiClient apiClient = new PortabilityApiClient(controlConfig);
				runtime = new PortabilityRuntime(
					apiClient,
					new Slf4jCommonLogger(LOGGER),
					indexStore,
					new FabricWorldBlockReader1192(server, addonConfig.scanChunksPerTick),
					artifactDir,
					addonConfig.maxExportVolumeBlocks
				);
				runtime.start();
			}
		} catch (Exception e) {
			LOGGER.error("Failed to start Beilin Data Portability runtime", e);
		}
	}

	private void onServerStopping(MinecraftServer server) {
		if (runtime != null) {
			runtime.stop();
			runtime = null;
		}
		if (indexStore != null) {
			indexStore.close();
			indexStore = null;
		}
		activeStore = null;
	}

	private static Path resolveArtifactDir(String configured) {
		Path raw = configured != null && !configured.isBlank()
			? Path.of(configured)
			: Path.of("beilin-data-portability-exports");
		if (raw.isAbsolute()) return raw;
		return FabricLoader.getInstance().getConfigDir().resolve(raw);
	}

	private static Path resolveWorldDbPath(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT)
			.resolve("beilin-data-portability")
			.resolve("index.db");
	}

	private static void registerCommands() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
				Commands.literal("beportability")
					.requires(source -> source.hasPermission(3))
					.then(Commands.literal("status").executes(ctx -> sendStatus(ctx.getSource())))
					.then(Commands.literal("checkpoint").executes(ctx -> runStoreCommand(ctx.getSource(), "checkpoint")))
					.then(Commands.literal("compact").executes(ctx -> runStoreCommand(ctx.getSource(), "compact")))
			));
	}

	private static int sendStatus(CommandSourceStack source) {
		BuildingIndexStore store = activeStore;
		String message = store == null ? "Beilin Data Portability index is not open" : store.diagnosticSummary();
		source.sendSuccess(Component.literal(message), false);
		return 1;
	}

	private static int runStoreCommand(CommandSourceStack source, String command) {
		BuildingIndexStore store = activeStore;
		if (store == null) {
			source.sendFailure(Component.literal("Beilin Data Portability index is not open"));
			return 0;
		}
		try {
			if ("compact".equals(command)) {
				store.compact();
			} else {
				store.checkpoint();
			}
			source.sendSuccess(Component.literal("Beilin Data Portability " + command + " complete"), true);
			return 1;
		} catch (Exception e) {
			source.sendFailure(Component.literal("Beilin Data Portability " + command + " failed: " + e.getMessage()));
			return 0;
		}
	}
}
