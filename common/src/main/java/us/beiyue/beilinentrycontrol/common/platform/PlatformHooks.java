package us.beiyue.beilinentrycontrol.common.platform;

import java.util.List;

public interface PlatformHooks {
	void runOnServerThread(Runnable task);
	List<String> getOnlineUsernames();
	void kickAll(String reason);
	void kickByUsername(String username, String reason);
}

