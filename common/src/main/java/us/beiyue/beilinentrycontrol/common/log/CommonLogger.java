package us.beiyue.beilinentrycontrol.common.log;

public interface CommonLogger {
	void debug(String message, Object... args);
	void info(String message, Object... args);
	void warn(String message, Object... args);
	void error(String message, Object... args);
}

