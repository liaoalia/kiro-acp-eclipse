package io.github.liaoalia.kiroacp.ui.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.ui.PlatformUI;
import org.junit.jupiter.api.Test;

import io.github.liaoalia.kiroacp.core.host.Settings.TerminalMode;
import io.github.liaoalia.kiroacp.core.pure.output.TerminalPresentation;
import io.github.liaoalia.kiroacp.core.services.TerminalDisplay;
import io.github.liaoalia.kiroacp.core.services.TerminalDisplay.Console;
import io.github.liaoalia.kiroacp.core.services.TerminalDisplay.Target;

/** The display's own rules, against consoles that only record. */
class ConsoleTerminalDisplayTest {

	private static final String SESSION = "0b8e2f4a-1111-2222-3333-444444444444";
	private static final String ESC = "\u001b";

	/** Consoles as the Console view would hold them. */
	private static final class FakeHost implements ConsoleHost {

		final List<FakeConsole> created = new ArrayList<>();
		boolean available = true;

		@Override
		public Sink create(String name, Runnable removed) {
			if (!available) {
				return null;
			}
			FakeConsole console = new FakeConsole(name, removed);
			created.add(console);
			return console;
		}

		List<String> names() {
			return created.stream().filter(each -> each.present).map(each -> each.name).toList();
		}
	}

	private static final class FakeConsole implements ConsoleHost.Sink {

		final String name;
		final Runnable removed;
		final StringBuilder text = new StringBuilder();
		final List<Boolean> shown = new ArrayList<>();
		boolean present = true;

		FakeConsole(String name, Runnable removed) {
			this.name = name;
			this.removed = removed;
		}

		@Override
		public void append(String chunk) {
			assertTrue(present, "text for a console that is gone");
			text.append(chunk);
		}

		@Override
		public void show(boolean focus) {
			shown.add(focus);
		}

		@Override
		public void remove() {
			userCloses();
		}

		/** The console manager tells its listeners the same way whoever removed the console. */
		void userCloses() {
			if (present) {
				present = false;
				removed.run();
			}
		}
	}

	private final FakeHost host = new FakeHost();
	private final ConsoleTerminalDisplay display = new ConsoleTerminalDisplay(host);
	private final AtomicInteger closedByUser = new AtomicInteger();

	@Test
	void aSharedConsoleCarriesTheNameOfItsSession() {
		Console console = display.open(shared(SESSION), closedByUser::incrementAndGet);

		assertNotNull(console);
		assertEquals(List.of("Kiro · 0b8e2f4a"), host.names());
		assertEquals(List.of(), host.created.get(0).shown, "created, not shown: the service says when");
	}

	@Test
	void aTargetWithoutANameIsNamedByTheMode() {
		display.open(new Target(TerminalMode.SHARED, SESSION, null, null), null);
		display.open(new Target(TerminalMode.PER_COMMAND, SESSION, "term-abc-1", " "), null);

		assertEquals(List.of(TerminalPresentation.sharedTerminalName(SESSION), "Kiro: term-abc-1"), host.names());
	}

	@Test
	void aSessionHasOneSharedConsoleHoweverOftenItIsAskedFor() {
		AtomicInteger second = new AtomicInteger();
		Console first = display.open(shared(SESSION), closedByUser::incrementAndGet);
		Console again = display.open(shared(SESSION), second::incrementAndGet);
		Console other = display.open(shared("sess-2"), null);

		assertSame(first, again);
		assertNotSame(first, other);
		assertEquals(List.of("Kiro · 0b8e2f4a", "Kiro · sess-2"), host.names());

		host.created.get(0).userCloses();
		assertEquals(0, closedByUser.get());
		assertEquals(1, second.get(), "whoever asked last is told");
	}

	@Test
	void everyCommandGetsAConsoleOfItsOwnInPerCommandMode() {
		Console one = display.open(command("term-abc-1", "npm test"), null);
		Console two = display.open(command("term-abc-2", "npm test"), null);

		assertNotSame(one, two);
		assertEquals(List.of("Kiro: npm test", "Kiro: npm test"), host.names());
	}

	@Test
	void hiddenModeShowsNothing() {
		assertNull(display.open(new Target(TerminalMode.HIDDEN, SESSION, "term-abc-1", "Kiro: ls"), null));
		assertNull(display.open(null, null));
		assertEquals(List.of(), host.created);
	}

	@Test
	void withoutAConsoleToShowInThereIsNoDisplay() {
		host.available = false;

		assertNull(display.open(shared(SESSION), closedByUser::incrementAndGet));
		assertNull(display.open(command("term-abc-1", "ls"), closedByUser::incrementAndGet));
	}

	/** The only state this runtime has: no workbench, so the Console view cannot be used. */
	@Test
	void withoutAWorkbenchTheRealDisplayShowsNothing() {
		assertFalse(PlatformUI.isWorkbenchRunning());
		TerminalDisplay real = new ConsoleTerminalDisplay();

		assertNull(real.open(shared(SESSION), closedByUser::incrementAndGet));
		assertNull(real.open(command("term-abc-1", "ls"), closedByUser::incrementAndGet));
		assertEquals(0, closedByUser.get());
	}

	@Test
	void whatIsWrittenArrivesAsPlainText() {
		Console console = display.open(shared(SESSION), null);

		console.write(TerminalPresentation.formatCommandHeader("npm test", "/work"));
		console.write(ESC + "[32mpassed" + ESC + "[0m\r\n");
		console.write("");
		console.write("cut " + ESC + "[3");
		console.write("1mhere\r");
		console.write("\n");

		assertEquals("\n$ npm test   [/work]\npassed\ncut here\n", host.created.get(0).text.toString());
	}

	@Test
	void theEndOfACommandCompletesItsLastLine() {
		Console console = display.open(shared(SESSION), null);
		console.write("no line end\r");

		console.exited("term-abc-1", 0, null);

		assertEquals("no line end\n", host.created.get(0).text.toString());
		assertTrue(host.created.get(0).present, "a shared console outlives its commands");
	}

	@Test
	void revealPassesOnWhetherToTakeTheFocus() {
		Console console = display.open(shared(SESSION), null);

		console.reveal(false);
		console.reveal(true);

		assertEquals(List.of(false, true), host.created.get(0).shown);
	}

	@Test
	void closeRemovesTheConsoleAndIsNotTheUsersDoing() {
		Console console = display.open(shared(SESSION), closedByUser::incrementAndGet);

		console.close();
		console.close();

		assertEquals(List.of(), host.names());
		assertEquals(0, closedByUser.get());
	}

	@Test
	void theUserClosingTheConsoleIsReportedOnce() {
		Console console = display.open(shared(SESSION), closedByUser::incrementAndGet);

		host.created.get(0).userCloses();
		console.close();

		assertEquals(1, closedByUser.get());
	}

	@Test
	void lateCallsAfterTheConsoleIsGoneAreIgnored() {
		Console closed = display.open(command("term-abc-1", "ls"), null);
		Console removed = display.open(command("term-abc-2", "ls"), null);
		closed.write("before\r\n");
		removed.write("before\r\n");
		closed.close();
		host.created.get(1).userCloses();

		for (Console console : List.of(closed, removed)) {
			// The fake fails on text for a console that is gone.
			console.write("late output of a killed command\r\n");
			console.exited("term-abc-1", null, "SIGKILL");
			console.reveal(false);
			console.reveal(true);
		}

		assertEquals("before\n", host.created.get(0).text.toString());
		assertEquals("before\n", host.created.get(1).text.toString());
		assertEquals(List.of(), host.created.get(0).shown);
		assertEquals(List.of(), host.created.get(1).shown);
	}

	@Test
	void afterTheUserClosedTheSharedConsoleTheSessionGetsANewOne() {
		Console first = display.open(shared(SESSION), closedByUser::incrementAndGet);
		first.write("first\r\n");
		host.created.get(0).userCloses();

		Console second = display.open(shared(SESSION), closedByUser::incrementAndGet);
		first.write("late\r\n");
		second.write("second\r\n");

		assertNotSame(first, second);
		assertEquals(2, host.created.size());
		assertEquals("second\n", host.created.get(1).text.toString());
		assertEquals(1, closedByUser.get());
	}

	@Test
	void onlyTheLastFinishedCommandConsolesAreKept() {
		int kept = ConsoleTerminalDisplay.FINISHED_COMMAND_CONSOLES;
		Console running = display.open(command("term-run", "sleep 100"), closedByUser::incrementAndGet);
		List<Console> finished = new ArrayList<>();
		for (int i = 0; i < kept + 2; i++) {
			Console console = display.open(command("term-" + i, "echo " + i), closedByUser::incrementAndGet);
			console.exited("term-" + i, 0, null);
			// Told twice, as a console of several commands would be: still one console.
			console.exited("term-" + i, 0, null);
			finished.add(console);
		}

		List<String> names = host.names();
		assertEquals(kept + 1, names.size());
		assertEquals("Kiro: sleep 100", names.get(0), "a console whose command still runs is not counted");
		assertEquals("Kiro: echo 2", names.get(1), "the oldest finished ones went");
		assertEquals(0, closedByUser.get(), "tidying up is not the user closing a console");
		assertNotNull(running);

		// One the user closed makes room without anything else going.
		host.created.get(host.created.size() - 1).userCloses();
		Console next = display.open(command("term-next", "echo next"), null);
		next.exited("term-next", 0, null);
		assertEquals(kept + 1, host.names().size());
		assertEquals("Kiro: echo 2", host.names().get(1));
	}

	@Test
	void sharedConsolesAreNeverTidiedAway() {
		Console console = display.open(shared(SESSION), null);
		for (int i = 0; i < ConsoleTerminalDisplay.FINISHED_COMMAND_CONSOLES * 3; i++) {
			console.exited("term-" + i, 0, null);
		}

		assertEquals(List.of("Kiro · 0b8e2f4a"), host.names());
	}

	private static Target shared(String sessionId) {
		return new Target(TerminalMode.SHARED, sessionId, null, TerminalPresentation.sharedTerminalName(sessionId));
	}

	private static Target command(String terminalId, String command) {
		return new Target(TerminalMode.PER_COMMAND, SESSION, terminalId, TerminalPresentation.commandTerminalName(command));
	}
}
