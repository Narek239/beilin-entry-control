package us.beiyue.beilindataportability.common;

import com.google.gson.JsonObject;

public final class ComponentSummary {
	public final int componentIndex;
	public final long regionId;
	public final String dimension;
	public final int minX;
	public final int minY;
	public final int minZ;
	public final int maxX;
	public final int maxY;
	public final int maxZ;
	public final int blockCount;
	public final int targetOwnedBlocks;
	public final String targetLastTouchedAt;
	public final int contributorCount;
	public final String riskFlags;
	public final String filename;
	public final int volumeBlocks;
	public final int nonAirBlocks;
	public final int targetAuthorRatioBp;
	public final int authorCount;

	public ComponentSummary(
		int componentIndex,
		long regionId,
		String dimension,
		int minX,
		int minY,
		int minZ,
		int maxX,
		int maxY,
		int maxZ,
		int volumeBlocks,
		int nonAirBlocks,
		int targetAuthorRatioBp,
		String targetLastTouchedAt,
		int authorCount,
		String riskFlags,
		String filename
	) {
		this.componentIndex = componentIndex;
		this.regionId = regionId;
		this.dimension = dimension;
		this.minX = minX;
		this.minY = minY;
		this.minZ = minZ;
		this.maxX = maxX;
		this.maxY = maxY;
		this.maxZ = maxZ;
		this.volumeBlocks = volumeBlocks;
		this.nonAirBlocks = nonAirBlocks;
		this.targetAuthorRatioBp = targetAuthorRatioBp;
		this.targetLastTouchedAt = targetLastTouchedAt;
		this.authorCount = authorCount;
		this.blockCount = nonAirBlocks;
		this.targetOwnedBlocks = Math.max(0, (int) Math.round(nonAirBlocks * (targetAuthorRatioBp / 10000.0D)));
		this.contributorCount = authorCount;
		this.riskFlags = riskFlags;
		this.filename = filename;
	}

	public JsonObject toApiJson() {
		JsonObject o = new JsonObject();
		o.addProperty("component_index", componentIndex);
		o.addProperty("region_id", regionId);
		o.addProperty("dimension", dimension);
		o.addProperty("min_x", minX);
		o.addProperty("min_y", minY);
		o.addProperty("min_z", minZ);
		o.addProperty("max_x", maxX);
		o.addProperty("max_y", maxY);
		o.addProperty("max_z", maxZ);
		o.addProperty("block_count", blockCount);
		o.addProperty("target_owned_blocks", targetOwnedBlocks);
		o.addProperty("volume_blocks", volumeBlocks);
		o.addProperty("non_air_blocks", nonAirBlocks);
		o.addProperty("target_author_ratio_bp", targetAuthorRatioBp);
		o.addProperty("author_count", authorCount);
		if (targetLastTouchedAt != null) o.addProperty("target_last_touched_at", targetLastTouchedAt);
		o.addProperty("contributor_count", contributorCount);
		if (riskFlags != null && !riskFlags.isBlank()) o.addProperty("risk_flags", riskFlags);
		if (filename != null) o.addProperty("filename", filename);
		return o;
	}
}
