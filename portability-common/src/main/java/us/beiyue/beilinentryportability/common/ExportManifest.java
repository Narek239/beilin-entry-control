package us.beiyue.beilinentryportability.common;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;

public final class ExportManifest {
	public final long requestId;
	public final String minecraftUsername;
	public final String generatedAt;
	public final int totalIndexedRegions;
	public final List<ComponentSummary> components;

	public ExportManifest(
		long requestId,
		String minecraftUsername,
		String generatedAt,
		int totalIndexedRegions,
		List<ComponentSummary> components
	) {
		this.requestId = requestId;
		this.minecraftUsername = minecraftUsername;
		this.generatedAt = generatedAt;
		this.totalIndexedRegions = totalIndexedRegions;
		this.components = components;
	}

	public JsonObject toManifestJson() {
		JsonObject root = new JsonObject();
		root.addProperty("format", "beilin-entry-portability-manifest-v2");
		root.addProperty("request_id", requestId);
		root.addProperty("minecraft_username", minecraftUsername);
		root.addProperty("generated_at", generatedAt);
		root.addProperty("total_indexed_regions", totalIndexedRegions);
		root.addProperty("component_count", components.size());
		JsonArray arr = new JsonArray();
		for (ComponentSummary c : components) {
			arr.add(c.toApiJson());
		}
		root.add("components", arr);
		return root;
	}
}
