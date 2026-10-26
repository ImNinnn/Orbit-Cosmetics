package dev.orbit.cosmetic.common.model;

/** A problem found while validating a model. Errors prevent loading; warnings are shown to the user. */
public record Issue(Severity severity, String code, String message) {
	public enum Severity {
		WARNING,
		ERROR
	}

	public static Issue warning(String code, String message) {
		return new Issue(Severity.WARNING, code, message);
	}

	public static Issue error(String code, String message) {
		return new Issue(Severity.ERROR, code, message);
	}

	public boolean isError() {
		return severity == Severity.ERROR;
	}

	@Override
	public String toString() {
		return severity + " [" + code + "] " + message;
	}
}
