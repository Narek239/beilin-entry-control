package us.beiyue.beilindataportability.common;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class PortabilityBridge {
	@FunctionalInterface
	public interface Listener {
		void onExportJobs(List<ExportJob> jobs);
	}

	private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();

	private PortabilityBridge() {
	}

	public static void addListener(Listener listener) {
		if (listener != null) {
			LISTENERS.add(listener);
		}
	}

	public static void removeListener(Listener listener) {
		if (listener != null) {
			LISTENERS.remove(listener);
		}
	}

	public static int listenerCount() {
		return LISTENERS.size();
	}

	public static void acceptExportJobsJson(String text) {
		if (text == null || text.isBlank()) return;
		JsonObject root = JsonParser.parseString(text).getAsJsonObject();
		if (!root.has("action") || !"export_jobs".equals(root.get("action").getAsString())) return;
		List<ExportJob> jobs = parseJobs(root);
		if (jobs.isEmpty()) return;
		List<ExportJob> safeJobs = List.copyOf(jobs);
		for (Listener listener : LISTENERS) {
			listener.onExportJobs(safeJobs);
		}
	}

	private static List<ExportJob> parseJobs(JsonObject root) {
		JsonArray jobs = root.has("jobs") && root.get("jobs").isJsonArray()
			? root.getAsJsonArray("jobs")
			: new JsonArray();
		List<ExportJob> out = new ArrayList<>();
		for (JsonElement e : jobs) {
			if (!e.isJsonObject()) continue;
			JsonObject o = e.getAsJsonObject();
			long id = o.has("request_id") ? o.get("request_id").getAsLong() : 0L;
			String username = stringOrNull(o, "minecraft_username");
			if (id <= 0 || username == null || username.isBlank()) continue;
			out.add(new ExportJob(
				id,
				username,
				stringOrNull(o, "requested_at"),
				stringOrNull(o, "reviewed_at")
			));
		}
		return out;
	}

	private static String stringOrNull(JsonObject o, String key) {
		if (!o.has(key) || o.get(key).isJsonNull() || !o.get(key).isJsonPrimitive()) {
			return null;
		}
		return o.get(key).getAsString();
	}
}
