package us.beiyue.beilindataportability.platform.fabric1192;

import com.google.gson.Gson;
import net.fabricmc.loader.api.FabricLoader;
import us.beiyue.beilinentrycontrol.common.config.CommonConfig;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class SharedControlConfig1192 implements CommonConfig {
	private static final Gson GSON = new Gson();
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

	public static SharedControlConfig1192 load() throws IOException {
		Path path = configPath();
		if (!Files.exists(path)) {
			return new SharedControlConfig1192();
		}
		try (Reader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			SharedControlConfig1192 c = GSON.fromJson(r, SharedControlConfig1192.class);
			return c != null ? c : new SharedControlConfig1192();
		}
	}
}
