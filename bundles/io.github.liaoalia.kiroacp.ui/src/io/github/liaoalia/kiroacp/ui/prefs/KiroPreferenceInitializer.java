package io.github.liaoalia.kiroacp.ui.prefs;

import org.eclipse.core.runtime.preferences.AbstractPreferenceInitializer;
import org.eclipse.core.runtime.preferences.DefaultScope;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;

import io.github.liaoalia.kiroacp.ui.services.PreferenceSettings;

/** The defaults of the reference's {@code kiroAcp.*} settings, for the preference page and "Restore Defaults". */
public final class KiroPreferenceInitializer extends AbstractPreferenceInitializer {

	@Override
	public void initializeDefaultPreferences() {
		IEclipsePreferences defaults = DefaultScope.INSTANCE.getNode(PreferenceSettings.QUALIFIER);
		defaults.put(PreferenceSettings.CLI_PATH, PreferenceSettings.DEFAULT_CLI_PATH);
		defaults.put(PreferenceSettings.CLI_ARGS, PreferenceSettings.DEFAULT_CLI_ARGS);
		defaults.putBoolean(PreferenceSettings.ATTACH_ACTIVE_FILE, PreferenceSettings.DEFAULT_ATTACH_ACTIVE_FILE);
		defaults.put(PreferenceSettings.AUTO_OPEN_DIFFS,
				PreferenceSettings.id(PreferenceSettings.DEFAULT_AUTO_OPEN_DIFFS));
		defaults.put(PreferenceSettings.TERMINAL_MODE, PreferenceSettings.id(PreferenceSettings.DEFAULT_TERMINAL_MODE));
		defaults.putInt(PreferenceSettings.TERMINAL_OUTPUT_BYTE_LIMIT,
				PreferenceSettings.DEFAULT_TERMINAL_OUTPUT_BYTE_LIMIT);
		defaults.putBoolean(PreferenceSettings.LOG_PROTOCOL, PreferenceSettings.DEFAULT_LOG_PROTOCOL);
		defaults.putBoolean(PreferenceSettings.SHOW_SETTINGS_BY_DEFAULT,
				PreferenceSettings.DEFAULT_SHOW_SETTINGS_BY_DEFAULT);
		defaults.putBoolean(PreferenceSettings.SESSION_PANEL_COLLAPSED,
				PreferenceSettings.DEFAULT_SESSION_PANEL_COLLAPSED);
		defaults.putBoolean(PreferenceSettings.REMEMBER_SESSION_OPTIONS,
				PreferenceSettings.DEFAULT_REMEMBER_SESSION_OPTIONS);
	}
}
