package io.github.liaoalia.kiroacp.core.session.history;

import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;

import io.github.liaoalia.kiroacp.core.acp.api.KiroAgent;
import io.github.liaoalia.kiroacp.core.acp.api.SessionListEntry;
import io.github.liaoalia.kiroacp.core.host.UiScheduler;
import io.github.liaoalia.kiroacp.core.model.ConnectionStatus;
import io.github.liaoalia.kiroacp.core.model.FromWebview;
import io.github.liaoalia.kiroacp.core.model.NoticeView.Severity;
import io.github.liaoalia.kiroacp.core.session.ChatController;
import io.github.liaoalia.kiroacp.core.session.PageHandle;

/**
 * The sessions the agent kept from earlier runs: listing them
 * ({@code session/list}), letting the user choose one and restoring it
 * ({@code session/load}); reference D21.
 *
 * <p>The page's history button ({@code browseSessions}) ends up in
 * {@link #browse}. Every method may be called from any thread; the futures
 * complete on the controller thread.
 */
public final class PastSessions {

	/** A looping agent is not followed for ever. */
	static final int MAX_LIST_PAGES = 50;

	/**
	 * A past session as the user is offered it.
	 *
	 * @param label       the title, else the id
	 * @param description when it was last used, relative to now ("5m ago"); empty when unknown
	 * @param detail      its working directory
	 * @param title       the agent's title, or {@code null}
	 * @param updatedAt   ISO timestamp, or {@code null}
	 */
	public record Entry(String sessionId, String label, String description, String detail, String title, String cwd,
			String updatedAt) {

		/** Whether the entry is among what the user typed: every word occurs in its label, time or directory, whatever the case. */
		public boolean matches(String filter) {
			if (filter == null || filter.isBlank()) {
				return true;
			}
			String text = (label + "\n" + description + "\n" + detail).toLowerCase(Locale.ROOT);
			for (String word : filter.trim().toLowerCase(Locale.ROOT).split("\\s+")) {
				if (!text.contains(word)) {
					return false;
				}
			}
			return true;
		}
	}

	/** Asks the user which past session to restore: a dialog in the IDE. */
	@FunctionalInterface
	public interface Picker {

		/**
		 * @param entries never empty, most recent first
		 * @return completes with the chosen session's id, or {@code null} when the user chose none;
		 *         on any thread. Called on the controller thread and must return at once.
		 */
		CompletionStage<String> pick(List<Entry> entries);
	}

	private final ChatController controller;
	private final Picker picker;
	private final UiScheduler scheduler;
	/** Titles of the sessions the agent listed, by id; a session without a title maps to {@code null}. Controller thread. */
	private final Map<String, String> pastTitles = new HashMap<>();

	private PastSessions(ChatController controller, Picker picker) {
		this.controller = Objects.requireNonNull(controller, "controller");
		this.picker = Objects.requireNonNull(picker, "picker");
		this.scheduler = controller.host().scheduler();
	}

	/** Make the page's history button work. */
	public static PastSessions install(ChatController controller, Picker picker) {
		PastSessions past = new PastSessions(controller, picker);
		controller.hooks().onMessage(FromWebview.BrowseSessions.class, (message, page) -> past.browse(page));
		return past;
	}

	/**
	 * The agent's past sessions of this workspace (every past session when
	 * none is of the workspace's directory), without the ones that are live
	 * here, most recent first as the agent lists them. Fails when the agent is
	 * not running or cannot list sessions.
	 */
	public CompletableFuture<List<Entry>> list() {
		return onController(controller.start().handle((started, error) -> null)).thenCompose(started -> {
			KiroAgent agent = controller.agent();
			if (agent == null || agent.status() != ConnectionStatus.READY) {
				return CompletableFuture.<List<Entry>>failedFuture(new IllegalStateException("The agent is not running."));
			}
			return listEntries(agent).thenApply(sessions -> {
				Set<String> live = new HashSet<>(controller.sessionIds());
				long now = controller.host().clock().getAsLong();
				List<Entry> entries = new ArrayList<>();
				for (SessionListEntry session : sessions) {
					if (!live.contains(session.sessionId())) {
						entries.add(new Entry(session.sessionId(), session.title() != null ? session.title() : session.sessionId(),
								relativeTime(session.updatedAt(), now, ZoneId.systemDefault(), Locale.getDefault()),
								session.cwd() != null ? session.cwd() : "", session.title(), session.cwd(), session.updatedAt()));
					}
				}
				return List.copyOf(entries);
			});
		});
	}

	/**
	 * Let the user choose one of the agent's past sessions and restore it into
	 * {@code page} (default: the page used last). What goes wrong is said in a
	 * notice.
	 */
	public CompletableFuture<Void> browse(PageHandle page) {
		return list().handle((entries, error) -> {
			String current = page != null ? page.sessionId() : controller.focusedSessionId();
			if (error != null) {
				controller.notice(live(current), Severity.WARNING, "Could not list past sessions: " + message(error));
				return CompletableFuture.<Void>completedFuture(null);
			}
			if (entries.isEmpty()) {
				controller.notice(live(current), Severity.INFO, "No past sessions to restore.");
				return CompletableFuture.<Void>completedFuture(null);
			}
			return onController(picker.pick(entries)).thenCompose(
					picked -> picked == null ? CompletableFuture.<Void>completedFuture(null) : open(picked, page).thenApply(id -> (Void) null));
		}).thenCompose(flow -> flow);
	}

	/**
	 * Restore a past session into {@code page} (default: the page used last),
	 * with the title the agent lists it under.
	 *
	 * @return the session's id, or {@code null} when it could not be restored (a notice says why)
	 */
	public CompletableFuture<String> open(String sessionId, PageHandle page) {
		return onController(controller.start().handle((started, error) -> null)).thenCompose(started -> {
			KiroAgent agent = controller.agent();
			boolean known = pastTitles.containsKey(sessionId) || controller.sessionIds().contains(sessionId);
			if (known || agent == null || agent.status() != ConnectionStatus.READY) {
				return CompletableFuture.completedFuture((Void) null);
			}
			// The title stays unknown until the agent reports one when the list cannot be had.
			return listEntries(agent).handle((listed, error) -> (Void) null);
		}).thenCompose(listed -> controller.openPastSession(sessionId, pastTitles.get(sessionId), page));
	}

	/** {@code session/list} of the workspace's directory, else of everything; notes the titles. Completes on the controller thread. */
	private CompletableFuture<List<SessionListEntry>> listEntries(KiroAgent agent) {
		Path cwd = controller.host().workspace().defaultCwd();
		CompletableFuture<List<SessionListEntry>> all = listAllPages(agent, cwd)
				.thenCompose(sessions -> sessions.isEmpty() ? listAllPages(agent, null) : CompletableFuture.completedFuture(sessions));
		return onController(all).thenApply(sessions -> {
			for (SessionListEntry entry : sessions) {
				pastTitles.put(entry.sessionId(), entry.title());
			}
			return sessions;
		});
	}

	/**
	 * Every page of {@code session/list} in one list: the agent hands the list
	 * out in pages. Ends with the last page, with a cursor the agent gave
	 * before (it is looping) and after {@value #MAX_LIST_PAGES} pages.
	 */
	static CompletableFuture<List<SessionListEntry>> listAllPages(KiroAgent agent, Path cwd) {
		return nextPage(agent, cwd, null, new ArrayList<>(), new HashSet<>(), 0);
	}

	private static CompletableFuture<List<SessionListEntry>> nextPage(KiroAgent agent, Path cwd, String cursor,
			List<SessionListEntry> sessions, Set<String> seenCursors, int page) {
		if (page >= MAX_LIST_PAGES) {
			return CompletableFuture.completedFuture(sessions);
		}
		return agent.listSessions(cwd, cursor).thenCompose(result -> {
			sessions.addAll(result.sessions());
			String next = result.nextCursor();
			if (next == null || next.isEmpty() || !seenCursors.add(next)) {
				return CompletableFuture.completedFuture(sessions);
			}
			return nextPage(agent, cwd, next, sessions, seenCursors, page + 1);
		});
	}

	/**
	 * How long ago, as the reference's {@code relativeTime} says it: "just
	 * now", minutes, hours, days up to six, then the date. Empty when the
	 * time is unknown or unreadable.
	 */
	static String relativeTime(String iso, long now, ZoneId zone, Locale locale) {
		if (iso == null || iso.isEmpty()) {
			return "";
		}
		long then;
		try {
			then = Instant.parse(iso).toEpochMilli();
		} catch (DateTimeParseException notUtc) {
			try {
				then = OffsetDateTime.parse(iso).toInstant().toEpochMilli();
			} catch (DateTimeParseException unreadable) {
				return "";
			}
		}
		long seconds = Math.max(0, Math.round((now - then) / 1000.0));
		if (seconds < 60) {
			return "just now";
		}
		long minutes = seconds / 60;
		if (minutes < 60) {
			return minutes + "m ago";
		}
		long hours = minutes / 60;
		if (hours < 24) {
			return hours + "h ago";
		}
		long days = hours / 24;
		if (days < 7) {
			return days + "d ago";
		}
		return DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale).format(Instant.ofEpochMilli(then).atZone(zone));
	}

	/** A session id only while it is live: a notice for one that is gone would be lost. */
	private String live(String sessionId) {
		return sessionId != null && controller.sessionIds().contains(sessionId) ? sessionId : null;
	}

	/**
	 * The same outcome on the controller thread: at once when it is there
	 * already and the caller is on that thread, else behind whatever is queued.
	 */
	private <T> CompletableFuture<T> onController(CompletionStage<T> source) {
		CompletableFuture<T> given = source.toCompletableFuture();
		if (given.isDone() && scheduler.isControllerThread()) {
			return given;
		}
		CompletableFuture<T> out = new CompletableFuture<>();
		source.whenComplete((value, error) -> scheduler.execute(() -> {
			if (error != null) {
				out.completeExceptionally(unwrap(error));
			} else {
				out.complete(value);
			}
		}));
		return out;
	}

	private static Throwable unwrap(Throwable error) {
		Throwable current = error;
		while ((current instanceof CompletionException || current instanceof ExecutionException) && current.getCause() != null) {
			current = current.getCause();
		}
		return current;
	}

	private static String message(Throwable error) {
		Throwable cause = unwrap(error);
		return cause.getMessage() != null && !cause.getMessage().isEmpty() ? cause.getMessage() : cause.toString();
	}
}
