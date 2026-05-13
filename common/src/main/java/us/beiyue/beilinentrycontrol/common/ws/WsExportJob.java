package us.beiyue.beilinentrycontrol.common.ws;

public final class WsExportJob {
	public final long requestId;
	public final String minecraftUsername;
	public final String requestedAt;
	public final String reviewedAt;

	public WsExportJob(long requestId, String minecraftUsername, String requestedAt, String reviewedAt) {
		this.requestId = requestId;
		this.minecraftUsername = minecraftUsername;
		this.requestedAt = requestedAt;
		this.reviewedAt = reviewedAt;
	}
}
