package us.beiyue.beilinentryportability.platform.fabric121x;

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
import us.beiyue.beilinentryportability.common.BuildingIndexStore;
import us.beiyue.beilinentryportability.common.PortabilityApiClient;
import us.beiyue.beilinentryportability.common.PortabilityRuntime;

import java.nio.file.Path;

public final class BeilinEntryPortability121x implements ModInitializer {
	public static final String MOD_ID = "beilin-entry-portability";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	private static volatile BuildingIndexStore activeStore;

	private PortabilityRuntime runtime;
	private BuildingIndexStore indexStore;
	private PortabilityAddonConfig121x addonConfig;
	private SharedControlConfig121x controlConfig;

	@Override
	public void onInitialize() {
		if (FabricLoader.getInstance().getEnvironmentType() != EnvType.SERVER) {
			return;
		}
		try {
			addonConfig = PortabilityAddonConfig121x.loadOrCreate();
			controlConfig = SharedControlConfig121x.load();
		} catch (Exception e) {
			LOGGER.error("Failed to load Beilin Entry Portability config", e);
			return;
		}
		BlockChangeRecorder121x.register(
			() -> addonConfig != null && addonConfig.recordingEnabled ? indexStore : null,
			LOGGER
		);
		if (!addonConfig.exportProcessingEnabled) {
			LOGGER.info("Beilin Entry Portability is installed. Recording={}, export processing disabled. Edit {} to enable WebSocket export processing.", addonConfig.recordingEnabled, PortabilityAddonConfig121x.configPath());
		}
		if (addonConfig.exportProcessingEnabled && !controlConfig.isApiKeyConfigured()) {
			LOGGER.warn("Beilin Entry Portability cannot start because {} has no valid apiKey.", SharedControlConfig121x.configPath());
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
					new FabricWorldBlockReader121x(server, addonConfig.scanChunksPerTick),
					addonConfig.claimJobs,
					artifactDir,
					addonConfig.maxExportVolumeBlocks
				);
				runtime.start();
			}
		} catch (Exception e) {
			LOGGER.error("Failed to start Beilin Entry Portability runtime", e);
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

	public static void flushIndexForWorldSave() {
	}

	public static void checkpointIndexAfterWorldSave() {
		// WAL is kept inside the world directory. Avoid checkpointing on the server
		// thread after each save because large saves can otherwise make stop hang.
	}

	private static Path resolveArtifactDir(String configured) {
		Path raw = configured != null && !configured.isBlank()
			? Path.of(configured)
			: Path.of("beilin-entry-portability-exports");
		if (raw.isAbsolute()) return raw;
		return FabricLoader.getInstance().getConfigDir().resolve(raw);
	}

	private static Path resolveWorldDbPath(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT)
			.resolve("beilin-entry-portability")
			.resolve("index.db");
	}

	private static void registerCommands() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
			Commands.literal("beportability")
				.requires(source -> source.hasPermission(3))
				.then(Commands.literal("status").executes(ctx -> sendStatus(ctx.getSource())))
				.then(Commands.literal("doctor").executes(ctx -> sendStatus(ctx.getSource())))
				.then(Commands.literal("checkpoint").executes(ctx -> runStoreCommand(ctx.getSource(), "checkpoint")))
				.then(Commands.literal("compact").executes(ctx -> runStoreCommand(ctx.getSource(), "compact")))
		));
	}

	private static int sendStatus(CommandSourceStack source) {
		BuildingIndexStore store = activeStore;
		String message = store == null ? "Beilin Entry Portability index is not open" : store.diagnosticSummary();
		source.sendSuccess(() -> Component.literal(message), false);
		return 1;
	}

	private static int runStoreCommand(CommandSourceStack source, String command) {
		BuildingIndexStore store = activeStore;
		if (store == null) {
			source.sendFailure(Component.literal("Beilin Entry Portability index is not open"));
			return 0;
		}
		try {
			if ("compact".equals(command)) {
				store.compact();
			} else {
				store.checkpoint();
			}
			source.sendSuccess(() -> Component.literal("Beilin Entry Portability " + command + " complete"), true);
			return 1;
		} catch (Exception e) {
			source.sendFailure(Component.literal("Beilin Entry Portability " + command + " failed: " + e.getMessage()));
			return 0;
		}
	}
}
