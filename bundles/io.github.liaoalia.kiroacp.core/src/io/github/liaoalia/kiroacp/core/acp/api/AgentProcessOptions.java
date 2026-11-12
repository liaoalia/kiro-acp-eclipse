package io.github.liaoalia.kiroacp.core.acp.api;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import io.github.liaoalia.kiroacp.core.host.Logger;

/**
 * How to start the agent.
 *
 * @param command	   resolved executable path or bare command name
 * @param args		  full argument list, e.g. {@code ["acp", "--agent-engine=v3"]}
 * @param cwd		   working directory for the child; usually the workspace root
 * @param env		   environment for the child; pass the inherited one so proxy variables flow through
 * @param clientVersion reported to the agent as {@code clientInfo.version}
 */
public record AgentProcessOptions(String command, List<String> args, Path cwd, Map<String, String> env, Logger logger,
		String clientVersion) {

	public AgentProcessOptions {
		args = List.copyOf(args);
		env = Map.copyOf(env);
	}
}
