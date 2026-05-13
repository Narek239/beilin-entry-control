package us.beiyue.beilinentrycontrol.common.ws;

import java.util.List;

@FunctionalInterface
public interface ExportJobsListener {
	void onExportJobs(List<WsExportJob> jobs);
}
