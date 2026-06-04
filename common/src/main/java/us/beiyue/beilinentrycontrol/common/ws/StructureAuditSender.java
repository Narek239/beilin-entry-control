package us.beiyue.beilinentrycontrol.common.ws;

@FunctionalInterface
public interface StructureAuditSender {
	boolean sendStructureAuditEvents(String text);
}
