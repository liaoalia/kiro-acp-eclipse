package io.github.liaoalia.kiroacp.core.session;

import io.github.liaoalia.kiroacp.core.acp.api.AgentProcessOptions;
import io.github.liaoalia.kiroacp.core.acp.api.ClientServices;
import io.github.liaoalia.kiroacp.core.acp.api.KiroAgent;
import io.github.liaoalia.kiroacp.core.acp.impl.KiroAgents;

/** How the controller gets an agent for a (re)start; tests point it at the fake agent. */
@FunctionalInterface
public interface AgentFactory {

	/** The real client: it looks the command up (PATH, default install folders) when the process starts. */
	AgentFactory DEFAULT = KiroAgents::create;

	/**
	 * @param options  what the settings ask for: the configured command, its arguments ({@code acp}
	 *                 unless configured), the workspace's working directory, the inherited environment
	 * @param services what the agent may ask of the host
	 * @return an agent that is not started yet
	 */
	KiroAgent create(AgentProcessOptions options, ClientServices services);
}
