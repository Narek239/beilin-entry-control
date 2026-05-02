package us.beiyue.beilinentrycontrol.common.http;

import java.net.InetAddress;

public final class InetAddressFormatting {
	private InetAddressFormatting() {
	}

	public static String hostLiteral(InetAddress addr) {
		byte[] raw = addr.getAddress();
		if (raw.length == 16) {
			return "[" + addr.getHostAddress() + "]";
		}
		return addr.getHostAddress();
	}
}
