package us.beiyue.beilinentrycontrol.common.gate;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Pure state holder: whether the server currently accepts players.
 */
public final class EntryGateState {
	public static final String SYNC_MESSAGE = "正在与 Beilin Entry Control 同步";

	private final AtomicBoolean acceptingPlayers = new AtomicBoolean(false);

	public boolean isAcceptingPlayers() {
		return acceptingPlayers.get();
	}

	public void setAcceptingPlayers(boolean v) {
		acceptingPlayers.set(v);
	}
}

