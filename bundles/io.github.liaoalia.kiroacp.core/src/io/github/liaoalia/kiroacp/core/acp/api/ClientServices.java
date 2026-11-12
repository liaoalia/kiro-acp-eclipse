package io.github.liaoalia.kiroacp.core.acp.api;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * What the agent may ask of the client; implemented by the host, called by the ACP layer.
 *
 * <p>Requests and results are the ACP objects of the method as JSON trees
 * ({@code ReadTextFileRequest} in, {@code ReadTextFileResponse} out, and so
 * on). Methods are called on the connection's reader thread and must return at
 * once: the work happens elsewhere and completes the future. A future that
 * fails with {@link AcpException} carrying a code is answered with that
 * JSON-RPC error, any other failure with an internal error.
 */
public interface ClientServices {

	/**
	 * {@code session/request_permission}.
	 *
	 * @param cancelled completes when the turn is cancelled; the implementation then stops asking
	 *				  the user. The layer answers the agent with the {@code cancelled} outcome itself.
	 */
	CompletableFuture<JsonNode> requestPermission(JsonNode request, CompletionStage<Void> cancelled);

	CompletableFuture<JsonNode> readTextFile(JsonNode request);

	CompletableFuture<JsonNode> writeTextFile(JsonNode request);

	CompletableFuture<JsonNode> createTerminal(JsonNode request);

	CompletableFuture<JsonNode> terminalOutput(JsonNode request);

	CompletableFuture<JsonNode> waitForTerminalExit(JsonNode request);

	CompletableFuture<JsonNode> killTerminal(JsonNode request);

	CompletableFuture<JsonNode> releaseTerminal(JsonNode request);

	/**
	 * Kiro extension {@code _kiro/openExternalUrl} (used for MCP OAuth). The host
	 * confirms with the user and hands the URL to the system browser.
	 *
	 * @return {@code null} when not supported: the request is then answered with -32601
	 */
	default CompletableFuture<Void> openExternalUrl(String url) {
		return null;
	}

	/**
	 * Run a terminal-type auth method in a terminal the user can see. The command
	 * line is {@code command}, {@code args}, then the method's own {@code args}:
	 * per the ACP spec a method's args are appended to the configured agent
	 * invocation, so Kiro's {@code ["--login"]} runs {@code kiro-cli acp --login}.
	 * For the standalone fallback ({@code kiro-cli login}) {@code args} is empty.
	 *
	 * @param method the ACP {@code AuthMethod} of type {@code terminal}
	 * @return completes when the user acknowledges completion (the host then restarts
	 *		 the agent process); {@code null} when not supported
	 */
	default CompletableFuture<Void> runTerminalAuth(JsonNode method, String command, List<String> args) {
		return null;
	}
}
