package io.github.liaoalia.kiroacp.core.session.auth;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.liaoalia.kiroacp.core.acp.api.AgentInfo;
import io.github.liaoalia.kiroacp.core.acp.api.KiroAgent;
import io.github.liaoalia.kiroacp.core.acp.impl.KiroAgents;
import io.github.liaoalia.kiroacp.core.host.Subscription;
import io.github.liaoalia.kiroacp.core.host.UiScheduler;
import io.github.liaoalia.kiroacp.core.model.FromWebview;
import io.github.liaoalia.kiroacp.core.model.NoticeView;
import io.github.liaoalia.kiroacp.core.session.ChatController;

/**
 * Signing in (reference D25, {@code login} of its chat controller).
 *
 * <p><b>When it is offered.</b> The agent client turns every
 * {@code session/new}, {@code session/load} or {@code session/prompt} that is
 * rejected as "not logged in" (by code or by message) into the connection
 * state {@code auth_required}, also when the connection looked ready until
 * then (a token that expired mid-session), and offers a terminal method: the
 * agent's own, else the fallback {@value KiroAgents#FALLBACK_LOGIN_METHOD_ID}.
 * The page shows that state as a banner with the agent's reason and a button,
 * which is the {@code login} message handled here.
 *
 * <p><b>What it does.</b> The method runs in a terminal the user can type
 * into. For the fallback that is {@code <cli> logout} (best effort) and then
 * {@code <cli> login}: one fresh sign-in, never another attempt to refresh
 * the token that was just refused; a method of the agent's that the agent
 * itself rejects as signed out ends there too. The fallback is accepted at
 * any time, so signing in again (to switch accounts) works while signed in.
 *
 * <p><b>Afterwards</b> the agent process is replaced, because only a new one
 * is known to read the new credentials, and every session with a conversation
 * is loaded back into it ({@link ChatController#restartKeepingSessions}).
 */
public final class SignIn implements AutoCloseable {

	private final ChatController controller;
	private final UiScheduler scheduler;
	private Subscription handler;

	private SignIn(ChatController controller) {
		this.controller = controller;
		this.scheduler = controller.host().scheduler();
	}

	/** Handles the page's sign-in button from here on. */
	public static SignIn install(ChatController controller) {
		SignIn signIn = new SignIn(controller);
		signIn.handler = controller.hooks().onMessage(FromWebview.Login.class,
				(message, page) -> signIn.login(message.methodId()));
		return signIn;
	}

	/**
	 * Sign in and reconnect. May be called from any thread, with or without a
	 * running agent (it is started first): a {@code /login} or a command of
	 * the IDE needs neither a session nor the sign-in banner.
	 *
	 * @param methodId the method the user chose, or {@code null} for the first terminal method the
	 *                 agent offers, else the fallback
	 * @return completes when the agent has been replaced and the sessions are back, or when the
	 *         sign-in failed and a notice says why; never fails
	 */
	public CompletableFuture<Void> login(String methodId) {
		CompletableFuture<Void> result = new CompletableFuture<>();
		controller.start().whenComplete((started, startError) -> onController(() -> {
			KiroAgent agent = controller.agent();
			if (agent == null) {
				// Shut down, or the start failed and said so.
				result.complete(null);
				return;
			}
			String id = methodId != null ? methodId : defaultMethod(controller.agentInfo());
			CompletableFuture<Void> signedIn;
			try {
				signedIn = agent.authenticate(id);
			} catch (RuntimeException e) {
				signedIn = CompletableFuture.failedFuture(e);
			}
			signedIn.thenCompose(done -> controller.restartKeepingSessions()).whenComplete((done, error) -> {
				if (error == null) {
					result.complete(null);
					return;
				}
				// The sign-in ends on a thread of the host services.
				onController(() -> {
					controller.notice(null, NoticeView.Severity.ERROR, "Sign-in failed: " + message(error));
					result.complete(null);
				});
			});
		}));
		return result;
	}

	/** The button is no longer handled. */
	@Override
	public void close() {
		handler.close();
	}

	private void onController(Runnable task) {
		if (scheduler.isControllerThread()) {
			task.run();
		} else {
			scheduler.execute(task);
		}
	}

	private static String defaultMethod(AgentInfo info) {
		if (info != null) {
			for (JsonNode method : info.authMethods()) {
				if ("terminal".equals(method.path("type").asText()) && method.path("id").isTextual()) {
					return method.path("id").asText();
				}
			}
		}
		return KiroAgents.FALLBACK_LOGIN_METHOD_ID;
	}

	private static String message(Throwable error) {
		Throwable cause = error;
		while ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null) {
			cause = cause.getCause();
		}
		String message = cause.getMessage();
		return message != null && !message.isEmpty() ? message : cause.toString();
	}
}
