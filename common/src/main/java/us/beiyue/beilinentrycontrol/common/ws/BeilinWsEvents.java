package us.beiyue.beilinentrycontrol.common.ws;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

public final class BeilinWsEvents {
	private static final CopyOnWriteArrayList<ExportJobsListener> EXPORT_JOB_LISTENERS = new CopyOnWriteArrayList<>();
	private static volatile List<WsExportJob> lastExportJobs = List.of();

	private BeilinWsEvents() {
	}

	public static void addExportJobsListener(ExportJobsListener listener) {
		ExportJobsListener safeListener = Objects.requireNonNull(listener, "listener");
		EXPORT_JOB_LISTENERS.add(safeListener);
		List<WsExportJob> cached = lastExportJobs;
		if (!cached.isEmpty()) {
			safeListener.onExportJobs(cached);
		}
	}

	public static void removeExportJobsListener(ExportJobsListener listener) {
		if (listener != null) {
			EXPORT_JOB_LISTENERS.remove(listener);
		}
	}

	static void dispatchExportJobs(List<WsExportJob> jobs) {
		List<WsExportJob> safeJobs = jobs == null ? List.of() : List.copyOf(jobs);
		lastExportJobs = safeJobs;
		for (ExportJobsListener listener : EXPORT_JOB_LISTENERS) {
			listener.onExportJobs(safeJobs);
		}
	}
}
