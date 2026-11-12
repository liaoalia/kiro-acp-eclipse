package io.github.liaoalia.kiroacp.ui.services;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;

import org.eclipse.core.runtime.preferences.DefaultScope;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.IEclipsePreferences.IPreferenceChangeListener;
import org.eclipse.core.runtime.preferences.IScopeContext;
import org.eclipse.core.runtime.preferences.InstanceScope;

import io.github.liaoalia.kiroacp.core.host.Settings;
import io.github.liaoalia.kiroacp.core.host.Subscription;

/**
 * The plugin's workspace preferences as the core's {@link Settings}.
 *
 * <p>Keys and stored values are those of the reference's {@code kiroAcp.*}
 * settings, without the prefix. A value is looked up in the instance scope,
 * then in the default scope (which a product may customise), and a value
 * that cannot be understood counts as missing; when both fail the built-in
 * default answers. So a preference file edited by hand can never stop the
 * agent from starting.
 *
 * <p>Callable from any thread.
 */
public final class PreferenceSettings implements Settings {

	/** The preference node: the UI bundle's id. */
	public static final String QUALIFIER = "io.github.liaoalia.kiroacp.ui";

	public static final String CLI_PATH = "cliPath";
	/** One line, split by {@link CliArguments#split}. */
	public static final String CLI_ARGS = "cliArgs";
	public static final String ATTACH_ACTIVE_FILE = "attachActiveFile";
	public static final String AUTO_OPEN_DIFFS = "autoOpenDiffs";
	public static final String TERMINAL_MODE = "terminal.mode";
	public static final String TERMINAL_OUTPUT_BYTE_LIMIT = "terminal.outputByteLimit";
	public static final String LOG_PROTOCOL = "logProtocol";
	public static final String SHOW_SETTINGS_BY_DEFAULT = "showSettingsByDefault";
	public static final String SESSION_PANEL_COLLAPSED = "sessionPanelCollapsed";
	public static final String REMEMBER_SESSION_OPTIONS = "rememberSessionOptions";

	public static final String DEFAULT_CLI_PATH = "kiro-cli";
	public static final String DEFAULT_CLI_ARGS = "";
	public static final boolean DEFAULT_ATTACH_ACTIVE_FILE = false;
	public static final AutoOpenDiffs DEFAULT_AUTO_OPEN_DIFFS = AutoOpenDiffs.WHEN_PERMISSION_REQUIRED;
	public static final TerminalMode DEFAULT_TERMINAL_MODE = TerminalMode.SHARED;
	public static final int DEFAULT_TERMINAL_OUTPUT_BYTE_LIMIT = 1_048_576;
	public static final boolean DEFAULT_LOG_PROTOCOL = false;
	public static final boolean DEFAULT_SHOW_SETTINGS_BY_DEFAULT = false;
	public static final boolean DEFAULT_SESSION_PANEL_COLLAPSED = false;
	public static final boolean DEFAULT_REMEMBER_SESSION_OPTIONS = true;

	private final IScopeContext instanceScope;
	private final IScopeContext defaultScope;

	public PreferenceSettings() {
		this(InstanceScope.INSTANCE, DefaultScope.INSTANCE);
	}

	PreferenceSettings(IScopeContext instanceScope, IScopeContext defaultScope) {
		this.instanceScope = Objects.requireNonNull(instanceScope, "instanceScope");
		this.defaultScope = Objects.requireNonNull(defaultScope, "defaultScope");
	}

	/** @return the stored value for the mode, as in the reference's setting */
	public static String id(TerminalMode mode) {
		return switch (mode) {
			case SHARED -> "shared";
			case PER_COMMAND -> "perCommand";
			case HIDDEN -> "hidden";
		};
	}

	/** @return the stored value for the choice, as in the reference's setting */
	public static String id(AutoOpenDiffs choice) {
		return switch (choice) {
			case ALWAYS -> "always";
			case WHEN_PERMISSION_REQUIRED -> "whenPermissionRequired";
			case NEVER -> "never";
		};
	}

	@Override
	public String cliPath() {
		return read(CLI_PATH, value -> value.isBlank() ? null : value.strip(), DEFAULT_CLI_PATH);
	}

	@Override
	public List<String> cliArgs() {
		return read(CLI_ARGS, CliArguments::split, List.of());
	}

	@Override
	public TerminalMode terminalMode() {
		return read(TERMINAL_MODE, PreferenceSettings::terminalMode, DEFAULT_TERMINAL_MODE);
	}

	@Override
	public int terminalOutputByteLimit() {
		return read(TERMINAL_OUTPUT_BYTE_LIMIT, PreferenceSettings::positiveInt, DEFAULT_TERMINAL_OUTPUT_BYTE_LIMIT);
	}

	@Override
	public boolean logProtocol() {
		return flag(LOG_PROTOCOL, DEFAULT_LOG_PROTOCOL);
	}

	@Override
	public AutoOpenDiffs autoOpenDiffs() {
		return read(AUTO_OPEN_DIFFS, PreferenceSettings::autoOpenDiffs, DEFAULT_AUTO_OPEN_DIFFS);
	}

	@Override
	public boolean attachActiveFile() {
		return flag(ATTACH_ACTIVE_FILE, DEFAULT_ATTACH_ACTIVE_FILE);
	}

	@Override
	public boolean showSettingsByDefault() {
		return flag(SHOW_SETTINGS_BY_DEFAULT, DEFAULT_SHOW_SETTINGS_BY_DEFAULT);
	}

	@Override
	public boolean sessionPanelCollapsed() {
		return flag(SESSION_PANEL_COLLAPSED, DEFAULT_SESSION_PANEL_COLLAPSED);
	}

	@Override
	public boolean rememberSessionOptions() {
		return flag(REMEMBER_SESSION_OPTIONS, DEFAULT_REMEMBER_SESSION_OPTIONS);
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>The listener runs on the thread that changed the preference, once per
	 * changed value. Changes of the default scope are not reported.
	 */
	@Override
	public Subscription onChange(Runnable listener) {
		Objects.requireNonNull(listener, "listener");
		IEclipsePreferences node = instanceScope.getNode(QUALIFIER);
		IPreferenceChangeListener nodeListener = event -> listener.run();
		node.addPreferenceChangeListener(nodeListener);
		return () -> {
			try {
				node.removePreferenceChangeListener(nodeListener);
			} catch (IllegalStateException nodeRemoved) {
				// The node went away, and its listeners with it.
			}
		};
	}

	private boolean flag(String key, boolean fallback) {
		return read(key, PreferenceSettings::flag, fallback);
	}

	/** @param parser returns null for a value it does not understand */
	private <T> T read(String key, Function<String, T> parser, T fallback) {
		T value = parse(instanceScope, key, parser);
		if (value == null) {
			value = parse(defaultScope, key, parser);
		}
		return value != null ? value : fallback;
	}

	private static <T> T parse(IScopeContext scope, String key, Function<String, T> parser) {
		String stored;
		try {
			stored = scope.getNode(QUALIFIER).get(key, null);
		} catch (IllegalStateException nodeRemoved) {
			return null;
		}
		return stored == null ? null : parser.apply(stored);
	}

	private static Boolean flag(String stored) {
		return switch (stored.strip().toLowerCase(Locale.ROOT)) {
			case "true" -> Boolean.TRUE;
			case "false" -> Boolean.FALSE;
			default -> null;
		};
	}

	private static Integer positiveInt(String stored) {
		try {
			int value = Integer.parseInt(stored.strip());
			return value > 0 ? value : null;
		} catch (NumberFormatException notANumber) {
			return null;
		}
	}

	private static TerminalMode terminalMode(String stored) {
		for (TerminalMode mode : TerminalMode.values()) {
			if (id(mode).equals(stored.strip())) {
				return mode;
			}
		}
		return null;
	}

	private static AutoOpenDiffs autoOpenDiffs(String stored) {
		for (AutoOpenDiffs choice : AutoOpenDiffs.values()) {
			if (id(choice).equals(stored.strip())) {
				return choice;
			}
		}
		return null;
	}
}
