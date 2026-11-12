package io.github.liaoalia.kiroacp.ui.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.core.runtime.preferences.DefaultScope;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.liaoalia.kiroacp.core.host.Settings.AutoOpenDiffs;
import io.github.liaoalia.kiroacp.core.host.Settings.TerminalMode;
import io.github.liaoalia.kiroacp.core.host.Subscription;

/** The real preference nodes of the bundle, emptied around each test. */
class PreferenceSettingsTest {

	private final IEclipsePreferences stored = InstanceScope.INSTANCE.getNode(PreferenceSettings.QUALIFIER);
	private final PreferenceSettings settings = new PreferenceSettings();

	@BeforeEach
	@AfterEach
	void forgetStoredValues() throws Exception {
		stored.clear();
	}

	@Test
	void nothingStoredGivesTheDefaultsOfTheReference() {
		assertEquals("kiro-cli", settings.cliPath());
		assertEquals(List.of(), settings.cliArgs());
		assertFalse(settings.attachActiveFile());
		assertEquals(AutoOpenDiffs.WHEN_PERMISSION_REQUIRED, settings.autoOpenDiffs());
		assertEquals(TerminalMode.SHARED, settings.terminalMode());
		assertEquals(1_048_576, settings.terminalOutputByteLimit());
		assertFalse(settings.logProtocol());
		assertFalse(settings.showSettingsByDefault());
		assertFalse(settings.sessionPanelCollapsed());
		assertTrue(settings.rememberSessionOptions());
	}

	@Test
	void theInitializerPutsTheSameDefaultsIntoTheDefaultScope() {
		// What the preference page shows and "Restore Defaults" restores.
		IEclipsePreferences defaults = DefaultScope.INSTANCE.getNode(PreferenceSettings.QUALIFIER);

		assertEquals("kiro-cli", defaults.get(PreferenceSettings.CLI_PATH, null));
		assertEquals("", defaults.get(PreferenceSettings.CLI_ARGS, null));
		assertEquals("false", defaults.get(PreferenceSettings.ATTACH_ACTIVE_FILE, null));
		assertEquals("whenPermissionRequired", defaults.get(PreferenceSettings.AUTO_OPEN_DIFFS, null));
		assertEquals("shared", defaults.get(PreferenceSettings.TERMINAL_MODE, null));
		assertEquals("1048576", defaults.get(PreferenceSettings.TERMINAL_OUTPUT_BYTE_LIMIT, null));
		assertEquals("false", defaults.get(PreferenceSettings.LOG_PROTOCOL, null));
		assertEquals("false", defaults.get(PreferenceSettings.SHOW_SETTINGS_BY_DEFAULT, null));
		assertEquals("false", defaults.get(PreferenceSettings.SESSION_PANEL_COLLAPSED, null));
		assertEquals("true", defaults.get(PreferenceSettings.REMEMBER_SESSION_OPTIONS, null));
	}

	@Test
	void everyStoredValueIsReadBack() {
		stored.put(PreferenceSettings.CLI_PATH, "C:\\Program Files\\kiro\\kiro-cli.exe");
		stored.put(PreferenceSettings.CLI_ARGS, "acp --agent \"my agent\"");
		stored.putBoolean(PreferenceSettings.ATTACH_ACTIVE_FILE, true);
		stored.put(PreferenceSettings.AUTO_OPEN_DIFFS, "never");
		stored.put(PreferenceSettings.TERMINAL_MODE, "perCommand");
		stored.putInt(PreferenceSettings.TERMINAL_OUTPUT_BYTE_LIMIT, 4096);
		stored.putBoolean(PreferenceSettings.LOG_PROTOCOL, true);
		stored.putBoolean(PreferenceSettings.SHOW_SETTINGS_BY_DEFAULT, true);
		stored.putBoolean(PreferenceSettings.SESSION_PANEL_COLLAPSED, true);
		stored.putBoolean(PreferenceSettings.REMEMBER_SESSION_OPTIONS, false);

		assertEquals("C:\\Program Files\\kiro\\kiro-cli.exe", settings.cliPath());
		assertEquals(List.of("acp", "--agent", "my agent"), settings.cliArgs());
		assertTrue(settings.attachActiveFile());
		assertEquals(AutoOpenDiffs.NEVER, settings.autoOpenDiffs());
		assertEquals(TerminalMode.PER_COMMAND, settings.terminalMode());
		assertEquals(4096, settings.terminalOutputByteLimit());
		assertTrue(settings.logProtocol());
		assertTrue(settings.showSettingsByDefault());
		assertTrue(settings.sessionPanelCollapsed());
		assertFalse(settings.rememberSessionOptions());
	}

	@Test
	void everyEnumValueHasAStoredForm() {
		for (TerminalMode mode : TerminalMode.values()) {
			stored.put(PreferenceSettings.TERMINAL_MODE, PreferenceSettings.id(mode));
			assertEquals(mode, settings.terminalMode());
		}
		for (AutoOpenDiffs choice : AutoOpenDiffs.values()) {
			stored.put(PreferenceSettings.AUTO_OPEN_DIFFS, PreferenceSettings.id(choice));
			assertEquals(choice, settings.autoOpenDiffs());
		}
		assertEquals(List.of("shared", "perCommand", "hidden"),
				List.of(TerminalMode.values()).stream().map(PreferenceSettings::id).toList());
		assertEquals(List.of("always", "whenPermissionRequired", "never"),
				List.of(AutoOpenDiffs.values()).stream().map(PreferenceSettings::id).toList());
	}

	@Test
	void aValueThatCannotBeUnderstoodFallsBackToTheDefault() {
		stored.put(PreferenceSettings.CLI_PATH, "   ");
		stored.put(PreferenceSettings.ATTACH_ACTIVE_FILE, "yes");
		stored.put(PreferenceSettings.AUTO_OPEN_DIFFS, "sometimes");
		stored.put(PreferenceSettings.TERMINAL_MODE, "SHARED_OR_SO");
		stored.put(PreferenceSettings.TERMINAL_OUTPUT_BYTE_LIMIT, "a lot");
		stored.put(PreferenceSettings.LOG_PROTOCOL, "1");
		stored.put(PreferenceSettings.REMEMBER_SESSION_OPTIONS, "");

		assertEquals("kiro-cli", settings.cliPath());
		assertFalse(settings.attachActiveFile());
		assertEquals(AutoOpenDiffs.WHEN_PERMISSION_REQUIRED, settings.autoOpenDiffs());
		assertEquals(TerminalMode.SHARED, settings.terminalMode());
		assertEquals(1_048_576, settings.terminalOutputByteLimit());
		assertFalse(settings.logProtocol());
		assertTrue(settings.rememberSessionOptions());
	}

	@Test
	void aByteLimitMustBeAPositiveInt() {
		for (String bad : List.of("0", "-1", "1.5", "99999999999", "")) {
			stored.put(PreferenceSettings.TERMINAL_OUTPUT_BYTE_LIMIT, bad);
			assertEquals(1_048_576, settings.terminalOutputByteLimit(), bad);
		}
		stored.put(PreferenceSettings.TERMINAL_OUTPUT_BYTE_LIMIT, " 2048 ");
		assertEquals(2048, settings.terminalOutputByteLimit());
	}

	@Test
	void thePathIsStrippedAndFlagsIgnoreCase() {
		stored.put(PreferenceSettings.CLI_PATH, "  /opt/kiro/kiro-cli ");
		stored.put(PreferenceSettings.LOG_PROTOCOL, "TRUE");

		assertEquals("/opt/kiro/kiro-cli", settings.cliPath());
		assertTrue(settings.logProtocol());
	}

	@Test
	void listenersHearEveryChangeUntilTheyAreRemoved() {
		AtomicInteger changes = new AtomicInteger();
		Subscription subscription = settings.onChange(changes::incrementAndGet);

		stored.put(PreferenceSettings.CLI_PATH, "other-cli");
		assertEquals(1, changes.get());
		// The new value is already there when the listener runs.
		assertEquals("other-cli", settings.cliPath());

		stored.putBoolean(PreferenceSettings.LOG_PROTOCOL, true);
		stored.remove(PreferenceSettings.CLI_PATH);
		assertEquals(3, changes.get());

		subscription.close();
		subscription.close();
		stored.putBoolean(PreferenceSettings.LOG_PROTOCOL, false);
		assertEquals(3, changes.get());
	}

	@Test
	void aChangeMadeThroughAnotherInstanceIsHeard() {
		AtomicInteger changes = new AtomicInteger();
		try (Subscription subscription = settings.onChange(changes::incrementAndGet)) {
			InstanceScope.INSTANCE.getNode(PreferenceSettings.QUALIFIER).put(PreferenceSettings.TERMINAL_MODE,
					"hidden");

			assertEquals(1, changes.get());
			assertEquals(TerminalMode.HIDDEN, new PreferenceSettings().terminalMode());
		}
	}
}
