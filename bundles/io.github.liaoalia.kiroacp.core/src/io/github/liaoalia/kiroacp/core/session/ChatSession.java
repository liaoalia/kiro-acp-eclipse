package io.github.liaoalia.kiroacp.core.session;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import io.github.liaoalia.kiroacp.core.acp.api.SessionState;
import io.github.liaoalia.kiroacp.core.host.Subscription;
import io.github.liaoalia.kiroacp.core.model.ModelView;
import io.github.liaoalia.kiroacp.core.model.NoticeView;
import io.github.liaoalia.kiroacp.core.pure.transcript.Transcript;
import io.github.liaoalia.kiroacp.core.pure.transcript.TranscriptState;

/** What the controller keeps for one live session ({@code ChatSession} in the reference). Controller thread only. */
final class ChatSession {

	SessionState state;
	/** Creation order, breaks {@code updatedAt} ties. */
	final int seq;
	/** The page group the session belongs to (reference D20). */
	String groupId;

	TranscriptState transcript;
	ModelView models;
	boolean turnActive;
	List<NoticeView> notices = List.of();
	int userMessageSeq;
	final Set<String> knownPermissionIds = new LinkedHashSet<>();
	/** {@code session_info_update.title} from the agent, when present. */
	String agentTitle;
	/** First prompt sent (or replayed by {@code session/load}), truncated: the fallback title. */
	String firstPrompt;
	/** True while {@code session/load} replays the session's history (reference D21). */
	boolean loading;
	/** Tool calls replayed by {@code session/load}: their terminal ids are of an earlier run and never resolved against live terminals. */
	final Set<String> replayedToolCalls = new HashSet<>();
	/** The session is being closed: nothing may start another turn on it. */
	boolean closing;
	/** While {@code /clear} replaces the session: the id of the session that takes its place, {@code null} when that failed. */
	CompletableFuture<String> replacement;
	/** Bumped whenever a turn starts or ends, so a late answer can tell it is late. */
	int turnGeneration;
	/** Armed by Stop: ends the turn here when the agent never answers {@code session/cancel} (reference D27). */
	Subscription cancelTimer;
	/**
	 * The Stop watchdog ended a turn whose {@code session/prompt} has not
	 * settled yet: until it does, updates that arrive while no turn runs are
	 * that turn's late output and are dropped.
	 */
	boolean abandonedTurn;
	boolean abandonedDropLogged;
	long updatedAt;

	ChatSession(SessionState state, int seq, String groupId, long now) {
		this.state = state;
		this.seq = seq;
		this.groupId = groupId;
		this.transcript = Transcript.withSessionSnapshot(Transcript.createTranscript(), state);
		this.models = Transcript.toModelView(state.models());
		this.updatedAt = now;
	}

	String id() {
		return state.sessionId();
	}

	String title() {
		return agentTitle != null ? agentTitle : firstPrompt != null ? firstPrompt : "New session";
	}

	/** At most ten notices are kept, as many as the page shows. */
	void addNotice(NoticeView notice) {
		List<NoticeView> next = new ArrayList<>(notices.subList(Math.max(0, notices.size() - 9), notices.size()));
		next.add(notice);
		notices = List.copyOf(next);
	}

	void stopCancelTimer() {
		if (cancelTimer != null) {
			cancelTimer.close();
			cancelTimer = null;
		}
	}
}
