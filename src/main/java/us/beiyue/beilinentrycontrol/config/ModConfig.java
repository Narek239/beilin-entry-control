package us.beiyue.beilinentrycontrol.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * API key and base host; loaded from config/beilin-entry-control.json
 */
public final class ModConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String FILE_NAME = "beilin-entry-control.json";

	public String apiKey = "";
	public String baseHost = "beiyue.us";
	public boolean useHttps = true;
	public boolean useWss = true;

	public String httpBase() {
		String scheme = useHttps ? "https" : "http";
		return scheme + "://" + baseHost + "/server/" + apiKey;
	}

	public String wsUri() {
		String scheme = useWss ? "wss" : "ws";
		return scheme + "://" + baseHost + "/server/" + apiKey + "/ws";
	}

	/** True if apiKey is set and not the default placeholder (required for server to start). */
	public boolean isApiKeyConfigured() {
		return apiKey != null && !apiKey.isBlank() && !"YOUR_API_KEY_HERE".equals(apiKey.trim());
	}

	public boolean isValid() {
		return apiKey != null && !apiKey.isBlank();
	}

	public static Path configPath() {
		return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
	}

	public static ModConfig loadOrCreate() throws IOException {
		Path path = configPath();
		if (!Files.exists(path)) {
			ModConfig defaults = new ModConfig();
			defaults.apiKey = "YOUR_API_KEY_HERE";
			try (Writer w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
				GSON.toJson(defaults, w);
			}
			return defaults;
		}
		try (Reader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			ModConfig c = GSON.fromJson(r, ModConfig.class);
			return c != null ? c : new ModConfig();
		}
	}
}
