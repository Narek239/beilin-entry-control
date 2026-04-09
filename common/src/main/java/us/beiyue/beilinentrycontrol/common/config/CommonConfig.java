package us.beiyue.beilinentrycontrol.common.config;

/**
 * Minimal config interface to keep {@code common} free of FabricLoader.
 */
public interface CommonConfig {
	boolean isValid();
	String httpBase();
	String wsUri();
	boolean isApiKeyConfigured();
}

