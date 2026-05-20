package us.beiyue.beilindataportability.common;

public final class ExportJob {
	public final long requestId;
	public final String minecraftUsername;
	public final String requestedAt;
	public final String reviewedAt;

	public ExportJob(long requestId, String minecraftUsername, String requestedAt, String reviewedAt) {
		this.requestId = requestId;
		this.minecraftUsername = minecraftUsername;
		this.requestedAt = requestedAt;
		this.reviewedAt = reviewedAt;
	}
}
