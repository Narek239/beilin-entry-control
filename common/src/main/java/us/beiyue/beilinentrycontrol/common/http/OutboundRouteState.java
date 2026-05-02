package us.beiyue.beilinentrycontrol.common.http;

import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shared between {@link us.beiyue.beilinentrycontrol.common.ws.BeilinWsClient} and {@link BeilinApiClient}:
 * which edge is preferred and the last BACKUP literal IP dialed by WS so HTTP matches WebSocket egress.
 */
public final class OutboundRouteState {
	private final AtomicReference<OutboundRoute> route = new AtomicReference<>(OutboundRoute.PRIMARY);
	private volatile InetAddress stickyBackupInet;

	public OutboundRoute getOutboundRoute() {
		return route.get();
	}

	public void setOutboundRoute(OutboundRoute r) {
		route.set(r);
		if (r == OutboundRoute.PRIMARY) {
			stickyBackupInet = null;
		}
	}

	/** BACKUP TLS dial picked this address; HTTPS should use the same when route is BACKUP. */
	public void setStickyBackupInet(InetAddress addr) {
		if (addr != null) {
			stickyBackupInet = addr;
		}
	}

	public InetAddress getStickyBackupInet() {
		return stickyBackupInet;
	}

	public void clearStickyBackupInet() {
		stickyBackupInet = null;
	}
}
