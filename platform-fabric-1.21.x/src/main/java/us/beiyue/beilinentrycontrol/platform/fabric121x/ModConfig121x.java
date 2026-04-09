package us.beiyue.beilinentrycontrol.platform.fabric121x;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import us.beiyue.beilinentrycontrol.common.config.CommonConfig;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ModConfig121x implements CommonConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String FILE_NAME = "beilin-entry-control.json";

	public String apiKey = "";
	public String baseHost = "beiyue.us";
	public boolean useHttps = true;
	public boolean useWss = true;

	@Override
	public String httpBase() {
		String scheme = useHttps ? "https" : "http";
		return scheme + "://" + baseHost + "/server/" + apiKey;
	}

	@Override
	public String wsUri() {
		String scheme = useWss ? "wss" : "ws";
		return scheme + "://" + baseHost + "/server/" + apiKey + "/ws";
	}

	@Override
	public boolean isApiKeyConfigured() {
		return apiKey != null && !apiKey.isBlank() && !"YOUR_API_KEY_HERE".equals(apiKey.trim());
	}

	@Override
	public boolean isValid() {
		return apiKey != null && !apiKey.isBlank();
	}

	public static Path configPath() {
		return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
	}

	public static ModConfig121x loadOrCreate() throws IOException {
		Path path = configPath();
		if (!Files.exists(path)) {
			ModConfig121x defaults = new ModConfig121x();
			defaults.apiKey = "YOUR_API_KEY_HERE";
			try (Writer w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
				GSON.toJson(defaults, w);
			}
			return defaults;
		}
		try (Reader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			ModConfig121x c = GSON.fromJson(r, ModConfig121x.class);
			return c != null ? c : new ModConfig121x();
		}
	}
}

