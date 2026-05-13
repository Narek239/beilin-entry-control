package us.beiyue.beilinentrycontrol.common.ws;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

public final class BeilinWsEvents {
	private static final CopyOnWriteArrayList<ExportJobsListener> EXPORT_JOB_LISTENERS = new CopyOnWriteArrayList<>();
	private static volatile Runnable exportJobsRequester;

	private BeilinWsEvents() {
	}

	public static void addExportJobsListener(ExportJobsListener listener) {
		EXPORT_JOB_LISTENERS.add(Objects.requireNonNull(listener, "listener"));
	}

	public static void removeExportJobsListener(ExportJobsListener listener) {
		if (listener != null) {
			EXPORT_JOB_LISTENERS.remove(listener);
		}
	}

	static void dispatchExportJobs(List<WsExportJob> jobs) {
		List<WsExportJob> safeJobs = jobs == null ? List.of() : List.copyOf(jobs);
		for (ExportJobsListener listener : EXPORT_JOB_LISTENERS) {
			listener.onExportJobs(safeJobs);
		}
	}

	static void setExportJobsRequester(Runnable requester) {
		exportJobsRequester = requester;
	}

	public static boolean requestExportJobs() {
		Runnable requester = exportJobsRequester;
		if (requester == null) {
			return false;
		}
		requester.run();
		return true;
	}
}
