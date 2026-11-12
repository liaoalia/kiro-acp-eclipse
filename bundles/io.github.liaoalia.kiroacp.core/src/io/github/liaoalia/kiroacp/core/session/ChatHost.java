package io.github.liaoalia.kiroacp.core.session;

import java.util.Objects;
import java.util.function.LongSupplier;

import io.github.liaoalia.kiroacp.core.host.Logger;
import io.github.liaoalia.kiroacp.core.host.Settings;
import io.github.liaoalia.kiroacp.core.host.UiScheduler;
import io.github.liaoalia.kiroacp.core.host.Workspace;
import io.github.liaoalia.kiroacp.core.services.ExternalActions;
import io.github.liaoalia.kiroacp.core.services.TerminalDisplay;
import io.github.liaoalia.kiroacp.core.services.TextFiles;

/**
 * Everything the chat controller needs from the IDE.
 *
 * @param terminalDisplay may be {@code null}: agent commands then run without a console
 * @param externalActions may be {@code null}: opening URLs and terminal sign-in are then unsupported
 * @param clientVersion   reported to the agent as {@code clientInfo.version}
 * @param clock           milliseconds since the epoch; tests pin it
 */
public record ChatHost(Settings settings, Workspace workspace, Logger logger, UiScheduler scheduler, TextFiles textFiles,
		TerminalDisplay terminalDisplay, ExternalActions externalActions, ChatActions actions, String clientVersion,
		LongSupplier clock) {

	public ChatHost {
		Objects.requireNonNull(settings, "settings");
		Objects.requireNonNull(workspace, "workspace");
		Objects.requireNonNull(scheduler, "scheduler");
		Objects.requireNonNull(textFiles, "textFiles");
		Objects.requireNonNull(actions, "actions");
		logger = logger != null ? logger : Logger.NONE;
		clientVersion = clientVersion != null ? clientVersion : "0.0.0";
		clock = clock != null ? clock : System::currentTimeMillis;
	}

	public ChatHost(Settings settings, Workspace workspace, Logger logger, UiScheduler scheduler, TextFiles textFiles,
			TerminalDisplay terminalDisplay, ExternalActions externalActions, ChatActions actions, String clientVersion) {
		this(settings, workspace, logger, scheduler, textFiles, terminalDisplay, externalActions, actions, clientVersion, null);
	}
}
