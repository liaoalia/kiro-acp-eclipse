package io.github.liaoalia.kiroacp.ui.prefs;

import java.io.File;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.preference.BooleanFieldEditor;
import org.eclipse.jface.preference.ComboFieldEditor;
import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.jface.preference.IntegerFieldEditor;
import org.eclipse.jface.preference.StringButtonFieldEditor;
import org.eclipse.jface.preference.StringFieldEditor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;
import org.eclipse.ui.preferences.ScopedPreferenceStore;

import io.github.liaoalia.kiroacp.core.host.Settings.AutoOpenDiffs;
import io.github.liaoalia.kiroacp.core.host.Settings.TerminalMode;
import io.github.liaoalia.kiroacp.ui.services.PreferenceSettings;

/**
 * Preferences &gt; Kiro ACP Chat: the reference's {@code kiroAcp.*} settings
 * that make sense in Eclipse, with its descriptions as tooltips.
 */
public final class KiroPreferencePage extends FieldEditorPreferencePage implements IWorkbenchPreferencePage {

	/** The id of the page in {@code plugin.xml}, for opening it from a command. */
	public static final String ID = "io.github.liaoalia.kiroacp.preferences";

	public KiroPreferencePage() {
		super(GRID);
		setPreferenceStore(new ScopedPreferenceStore(InstanceScope.INSTANCE, PreferenceSettings.QUALIFIER));
		setDescription("Settings of the Kiro chat and of the kiro-cli agent process it talks to.");
	}

	@Override
	public void init(IWorkbench workbench) {
	}

	@Override
	protected void createFieldEditors() {
		Composite parent = getFieldEditorParent();

		CliPathFieldEditor cliPath = new CliPathFieldEditor(PreferenceSettings.CLI_PATH, "&CLI path:", parent);
		tip(cliPath, parent, "Path to the kiro-cli executable. A bare name is looked up on PATH, then in the default"
				+ " install locations. Empty means kiro-cli. Applies when the agent process is next started.");
		addField(cliPath);

		StringFieldEditor cliArgs = new StringFieldEditor(PreferenceSettings.CLI_ARGS, "CLI &arguments:", parent);
		tip(cliArgs, parent, "Extra arguments appended after \"acp\", separated by spaces, for example"
				+ " --agent-engine=v3. Put an argument that contains spaces in quotes.");
		addField(cliArgs);

		ComboFieldEditor terminalMode = new ComboFieldEditor(PreferenceSettings.TERMINAL_MODE, "&Terminals:",
				new String[][] {
						{ "One console per session", PreferenceSettings.id(TerminalMode.SHARED) },
						{ "One console per command", PreferenceSettings.id(TerminalMode.PER_COMMAND) },
						{ "Hidden (output only in the chat card)", PreferenceSettings.id(TerminalMode.HIDDEN) } },
				parent);
		terminalMode.getLabelControl(parent).setToolTipText(
				"How terminals opened by the agent are shown. Read when each command starts, so a change applies"
						+ " to the next command.");
		addField(terminalMode);

		IntegerFieldEditor byteLimit = new IntegerFieldEditor(PreferenceSettings.TERMINAL_OUTPUT_BYTE_LIMIT,
				"Terminal &output limit (bytes):", parent);
		byteLimit.setValidRange(1, Integer.MAX_VALUE);
		tip(byteLimit, parent,
				"Bytes of terminal output kept for the agent when it does not ask for a limit itself.");
		addField(byteLimit);

		ComboFieldEditor autoOpenDiffs = new ComboFieldEditor(PreferenceSettings.AUTO_OPEN_DIFFS,
				"Open &diffs automatically:",
				new String[][] {
						{ "For every proposed file change", PreferenceSettings.id(AutoOpenDiffs.ALWAYS) },
						{ "Only when a change waits for permission",
								PreferenceSettings.id(AutoOpenDiffs.WHEN_PERMISSION_REQUIRED) },
						{ "Never (use the links in the chat)", PreferenceSettings.id(AutoOpenDiffs.NEVER) } },
				parent);
		autoOpenDiffs.getLabelControl(parent).setToolTipText(
				"When to open a compare editor for file changes the agent proposes. In autonomous modes changes"
						+ " are applied without a permission request; an editor for each would hide the chat.");
		addField(autoOpenDiffs);

		addFlag(PreferenceSettings.ATTACH_ACTIVE_FILE, "Attach the active editor's &file to each prompt", parent,
				"Adds the file of the active editor to each prompt as a removable chip. Off by default: add"
						+ " files explicitly with @.");
		addFlag(PreferenceSettings.LOG_PROTOCOL, "&Log protocol messages", parent,
				"Writes every JSON-RPC message to the \"Kiro ACP\" console (useful when reporting bugs).");
		addFlag(PreferenceSettings.SHOW_SETTINGS_BY_DEFAULT, "&Show the mode, model and option pickers by default",
				parent, "Shows the pickers without pressing the settings button in the chat.");
		addFlag(PreferenceSettings.REMEMBER_SESSION_OPTIONS, "&Remember session options", parent,
				"Remembers the mode, model and options you choose and applies them to every new session (only"
						+ " values the agent offers are applied).");
	}

	private void addFlag(String key, String label, Composite parent, String tooltip) {
		BooleanFieldEditor flag = new BooleanFieldEditor(key, label, parent);
		flag.getDescriptionControl(parent).setToolTipText(tooltip);
		addField(flag);
	}

	private static void tip(StringFieldEditor editor, Composite parent, String tooltip) {
		editor.getLabelControl(parent).setToolTipText(tooltip);
		editor.getTextControl(parent).setToolTipText(tooltip);
	}

	/**
	 * A path with a Browse button that also accepts what is not a file.
	 *
	 * <p>JFace's file field rejects anything but an existing file, and the
	 * default here is the bare command name {@code kiro-cli}, found on PATH.
	 */
	private static final class CliPathFieldEditor extends StringButtonFieldEditor {

		CliPathFieldEditor(String name, String label, Composite parent) {
			super(name, label, parent);
			setEmptyStringAllowed(true);
		}

		@Override
		protected String changePressed() {
			FileDialog dialog = new FileDialog(getShell(), SWT.OPEN | SWT.SHEET);
			dialog.setText("Select the kiro-cli executable");
			// Only a path says where to start; a bare name has no folder to open.
			File current = new File(getTextControl().getText().strip());
			if (current.getParentFile() != null) {
				dialog.setFilterPath(current.getParent());
				dialog.setFileName(current.getName());
			}
			return dialog.open();
		}
	}
}
