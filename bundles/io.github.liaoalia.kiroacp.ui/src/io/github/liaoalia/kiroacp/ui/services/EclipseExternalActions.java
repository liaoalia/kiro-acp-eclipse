package io.github.liaoalia.kiroacp.ui.services;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import org.eclipse.swt.program.Program;

import io.github.liaoalia.kiroacp.core.acp.process.Platform;
import io.github.liaoalia.kiroacp.core.host.Logger;
import io.github.liaoalia.kiroacp.core.services.ExternalActions;
import io.github.liaoalia.kiroacp.core.services.HostServices;

/**
 * Opens URLs in the system browser and runs the sign-in in a terminal window
 * of the operating system (reference D25).
 *
 * <p><b>URLs.</b> Only {@code http} and {@code https} URLs with a host are
 * ever handed to the system, and only after the user agreed to that host with
 * the whole URL in view. Anything else is refused without a question: the
 * system would start whatever program is registered for it.
 *
 * <p><b>Sign-in.</b> The platform has no terminal the user can type into, so
 * the command runs in a window of the system's own terminal program
 * ({@link SignInTerminal}); whether TM Terminal is installed is not looked at.
 * The stage completes with the command's exit code as soon as its script has
 * left it behind, or with {@code null} when the user says they are done: the
 * window may have been closed with the command still running, and nothing
 * would tell.
 *
 * <p>Both methods are called on worker threads and return at once; neither
 * touches the UI except through {@link Prompts}.
 */
public final class EclipseExternalActions implements ExternalActions {

	/** The button of the notice shown while the sign-in window is open. */
	static final String SIGN_IN_DONE = "I Have Signed In";

	private static final long POLL_MILLIS = 250;

	/** Hands a URL to the system; false when the system has nothing to open it with. */
	interface UrlOpener {

		boolean open(String url);
	}

	/** Starts the terminal program. */
	interface TerminalLauncher {

		/**
		 * @param env variables to set over the IDE's own environment
		 * @return completes with the exit code of the started program, which is the terminal program
		 *         and not the sign-in
		 * @throws IOException when the program cannot be started
		 */
		CompletionStage<Integer> start(SignInTerminal.Plan plan, Map<String, String> env) throws IOException;
	}

	private final Prompts prompts;
	private final Logger logger;
	private final UrlOpener opener;
	private final TerminalLauncher launcher;
	private final Platform platform;
	private final Map<String, String> env;
	private final Predicate<String> onPath;
	private final long pollMillis;

	/** @param logger may be {@code null} */
	public EclipseExternalActions(Prompts prompts, Logger logger) {
		// With DialogPrompts the answer arrives on the UI thread, which is where
		// Program.launch is at home on every window system.
		this(prompts, logger, Program::launch, EclipseExternalActions::startProcess, Platform.current(), System.getenv(),
				program -> SignInTerminal.onPath(program, System.getenv()), POLL_MILLIS);
	}

	EclipseExternalActions(Prompts prompts, Logger logger, UrlOpener opener, TerminalLauncher launcher,
			Platform platform, Map<String, String> env, Predicate<String> onPath, long pollMillis) {
		this.prompts = Objects.requireNonNull(prompts, "prompts");
		this.logger = logger != null ? logger : Logger.NONE;
		this.opener = Objects.requireNonNull(opener, "opener");
		this.launcher = Objects.requireNonNull(launcher, "launcher");
		this.platform = Objects.requireNonNull(platform, "platform");
		this.env = Map.copyOf(env);
		this.onPath = Objects.requireNonNull(onPath, "onPath");
		this.pollMillis = pollMillis;
	}

	@Override
	public CompletionStage<OpenResult> confirmAndOpenUrl(String host, String url) {
		if (!isWebUrl(url)) {
			logger.warn("Refused to open a URL that is not a plain http(s) address");
			return CompletableFuture.completedFuture(OpenResult.FAILED);
		}
		return prompts.ask(new Prompts.Question("Kiro", HostServices.openUrlQuestion(host), url, "Open", "Cancel", false))
				.thenApply(agreed -> {
					if (!Boolean.TRUE.equals(agreed)) {
						return OpenResult.DECLINED;
					}
					try {
						return opener.open(url) ? OpenResult.OPENED : OpenResult.FAILED;
					} catch (RuntimeException e) {
						logger.error("Opening a URL in the system browser failed", e);
						return OpenResult.FAILED;
					}
				});
	}

	/** @return whether the text is an {@code http} or {@code https} URL naming a host */
	static boolean isWebUrl(String url) {
		if (url == null) {
			return false;
		}
		try {
			URI uri = new URI(url);
			String scheme = uri.getScheme();
			String host = uri.getHost();
			return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) && host != null
					&& !host.isEmpty();
		} catch (URISyntaxException e) {
			return false;
		}
	}

	@Override
	public CompletionStage<Integer> runSignIn(SignIn signIn) {
		SignInTerminal.Plan plan;
		CompletionStage<Integer> terminal;
		try {
			plan = SignInTerminal.prepare(platform, signIn, env, onPath);
			try {
				terminal = launcher.start(plan, signIn.env());
			} catch (IOException | RuntimeException e) {
				SignInTerminal.discard(plan);
				throw e;
			}
		} catch (IOException | RuntimeException e) {
			return CompletableFuture.failedFuture(
					new IllegalStateException("The sign-in terminal could not be started: " + e.getMessage(), e));
		}

		CompletableFuture<Integer> result = new CompletableFuture<>();
		ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor(task -> {
			Thread thread = new Thread(task, "kiro-signin");
			thread.setDaemon(true);
			return thread;
		});
		poller.scheduleWithFixedDelay(() -> {
			Integer code = SignInTerminal.readStatus(plan.status());
			if (code != null) {
				result.complete(code);
			}
		}, pollMillis, pollMillis, TimeUnit.MILLISECONDS);

		Prompts.Notice notice = prompts.tell(signIn.title(), HostServices.SIGN_IN_HINT, SIGN_IN_DONE);
		// An exit code that is there by now says more than the user's word.
		notice.acknowledged().whenComplete((nothing, failure) -> result.complete(SignInTerminal.readStatus(plan.status())));
		terminal.whenComplete((exit, failure) -> {
			Integer code = SignInTerminal.readStatus(plan.status());
			if (code != null) {
				result.complete(code);
			} else if (failure != null || exit == null || exit != 0) {
				// The terminal program failed before the command got anywhere. One that
				// ends well says nothing: most return as soon as their window is open.
				result.completeExceptionally(new IllegalStateException("The sign-in terminal could not be started"
						+ (failure != null ? ": " + failure.getMessage() : " (exit code " + exit + ")")));
			}
		});
		// The caller gets the stage after the tidying up, so it never sees a sign-in
		// that is over with its notice still on show.
		return result.whenComplete((code, failure) -> {
			poller.shutdownNow();
			notice.dismiss();
			if (code != null) {
				// The script is past its last line; without a code it may still be running.
				SignInTerminal.discard(plan);
			}
		});
	}

	private static CompletionStage<Integer> startProcess(SignInTerminal.Plan plan, Map<String, String> env)
			throws IOException {
		ProcessBuilder builder = new ProcessBuilder(plan.argv());
		builder.environment().putAll(env);
		// Nobody reads them, and an unread pipe would block the terminal program.
		builder.redirectErrorStream(true);
		builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
		Process process = builder.start();
		process.getOutputStream().close();
		return process.onExit().thenApply(Process::exitValue);
	}
}
