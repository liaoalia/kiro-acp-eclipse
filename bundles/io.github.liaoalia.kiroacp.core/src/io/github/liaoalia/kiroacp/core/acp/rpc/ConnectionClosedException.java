package io.github.liaoalia.kiroacp.core.acp.rpc;

import java.io.IOException;

/** The connection is down: the peer's output ended, a stream failed, or it was closed here. */
public final class ConnectionClosedException extends IOException {

	private static final long serialVersionUID = 1L;

	public ConnectionClosedException(String message, Throwable cause) {
		super(message, cause);
	}
}
