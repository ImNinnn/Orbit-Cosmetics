package dev.orbit.cosmetic.common.server;

/** Bridges to the platform logger (SLF4J on Fabric, java.util.logging on Paper). */
public interface OrbitLogger {
	void info(String message);

	void warn(String message);

	void error(String message, Throwable error);
}
