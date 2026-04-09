package us.beiyue.beilinentrycontrol.platform.fabric121x;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import us.beiyue.beilinentrycontrol.common.platform.PlatformHooks;

import java.util.ArrayList;
import java.util.List;

public final class PlatformHooks121x implements PlatformHooks {
	private final MinecraftServer server;

	public PlatformHooks121x(MinecraftServer server) {
		this.server = server;
	}

	@Override
	public void runOnServerThread(Runnable task) {
		server.execute(task);
	}

	@Override
	public List<String> getOnlineUsernames() {
		List<ServerPlayer> players = server.getPlayerList().getPlayers();
		List<String> out = new ArrayList<>(players.size());
		for (ServerPlayer p : players) {
			String u = p.getGameProfile().getName();
			if (u != null && !u.isEmpty()) out.add(u);
		}
		return out;
	}

	@Override
	public void kickAll(String reason) {
		Component msg = Component.literal(reason != null ? reason : "已被踢出");
		List<ServerPlayer> copy = new ArrayList<>(server.getPlayerList().getPlayers());
		for (ServerPlayer p : copy) {
			p.connection.disconnect(msg);
		}
	}

	@Override
	public void kickByUsername(String username, String reason) {
		if (username == null || username.isEmpty()) return;
		Component msg = Component.literal(reason != null && !reason.isEmpty() ? reason : "已被踢出");
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (username.equalsIgnoreCase(p.getGameProfile().getName())) {
				p.connection.disconnect(msg);
				break;
			}
		}
	}
}

