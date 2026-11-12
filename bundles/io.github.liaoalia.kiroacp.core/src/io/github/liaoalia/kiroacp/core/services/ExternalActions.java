package io.github.liaoalia.kiroacp.core.services;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;

import io.github.liaoalia.kiroacp.core.acp.process.AgentCommandLine;

/**
 * What the agent asks for that only the IDE's UI can do: opening a URL in the
 * user's browser and running a sign-in in a terminal the user can type into.
 *
 * <p>Both methods are called on a worker thread of {@link HostServices}, never
 * on the agent connection's reader thread nor on the UI thread, and may block
 * before returning (for instance on a dialog shown synchronously on the UI
 * thread). The returned stages may complete on any thread.
 */
public interface ExternalActions {

	enum OpenResult {
		/** The user agreed and the system took the URL. */
		OPENED,
		/** The user said no, or dismissed the question. */
		DECLINED,
		/** The user agreed but the system could not open the URL. */
		FAILED
	}

	/**
	 * A sign-in command to run where the user sees it and can answer it.
	 *
	 * @param title   the terminal's name ({@link HostServices#SIGN_IN_TERMINAL_NAME})
	 * @param command the CLI as configured and resolved
	 * @param args    its arguments
	 * @param launch  the same, as it has to be started with a {@link ProcessBuilder}: a Windows
	 *                {@code .cmd}/{@code .bat} launcher goes through {@code cmd.exe}
	 * @param env     variables to set over the IDE's own environment; possibly empty
	 */
	record SignIn(String title, String command, List<String> args, AgentCommandLine launch, Map<String, String> env) {

		public SignIn {
			args = List.copyOf(args);
			env = Map.copyOf(env);
		}
	}

	/**
	 * Ask the user whether the URL may be opened
	 * ({@link HostServices#openUrlQuestion}, with the whole URL as the detail)
	 * and, if so, hand it to the system browser.
	 *
	 * @param host the host of the URL, or the URL itself when it has none
	 */
	CompletionStage<OpenResult> confirmAndOpenUrl(String host, String url);

	/**
	 * Run the sign-in as the process of a terminal the user can see and type
	 * into, and reveal it. The user should be told
	 * {@link HostServices#SIGN_IN_HINT}.
	 *
	 * @return completes with the command's exit code when it ended, or with {@code null} when the
	 *         terminal went away without one or the user said they are done; the agent is then
	 *         reconnected unless the code is a failure. Fails when the terminal could not be started
	 */
	CompletionStage<Integer> runSignIn(SignIn signIn);
}
