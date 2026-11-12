package io.github.liaoalia.kiroacp.core.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConnectionView(ConnectionStatus status, String agentName, String agentVersion, String message,
		List<AuthMethodView> authMethods,
		/** The agent advertised {@code session/list} and {@code session/load}. */
		Boolean canBrowseSessions,
		/** The agent advertised {@code promptCapabilities.image}. */
		Boolean acceptsImages) {

	public static ConnectionView of(ConnectionStatus status) {
		return new ConnectionView(status, null, null, null, null, null, null);
	}
}
