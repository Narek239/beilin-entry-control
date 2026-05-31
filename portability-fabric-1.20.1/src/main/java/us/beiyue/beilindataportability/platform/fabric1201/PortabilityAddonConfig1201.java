package us.beiyue.beilindataportability.platform.fabric1201;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class PortabilityAddonConfig1201 {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String FILE_NAME = "beilin-data-portability.json";

	public boolean exportProcessingEnabled = true;
	public boolean recordingEnabled = true;
	public boolean recordWorldEditBulkPlacements = true;
	public boolean recordEffortlessBulkPlacements = true;
	public boolean discardLinearBulkPlacements = true;
	public String artifactDirectory = "beilin-data-portability-exports";
	public int maxExportVolumeBlocks = 4_000_000;
	public int scanChunksPerTick = 12;

	public static Path configPath() {
		return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
	}

	public static PortabilityAddonConfig1201 loadOrCreate() throws IOException {
		Path path = configPath();
		if (!Files.exists(path)) {
			PortabilityAddonConfig1201 defaults = new PortabilityAddonConfig1201();
			try (Writer w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
				GSON.toJson(defaults, w);
			}
			return defaults;
		}
		try (Reader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			PortabilityAddonConfig1201 c = GSON.fromJson(r, PortabilityAddonConfig1201.class);
			return c != null ? c : new PortabilityAddonConfig1201();
		}
	}
}
