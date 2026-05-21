package us.beiyue.beilindataportability.platform.fabric1192;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class PortabilityAddonConfig1192 {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String FILE_NAME = "beilin-data-portability.json";

	public boolean exportProcessingEnabled = true;
	public boolean recordingEnabled = true;
	public String artifactDirectory = "beilin-data-portability-exports";
	public int maxExportVolumeBlocks = 4_000_000;
	public int scanChunksPerTick = 12;

	public static Path configPath() {
		return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
	}

	public static PortabilityAddonConfig1192 loadOrCreate() throws IOException {
		Path path = configPath();
		if (!Files.exists(path)) {
			PortabilityAddonConfig1192 defaults = new PortabilityAddonConfig1192();
			try (Writer w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
				GSON.toJson(defaults, w);
			}
			return defaults;
		}
		try (Reader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			PortabilityAddonConfig1192 c = GSON.fromJson(r, PortabilityAddonConfig1192.class);
			return c != null ? c : new PortabilityAddonConfig1192();
		}
	}
}
