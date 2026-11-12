package io.github.liaoalia.kiroacp.core.session;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.liaoalia.kiroacp.core.host.Settings;
import io.github.liaoalia.kiroacp.core.model.NoticeView;
import io.github.liaoalia.kiroacp.core.model.ToolCallView;
import io.github.liaoalia.kiroacp.core.model.ToolContentView;
import io.github.liaoalia.kiroacp.core.model.UiState;
import io.github.liaoalia.kiroacp.core.pure.transcript.Transcript;
import io.github.liaoalia.kiroacp.core.pure.transcript.TranscriptState;
import io.github.liaoalia.kiroacp.core.pure.transcript.UiStateExtras;
import io.github.liaoalia.kiroacp.core.services.TerminalService.TerminalSnapshot;

/** Builds the full {@code state} a page gets, and fills tool cards with what their terminals printed. */
final class Snapshots {

	private final ChatController c;

	Snapshots(ChatController controller) {
		this.c = controller;
	}

	/** {@code UiState} as the page sees it: its session and the sessions of its group. */
	UiState forPage(PageBinding page) {
		return build(page.displayed, page.groupId);
	}

	/**
	 * @param id      the session to describe, or {@code null} for a page without one
	 * @param groupId whose sessions to list, or {@code null} for all
	 */
	UiState build(String id, String groupId) {
		ChatSession session = id != null ? c.sessions.get(id) : null;
		TranscriptState transcript = session != null ? session.transcript : Transcript.createTranscript();
		List<NoticeView> notices = new ArrayList<>(c.globalNotices);
		if (session != null) {
			notices.addAll(session.notices);
		}
		UiState state = Transcript.snapshotToUiState(transcript,
				new UiStateExtras(c.connection, session != null ? session.id() : null, c.listing.summaries(groupId),
						session != null && session.turnActive, session != null ? c.pendingFor(session.id()) : List.of(),
						session != null ? session.models : null, c.hooks.composer().chips(), notices));
		Map<String, ToolCallView> toolCalls = new LinkedHashMap<>();
		state.toolCalls().forEach((callId, call) -> toolCalls.put(callId, withTerminalOutput(call, session)));
		Settings settings = c.host.settings();
		return new UiState(state.connection(),
				// Seam (reference D22): the page offers its composer during a turn only when something takes those prompts.
				c.hooks.midTurn() != null, state.sessionId(), state.sessions(), state.turnActive(), state.items(), toolCalls,
				state.plan(), state.permissions(), state.modes(), state.configOptions(), state.models(),
				c.hooks.commands(transcript, c.agentInfo), state.chips(), state.notices(),
				session != null ? c.hooks.usage(session.id()) : null, session != null ? session.updatedAt : null,
				new UiState.Prefs(settings.showSettingsByDefault(), settings.sessionPanelCollapsed()));
	}

	/**
	 * The transcript only knows a terminal's id; the output kept for it and
	 * its exit code come from the terminal service, read at the moment the
	 * card is posted. A running command's card therefore shows more output
	 * each time the agent updates it, and nothing in between, as in the
	 * reference. Only terminals of {@code session} qualify, and a tool call
	 * replayed by {@code session/load} keeps what the agent sent: its ids are
	 * of an earlier run (reference D9, D21).
	 */
	ToolCallView withTerminalOutput(ToolCallView call, ChatSession session) {
		if (session == null || session.replayedToolCalls.contains(call.toolCallId())) {
			return call;
		}
		if (call.content().stream().noneMatch(ToolContentView.Terminal.class::isInstance)) {
			return call;
		}
		List<ToolContentView> content = new ArrayList<>(call.content().size());
		for (ToolContentView item : call.content()) {
			TerminalSnapshot snapshot = item instanceof ToolContentView.Terminal terminal
					? c.terminal.snapshot(terminal.terminalId(), session.id())
					: null;
			if (snapshot == null) {
				content.add(item);
			} else {
				String id = ((ToolContentView.Terminal) item).terminalId();
				// Three states for the page: running (no field), ended with a code, ended without one (killed).
				content.add(snapshot.exited() ? ToolContentView.Terminal.ended(id, snapshot.output(), snapshot.exitCode())
						: ToolContentView.Terminal.running(id, snapshot.output()));
			}
		}
		return new ToolCallView(call.toolCallId(), call.title(), call.kind(), call.status(), content, call.locations(),
				call.rawInput(), call.rawOutput(), call.pendingPermissionId(), call.failureReason());
	}
}
