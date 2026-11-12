package io.github.liaoalia.kiroacp.ui.chat;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The identity of one chat view, under which its page keeps persisted UI state.
 *
 * <p>Neither the view id nor its secondary id will do: every workbench window
 * has its own instance of the same view, and all pages share one browser
 * storage. Each instance therefore gets a random id of its own, saved in the
 * view's memento so it is the same one after a restart.
 */
public final class ViewInstanceId {

	/** Memento key the id is stored under. */
	public static final String MEMENTO_KEY = "instanceId";

	private static final Pattern VALID = Pattern.compile("[A-Za-z0-9-]{8,64}");

	private ViewInstanceId() {
	}

	/** @return the stored id when it is one this class issued, otherwise a new one */
	public static String resolve(String stored) {
		return stored != null && VALID.matcher(stored).matches() ? stored : UUID.randomUUID().toString();
	}
}
