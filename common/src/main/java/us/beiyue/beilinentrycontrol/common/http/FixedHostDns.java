package us.beiyue.beilinentrycontrol.common.http;

import okhttp3.Dns;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Objects;

/**
 * Resolves one logical host to a selected address while leaving all other names on system DNS.
 */
public final class FixedHostDns implements Dns {
	private final String logicalHost;
	private final AddressProvider addressProvider;
	private final Dns fallback;

	public FixedHostDns(String logicalHost, AddressProvider addressProvider) {
		this(logicalHost, addressProvider, Dns.SYSTEM);
	}

	public FixedHostDns(String logicalHost, AddressProvider addressProvider, Dns fallback) {
		this.logicalHost = Objects.requireNonNull(logicalHost, "logicalHost");
		this.addressProvider = Objects.requireNonNull(addressProvider, "addressProvider");
		this.fallback = Objects.requireNonNull(fallback, "fallback");
	}

	@Override
	public List<InetAddress> lookup(String hostname) throws UnknownHostException {
		if (logicalHost.equalsIgnoreCase(hostname)) {
			InetAddress address = addressProvider.get();
			if (address == null) {
				throw new UnknownHostException(hostname);
			}
			return List.of(address);
		}
		return fallback.lookup(hostname);
	}

	@FunctionalInterface
	public interface AddressProvider {
		InetAddress get() throws UnknownHostException;
	}
}
