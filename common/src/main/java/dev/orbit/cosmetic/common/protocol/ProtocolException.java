package dev.orbit.cosmetic.common.protocol;

/** A malformed or out-of-policy message. The message is dropped; the connection is never trusted with it. */
public final class ProtocolException extends Exception {
	public ProtocolException(String message) {
		super(message);
	}
}
