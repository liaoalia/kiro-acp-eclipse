package io.github.liaoalia.kiroacp.core.services;

import static io.github.liaoalia.kiroacp.core.services.Fakes.get;
import static io.github.liaoalia.kiroacp.core.services.Fakes.json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.liaoalia.kiroacp.core.host.Subscription;
import io.github.liaoalia.kiroacp.core.model.PermissionOptionView;
import io.github.liaoalia.kiroacp.core.model.PermissionScope;
import io.github.liaoalia.kiroacp.core.model.PermissionView;
import io.github.liaoalia.kiroacp.core.model.ToolContentView;
import io.github.liaoalia.kiroacp.core.model.ToolKind;
import io.github.liaoalia.kiroacp.core.pure.permission.PolicyDecision;

class PermissionManagerTest {

	private static final String FOUR_OPTIONS = """
			[{"optionId":"allow","name":"Allow","kind":"allow_once"},
			 {"optionId":"allow-all","name":"Always allow","kind":"allow_always"},
			 {"optionId":"reject","name":"Reject","kind":"reject_once"},
			 {"optionId":"reject-all","name":"Always reject","kind":"reject_always"}]""";

	private final PermissionManager manager = new PermissionManager();
	private final List<PermissionResolution> resolutions = new CopyOnWriteArrayList<>();
	private final AtomicInteger changes = new AtomicInteger();

	PermissionManagerTest() {
		manager.onDidChange(changes::incrementAndGet);
		manager.onDidResolve(resolutions::add);
	}

	/** A request as the fake agent sends it: with a kind and without consent. */
	private static ObjectNode request(String sessionId, String toolCallId, String kind) {
		ObjectNode request = (ObjectNode) json("{\"toolCall\":{\"title\":\"Edit config.json\"}}");
		request.put("sessionId", sessionId);
		ObjectNode toolCall = (ObjectNode) request.get("toolCall");
		toolCall.put("toolCallId", toolCallId);
		if (kind != null) {
			toolCall.put("kind", kind);
		}
		request.set("options", json(FOUR_OPTIONS));
		return request;
	}

	/** A request as Kiro V3 sends it: no kind, a consent context, and no way to always allow. */
	private static ObjectNode consentRequest(String sessionId, String toolCallId, String capability, String resource) {
		ObjectNode request = request(sessionId, toolCallId, null);
		request.set("options", json("""
				[{"optionId":"yes","name":"Allow","kind":"allow_once"},
				 {"optionId":"no","name":"Deny","kind":"reject_once"},
				 {"optionId":"never","name":"Always deny","kind":"reject_always"}]"""));
		ObjectNode consent = request.putObject("_meta").putObject("kiro").putObject("consent");
		consent.put("capability", capability);
		consent.put("resource", resource);
		consent.put("persistableConsent", true);
		return request;
	}

	private static String outcome(JsonNode response) {
		JsonNode outcome = response.path("outcome");
		return outcome.path("outcome").asText() + (outcome.has("optionId") ? ":" + outcome.get("optionId").asText() : "");
	}

	private String onlyPendingId() {
		List<PermissionView> pending = manager.pending();
		assertEquals(1, pending.size(), pending.toString());
		return pending.get(0).requestId();
	}

	@Test
	void aRequestStaysPendingUntilAnOptionIsChosen() throws Exception {
		ObjectNode request = request("s1", "call-edit", "edit");
		((ObjectNode) request.get("toolCall")).set("content", json("""
				[{"type":"content","content":{"type":"text","text":"x"}},
				 {"type":"diff","path":"/w/config.json","oldText":null,"newText":"{}"},
				 {"type":"diff","path":"/w/other.json","oldText":"a"}]"""));

		CompletableFuture<JsonNode> answer = manager.request(request, new CompletableFuture<>());

		assertFalse(answer.isDone());
		assertEquals(1, changes.get());
		assertEquals(1, manager.size());
		PermissionView view = manager.pending().get(0);
		assertEquals("perm-1", view.requestId());
		assertEquals("call-edit", view.toolCallId());
		assertEquals("Edit config.json", view.title());
		assertEquals(ToolKind.EDIT, view.kind());
		assertTrue(view.hasDiff());
		assertNull(view.consent());
		assertEquals(List.of("allow", "allow-all", "reject", "reject-all"),
				view.options().stream().map(PermissionOptionView::optionId).toList());
		assertEquals(PermissionOptionView.Kind.REJECT_ALWAYS, view.options().get(3).kind());
		assertEquals("Always allow", view.options().get(1).name());
		assertEquals(List.of(PermissionScope.KIND), view.policies().stream().map(PermissionView.Policy::scope).toList());
		assertEquals(view, manager.get("perm-1"));

		PendingPermission entry = manager.getEntry("perm-1");
		assertEquals("s1", entry.sessionId());
		assertEquals(List.of(new ToolContentView.Diff("/w/config.json", null, "{}"),
				new ToolContentView.Diff("/w/other.json", "a", "")), entry.diffs());
		assertTrue(entry.createdAt() > 0);

		manager.resolve("perm-1", "allow-all");

		assertEquals("selected:allow-all", outcome(get(answer)));
		assertEquals(0, manager.size());
		assertNull(manager.get("perm-1"));
		assertNull(manager.getEntry("perm-1"));
		assertEquals(2, changes.get());
		assertEquals(List.of(new PermissionResolution("perm-1", "s1", "call-edit", ToolKind.EDIT,
				PermissionResolution.Outcome.ALLOW, "allow-all")), resolutions);
	}

	@Test
	void choosingARejectOptionIsReportedAsReject() throws Exception {
		CompletableFuture<JsonNode> answer = manager.request(request("s1", "call-edit", "edit"), null);
		manager.resolve(onlyPendingId(), "reject");
		assertEquals("selected:reject", outcome(get(answer)));
		assertEquals(PermissionResolution.Outcome.REJECT, resolutions.get(0).outcome());
		assertEquals("reject", resolutions.get(0).optionId());
	}

	@Test
	void resolveRefusesUnknownRequestsAndOptionsThatWereNotOffered() {
		CompletableFuture<JsonNode> answer = manager.request(request("s1", "call-edit", "edit"), null);
		String id = onlyPendingId();

		assertEquals("Unknown permission request: perm-9",
				assertThrows(IllegalArgumentException.class, () -> manager.resolve("perm-9", "allow")).getMessage());
		assertEquals("Option \"nope\" is not offered by request " + id,
				assertThrows(IllegalArgumentException.class, () -> manager.resolve(id, "nope")).getMessage());
		assertThrows(IllegalArgumentException.class, () -> manager.resolve(id, null));

		assertFalse(answer.isDone());
		assertEquals(1, manager.size());
		assertTrue(resolutions.isEmpty());
	}

	@Test
	void decideRequestPicksTheOnceOptionElseTheAlwaysOne() throws Exception {
		CompletableFuture<JsonNode> first = manager.request(request("s1", "call-1", "edit"), null);
		assertEquals("allow", manager.decideRequest(onlyPendingId(), PolicyDecision.ALLOW));
		assertEquals("selected:allow", outcome(get(first)));

		ObjectNode onlyAlways = request("s1", "call-2", "edit");
		onlyAlways.set("options", json("""
				[{"optionId":"a","name":"Always","kind":"allow_always"},{"optionId":"r","name":"Never","kind":"reject_always"}]"""));
		CompletableFuture<JsonNode> second = manager.request(onlyAlways, null);
		assertEquals("r", manager.decideRequest(onlyPendingId(), PolicyDecision.REJECT));
		assertEquals("selected:r", outcome(get(second)));
		assertEquals(List.of(PermissionResolution.Outcome.ALLOW, PermissionResolution.Outcome.REJECT),
				resolutions.stream().map(PermissionResolution::outcome).toList());
	}

	@Test
	void decideRequestLeavesARequestPendingThatOffersNoSuchOption() {
		ObjectNode onlyAllow = request("s1", "call-1", "edit");
		onlyAllow.set("options", json("[{\"optionId\":\"a\",\"name\":\"Allow\",\"kind\":\"allow_once\"}]"));
		CompletableFuture<JsonNode> answer = manager.request(onlyAllow, null);
		String id = onlyPendingId();

		assertNull(manager.decideRequest(id, PolicyDecision.REJECT));
		assertNull(manager.decideRequest("perm-9", PolicyDecision.ALLOW));

		assertFalse(answer.isDone());
		assertEquals(1, manager.size());
	}

	/** Reference F7.1. */
	@Test
	void aKindPolicyAnswersTheRequestAndLaterRequestsOfThatKindInTheSession() throws Exception {
		CompletableFuture<JsonNode> first = manager.request(request("s1", "call-edit", "edit"), null);
		manager.setPolicy(onlyPendingId(), PolicyDecision.REJECT);
		assertEquals("selected:reject", outcome(get(first)));
		assertEquals(0, manager.size());
		int changesSoFar = changes.get();

		CompletableFuture<JsonNode> again = manager.request(request("s1", "call-edit-2", "edit"), null);
		assertTrue(again.isDone());
		assertEquals("selected:reject", outcome(get(again)));
		assertEquals(changesSoFar, changes.get(), "an automatic answer is never shown");
		assertEquals(1, resolutions.size());

		assertFalse(manager.request(request("s1", "call-exec", "execute"), null).isDone(), "another kind still asks");
		assertFalse(manager.request(request("s2", "call-edit", "edit"), null).isDone(), "another session still asks");
	}

	@Test
	void anAllowPolicyPicksTheAgentsAllowOnceOption() throws Exception {
		manager.request(request("s1", "call-1", "execute"), null);
		manager.setPolicy(onlyPendingId(), PolicyDecision.ALLOW, PermissionScope.KIND);
		assertEquals("selected:allow", outcome(get(manager.request(request("s1", "call-2", "execute"), null))));
		assertEquals("selected:allow", outcome(get(manager.request(request("s1", "call-3", "execute"), null))));
	}

	/** Reference F7.2. */
	@Test
	void clearPoliciesMakesTheSessionAskAgain() throws Exception {
		manager.request(request("s1", "call-1", "edit"), null);
		manager.setPolicy(onlyPendingId(), PolicyDecision.REJECT);
		manager.request(request("s2", "call-1", "edit"), null);
		manager.setPolicy(onlyPendingId(), PolicyDecision.ALLOW);

		manager.clearPolicies("s1");
		assertFalse(manager.request(request("s1", "call-2", "edit"), null).isDone());
		assertEquals("selected:allow", outcome(get(manager.request(request("s2", "call-2", "edit"), null))));

		manager.clearPolicies();
		assertFalse(manager.request(request("s2", "call-3", "edit"), null).isDone());
	}

	/** Reference F7.3. */
	@Test
	void aResourcePolicyAllowsTheSamePathAndStillAsksForAnother() throws Exception {
		String dir = System.getProperty("java.io.tmpdir");
		String path = dir + "/sub/nokind.txt";
		PermissionManager.ToolCallKinds kinds = (sessionId, toolCallId) -> ToolKind.EDIT;
		PermissionManager withKinds = new PermissionManager(kinds, null);

		CompletableFuture<JsonNode> first = withKinds.request(consentRequest("s1", "call-nokind", "fs_write", path), null);
		PermissionView view = withKinds.pending().get(0);
		assertEquals(ToolKind.EDIT, view.kind());
		PermissionView.Policy resource = view.policies().stream().filter(p -> p.scope() == PermissionScope.RESOURCE)
				.findFirst().orElseThrow();
		assertEquals("Allow all changes to nokind.txt this session", resource.label());

		withKinds.setPolicy(view.requestId(), PolicyDecision.ALLOW, PermissionScope.RESOURCE);
		assertEquals("selected:yes", outcome(get(first)));
		assertEquals(0, withKinds.size());

		// The same file, spelled differently.
		String samePath = dir + "/sub/../sub/nokind.txt";
		assertEquals("selected:yes",
				outcome(get(withKinds.request(consentRequest("s1", "call-nokind", "fs_write", samePath), null))));

		assertFalse(withKinds.request(consentRequest("s1", "call-other", "fs_write", dir + "/sub/config.json"), null).isDone(),
				"another path asks");
		assertFalse(withKinds.request(consentRequest("s1", "call-read", "fs_read", path), null).isDone(),
				"another capability on the same path asks");
		assertFalse(withKinds.request(request("s1", "call-edit", "edit"), null).isDone(),
				"a request of the same kind without consent asks");
		assertFalse(withKinds.request(consentRequest("s2", "call-nokind", "fs_write", path), null).isDone(),
				"another session asks");
	}

	@Test
	void aCapabilityPolicyAnswersEveryResourceOfThatCapability() throws Exception {
		manager.request(consentRequest("s1", "call-1", "shell", "ls -la"), null);
		manager.setPolicy(onlyPendingId(), PolicyDecision.REJECT, PermissionScope.CAPABILITY);

		assertEquals("selected:no", outcome(get(manager.request(consentRequest("s1", "call-2", "shell", "rm -rf x"), null))));
		assertFalse(manager.request(consentRequest("s1", "call-3", "fs_write", "/w/a.txt"), null).isDone());
	}

	@Test
	void theMostSpecificPolicyWins() throws Exception {
		manager.request(consentRequest("s1", "call-2", "shell", "ls"), null);
		manager.setPolicy(onlyPendingId(), PolicyDecision.ALLOW, PermissionScope.RESOURCE);
		manager.request(consentRequest("s1", "call-3", "shell", "rm"), null);
		manager.setPolicy(onlyPendingId(), PolicyDecision.REJECT, PermissionScope.CAPABILITY);

		assertEquals("selected:yes", outcome(get(manager.request(consentRequest("s1", "call-4", "shell", "ls"), null))));
		assertEquals("selected:no", outcome(get(manager.request(consentRequest("s1", "call-5", "shell", "pwd"), null))));
	}

	@Test
	void setPolicyRefusesAScopeTheRequestHasNothingFor() {
		CompletableFuture<JsonNode> answer = manager.request(request("s1", "call-edit", "edit"), null);
		String id = onlyPendingId();

		assertEquals("Permission request " + id + " has no resource to remember",
				assertThrows(IllegalArgumentException.class,
						() -> manager.setPolicy(id, PolicyDecision.ALLOW, PermissionScope.RESOURCE)).getMessage());
		assertEquals("Permission request " + id + " has no capability to remember",
				assertThrows(IllegalArgumentException.class,
						() -> manager.setPolicy(id, PolicyDecision.ALLOW, PermissionScope.CAPABILITY)).getMessage());
		assertEquals("Unknown permission request: perm-9", assertThrows(IllegalArgumentException.class,
				() -> manager.setPolicy("perm-9", PolicyDecision.ALLOW)).getMessage());
		assertFalse(answer.isDone());
	}

	@Test
	void aPolicyWithoutAMatchingOptionIsRememberedButLeavesTheRequestPending() throws Exception {
		ObjectNode onlyReject = request("s1", "call-1", "edit");
		onlyReject.set("options", json("[{\"optionId\":\"r\",\"name\":\"Reject\",\"kind\":\"reject_once\"}]"));
		CompletableFuture<JsonNode> answer = manager.request(onlyReject, null);

		manager.setPolicy(onlyPendingId(), PolicyDecision.ALLOW);

		assertFalse(answer.isDone());
		assertEquals("selected:allow", outcome(get(manager.request(request("s1", "call-2", "edit"), null))));
	}

	/** Reference F7.4 and F7.5. */
	@Test
	void aRequestWithoutAKindTakesTheKindOfTheKnownToolCallOfItsOwnSession() {
		List<String> asked = new ArrayList<>();
		PermissionManager withKinds = new PermissionManager((sessionId, toolCallId) -> {
			asked.add(sessionId + "/" + toolCallId);
			// The fake agent reuses call-nokind for an edit in one session and an execute in another.
			return sessionId.equals("s1") ? ToolKind.EDIT : sessionId.equals("s2") ? ToolKind.EXECUTE : null;
		}, null);

		withKinds.request(consentRequest("s1", "call-nokind", "fs_write", "/w/nokind.txt"), null);
		withKinds.request(consentRequest("s2", "call-nokind", "shell", "ls"), null);
		withKinds.request(consentRequest("s3", "call-nokind", "shell", "ls"), null);
		withKinds.request(request("s2", "call-own", "read"), null);
		ObjectNode bogus = request("s1", "call-bogus", "teleport");
		withKinds.request(bogus, null);

		List<PermissionView> pending = withKinds.pending();
		assertEquals(List.of(ToolKind.EDIT, ToolKind.EXECUTE, ToolKind.OTHER, ToolKind.READ, ToolKind.EDIT),
				pending.stream().map(PermissionView::kind).toList());
		assertEquals(List.of("s1/call-nokind", "s2/call-nokind", "s3/call-nokind", "s1/call-bogus"), asked,
				"a request with a kind of its own does not ask");

		PermissionView first = pending.get(0);
		assertEquals(new PermissionView.Consent("fs_write", "/w/nokind.txt", true), first.consent());
		assertEquals(List.of(PermissionScope.KIND, PermissionScope.CAPABILITY, PermissionScope.RESOURCE),
				first.policies().stream().map(PermissionView.Policy::scope).toList());
		assertEquals("Allow all edits this session", first.policies().get(0).label());
	}

	@Test
	void aRequestWithoutKindOrKnownToolCallIsOther() {
		manager.request(request("s1", "call-1", null), null);
		assertEquals(ToolKind.OTHER, manager.pending().get(0).kind());
	}

	@Test
	void malformedRequestsAreStillAskedAbout() throws Exception {
		CompletableFuture<JsonNode> answer = manager.request(json("""
				{"sessionId":"s1","toolCall":{"toolCallId":"call-x"},
				 "options":[{"name":"no id"},{"optionId":"odd","name":"Odd","kind":"sometimes"},"text"],
				 "_meta":{"kiro":{"consent":{"capability":7}}}}"""), null);

		PermissionView view = manager.pending().get(0);
		assertEquals("call-x", view.title(), "the tool call id stands in for a missing title");
		assertNull(view.consent(), "a consent object without usable fields is none");
		assertFalse(view.hasDiff());
		assertEquals(1, view.options().size());
		assertNull(view.options().get(0).kind());
		assertNull(manager.decideRequest(view.requestId(), PolicyDecision.ALLOW));

		manager.resolve(view.requestId(), "odd");
		assertEquals("selected:odd", outcome(get(answer)));
		assertEquals(PermissionResolution.Outcome.REJECT, resolutions.get(0).outcome());

		manager.request(json("{}"), null);
		PermissionView bare = manager.pending().get(0);
		assertEquals("Permission request", bare.title());
		assertEquals("", bare.toolCallId());
		assertEquals("", manager.getEntry(bare.requestId()).sessionId());
		assertTrue(bare.options().isEmpty());
	}

	@Test
	void cancelAllAnswersThePendingRequestsOfOneSessionOrOfAll() throws Exception {
		CompletableFuture<JsonNode> a1 = manager.request(request("s1", "call-1", "edit"), null);
		CompletableFuture<JsonNode> b = manager.request(request("s2", "call-1", "edit"), null);
		CompletableFuture<JsonNode> a2 = manager.request(request("s1", "call-2", "execute"), null);
		int before = changes.get();

		manager.cancelAll("s1");

		assertEquals("cancelled", outcome(get(a1)));
		assertEquals("cancelled", outcome(get(a2)));
		assertFalse(b.isDone());
		assertEquals(1, manager.size());
		assertEquals(before + 2, changes.get());
		assertEquals(List.of("perm-1", "perm-3"), resolutions.stream().map(PermissionResolution::requestId).toList());
		assertTrue(resolutions.stream().allMatch(
				r -> r.outcome() == PermissionResolution.Outcome.CANCELLED && r.optionId() == null && r.sessionId().equals("s1")));

		manager.cancelAll("nobody");
		assertFalse(b.isDone());

		manager.cancelAll();
		assertEquals("cancelled", outcome(get(b)));
		assertEquals(0, manager.size());
	}

	@Test
	void cancellingTheTurnAnswersItsRequest() throws Exception {
		CompletableFuture<Void> cancelled = new CompletableFuture<>();
		CompletableFuture<JsonNode> answer = manager.request(request("s1", "call-1", "edit"), cancelled);
		CompletableFuture<JsonNode> other = manager.request(request("s1", "call-2", "edit"), new CompletableFuture<>());
		assertEquals(2, manager.size());

		cancelled.complete(null);

		assertEquals("cancelled", outcome(get(answer)));
		assertFalse(other.isDone());
		assertEquals(List.of("perm-2"), manager.pending().stream().map(PermissionView::requestId).toList());
		assertEquals(List.of(new PermissionResolution("perm-1", "s1", "call-1", ToolKind.EDIT,
				PermissionResolution.Outcome.CANCELLED, null)), resolutions);
	}

	@Test
	void aCancellationAfterTheAnswerChangesNothing() throws Exception {
		CompletableFuture<Void> cancelled = new CompletableFuture<>();
		CompletableFuture<JsonNode> answer = manager.request(request("s1", "call-1", "edit"), cancelled);
		manager.resolve(onlyPendingId(), "allow");
		int before = changes.get();

		cancelled.complete(null);

		assertEquals("selected:allow", outcome(get(answer)));
		assertEquals(before, changes.get());
		assertEquals(1, resolutions.size());
	}

	@Test
	void aRequestOfAnAlreadyCancelledTurnIsNeverShown() throws Exception {
		CompletableFuture<JsonNode> answer = manager.request(request("s1", "call-1", "edit"),
				CompletableFuture.completedFuture(null));

		assertTrue(answer.isDone());
		assertEquals("cancelled", outcome(get(answer)));
		assertEquals(0, manager.size());
		assertEquals(0, changes.get());
		assertTrue(resolutions.isEmpty());

		CompletableFuture<Void> failed = CompletableFuture.failedFuture(new IllegalStateException("gone"));
		assertEquals("cancelled", outcome(get(manager.request(request("s1", "call-2", "edit"), failed))));
	}

	@Test
	void aPolicyAnswersEvenARequestOfACancelledTurn() throws Exception {
		manager.request(request("s1", "call-1", "edit"), null);
		manager.setPolicy(onlyPendingId(), PolicyDecision.ALLOW);

		assertEquals("selected:allow",
				outcome(get(manager.request(request("s1", "call-2", "edit"), CompletableFuture.completedFuture(null)))));
	}

	@Test
	void queriesFindPendingRequestsByToolCallAndKindOldestFirst() {
		manager.request(request("s1", "call-a", "edit"), null);
		manager.request(request("s1", "call-b", "switch_mode"), null);
		manager.request(request("s2", "call-a", "execute"), null);
		manager.request(request("s2", "call-c", "switch_mode"), null);

		assertEquals(List.of("perm-1", "perm-2", "perm-3", "perm-4"),
				manager.pending().stream().map(PermissionView::requestId).toList());
		assertEquals("perm-1", manager.pendingForToolCall("call-a").requestId());
		assertEquals("perm-2", manager.findByKind(ToolKind.SWITCH_MODE).requestId());
		assertNull(manager.pendingForToolCall("call-z"));
		assertNull(manager.findByKind(ToolKind.FETCH));

		manager.decideRequest("perm-2", PolicyDecision.ALLOW);
		assertEquals("perm-4", manager.findByKind(ToolKind.SWITCH_MODE).requestId());
		manager.cancelAll("s2");
		assertNull(manager.findByKind(ToolKind.SWITCH_MODE));
	}

	@Test
	void listenersSeeTheNewStateBeforeTheAgentIsAnswered() throws Exception {
		List<String> seen = new ArrayList<>();
		CompletableFuture<JsonNode> answer = manager.request(request("s1", "call-1", "edit"), null);
		manager.onDidResolve(
				resolution -> seen.add("resolved pending=" + manager.size() + " answered=" + answer.isDone()));
		manager.onDidChange(() -> seen.add("changed pending=" + manager.size() + " answered=" + answer.isDone()));

		manager.decideRequest("perm-1", PolicyDecision.ALLOW);

		assertEquals(List.of("resolved pending=0 answered=false", "changed pending=0 answered=false"), seen);
		assertTrue(answer.isDone());
	}

	@Test
	void aRemovedListenerIsNotCalledAndAFailingOneDoesNotStopTheOthers() throws Exception {
		Fakes.RecordingLogger logger = new Fakes.RecordingLogger();
		PermissionManager logged = new PermissionManager(null, logger);
		AtomicInteger removed = new AtomicInteger();
		AtomicInteger kept = new AtomicInteger();
		Subscription subscription = logged.onDidChange(removed::incrementAndGet);
		Subscription resolveSubscription = logged.onDidResolve(resolution -> removed.incrementAndGet());
		logged.onDidChange(() -> {
			throw new IllegalStateException("listener");
		});
		logged.onDidResolve(resolution -> {
			throw new IllegalStateException("listener");
		});
		logged.onDidChange(kept::incrementAndGet);
		logged.onDidResolve(resolution -> kept.incrementAndGet());
		subscription.close();
		subscription.close();
		resolveSubscription.close();

		CompletableFuture<JsonNode> answer = logged.request(request("s1", "call-1", "edit"), null);
		logged.resolve("perm-1", "allow");

		assertEquals("selected:allow", outcome(get(answer)));
		assertEquals(0, removed.get());
		assertEquals(3, kept.get());
		assertTrue(logger.has("A permission change listener failed"), logger.lines.toString());
		assertTrue(logger.has("A permission resolution listener failed"), logger.lines.toString());
	}

	@Test
	void aListenerMayAnswerAnotherRequest() throws Exception {
		CompletableFuture<JsonNode> first = manager.request(request("s1", "call-1", "edit"), null);
		CompletableFuture<JsonNode> second = manager.request(request("s1", "call-2", "edit"), null);
		manager.onDidResolve(resolution -> {
			if (resolution.requestId().equals("perm-1")) {
				manager.decideRequest("perm-2", PolicyDecision.REJECT);
			}
		});

		manager.resolve("perm-1", "allow");

		assertEquals("selected:allow", outcome(get(first)));
		assertEquals("selected:reject", outcome(get(second)));
	}

	@Test
	void closingCancelsWhatIsPendingWithoutTellingListeners() throws Exception {
		CompletableFuture<JsonNode> answer = manager.request(request("s1", "call-1", "edit"), null);
		int before = changes.get();

		manager.close();
		manager.close();

		assertEquals("cancelled", outcome(get(answer)));
		assertEquals(before, changes.get());
		assertTrue(resolutions.isEmpty());
		assertEquals("cancelled", outcome(get(manager.request(request("s1", "call-2", "edit"), null))));
		assertEquals(0, manager.size());
	}

	@Test
	void requestsAndAnswersFromManyThreadsAllComplete() throws Exception {
		int threads = 8;
		int perThread = 50;
		ExecutorService pool = Executors.newFixedThreadPool(threads * 2);
		List<CompletableFuture<JsonNode>> answers = new CopyOnWriteArrayList<>();
		CountDownLatch asked = new CountDownLatch(threads);
		// Whatever becomes pending is answered from other threads, as the UI would.
		manager.onDidChange(() -> pool.execute(() -> {
			for (PermissionView view : manager.pending()) {
				manager.decideRequest(view.requestId(), PolicyDecision.ALLOW);
			}
		}));
		try {
			for (int t = 0; t < threads; t++) {
				String session = "s" + t;
				pool.execute(() -> {
					for (int i = 0; i < perThread; i++) {
						CompletableFuture<Void> cancelled = new CompletableFuture<>();
						answers.add(manager.request(request(session, "call-" + i, "edit"), cancelled));
						if (i % 5 == 0) {
							cancelled.complete(null);
						}
					}
					asked.countDown();
				});
			}
			assertTrue(asked.await(Fakes.SECONDS, TimeUnit.SECONDS));
			assertEquals(threads * perThread, answers.size());
			for (CompletableFuture<JsonNode> answer : answers) {
				String outcome = outcome(get(answer));
				assertTrue(outcome.equals("selected:allow") || outcome.equals("cancelled"), outcome);
			}
			assertEquals(0, manager.size());
			assertEquals(threads * perThread, resolutions.size());
			assertNotNull(resolutions.get(0).toolKind());
		} finally {
			pool.shutdownNow();
		}
	}
}
