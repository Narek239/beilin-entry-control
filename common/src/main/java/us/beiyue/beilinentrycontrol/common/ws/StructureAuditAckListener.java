package us.beiyue.beilinentrycontrol.common.ws;

import java.util.List;

@FunctionalInterface
public interface StructureAuditAckListener {
	void onStructureAuditAck(List<String> eventIds);
}
