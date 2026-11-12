package io.github.liaoalia.kiroacp.core.pure.permission;

import java.util.Locale;

import io.github.liaoalia.kiroacp.core.model.PermissionView;
import io.github.liaoalia.kiroacp.core.model.ToolKind;

/**
 * What a permission request is about: the inputs of every policy scope.
 *
 * @param toolKind   the ACP tool kind as sent on the wire ({@code edit}, {@code execute}, …)
 * @param capability {@code _meta.kiro.consent.capability} (e.g. {@code fs_write}, {@code shell});
 *                   {@code null} or empty when absent
 * @param resource   {@code _meta.kiro.consent.resource} verbatim (a file path, a shell command, …);
 *                   {@code null} or empty when absent
 */
public record PolicySubject(String toolKind, String capability, String resource) {

	/** A request known by its tool kind only. */
	public static PolicySubject ofKind(String toolKind) {
		return new PolicySubject(toolKind, null, null);
	}

	/** A request known by its tool kind only. */
	public static PolicySubject ofKind(ToolKind toolKind) {
		return ofKind(wireName(toolKind));
	}

	/** The subject of a request of {@code toolKind} with the Kiro consent context it carried, if any. */
	public static PolicySubject of(ToolKind toolKind, PermissionView.Consent consent) {
		return new PolicySubject(wireName(toolKind), consent == null ? null : consent.capability(),
				consent == null ? null : consent.resource());
	}

	/** The kind as ACP spells it; the enum constants are named after their wire names. */
	static String wireName(ToolKind toolKind) {
		return toolKind.name().toLowerCase(Locale.ROOT);
	}

	boolean hasCapability() {
		return capability != null && !capability.isEmpty();
	}

	boolean hasResource() {
		return resource != null && !resource.isEmpty();
	}
}
