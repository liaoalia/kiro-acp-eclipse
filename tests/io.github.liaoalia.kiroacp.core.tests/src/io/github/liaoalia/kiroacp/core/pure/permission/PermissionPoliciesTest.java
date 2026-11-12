package io.github.liaoalia.kiroacp.core.pure.permission;

import static io.github.liaoalia.kiroacp.core.pure.permission.PermissionPolicies.decide;
import static io.github.liaoalia.kiroacp.core.pure.permission.PermissionPolicies.decisionOfOption;
import static io.github.liaoalia.kiroacp.core.pure.permission.PermissionPolicies.isFsResource;
import static io.github.liaoalia.kiroacp.core.pure.permission.PermissionPolicies.looksLikePath;
import static io.github.liaoalia.kiroacp.core.pure.permission.PermissionPolicies.normalizeResource;
import static io.github.liaoalia.kiroacp.core.pure.permission.PermissionPolicies.offeredPolicies;
import static io.github.liaoalia.kiroacp.core.pure.permission.PermissionPolicies.policyKey;
import static io.github.liaoalia.kiroacp.core.pure.permission.PermissionPolicies.selectOption;
import static io.github.liaoalia.kiroacp.core.pure.permission.PolicyDecision.ALLOW;
import static io.github.liaoalia.kiroacp.core.pure.permission.PolicyDecision.REJECT;
import static io.github.liaoalia.kiroacp.core.pure.text.Platform.POSIX;
import static io.github.liaoalia.kiroacp.core.pure.text.Platform.WIN32;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import io.github.liaoalia.kiroacp.core.model.PermissionOptionView;
import io.github.liaoalia.kiroacp.core.model.PermissionOptionView.Kind;
import io.github.liaoalia.kiroacp.core.model.PermissionScope;
import io.github.liaoalia.kiroacp.core.model.PermissionView;
import io.github.liaoalia.kiroacp.core.model.ToolKind;
import io.github.liaoalia.kiroacp.core.pure.text.Platform;

/**
 * The cases of the reference's {@code test/features-permissionPolicy.test.ts}
 * (its {@code "linux"} is {@link Platform#POSIX} here), then those of the kind
 * and consent resolution the reference keeps in {@code permissions.ts}.
 */
class PermissionPoliciesTest {

	private static final List<PermissionOptionView> OPTIONS = List.of(option("once", Kind.ALLOW_ONCE),
			option("always", Kind.ALLOW_ALWAYS), option("no", Kind.REJECT_ONCE), option("never", Kind.REJECT_ALWAYS));

	private static PermissionOptionView option(String id, Kind kind) {
		return new PermissionOptionView(id, id, kind);
	}

	private static PolicySubject subject(String toolKind) {
		return new PolicySubject(toolKind, null, null);
	}

	private static PolicySubject subject(String toolKind, String capability, String resource) {
		return new PolicySubject(toolKind, capability, resource);
	}

	private static PolicySubject withResource(PolicySubject subject, String resource) {
		return new PolicySubject(subject.toolKind(), subject.capability(), resource);
	}

	@Test
	void selectOptionPrefersAllowOnceAndRejectOnce() {
		assertEquals("once", selectOption(OPTIONS, ALLOW));
		assertEquals("no", selectOption(OPTIONS, REJECT));
	}

	@Test
	void selectOptionFallsBackToAlwaysWhenOnceIsMissing() {
		List<PermissionOptionView> onlyAlways = OPTIONS.stream().filter(o -> o.kind().name().endsWith("ALWAYS"))
				.toList();
		assertEquals("always", selectOption(onlyAlways, ALLOW));
		assertEquals("never", selectOption(onlyAlways, REJECT));
	}

	@Test
	void selectOptionReturnsNullWhenNoMatchingOptionExists() {
		assertNull(selectOption(List.of(option("x", Kind.REJECT_ONCE)), ALLOW));
		assertNull(selectOption(List.of(), REJECT));
	}

	@Test
	void decideConsultsTheSessionAndKindRule() {
		PermissionPolicy policy = new PermissionPolicy();
		assertNull(decide(policy, "s1", "edit", OPTIONS));
		policy.set("s1", "kind:edit", ALLOW);
		assertEquals("once", decide(policy, "s1", "edit", OPTIONS));
		assertNull(decide(policy, "s1", "execute", OPTIONS), "other kinds unaffected");
		assertNull(decide(policy, "s2", "edit", OPTIONS), "other sessions unaffected");
		policy.set("s1", "kind:execute", REJECT);
		assertEquals("no", decide(policy, "s1", "execute", OPTIONS));
	}

	@Test
	void policyKeyBuildsKindCapabilityAndResourceKeysAndNormalisesFilePaths() {
		PolicySubject subject = subject("edit", "fs_write", "/work/src/../hello.txt");
		assertEquals("kind:edit", policyKey(PermissionScope.KIND, subject, POSIX));
		assertEquals("cap:fs_write", policyKey(PermissionScope.CAPABILITY, subject, POSIX));
		assertEquals("res:fs_write\u0000/work/hello.txt", policyKey(PermissionScope.RESOURCE, subject, POSIX));
		assertEquals("res:\u0000c:\\Work\\hello.txt",
				policyKey(PermissionScope.RESOURCE, subject("edit", null, "C:/Work/a/../hello.txt"), WIN32),
				"an fs tool kind without a capability still normalises; the capability part is empty");
		assertNull(policyKey(PermissionScope.CAPABILITY, subject("edit")), "no capability → no key");
		assertNull(policyKey(PermissionScope.RESOURCE, subject("edit")), "no resource → no key");
		// Non-path resources (a shell command) are kept verbatim.
		assertEquals("res:\u0000npm test", policyKey(PermissionScope.RESOURCE, subject("execute", null, "npm test")));
		assertEquals("res:shell\u0000npm test",
				policyKey(PermissionScope.RESOURCE, subject("execute", "shell", "npm test")));
	}

	@Test
	void theResourceKeyIsQualifiedByTheCapability() {
		// A write rule never matches a shell or read request on the same path.
		PermissionPolicy policy = new PermissionPolicy();
		PolicySubject write = subject("edit", "fs_write", "/work/deploy.sh");
		PolicySubject shell = subject("execute", "shell", "/work/deploy.sh");
		PolicySubject read = subject("read", "fs_read", "/work/deploy.sh");
		assertNotEquals(policyKey(PermissionScope.RESOURCE, shell, POSIX),
				policyKey(PermissionScope.RESOURCE, write, POSIX));
		assertNotEquals(policyKey(PermissionScope.RESOURCE, read, POSIX),
				policyKey(PermissionScope.RESOURCE, write, POSIX));

		policy.set("s1", policyKey(PermissionScope.RESOURCE, write, POSIX), ALLOW);
		assertEquals("once", decide(policy, "s1", write, OPTIONS, POSIX), "the write rule answers writes");
		assertEquals("once", decide(policy, "s1", withResource(write, "/work/x/../deploy.sh"), OPTIONS, POSIX),
				"normalised");
		assertNull(decide(policy, "s1", shell, OPTIONS, POSIX), "a shell request on the same path still asks");
		assertNull(decide(policy, "s1", read, OPTIONS, POSIX), "a read request on the same path still asks");

		policy.set("s1", policyKey(PermissionScope.RESOURCE, read, POSIX), ALLOW);
		assertEquals("once", decide(policy, "s1", read, OPTIONS, POSIX), "the read rule answers reads");
		assertNull(decide(policy, "s1", shell, OPTIONS, POSIX), "a read rule does not answer a shell request");
		PermissionPolicy policy2 = new PermissionPolicy();
		policy2.set("s1", policyKey(PermissionScope.RESOURCE, read, POSIX), ALLOW);
		assertNull(decide(policy2, "s1", write, OPTIONS, POSIX), "a read rule does not answer a write");
	}

	@Test
	void onlyFileSystemResourcesArePathNormalised() {
		// A shell command keeps its exact key and label.
		assertTrue(isFsResource(subject("edit", "fs_write", null)));
		assertTrue(isFsResource(subject("other", "fs_read", null)));
		assertFalse(isFsResource(subject("execute", "shell", null)));
		assertFalse(isFsResource(subject("edit", "shell", null)), "the capability decides when present");
		assertTrue(isFsResource(subject("edit")));
		assertTrue(isFsResource(subject("read")));
		assertTrue(isFsResource(subject("delete")));
		assertTrue(isFsResource(subject("move")));
		assertFalse(isFsResource(subject("execute")));
		assertFalse(isFsResource(subject("other")));

		String command = "/bin/echo x/../rm -rf target";
		assertEquals(command, normalizeResource(command, false, POSIX));
		assertEquals("/bin/rm -rf target", normalizeResource(command, true, POSIX), "as a path it would collapse");
		PolicySubject subject = subject("execute", "shell", command);
		assertEquals("res:shell\u0000" + command, policyKey(PermissionScope.RESOURCE, subject, POSIX), "exact key");
		List<PermissionView.Policy> offered = offeredPolicies(subject, POSIX);
		assertEquals("Allow \"" + command + "\" every time this session", offered.get(2).label(), "exact label");
		assertEquals("Reject \"" + command + "\" every time this session", offered.get(2).rejectLabel());
		// Resolved as `other` with no capability: still verbatim.
		assertEquals("res:\u0000" + command,
				policyKey(PermissionScope.RESOURCE, subject("other", null, command), POSIX));
		// A shell command that merely looks like a path with `..` is not collapsed, so two commands stay distinct.
		PermissionPolicy policy = new PermissionPolicy();
		policy.set("s1", policyKey(PermissionScope.RESOURCE, withResource(subject, "/bin/rm -rf target"), POSIX),
				ALLOW);
		assertNull(decide(policy, "s1", subject, OPTIONS, POSIX), "the dotted command is another command");
	}

	@Test
	void looksLikePathAcceptsAbsolutePathsOnly() {
		assertTrue(looksLikePath("/work/hello.txt", POSIX));
		assertTrue(looksLikePath("C:\\work\\hello.txt", WIN32));
		assertFalse(looksLikePath("hello.txt", POSIX));
		assertFalse(looksLikePath("npm test", POSIX));
		assertFalse(looksLikePath("/bin/sh -c 'x'\nrm", POSIX));
	}

	@Test
	void decidePrefersTheResourceRuleThenTheCapabilityRuleThenTheKindRule() {
		PermissionPolicy policy = new PermissionPolicy();
		PolicySubject a = subject("edit", "fs_write", "/w/a.txt");
		PolicySubject b = subject("edit", "fs_write", "/w/b.txt");
		PolicySubject c = subject("edit", "fs_delete", "/w/c.txt");

		policy.set("s1", policyKey(PermissionScope.KIND, a, POSIX), REJECT);
		assertEquals("no", decide(policy, "s1", a, OPTIONS, POSIX), "kind rule applies to a");
		assertEquals("no", decide(policy, "s1", c, OPTIONS, POSIX), "kind rule applies to c");

		policy.set("s1", policyKey(PermissionScope.CAPABILITY, a, POSIX), ALLOW);
		assertEquals("once", decide(policy, "s1", a, OPTIONS, POSIX), "capability beats kind for a");
		assertEquals("once", decide(policy, "s1", b, OPTIONS, POSIX), "capability beats kind for b");
		assertEquals("no", decide(policy, "s1", c, OPTIONS, POSIX), "other capability still falls back to kind");

		policy.set("s1", policyKey(PermissionScope.RESOURCE, b, POSIX), REJECT);
		assertEquals("no", decide(policy, "s1", b, OPTIONS, POSIX), "resource beats capability for b");
		assertEquals("once", decide(policy, "s1", a, OPTIONS, POSIX), "a is unaffected by b's resource rule");
		assertEquals("no", decide(policy, "s1", withResource(b, "/w/x/../b.txt"), OPTIONS, POSIX),
				"resource keys are normalised");
		assertNull(decide(policy, "s2", b, OPTIONS, POSIX), "other session unaffected");
	}

	@Test
	void aResourceRuleWithoutConsentFieldsNeverMatchesAndAKindOnlyRequestUsesTheKindRule() {
		PermissionPolicy policy = new PermissionPolicy();
		policy.set("s1", "res:fs_write\u0000/w/a.txt", ALLOW);
		assertNull(decide(policy, "s1", subject("edit"), OPTIONS, POSIX));
		policy.set("s1", "cap:fs_write", ALLOW);
		assertNull(decide(policy, "s1", subject("edit"), OPTIONS, POSIX));
		policy.set("s1", "kind:edit", REJECT);
		assertEquals("no", decide(policy, "s1", subject("edit"), OPTIONS, POSIX));
	}

	@Test
	void clearRemovesOneSessionOfEveryScopeOrAll() {
		PermissionPolicy policy = new PermissionPolicy();
		policy.set("s1", "kind:edit", ALLOW);
		policy.set("s1", "cap:fs_write", ALLOW);
		policy.set("s1", "res:/w/a.txt", REJECT);
		policy.set("s2", "kind:edit", ALLOW);
		policy.clear("s1");
		assertNull(policy.get("s1", "kind:edit"));
		assertNull(policy.get("s1", "cap:fs_write"));
		assertNull(policy.get("s1", "res:/w/a.txt"));
		assertEquals(ALLOW, policy.get("s2", "kind:edit"));
		policy.clear();
		assertEquals(0, policy.size());
	}

	@Test
	void decisionOfOptionMapsOptionIdsBackToAllowOrReject() {
		assertEquals(ALLOW, decisionOfOption(OPTIONS, "once"));
		assertEquals(ALLOW, decisionOfOption(OPTIONS, "always"));
		assertEquals(REJECT, decisionOfOption(OPTIONS, "no"));
		assertEquals(REJECT, decisionOfOption(OPTIONS, "never"));
		assertNull(decisionOfOption(OPTIONS, "nope"));
	}

	private static List<List<Object>> rows(List<PermissionView.Policy> policies) {
		return policies.stream().map(p -> List.<Object>of(p.scope(), p.label(), p.rejectLabel())).toList();
	}

	private static List<List<Object>> kindRow(String noun) {
		return List.of(List.of(PermissionScope.KIND, "Allow all " + noun + " this session",
				"Reject all " + noun + " this session"));
	}

	@Test
	void offeredPoliciesKindLabelsUseTheToolKindNoun() {
		String[][] nouns = { { "edit", "edits" }, { "read", "reads" }, { "execute", "commands" },
				{ "delete", "deletions" }, { "move", "moves" }, { "search", "searches" }, { "fetch", "fetches" },
				{ "switch_mode", "mode switches" }, { "think", "thoughts" }, { "other", "actions of this kind" } };
		for (String[] pair : nouns) {
			assertEquals(kindRow(pair[1]), rows(offeredPolicies(subject(pair[0]))), pair[0]);
		}
	}

	@Test
	void offeredPoliciesCapabilityAndFileResourceAddTheirOwnEntries() {
		List<PermissionView.Policy> offered = offeredPolicies(subject("edit", "fs_write", "/work/src/hello.txt"),
				POSIX);
		assertEquals(List.of(PermissionScope.KIND, PermissionScope.CAPABILITY, PermissionScope.RESOURCE),
				offered.stream().map(PermissionView.Policy::scope).toList(), "general to specific");
		assertEquals("Allow all fs_write this session", offered.get(1).label());
		assertEquals("Reject all fs_write this session", offered.get(1).rejectLabel());
		assertEquals("Allow all changes to hello.txt this session", offered.get(2).label());
		assertEquals("Reject all changes to hello.txt this session", offered.get(2).rejectLabel());

		List<PermissionView.Policy> win = offeredPolicies(subject("edit", null, "C:\\Work\\tasks.json"), WIN32);
		assertEquals("Allow all changes to tasks.json this session", win.get(1).label());
	}

	@Test
	void offeredPoliciesANonPathResourceIsQuotedVerbatimAndACapabilityEqualToTheKindIsSkipped() {
		List<PermissionView.Policy> offered = offeredPolicies(subject("execute", "execute", "npm test"));
		assertEquals(
				List.of(List.of(PermissionScope.KIND, "Allow all commands this session",
						"Reject all commands this session"),
						List.of(PermissionScope.RESOURCE, "Allow \"npm test\" every time this session",
								"Reject \"npm test\" every time this session")),
				rows(offered));
		assertEquals(List.of(PermissionScope.KIND),
				offeredPolicies(subject("edit", "  ", null)).stream().map(PermissionView.Policy::scope).toList(),
				"a blank capability offers nothing");
	}

	private static List<String> resourceLabels(PolicySubject subject) {
		PermissionView.Policy resource = offeredPolicies(subject, POSIX).stream()
				.filter(p -> p.scope() == PermissionScope.RESOURCE).findFirst().orElseThrow();
		return List.of(resource.label(), resource.rejectLabel());
	}

	@Test
	void offeredPoliciesTheResourceLabelIsWordedByCapability() {
		assertEquals(
				List.of("Allow all changes to deploy.sh this session", "Reject all changes to deploy.sh this session"),
				resourceLabels(subject("edit", "fs_write", "/w/deploy.sh")));
		assertEquals(List.of("Allow reading deploy.sh this session", "Reject reading deploy.sh this session"),
				resourceLabels(subject("read", "fs_read", "/w/deploy.sh")));
		assertEquals(List.of("Allow reading deploy.sh this session", "Reject reading deploy.sh this session"),
				resourceLabels(subject("read", null, "/w/deploy.sh")));
		// A shell command whose resource is the same path is quoted verbatim, never "changes to".
		assertEquals(
				List.of("Allow \"/w/deploy.sh\" every time this session",
						"Reject \"/w/deploy.sh\" every time this session"),
				resourceLabels(subject("execute", "shell", "/w/deploy.sh")));
		assertEquals(
				List.of("Allow \"npm test\" every time this session", "Reject \"npm test\" every time this session"),
				resourceLabels(subject("execute", null, "npm test")));
		// Anything else names the capability and the basename (a path) or the resource.
		assertEquals(List.of("Allow mcp_tool on /w/deploy.sh this session", "Reject mcp_tool on /w/deploy.sh this session"),
				resourceLabels(subject("other", "mcp_tool", "/w/deploy.sh")));
		assertEquals(List.of("Allow delete on deploy.sh this session", "Reject delete on deploy.sh this session"),
				resourceLabels(subject("delete", null, "/w/deploy.sh")));
		assertEquals(
				List.of("Allow network on https://example.com this session",
						"Reject network on https://example.com this session"),
				resourceLabels(subject("other", "network", "https://example.com")));
	}

	// --- beyond the reference's test file ---

	@Test
	void anEmptyCapabilityOrResourceCountsAsAbsent() {
		assertNull(policyKey(PermissionScope.CAPABILITY, subject("edit", "", "")));
		assertNull(policyKey(PermissionScope.RESOURCE, subject("edit", "", "")));
		assertEquals(List.of(PermissionScope.KIND),
				offeredPolicies(subject("edit", "", " \n")).stream().map(PermissionView.Policy::scope).toList());
		assertNull(policyKey(null, subject("edit")));
	}

	@Test
	void aQuotedCommandIsEscapedLikeJson() {
		assertEquals(List.of("Allow \"echo \\\"a\\\"\\n\" every time this session",
				"Reject \"echo \\\"a\\\"\\n\" every time this session"),
				resourceLabels(subject("execute", "shell", "echo \"a\"\n")));
	}

	@Test
	void anOptionOfAnUnknownKindIsNeverSelectedAndCountsAsReject() {
		List<PermissionOptionView> options = List.of(new PermissionOptionView("odd", "Odd", null));
		assertNull(selectOption(options, ALLOW));
		assertNull(selectOption(options, REJECT));
		assertEquals(REJECT, decisionOfOption(options, "odd"));
	}

	@Test
	void theKindIsTheRequestsOwnElseTheKnownToolCallsElseOther() {
		JsonNodeFactory json = JsonNodeFactory.instance;
		assertEquals(ToolKind.EDIT, PermissionPolicies.resolveKind(json.textNode("edit"), ToolKind.READ));
		assertEquals(ToolKind.SWITCH_MODE, PermissionPolicies.resolveKind(json.textNode("switch_mode"), null));
		assertEquals(ToolKind.READ, PermissionPolicies.resolveKind(null, ToolKind.READ), "Kiro sends no kind");
		assertEquals(ToolKind.READ, PermissionPolicies.resolveKind(json.missingNode(), ToolKind.READ));
		assertEquals(ToolKind.READ, PermissionPolicies.resolveKind(json.nullNode(), ToolKind.READ));
		assertEquals(ToolKind.READ, PermissionPolicies.resolveKind(json.textNode("teleport"), ToolKind.READ),
				"not an ACP kind");
		assertEquals(ToolKind.READ, PermissionPolicies.resolveKind(json.textNode("EDIT"), ToolKind.READ));
		assertEquals(ToolKind.OTHER, PermissionPolicies.resolveKind(json.numberNode(1), null));
		for (ToolKind kind : ToolKind.values()) {
			assertEquals(kind, PermissionPolicies.toolKindOf(PolicySubject.ofKind(kind).toolKind()));
		}
	}

	@Test
	void theSubjectCarriesTheConsentOfTheRequestMeta() throws Exception {
		JsonNode meta = new ObjectMapper().readTree(
				"{\"kiro\":{\"consent\":{\"capability\":\"fs_write\",\"resource\":\"/w/a.txt\",\"persistableConsent\":true}}}");
		PermissionView.Consent consent = PermissionPolicies.consentOf(meta);
		assertEquals(new PermissionView.Consent("fs_write", "/w/a.txt", true), consent);
		assertEquals(new PolicySubject("edit", "fs_write", "/w/a.txt"), PolicySubject.of(ToolKind.EDIT, consent));
		assertEquals(new PolicySubject("execute", null, null), PolicySubject.of(ToolKind.EXECUTE, null));

		ObjectMapper mapper = new ObjectMapper();
		assertNull(PermissionPolicies.consentOf(null));
		assertNull(PermissionPolicies.consentOf(mapper.readTree("\"x\"")));
		assertNull(PermissionPolicies.consentOf(mapper.readTree("{\"kiro\":[]}")));
		assertNull(PermissionPolicies.consentOf(mapper.readTree("{\"kiro\":{\"consent\":null}}")));
		assertEquals(new PermissionView.Consent(null, null, null),
				PermissionPolicies.consentOf(mapper.readTree(
						"{\"kiro\":{\"consent\":{\"capability\":7,\"resource\":[],\"persistableConsent\":\"yes\"}}}")),
				"fields of another type are dropped");
	}
}
