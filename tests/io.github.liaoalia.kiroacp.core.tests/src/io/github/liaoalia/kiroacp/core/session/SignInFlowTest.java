package io.github.liaoalia.kiroacp.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.liaoalia.kiroacp.core.model.ConnectionStatus;
import io.github.liaoalia.kiroacp.core.model.FromWebview;
import io.github.liaoalia.kiroacp.core.model.ToWebview;
import io.github.liaoalia.kiroacp.core.session.Harness.Client;

class SignInFlowTest {

	@TempDir
	Path dir;

	@Test
	void theSignInButtonRunsTheMethodAndStartsTheAgentAfresh() throws Exception {
		try (Harness h = Harness.launch(dir, "--auth-required")) {
			SignInFlow.install(h.controller);
			Client view = h.attach("view", AttachOptions.ownGroup("view"));
			view.send(new FromWebview.Ready());
			view.page.await(ToWebview.Connection.class, 0,
					c -> c.connection().status() == ConnectionStatus.AUTH_REQUIRED, "the sign-in state");
			assertEquals(1, h.launches.size());

			view.send(new FromWebview.Login("kiro-login"));

			// The fake host's sign-in "terminal" exits with 0 at once; the agent is then started again.
			h.await(() -> h.launches.size() == 2, "the agent to be started again after signing in");
		}
	}
}
