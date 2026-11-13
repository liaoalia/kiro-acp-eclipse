package io.github.liaoalia.kiroacp.core.session.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;

import org.junit.jupiter.api.Test;

import io.github.liaoalia.kiroacp.core.acp.api.KiroAgent;
import io.github.liaoalia.kiroacp.core.acp.api.SessionListEntry;
import io.github.liaoalia.kiroacp.core.acp.api.SessionListPage;
import io.github.liaoalia.kiroacp.core.session.history.PastSessions.Entry;

/** The parts of the past-session list that need no agent process: paging, the relative time, the dialog's filter. */
class PastSessionsTest {

	private static final long NOW = Instant.parse("2026-11-13T12:00:00Z").toEpochMilli();

	/** An agent that only lists sessions; what it was asked is written down as {@code cwd|cursor}. */
	private static KiroAgent lister(List<String> asked, BiFunction<Path, String, SessionListPage> pages) {
		return (KiroAgent) Proxy.newProxyInstance(KiroAgent.class.getClassLoader(), new Class<?>[] { KiroAgent.class },
				(proxy, method, args) -> {
					if (!method.getName().equals("listSessions")) {
						throw new UnsupportedOperationException(method.getName());
					}
					asked.add(args[0] + "|" + args[1]);
					try {
						return CompletableFuture.completedFuture(pages.apply((Path) args[0], (String) args[1]));
					} catch (RuntimeException e) {
						return CompletableFuture.failedFuture(e);
					}
				});
	}

	private static SessionListEntry entry(String id) {
		return new SessionListEntry(id, "/work", "Title of " + id, null);
	}

	@Test
	void everyPageOfTheListIsFollowedToTheLast() {
		List<String> asked = new ArrayList<>();
		KiroAgent agent = lister(asked, (cwd, cursor) -> cursor == null ? new SessionListPage(List.of(entry("a"), entry("b")), "2")
				: cursor.equals("2") ? new SessionListPage(List.of(entry("c"), entry("d")), "4")
						: new SessionListPage(List.of(entry("e")), null));

		List<SessionListEntry> all = PastSessions.listAllPages(agent, Path.of("work")).join();

		assertEquals(List.of("a", "b", "c", "d", "e"), all.stream().map(SessionListEntry::sessionId).toList());
		assertEquals(List.of("work|null", "work|2", "work|4"), asked, "the folder is asked for on every page");
	}

	@Test
	void anEmptyCursorEndsTheListAsNoCursorDoes() {
		List<String> asked = new ArrayList<>();
		KiroAgent agent = lister(asked, (cwd, cursor) -> new SessionListPage(List.of(entry("a")), ""));

		assertEquals(1, PastSessions.listAllPages(agent, null).join().size());
		assertEquals(List.of("null|null"), asked);
	}

	@Test
	void anAgentThatHandsOutACursorTwiceIsNotFollowedInCircles() {
		List<String> asked = new ArrayList<>();
		// a -> b -> a -> ...
		KiroAgent agent = lister(asked, (cwd, cursor) -> new SessionListPage(List.of(entry("s" + asked.size())),
				cursor == null || cursor.equals("b") ? "a" : "b"));

		List<SessionListEntry> all = PastSessions.listAllPages(agent, null).join();

		assertEquals(List.of("null|null", "null|a", "null|b"), asked);
		assertEquals(3, all.size(), "what was listed until then is kept");
	}

	@Test
	void anAgentThatNeverEndsTheListIsLeftAfterFiftyPages() {
		List<String> asked = new ArrayList<>();
		KiroAgent agent = lister(asked, (cwd, cursor) -> new SessionListPage(List.of(entry("s" + asked.size())), "c" + asked.size()));

		List<SessionListEntry> all = PastSessions.listAllPages(agent, null).join();

		assertEquals(PastSessions.MAX_LIST_PAGES, asked.size());
		assertEquals(50, all.size());
	}

	@Test
	void aPageThatFailsFailsTheList() {
		KiroAgent agent = lister(new ArrayList<>(), (cwd, cursor) -> {
			if (cursor != null) {
				throw new IllegalStateException("unknown cursor " + cursor);
			}
			return new SessionListPage(List.of(entry("a")), "1");
		});

		CompletableFuture<List<SessionListEntry>> all = PastSessions.listAllPages(agent, null);

		assertTrue(all.isCompletedExceptionally());
	}

	@Test
	void theTimeIsSaidRelativeToNowAsInTheReference() {
		assertEquals("", relative(null), "unknown");
		assertEquals("", relative(""));
		assertEquals("", relative("yesterday"), "unreadable");
		assertEquals("just now", relative("2026-11-13T12:00:00Z"));
		assertEquals("just now", relative("2026-11-13T11:59:01Z"), "59 seconds");
		assertEquals("just now", relative("2026-11-13T11:59:00.600Z"), "59.4 seconds round down");
		assertEquals("1m ago", relative("2026-11-13T11:59:00.500Z"), "59.5 seconds round up");
		assertEquals("just now", relative("2026-11-13T12:05:00Z"), "a clock that is ahead of ours");
		assertEquals("1m ago", relative("2026-11-13T11:59:00Z"));
		assertEquals("59m ago", relative("2026-11-13T11:00:01Z"));
		assertEquals("1h ago", relative("2026-11-13T11:00:00Z"));
		assertEquals("23h ago", relative("2026-11-12T12:00:01Z"));
		assertEquals("1d ago", relative("2026-11-12T12:00:00Z"));
		assertEquals("6d ago", relative("2026-11-06T12:00:01Z"));
		assertEquals("11/6/26", relative("2026-11-06T12:00:00Z"), "from a week on: the date");
		assertEquals("4d ago", relative("2026-11-09T10:00:00.000Z"), "with milliseconds, as the agent writes it");
		assertEquals("2h ago", relative("2026-11-13T12:00:00+02:00"), "a time with an offset");
	}

	@Test
	void theDateOfAnOldSessionIsTheLocalOne() {
		assertEquals("06.11.26", PastSessions.relativeTime("2026-11-05T23:30:00Z", NOW, ZoneOffset.ofHours(2), Locale.GERMANY));
		assertEquals("11/5/26", PastSessions.relativeTime("2026-11-05T23:30:00Z", NOW, ZoneOffset.UTC, Locale.US));
	}

	@Test
	void whatIsTypedNarrowsTheListByTitleTimeAndFolder() {
		Entry entry = new Entry("past-1", "Investigate flaky CI", "4d ago", "C:\\work\\shop", "Investigate flaky CI", "C:\\work\\shop", null);

		assertTrue(entry.matches(null));
		assertTrue(entry.matches(""));
		assertTrue(entry.matches("   "));
		assertTrue(entry.matches("flaky"));
		assertTrue(entry.matches("FLAKY ci"), "whatever the case, every word");
		assertTrue(entry.matches("ci investigate"), "in any order");
		assertTrue(entry.matches("4d"), "the time");
		assertTrue(entry.matches("shop"), "the folder");
		assertTrue(entry.matches("flaky shop"), "words from different columns");
		assertFalse(entry.matches("flaky deploy"), "every word has to occur");
		assertFalse(entry.matches("past-1"), "the id is not shown when there is a title, so it is not searched");
		assertTrue(new Entry("past-9", "past-9", "", "", null, "", null).matches("past-9"), "without a title the id is the label");
	}

	private static String relative(String iso) {
		return PastSessions.relativeTime(iso, NOW, ZoneOffset.UTC, Locale.US);
	}
}
