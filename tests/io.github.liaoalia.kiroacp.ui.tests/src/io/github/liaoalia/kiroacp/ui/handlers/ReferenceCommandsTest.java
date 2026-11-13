package io.github.liaoalia.kiroacp.ui.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.eclipse.core.commands.IHandler;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.Platform;
import org.junit.jupiter.api.Test;

/**
 * The commands of the reference's {@code package.json} (0.3.11, plus the
 * agent link of its working tree) against the commands this plug-in
 * contributes (plan Task 17): each one is either a command here, with a
 * handler that can be created, or is listed as not ported. A command added
 * here or there has to be entered in the table, which is the one the
 * decisions quote.
 *
 * <p>Only the plug-in registry is read: no handler is executed, they all need
 * a workbench window.
 */
class ReferenceCommandsTest {

	private static final String PREFIX = "io.github.liaoalia.kiroacp.command.";
	/** In the table: the reference's command has no command here. */
	private static final String NOT_PORTED = null;

	/** Reference command id to the id of the command here, without the prefix. */
	private static final Map<String, String> COMMANDS = new LinkedHashMap<>();
	static {
		COMMANDS.put("kiroAcp.newSession", "newSession");
		// One kind of chat view: there is no editor tab beside a sidebar to tell apart.
		COMMANDS.put("kiroAcp.newSessionInSidebar", NOT_PORTED);
		// The history button of the page opens the dialog of past sessions.
		COMMANDS.put("kiroAcp.openPastSession", NOT_PORTED);
		COMMANDS.put("kiroAcp.openPastSessionInSidebar", NOT_PORTED);
		COMMANDS.put("kiroAcp.cancel", "cancelTurn");
		COMMANDS.put("kiroAcp.restart", "restartAgent");
		COMMANDS.put("kiroAcp.login", "login");
		COMMANDS.put("kiroAcp.sendSelection", "sendSelection");
		COMMANDS.put("kiroAcp.openPlan", "openPlan");
		COMMANDS.put("kiroAcp.showLog", "showLog");
		COMMANDS.put("kiroAcp.forgetSessionOptions", "forgetSessionOptions");
		// Accept and Reject are buttons above the panes of the compare editor.
		COMMANDS.put("kiroAcp.acceptDiff", NOT_PORTED);
		COMMANDS.put("kiroAcp.rejectDiff", NOT_PORTED);
		COMMANDS.put("kiroAcp.approvePlan", "approvePlan");
		COMMANDS.put("kiroAcp.rejectPlan", "rejectPlan");
		COMMANDS.put("kiroAcp.focus", "focusChat");
		// "New Chat Tab": nothing here opens a second chat view.
		COMMANDS.put("kiroAcp.openInEditor", NOT_PORTED);
	}

	@Test
	void everyCommandOfTheReferenceIsACommandWithAHandlerHereOrIsListedAsNotPorted() throws Exception {
		assertEquals(17, COMMANDS.size(), "the commands of the reference's package.json");
		List<String> problems = new ArrayList<>();
		for (Map.Entry<String, String> entry : COMMANDS.entrySet()) {
			if (entry.getValue() == NOT_PORTED) {
				continue;
			}
			String id = PREFIX + entry.getValue();
			IConfigurationElement command = element("org.eclipse.ui.commands", "command", "id", id);
			if (command == null) {
				problems.add(entry.getKey() + ": no command " + id);
				continue;
			}
			assertNotNull(command.getAttribute("name"), id);
			assertEquals("io.github.liaoalia.kiroacp.commands", command.getAttribute("categoryId"), id);
			IConfigurationElement handler = element("org.eclipse.ui.handlers", "handler", "commandId", id);
			if (handler == null) {
				problems.add(entry.getKey() + ": command " + id + " has no handler");
			} else {
				assertTrue(handler.createExecutableExtension("class") instanceof IHandler, id);
			}
		}
		assertEquals(List.of(), problems);
	}

	@Test
	void everyCommandHereIsOneOfTheReference() {
		Set<String> contributed = new TreeSet<>();
		for (IConfigurationElement element : Platform.getExtensionRegistry().getConfigurationElementsFor("org.eclipse.ui.commands")) {
			String id = element.getAttribute("id");
			if ("command".equals(element.getName()) && id != null && id.startsWith("io.github.liaoalia.kiroacp.")) {
				contributed.add(id);
			}
		}
		Set<String> mapped = new TreeSet<>();
		COMMANDS.values().stream().filter(id -> id != NOT_PORTED).forEach(id -> mapped.add(PREFIX + id));
		assertEquals(mapped, contributed);
		assertEquals(11, contributed.size());
	}

	@Test
	void theCommandsOfTheViewAreOfferedInItsToolbarAndMenu() {
		// Reference: view/title. Here the toolbar and the menu of the chat view; Send Selection is in the editors' context menu.
		assertEquals(List.of("newSession"), contributedTo("toolbar:io.github.liaoalia.kiroacp.chat"));
		assertEquals(List.of("cancelTurn", "restartAgent", "login", "showLog", "forgetSessionOptions", "openPlan"),
				contributedTo("menu:io.github.liaoalia.kiroacp.chat"));
		assertEquals(List.of("sendSelection"), contributedTo("popup:org.eclipse.ui.popup.any?after=additions"));
	}

	private static List<String> contributedTo(String location) {
		List<String> ids = new ArrayList<>();
		for (IConfigurationElement contribution : Platform.getExtensionRegistry().getConfigurationElementsFor("org.eclipse.ui.menus")) {
			if ("menuContribution".equals(contribution.getName()) && location.equals(contribution.getAttribute("locationURI"))
					&& contribution.getContributor().getName().equals("io.github.liaoalia.kiroacp.ui")) {
				for (IConfigurationElement command : contribution.getChildren("command")) {
					ids.add(command.getAttribute("commandId").substring(PREFIX.length()));
				}
			}
		}
		return ids;
	}

	private static IConfigurationElement element(String point, String name, String attribute, String value) {
		for (IConfigurationElement element : Platform.getExtensionRegistry().getConfigurationElementsFor(point)) {
			if (name.equals(element.getName()) && value.equals(element.getAttribute(attribute))) {
				return element;
			}
		}
		return null;
	}
}
