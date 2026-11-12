package io.github.liaoalia.kiroacp.core.pure.permission;

import java.util.HashMap;
import java.util.Map;

import io.github.liaoalia.kiroacp.core.model.PermissionScope;
import io.github.liaoalia.kiroacp.core.pure.text.Platform;

/**
 * Remembered decisions keyed by {@code (sessionId, scope key)}; see
 * {@link PermissionPolicies#policyKey}.
 *
 * <p>Synchronized: requests are answered on the connection's reader thread
 * while the user's choices arrive from the UI.
 */
public final class PermissionPolicy {

	private final Map<String, PolicyDecision> rules = new HashMap<>();

	private static String ruleKey(String sessionId, String key) {
		return sessionId + "\u0000" + key;
	}

	/** Record {@code decision} for every later request of {@code sessionId} matching {@code key}. */
	public synchronized void set(String sessionId, String key, PolicyDecision decision) {
		rules.put(ruleKey(sessionId, key), decision);
	}

	/** The remembered decision for one key, or {@code null}. */
	public synchronized PolicyDecision get(String sessionId, String key) {
		return rules.get(ruleKey(sessionId, key));
	}

	/** {@link #lookup(String, PolicySubject, Platform)} for the current platform. */
	public PolicyDecision lookup(String sessionId, PolicySubject subject) {
		return lookup(sessionId, subject, null);
	}

	/**
	 * The remembered decision for {@code subject}, consulting the resource, then
	 * the capability, then the kind rule; {@code null} when there is none.
	 *
	 * @param platform {@code null} for the current platform
	 */
	public synchronized PolicyDecision lookup(String sessionId, PolicySubject subject, Platform platform) {
		for (PermissionScope scope : PermissionPolicies.SCOPE_PRECEDENCE) {
			String key = PermissionPolicies.policyKey(scope, subject, platform);
			if (key == null) {
				continue;
			}
			PolicyDecision decision = get(sessionId, key);
			if (decision != null) {
				return decision;
			}
		}
		return null;
	}

	/** Forget the rules (of every scope) of one session. */
	public synchronized void clear(String sessionId) {
		String prefix = sessionId + "\u0000";
		rules.keySet().removeIf(key -> key.startsWith(prefix));
	}

	/** Forget the rules of every session. */
	public synchronized void clear() {
		rules.clear();
	}

	/** Number of remembered rules (for tests and diagnostics). */
	public synchronized int size() {
		return rules.size();
	}
}
