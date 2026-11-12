package io.github.liaoalia.kiroacp.core.pure.permission;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.liaoalia.kiroacp.core.model.PermissionOptionView;
import io.github.liaoalia.kiroacp.core.model.PermissionScope;
import io.github.liaoalia.kiroacp.core.model.PermissionView;
import io.github.liaoalia.kiroacp.core.model.ToolKind;
import io.github.liaoalia.kiroacp.core.pure.text.JsText;
import io.github.liaoalia.kiroacp.core.pure.text.NodePath;
import io.github.liaoalia.kiroacp.core.pure.text.Platform;
import io.github.liaoalia.kiroacp.core.pure.text.TextRange;

/**
 * Client-side permission policy (D6, D23 of the reference).
 *
 * <p>"Always allow / always reject for this session" is remembered per session
 * under one of three scopes: the tool kind ({@code kind:<kind>}), the Kiro
 * consent capability ({@code cap:<capability>}) or the exact consent resource
 * qualified by its capability ({@code res:<capability>\0<resource>}, so a rule
 * for writing a file never answers a request to run or read it). A rule
 * auto-selects one of the options the agent supplied for later requests. The
 * most specific rule wins (resource, then capability, then kind). The policy
 * never invents option ids: when the agent offers no matching option the
 * request falls through to the user.
 *
 * <p>Methods without a {@link Platform} parameter use the current platform, as
 * does a {@code null} platform.
 */
public final class PermissionPolicies {

	/** Scopes from the most to the least specific; {@link #decide} consults them in this order. */
	public static final List<PermissionScope> SCOPE_PRECEDENCE = List.of(PermissionScope.RESOURCE,
			PermissionScope.CAPABILITY, PermissionScope.KIND);

	/** Plural nouns for the tool-kind labels ("Allow all edits this session"). */
	private static final Map<String, String> KIND_NOUNS = Map.of("edit", "edits", "read", "reads", "execute",
			"commands", "delete", "deletions", "move", "moves", "search", "searches", "fetch", "fetches",
			"switch_mode", "mode switches", "think", "thoughts", "other", "actions of this kind");

	/** Consent capabilities whose resource is a file-system path. */
	private static final Set<String> FS_CAPABILITIES = Set.of("fs_read", "fs_write");

	/** Tool kinds whose resource is a file-system path (when the capability does not say). */
	private static final Set<String> FS_TOOL_KINDS = Set.of("read", "edit", "delete", "move");

	private PermissionPolicies() {
	}

	// --- kind resolution and consent (D23) ---------------------------------

	/** The tool kind a JSON value names, or {@code null} when it is absent or not an ACP tool kind. */
	public static ToolKind toolKindOf(JsonNode kind) {
		return kind != null && kind.isTextual() ? toolKindOf(kind.textValue()) : null;
	}

	/** The tool kind of a wire name, or {@code null} when it is not an ACP tool kind. */
	public static ToolKind toolKindOf(String kind) {
		if (kind == null) {
			return null;
		}
		for (ToolKind candidate : ToolKind.values()) {
			if (PolicySubject.wireName(candidate).equals(kind)) {
				return candidate;
			}
		}
		return null;
	}

	/**
	 * The kind a permission request is keyed and labelled by: the request's own
	 * ({@code toolCall.kind}), else the kind of the tool call it refers to in the
	 * request's session, else {@code other}. The real Kiro CLI sends none on the
	 * request, which would otherwise land every rule on {@code other}.
	 *
	 * @param requestKind {@code toolCall.kind} of the request; may be {@code null} or missing
	 * @param knownKind   kind of the already-known tool call of the same session, or {@code null}
	 */
	public static ToolKind resolveKind(JsonNode requestKind, ToolKind knownKind) {
		ToolKind own = toolKindOf(requestKind);
		if (own != null) {
			return own;
		}
		return knownKind != null ? knownKind : ToolKind.OTHER;
	}

	/**
	 * The Kiro consent context of a request: {@code _meta.kiro.consent}, keeping
	 * only fields of the expected type.
	 *
	 * @param meta the request's {@code _meta}; may be {@code null} or missing
	 * @return {@code null} when the request carries no consent object
	 */
	public static PermissionView.Consent consentOf(JsonNode meta) {
		if (meta == null || !meta.isObject()) {
			return null;
		}
		JsonNode kiro = meta.get("kiro");
		if (kiro == null || !kiro.isObject()) {
			return null;
		}
		JsonNode consent = kiro.get("consent");
		if (consent == null || !consent.isObject()) {
			return null;
		}
		JsonNode capability = consent.get("capability");
		JsonNode resource = consent.get("resource");
		JsonNode persistable = consent.get("persistableConsent");
		return new PermissionView.Consent(capability != null && capability.isTextual() ? capability.textValue() : null,
				resource != null && resource.isTextual() ? resource.textValue() : null,
				persistable != null && persistable.isBoolean() ? persistable.booleanValue() : null);
	}

	// --- keys ---------------------------------------------------------------

	public static boolean looksLikePath(String resource) {
		return looksLikePath(resource, null);
	}

	/** True when a consent resource names a file: an absolute path without line breaks. */
	public static boolean looksLikePath(String resource, Platform platform) {
		if (resource.indexOf('\r') >= 0 || resource.indexOf('\n') >= 0) {
			return false;
		}
		return TextRange.isAbsolutePath(resource, platform);
	}

	/**
	 * True when the consent resource of {@code subject} names a file-system
	 * entry: the capability is {@code fs_read}/{@code fs_write}, or (without a
	 * capability) the tool kind is a file operation. Only such resources are
	 * path-normalised; a shell command stays verbatim however path-like it looks.
	 */
	public static boolean isFsResource(PolicySubject subject) {
		String capability = trimmed(subject.capability());
		if (!capability.isEmpty()) {
			return FS_CAPABILITIES.contains(capability);
		}
		return FS_TOOL_KINDS.contains(subject.toolKind());
	}

	public static String normalizeResource(String resource, boolean fsResource) {
		return normalizeResource(resource, fsResource, null);
	}

	/**
	 * Canonical form of a resource for the {@code res:} scope: an absolute path
	 * of a file-system subject is normalised with {@link TextRange#normalizeFsPath};
	 * everything else (a shell command, a relative path, any non-fs resource) is
	 * kept verbatim.
	 */
	public static String normalizeResource(String resource, boolean fsResource, Platform platform) {
		return fsResource && looksLikePath(resource, platform) ? TextRange.normalizeFsPath(resource, platform)
				: resource;
	}

	public static String policyKey(PermissionScope scope, PolicySubject subject) {
		return policyKey(scope, subject, null);
	}

	/**
	 * The rule key of {@code scope} for {@code subject} ({@code kind:edit},
	 * {@code cap:fs_write}, {@code res:fs_write\0/abs/path}), or {@code null}
	 * when the subject lacks that field. The resource key carries the capability
	 * (empty when absent) so a rule remembered for one capability never matches
	 * another on the same path.
	 */
	public static String policyKey(PermissionScope scope, PolicySubject subject, Platform platform) {
		if (scope == null) {
			return null;
		}
		switch (scope) {
		case KIND:
			return "kind:" + subject.toolKind();
		case CAPABILITY:
			return subject.hasCapability() ? "cap:" + subject.capability() : null;
		case RESOURCE: {
			if (!subject.hasResource()) {
				return null;
			}
			String resource = normalizeResource(subject.resource(), isFsResource(subject), platform);
			return "res:" + (subject.capability() == null ? "" : subject.capability()) + "\u0000" + resource;
		}
		default:
			return null;
		}
	}

	// --- options ------------------------------------------------------------

	/**
	 * Pick the option id that implements {@code decision}: allow →
	 * {@code allow_once} (else {@code allow_always}); reject → {@code reject_once}
	 * (else {@code reject_always}). Returns {@code null} when the agent offered
	 * no such option.
	 */
	public static String selectOption(List<PermissionOptionView> options, PolicyDecision decision) {
		List<PermissionOptionView.Kind> preferred = decision == PolicyDecision.ALLOW
				? List.of(PermissionOptionView.Kind.ALLOW_ONCE, PermissionOptionView.Kind.ALLOW_ALWAYS)
				: List.of(PermissionOptionView.Kind.REJECT_ONCE, PermissionOptionView.Kind.REJECT_ALWAYS);
		for (PermissionOptionView.Kind kind : preferred) {
			for (PermissionOptionView option : options) {
				if (option.kind() == kind) {
					return option.optionId();
				}
			}
		}
		return null;
	}

	/** {@link #decide(PermissionPolicy, String, PolicySubject, List, Platform)} for a request known by its tool kind only. */
	public static String decide(PermissionPolicy policy, String sessionId, String toolKind,
			List<PermissionOptionView> options) {
		return decide(policy, sessionId, PolicySubject.ofKind(toolKind), options, null);
	}

	public static String decide(PermissionPolicy policy, String sessionId, PolicySubject subject,
			List<PermissionOptionView> options) {
		return decide(policy, sessionId, subject, options, null);
	}

	/**
	 * Consult {@code policy} for a request: returns the option id to auto-select,
	 * or {@code null} when the user must be asked (no rule, or no matching option).
	 */
	public static String decide(PermissionPolicy policy, String sessionId, PolicySubject subject,
			List<PermissionOptionView> options, Platform platform) {
		PolicyDecision decision = policy.lookup(sessionId, subject, platform);
		if (decision == null) {
			return null;
		}
		return selectOption(options, decision);
	}

	/** Map a chosen option id back to allow/reject ({@code null} for unknown ids). */
	public static PolicyDecision decisionOfOption(List<PermissionOptionView> options, String optionId) {
		for (PermissionOptionView option : options) {
			if (option.optionId() != null && option.optionId().equals(optionId)) {
				return option.kind() == PermissionOptionView.Kind.ALLOW_ONCE
						|| option.kind() == PermissionOptionView.Kind.ALLOW_ALWAYS ? PolicyDecision.ALLOW
								: PolicyDecision.REJECT;
			}
		}
		return null;
	}

	// --- offered policies ---------------------------------------------------

	public static List<PermissionView.Policy> offeredPolicies(PolicySubject subject) {
		return offeredPolicies(subject, null);
	}

	/**
	 * The session-wide policies worth offering for a request, with labels, most
	 * general first: always the tool kind; the consent capability when it says
	 * something the kind does not; the consent resource when present, worded by
	 * the capability (else the kind): "Allow all changes to &lt;basename&gt;" for
	 * {@code fs_write}/{@code edit}, "Allow reading &lt;basename&gt;" for
	 * {@code fs_read}/{@code read}, {@code Allow "<command>" every time} for
	 * {@code shell}/{@code execute}, and "Allow &lt;capability&gt; on
	 * &lt;basename|resource&gt;" for anything else.
	 */
	public static List<PermissionView.Policy> offeredPolicies(PolicySubject subject, Platform platform) {
		List<PermissionView.Policy> out = new ArrayList<>();
		String noun = KIND_NOUNS.getOrDefault(subject.toolKind(), KIND_NOUNS.get("other"));
		out.add(new PermissionView.Policy(PermissionScope.KIND, "Allow all " + noun + " this session",
				"Reject all " + noun + " this session"));
		String capability = trimmed(subject.capability());
		if (!capability.isEmpty() && !capability.equals(subject.toolKind())) {
			out.add(new PermissionView.Policy(PermissionScope.CAPABILITY, "Allow all " + capability + " this session",
					"Reject all " + capability + " this session"));
		}
		String resource = subject.resource();
		if (resource != null && !JsText.trim(resource).isEmpty()) {
			String[] labels = resourceLabels(subject, capability, resource, platform);
			out.add(new PermissionView.Policy(PermissionScope.RESOURCE, labels[0] + " this session",
					labels[1] + " this session"));
		}
		return out;
	}

	/** Allow / reject labels (without the "this session" suffix) of the resource policy, worded by capability. */
	private static String[] resourceLabels(PolicySubject subject, String capability, String resource,
			Platform platform) {
		String what = capability.isEmpty() ? subject.toolKind() : capability;
		String name = isFsResource(subject) && looksLikePath(resource, platform) ? basenameOf(resource, platform)
				: resource;
		switch (what) {
		case "fs_write":
		case "edit":
			return new String[] { "Allow all changes to " + name, "Reject all changes to " + name };
		case "fs_read":
		case "read":
			return new String[] { "Allow reading " + name, "Reject reading " + name };
		case "shell":
		case "execute": {
			// The command itself is the rule, so it is shown whole, quoted and escaped.
			String quoted = JsText.jsonQuote(resource);
			return new String[] { "Allow " + quoted + " every time", "Reject " + quoted + " every time" };
		}
		default:
			return new String[] { "Allow " + what + " on " + name, "Reject " + what + " on " + name };
		}
	}

	private static String basenameOf(String p, Platform platform) {
		String base = NodePath.basename(p, platform);
		return base.isEmpty() ? p : base;
	}

	private static String trimmed(String s) {
		return s == null ? "" : JsText.trim(s);
	}
}
