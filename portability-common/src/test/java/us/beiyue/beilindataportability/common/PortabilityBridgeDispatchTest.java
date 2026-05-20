package us.beiyue.beilindataportability.common;

import java.util.ArrayList;
import java.util.List;

public final class PortabilityBridgeDispatchTest {
	public static void main(String[] args) {
		List<ExportJob> received = new ArrayList<>();
		PortabilityBridge.Listener listener = received::addAll;
		PortabilityBridge.addListener(listener);
		try {
			PortabilityBridge.acceptExportJobsJson("{\"action\":\"export_jobs\",\"jobs\":[{\"request_id\":11,\"minecraft_username\":\"Builder\",\"requested_at\":\"r\",\"reviewed_at\":\"v\"}]}");
		} finally {
			PortabilityBridge.removeListener(listener);
		}

		if (received.size() != 1) {
			throw new AssertionError("expected one export job, got " + received.size());
		}
		ExportJob job = received.get(0);
		if (job.requestId != 11L || !"Builder".equals(job.minecraftUsername) || !"r".equals(job.requestedAt) || !"v".equals(job.reviewedAt)) {
			throw new AssertionError("unexpected export job payload");
		}
	}
}
