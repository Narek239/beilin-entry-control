package us.beiyue.beilinentrycontrol.platform.fabric1201;

import org.slf4j.Logger;
import us.beiyue.beilinentrycontrol.common.log.CommonLogger;

public final class Slf4jCommonLogger implements CommonLogger {
	private final Logger logger;

	public Slf4jCommonLogger(Logger logger) {
		this.logger = logger;
	}

	@Override
	public void debug(String message, Object... args) {
		logger.debug(message, args);
	}

	@Override
	public void info(String message, Object... args) {
		logger.info(message, args);
	}

	@Override
	public void warn(String message, Object... args) {
		logger.warn(message, args);
	}

	@Override
	public void error(String message, Object... args) {
		logger.error(message, args);
	}
}

