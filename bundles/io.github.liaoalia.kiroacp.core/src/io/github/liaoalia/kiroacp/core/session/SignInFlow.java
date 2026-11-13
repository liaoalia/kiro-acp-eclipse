package io.github.liaoalia.kiroacp.core.session;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.liaoalia.kiroacp.core.acp.api.AgentInfo;
import io.github.liaoalia.kiroacp.core.acp.api.KiroAgent;
import io.github.liaoalia.kiroacp.core.acp.impl.KiroAgents;
import io.github.liaoalia.kiroacp.core.host.Subscription;
import io.github.liaoalia.kiroacp.core.model.FromWebview;
import io.github.liaoalia.kiroacp.core.model.NoticeView;

/**
 * The page's "sign in" button: run the auth method, then start the agent
 * afresh so it picks the new credentials up (the reference's {@code login}).
 *
 * <p>The agent runs a terminal method through the host, which shows the
 * sign-in command in a terminal the user completes.
 */
public final class SignInFlow {

	private final ChatController controller;

	private SignInFlow(ChatController controller) {
		this.controller = controller;
	}

	/** @return removes the handler again */
	public static Subscription install(ChatController controller) {
		SignInFlow flow = new SignInFlow(controller);
		return controller.hooks().onMessage(FromWebview.Login.class, (message, page) -> flow.signIn(message.methodId()));
	}

	/** Sign in as the page's button does, for whoever else offers it ({@code /login}). */
	public static void signIn(ChatController controller, String methodId) {
		new SignInFlow(controller).signIn(methodId);
	}

	/** @param methodId the method the user chose, or {@code null} for the agent's terminal method */
	void signIn(String methodId) {
		controller.start().whenComplete((started, startError) -> {
			KiroAgent agent = controller.agent();
			if (agent == null) {
				return;
			}
			String id = methodId != null ? methodId : defaultMethod(controller.agentInfo());
			agent.authenticate(id).thenCompose(done -> controller.restart()).whenComplete((done, error) -> {
				if (error != null) {
					controller.notice(null, NoticeView.Severity.ERROR, "Sign-in failed: " + Async.message(error));
				}
			});
		});
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
}
