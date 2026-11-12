package io.github.liaoalia.kiroacp.ui.prefs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.liaoalia.kiroacp.core.host.Settings.AutoOpenDiffs;
import io.github.liaoalia.kiroacp.core.host.Settings.TerminalMode;
import io.github.liaoalia.kiroacp.ui.services.PreferenceSettings;

/**
 * The page's controls, built in a shell that is never opened: nothing appears
 * on the desktop, and the Browse button's dialog is never asked for.
 */
class KiroPreferencePageTest {

	private final IEclipsePreferences stored = InstanceScope.INSTANCE.getNode(PreferenceSettings.QUALIFIER);
	private final PreferenceSettings settings = new PreferenceSettings();
	private Display display;
	private boolean ownDisplay;
	private Shell shell;
	private KiroPreferencePage page;

	@BeforeEach
	void createShell() throws Exception {
		stored.clear();
		display = Display.getCurrent();
		if (display == null) {
			display = new Display();
			ownDisplay = true;
		}
		shell = new Shell(display);
	}

	@AfterEach
	void close() throws Exception {
		if (page != null) {
			page.dispose();
		}
		if (!shell.isDisposed()) {
			shell.dispose();
		}
		if (ownDisplay && !display.isDisposed()) {
			display.dispose();
		}
		stored.clear();
	}

	@Test
	void thePageShowsTheDefaultsAndAcceptsThem() {
		open();

		assertTrue(page.isValid(), page.getErrorMessage());
		// A bare command name is a valid CLI path although it is not a file.
		assertEquals(List.of("kiro-cli", "", "1048576"), texts().stream().map(Text::getText).toList());
		assertEquals(List.of("One console per session", "Only when a change waits for permission"),
				combos().stream().map(Combo::getText).toList());
		assertEquals(List.of(false, false, false, true), checks().stream().map(Button::getSelection).toList());
	}

	@Test
	void theFieldsExplainThemselvesInTooltips() {
		open();

		for (Text text : texts()) {
			assertNotNull(text.getToolTipText());
		}
		for (Button check : checks()) {
			assertNotNull(check.getToolTipText(), check.getText());
		}
	}

	@Test
	void storedValuesAreShown() {
		stored.put(PreferenceSettings.CLI_PATH, "/opt/kiro/kiro-cli");
		stored.put(PreferenceSettings.CLI_ARGS, "--agent \"my agent\"");
		stored.put(PreferenceSettings.TERMINAL_MODE, "hidden");
		stored.putInt(PreferenceSettings.TERMINAL_OUTPUT_BYTE_LIMIT, 2048);
		stored.put(PreferenceSettings.AUTO_OPEN_DIFFS, "always");
		stored.putBoolean(PreferenceSettings.LOG_PROTOCOL, true);
		stored.putBoolean(PreferenceSettings.REMEMBER_SESSION_OPTIONS, false);
		open();

		assertEquals(List.of("/opt/kiro/kiro-cli", "--agent \"my agent\"", "2048"),
				texts().stream().map(Text::getText).toList());
		assertEquals(List.of("Hidden (output only in the chat card)", "For every proposed file change"),
				combos().stream().map(Combo::getText).toList());
		assertEquals(List.of(false, true, false, false), checks().stream().map(Button::getSelection).toList());
	}

	@Test
	void okStoresWhatWasEnteredWhereTheSettingsReadIt() {
		open();
		type(texts().get(0), "C:\\Program Files\\kiro\\kiro-cli.exe");
		type(texts().get(1), "--agent \"my agent\" -v");
		type(texts().get(2), "4096");
		choose(combos().get(0), "One console per command");
		choose(combos().get(1), "Never (use the links in the chat)");
		for (Button check : checks()) {
			check.setSelection(!check.getSelection());
			check.notifyListeners(SWT.Selection, new Event());
		}

		assertTrue(page.isValid(), page.getErrorMessage());
		assertTrue(page.performOk());

		assertEquals("C:\\Program Files\\kiro\\kiro-cli.exe", settings.cliPath());
		assertEquals(List.of("--agent", "my agent", "-v"), settings.cliArgs());
		assertEquals(4096, settings.terminalOutputByteLimit());
		assertEquals(TerminalMode.PER_COMMAND, settings.terminalMode());
		assertEquals(AutoOpenDiffs.NEVER, settings.autoOpenDiffs());
		assertTrue(settings.attachActiveFile());
		assertTrue(settings.logProtocol());
		assertTrue(settings.showSettingsByDefault());
		assertFalse(settings.rememberSessionOptions());
	}

	@Test
	void anEmptyCliPathIsAcceptedAndMeansTheDefault() {
		open();
		type(texts().get(0), "");

		assertTrue(page.isValid(), page.getErrorMessage());
		assertTrue(page.performOk());
		assertEquals("kiro-cli", settings.cliPath());
	}

	@Test
	void aByteLimitThatIsNotAPositiveNumberIsRejected() {
		open();

		type(texts().get(2), "0");
		assertFalse(page.isValid());
		type(texts().get(2), "lots");
		assertFalse(page.isValid());
		type(texts().get(2), "65536");
		assertTrue(page.isValid(), page.getErrorMessage());
	}

	private void open() {
		page = new KiroPreferencePage();
		page.createControl(shell);
	}

	/** Sets the text as typing does: the editors validate on the key-up notification, sent to the widget only. */
	private static void type(Text text, String value) {
		text.setText(value);
		text.notifyListeners(SWT.KeyUp, new Event());
	}

	private static void choose(Combo combo, String item) {
		int index = combo.indexOf(item);
		if (index < 0) {
			fail("No item \"" + item + "\" in " + List.of(combo.getItems()));
		}
		combo.select(index);
		combo.notifyListeners(SWT.Selection, new Event());
	}

	/** CLI path, CLI arguments, byte limit, in the order of the page. */
	private List<Text> texts() {
		return controls(Text.class);
	}

	/** Terminal mode, auto-open diffs. */
	private List<Combo> combos() {
		return controls(Combo.class);
	}

	/** Attach active file, log protocol, show settings, remember session options. */
	private List<Button> checks() {
		return controls(Button.class).stream().filter(button -> (button.getStyle() & SWT.CHECK) != 0).toList();
	}

	private <T extends Control> List<T> controls(Class<T> type) {
		List<T> found = new ArrayList<>();
		collect(page.getControl(), type, found);
		return found;
	}

	private static <T extends Control> void collect(Control control, Class<T> type, List<T> found) {
		if (type.isInstance(control)) {
			found.add(type.cast(control));
		}
		if (control instanceof Composite composite) {
			for (Control child : composite.getChildren()) {
				collect(child, type, found);
			}
		}
	}
}
