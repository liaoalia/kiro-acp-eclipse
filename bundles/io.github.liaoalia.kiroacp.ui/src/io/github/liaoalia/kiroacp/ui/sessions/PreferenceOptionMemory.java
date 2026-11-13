package io.github.liaoalia.kiroacp.ui.sessions;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.IScopeContext;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.osgi.service.prefs.BackingStoreException;
import org.osgi.service.prefs.Preferences;

import io.github.liaoalia.kiroacp.core.host.Logger;
import io.github.liaoalia.kiroacp.core.session.history.OptionMemory;
import io.github.liaoalia.kiroacp.ui.services.PreferenceSettings;

/**
 * The remembered session options in the workspace's preferences: what the
 * reference keeps in its workspace state.
 *
 * <p>They live in a node of their own below the bundle's, so that writing
 * them is no change of a setting and "Restore Defaults" of the preference
 * page leaves them alone. The value is there for every reader at once; the
 * file behind it is written from another thread.
 */
public final class PreferenceOptionMemory implements OptionMemory {

	static final String NODE = "memory";
	static final String KEY = "sessionOptions";

	private final IScopeContext scope;
	private final Logger logger;

	public PreferenceOptionMemory(Logger logger) {
		this(InstanceScope.INSTANCE, logger);
	}

	PreferenceOptionMemory(IScopeContext scope, Logger logger) {
		this.scope = Objects.requireNonNull(scope, "scope");
		this.logger = logger != null ? logger : Logger.NONE;
	}

	@Override
	public String read() {
		return node().get(KEY, null);
	}

	@Override
	public void write(String text) {
		Preferences node = node();
		if (text == null) {
			node.remove(KEY);
		} else {
			node.put(KEY, text);
		}
		// The caller is the UI thread, and this is a file.
		CompletableFuture.runAsync(() -> save(node));
	}

	/** Writes the node to the disk now. */
	void save(Preferences node) {
		try {
			node.flush();
		} catch (BackingStoreException | IllegalStateException e) {
			logger.warn("could not save the remembered session options: " + e.getMessage());
		}
	}

	Preferences node() {
		IEclipsePreferences bundle = scope.getNode(PreferenceSettings.QUALIFIER);
		return bundle.node(NODE);
	}
}
