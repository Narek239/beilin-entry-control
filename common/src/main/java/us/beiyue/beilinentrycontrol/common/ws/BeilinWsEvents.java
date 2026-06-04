package us.beiyue.beilinentrycontrol.common.ws;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

public final class BeilinWsEvents {
	private static final CopyOnWriteArrayList<ExportJobsListener> EXPORT_JOB_LISTENERS = new CopyOnWriteArrayList<>();
	private static final CopyOnWriteArrayList<StructureAuditAckListener> STRUCTURE_AUDIT_ACK_LISTENERS = new CopyOnWriteArrayList<>();
	private static final AtomicReference<StructureAuditSender> STRUCTURE_AUDIT_SENDER = new AtomicReference<>();
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

	public static void setStructureAuditSender(StructureAuditSender sender) {
		STRUCTURE_AUDIT_SENDER.set(sender);
	}

	public static boolean sendStructureAuditEvents(String text) {
		if (text == null || text.isBlank()) return false;
		StructureAuditSender sender = STRUCTURE_AUDIT_SENDER.get();
		return sender != null && sender.sendStructureAuditEvents(text);
	}

	public static void addStructureAuditAckListener(StructureAuditAckListener listener) {
		STRUCTURE_AUDIT_ACK_LISTENERS.add(Objects.requireNonNull(listener, "listener"));
	}

	public static void removeStructureAuditAckListener(StructureAuditAckListener listener) {
		if (listener != null) {
			STRUCTURE_AUDIT_ACK_LISTENERS.remove(listener);
		}
	}

	static void dispatchStructureAuditAck(List<String> eventIds) {
		List<String> safeIds = eventIds == null ? List.of() : List.copyOf(eventIds);
		if (safeIds.isEmpty()) return;
		for (StructureAuditAckListener listener : STRUCTURE_AUDIT_ACK_LISTENERS) {
			listener.onStructureAuditAck(safeIds);
		}
	}
}
