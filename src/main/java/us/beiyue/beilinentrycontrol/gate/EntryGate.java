package us.beiyue.beilinentrycontrol.gate;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Global accept flag + kick all on WS down (main thread only for kicks).
 */
public final class EntryGate {
	public static final String SYNC_MESSAGE = "正在与 Beilin Entry Control 同步";

	private static final AtomicBoolean acceptingPlayers = new AtomicBoolean(false);

	public static boolean isAcceptingPlayers() {
		return acceptingPlayers.get();
	}

	public static void setAcceptingPlayers(boolean v) {
		acceptingPlayers.set(v);
	}

	/**
	 * Must run via server.execute — kicks every online player.
	 */
	public static void kickAll(MinecraftServer server, String reason) {
		Component msg = Component.literal(reason != null ? reason : SYNC_MESSAGE);
		List<ServerPlayer> copy = new ArrayList<>(server.getPlayerList().getPlayers());
		for (ServerPlayer p : copy) {
			p.connection.disconnect(msg);
		}
	}

	public static void kickByUsername(MinecraftServer server, String username, String reason) {
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
