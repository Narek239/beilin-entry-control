package us.beiyue.beilinentrycontrol.platform.fabric1192;

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

public final class ModConfig1192 implements CommonConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String FILE_NAME = "beilin-entry-control.json";

	public String apiKey = "";
	public String baseHost = "beiyue.us";
	public String wsBackupDnsHost = "saas.wiki-beilin.org";
	public long wsPrimaryProbeIntervalSec = 10;
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
	public String wsBackupDnsHost() {
		return wsBackupDnsHost != null && !wsBackupDnsHost.isBlank() ? wsBackupDnsHost : "saas.wiki-beilin.org";
	}

	@Override
	public long wsPrimaryProbeIntervalSec() {
		return wsPrimaryProbeIntervalSec > 0 ? wsPrimaryProbeIntervalSec : 10;
	}

	@Override
	public boolean isValid() {
		return apiKey != null && !apiKey.isBlank();
	}

	public static Path configPath() {
		return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
	}

	public static ModConfig1192 loadOrCreate() throws IOException {
		Path path = configPath();
		if (!Files.exists(path)) {
			ModConfig1192 defaults = new ModConfig1192();
			defaults.apiKey = "YOUR_API_KEY_HERE";
			try (Writer w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
				GSON.toJson(defaults, w);
			}
			return defaults;
		}
		try (Reader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			ModConfig1192 c = GSON.fromJson(r, ModConfig1192.class);
			return c != null ? c : new ModConfig1192();
		}
	}
}

