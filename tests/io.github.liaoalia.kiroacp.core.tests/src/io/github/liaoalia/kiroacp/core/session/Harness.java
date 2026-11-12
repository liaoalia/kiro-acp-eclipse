package io.github.liaoalia.kiroacp.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.liaoalia.kiroacp.core.acp.FakeAgent;
import io.github.liaoalia.kiroacp.core.acp.api.AgentProcessOptions;
import io.github.liaoalia.kiroacp.core.acp.impl.KiroAgents;
import io.github.liaoalia.kiroacp.core.model.FromWebview;
import io.github.liaoalia.kiroacp.core.model.MessageRole;
import io.github.liaoalia.kiroacp.core.model.ToWebview;
import io.github.liaoalia.kiroacp.core.model.UiJson;
import io.github.liaoalia.kiroacp.core.services.DiskTextFiles;
import io.github.liaoalia.kiroacp.core.session.Hosts.RecordingPage;

/**
 * A controller on the reference's fake agent, with fake hosts and recording
 * pages: the counterpart of the reference's {@code test/e2e/harness.ts}.
 */
final class Harness implements AutoCloseable {

	static final ObjectMapper MAPPER = new ObjectMapper();
	/** What the pinned clock says. */
	static final long NOW = 1_700_000_000_000L;

	final Hosts.Signal signal = new Hosts.Signal();
	final Hosts.TestScheduler scheduler = new Hosts.TestScheduler(signal);
	final Hosts.RecordingLogger logger = new Hosts.RecordingLogger(signal);
	final Hosts.RecordingActions actions = new Hosts.RecordingActions(scheduler, signal);
	final Hosts.FakeSettings settings = new Hosts.FakeSettings();
	final Hosts.OpenEverything external = new Hosts.OpenEverything();
	final AtomicLong clock = new AtomicLong(NOW);
	/** What the controller asked the agent factory for, one entry per (re)start. */
	final List<AgentProcessOptions> launches = new CopyOnWriteArrayList<>();
	final Path dir;
	final ChatController controller;
	private final List<Client> clients = new ArrayList<>();

	/** A page and the handle the controller gave for it. */
	final class Client {
		final RecordingPage page;
		final PageHandle handle;

		Client(String name, AttachOptions options) {
			this.page = new RecordingPage(name, scheduler, signal);
			this.handle = controller.attach(page, options);
		}

		void send(FromWebview message) {
			handle.onMessage(message);
		}

		void prompt(String text) {
			send(new FromWebview.Prompt(text, List.of(), null));
		}

		int mark() {
			return page.mark();
		}

		String sessionId() {
			return handle.sessionId();
		}

		/** Waits for the {@code turn} message that ends a turn. */
		ToWebview.Turn awaitTurnEnd(int mark) throws Exception {
			ToWebview.Turn end = page.await(ToWebview.Turn.class, mark, turn -> !turn.active(), "the end of the turn");
			scheduler.flush();
			return end;
		}

		/** Sends a prompt and waits for its turn to end. */
		ToWebview.Turn turn(String text) throws Exception {
			int mark = mark();
			prompt(text);
			return awaitTurnEnd(mark);
		}

		List<ToWebview.Chunk> agentChunks(int mark) {
			return page.ofType(ToWebview.Chunk.class, mark).stream().filter(chunk -> chunk.role() == MessageRole.AGENT).toList();
		}

		/** The {@code UiState} the page would get for {@code ready}, as JSON. */
		JsonNode snapshot() throws Exception {
			return scheduler.call(() -> UiJson.toJson(new ToWebview.State(handle.snapshot())).get("state"));
		}
	}

	private Harness(Path dir, List<String> agentArgs) {
		this.dir = dir;
		String script = FakeAgent.fixtures().resolve("fake-agent").resolve("agent.mjs").toString();
		ChatHost host = new ChatHost(settings, new Hosts.FakeWorkspace(dir), logger, scheduler, new DiskTextFiles(), null, external,
				actions, "0.1.0-test", clock::get);
		this.controller = ChatController.create(host, (options, services) -> {
			launches.add(options);
			List<String> args = new ArrayList<>(List.of(script, "acp"));
			args.addAll(agentArgs);
			return KiroAgents.create(new AgentProcessOptions("node", args, options.cwd(), options.env(), options.logger(),
					options.clientVersion()), services);
		});
	}

	/** @param agentArgs what follows {@code agent.mjs acp} */
	static Harness launch(Path dir, String... agentArgs) {
		return new Harness(dir, List.of(agentArgs));
	}

	/** A page that is attached and has not said anything yet. */
	Client attach(String name, AttachOptions options) {
		Client client = new Client(name, options);
		clients.add(client);
		return client;
	}

	/**
	 * A page that said {@code ready} and displays a session whose commands the
	 * agent has announced. The fake agent does that a few milliseconds after
	 * answering {@code session/new}, so a test that went on at once would find
	 * that update anywhere among the messages of its first turn.
	 */
	Client open(String name, AttachOptions options) throws Exception {
		Client client = attach(name, options);
		client.send(new FromWebview.Ready());
		awaitSettled(client);
		return client;
	}

	/**
	 * Waits until the page displays a session and has heard everything the
	 * agent says about a new one: its commands as an update, then the session
	 * state that update changed (modes, config options, models, commands), as
	 * in the reference. A page that took over a session nobody displayed got
	 * all of that in its {@code state}.
	 */
	void awaitSettled(Client client) throws Exception {
		await(() -> {
			if (client.sessionId() == null) {
				return false;
			}
			List<String> types = client.page.types(0);
			int state = types.lastIndexOf("state");
			if (state < 0) {
				return false;
			}
			long announced = types.subList(state, types.size()).stream().filter("commands"::equals).count();
			return announced >= 2 || client.page.jsonSince(0).get(state).at("/state/commands").size() > 0;
		}, "a session with commands in " + client.page);
		scheduler.flush();
	}

	/** A chat view: a page with a session group of its own. */
	Client openView(String name) throws Exception {
		return open(name, AttachOptions.ownGroup(name));
	}

	void await(BooleanSupplier condition, String label) throws InterruptedException {
		signal.await(condition, label, new Object() {
			@Override
			public String toString() {
				return clients.stream().map(c -> c.page.toString()).toList() + "; log: " + logger.lines;
			}
		});
	}

	<T> T get(CompletableFuture<T> future) throws Exception {
		return future.get(Hosts.SECONDS, TimeUnit.SECONDS);
	}

	/** Runs on the controller thread. */
	<T> T ask(java.util.concurrent.Callable<T> question) throws Exception {
		return scheduler.call(question);
	}

	static JsonNode json(String text) {
		try {
			return MAPPER.readTree(text);
		} catch (com.fasterxml.jackson.core.JsonProcessingException e) {
			throw new IllegalArgumentException(text, e);
		}
	}

	@Override
	public void close() throws Exception {
		get(controller.shutdown());
		scheduler.close();
		for (Client client : clients) {
			Hosts.assertNoProblems(client.page.problems);
		}
		Hosts.assertNoProblems(actions.problems);
		assertEquals(List.of(), List.copyOf(scheduler.failures), "nothing may throw on the controller thread");
	}
}
