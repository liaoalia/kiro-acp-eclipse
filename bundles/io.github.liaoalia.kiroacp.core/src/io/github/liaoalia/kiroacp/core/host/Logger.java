package io.github.liaoalia.kiroacp.core.host;

/** Where the core reports what it does. Implementations must be callable from any thread. */
public interface Logger {

	/** Direction of a traced protocol line. */
	enum Direction {
		SEND, RECV, STDERR
	}

	void info(String message);

	void warn(String message);

	void error(String message, Throwable cause);

	default void error(String message) {
		error(message, null);
	}

	/** @return whether {@link #trace} does anything; lets callers skip building the payload */
	default boolean traces() {
		return false;
	}

	/** Raw JSON-RPC traffic and the agent's stderr. Only called when {@link #traces()}. */
	default void trace(Direction direction, String payload) {
	}

	/** Discards everything. */
	Logger NONE = new Logger() {
		@Override
		public void info(String message) {
		}

		@Override
		public void warn(String message) {
		}

		@Override
		public void error(String message, Throwable cause) {
		}
	};
}
