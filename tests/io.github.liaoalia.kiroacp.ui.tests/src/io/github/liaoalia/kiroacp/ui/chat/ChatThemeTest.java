package io.github.liaoalia.kiroacp.ui.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.RGB;
import org.junit.jupiter.api.Test;

/** Which Eclipse colours become page variables, decided without a display. */
class ChatThemeTest {

	private static final RGB WHITE = new RGB(255, 255, 255);
	private static final RGB BLACK = new RGB(0, 0, 0);
	private static final RGB LIGHT_GREY = new RGB(240, 240, 240);
	private static final RGB MID_GREY = new RGB(160, 160, 160);
	private static final RGB HIGHLIGHT = new RGB(0, 120, 215);
	private static final RGB BLUE = new RGB(0, 0, 255);
	private static final RGB RED = new RGB(255, 0, 0);
	private static final RGB QUALIFIER = new RGB(96, 96, 96);
	private static final RGB DARK = new RGB(30, 30, 30);
	private static final RGB PALE = new RGB(220, 220, 220);

	/** Eclipse's light theme on a light system. */
	private static ChatTheme.Palette light() {
		return new ChatTheme.Palette(new RGB(248, 248, 248), BLACK, LIGHT_GREY, WHITE, BLACK, HIGHLIGHT, WHITE,
				MID_GREY, BLUE, RED, RED, QUALIFIER, new FontData("Segoe UI", 9, SWT.NORMAL),
				new FontData("Consolas", 10, SWT.NORMAL));
	}

	@Test
	void aLightViewOnALightSystemTakesEclipsesAndTheSystemsColours() {
		Map<String, String> vars = ChatTheme.variables(light());

		assertFalse(ChatTheme.isDark(light().background()));
		assertEquals("#f8f8f8", vars.get("--vscode-sideBar-background"));
		assertEquals("#000000", vars.get("--vscode-foreground"));
		assertEquals("#0000ff", vars.get("--vscode-textLink-foreground"));
		assertEquals("#ff0000", vars.get("--vscode-textLink-activeForeground"));
		assertEquals("#ff0000", vars.get("--vscode-errorForeground"));
		assertEquals("#606060", vars.get("--vscode-descriptionForeground"));
		assertEquals("#ffffff", vars.get("--vscode-input-background"));
		assertEquals("#000000", vars.get("--vscode-input-foreground"));
		assertEquals("#ffffff", vars.get("--vscode-dropdown-background"));
		assertEquals("#0078d7", vars.get("--vscode-list-activeSelectionBackground"));
		assertEquals("#ffffff", vars.get("--vscode-list-activeSelectionForeground"));
		assertEquals("#a0a0a0", vars.get("--vscode-widget-border"));
		assertEquals("#a0a0a0", vars.get("--vscode-input-border"));
	}

	@Test
	void fontsArriveAsCssFamiliesAndPixelSizes() {
		Map<String, String> vars = ChatTheme.variables(light());

		assertEquals("\"Segoe UI\", system-ui, sans-serif", vars.get("--vscode-font-family"));
		assertEquals("12px", vars.get("--vscode-font-size"));
		assertEquals("\"Consolas\", monospace", vars.get("--vscode-editor-font-family"));
		assertEquals("13px", vars.get("--vscode-editor-font-size"));
	}

	@Test
	void aFontNameCannotBreakOutOfItsCssString() {
		ChatTheme.Palette p = light();
		ChatTheme.Palette odd = new ChatTheme.Palette(p.background(), p.foreground(), p.systemBackground(),
				p.listBackground(), p.listForeground(), p.selection(), p.selectionText(), p.border(), p.link(),
				p.activeLink(), p.error(), p.secondary(), new FontData("Odd\"; color: red; \\", 9, SWT.NORMAL), null);

		Map<String, String> vars = ChatTheme.variables(odd);

		assertEquals("\"Odd\\\"; color: red; \\\\\", system-ui, sans-serif", vars.get("--vscode-font-family"));
		assertFalse(vars.containsKey("--vscode-editor-font-family"));
	}

	@Test
	void aDarkViewOnALightSystemKeepsTheSystemsLightColoursOut() {
		// Eclipse's dark theme on Windows in light mode: only Eclipse's own colours are dark.
		ChatTheme.Palette p = new ChatTheme.Palette(DARK, PALE, LIGHT_GREY, WHITE, BLACK, HIGHLIGHT, WHITE, MID_GREY,
				new RGB(111, 197, 238), new RGB(138, 201, 242), new RGB(247, 68, 117), new RGB(160, 160, 160), null,
				null);

		Map<String, String> vars = ChatTheme.variables(p);

		assertTrue(ChatTheme.isDark(p.background()));
		assertEquals(Set.of("--vscode-sideBar-background", "--vscode-foreground", "--vscode-textLink-foreground",
				"--vscode-textLink-activeForeground", "--vscode-errorForeground", "--vscode-descriptionForeground"),
				vars.keySet());
		assertEquals("#6fc5ee", vars.get("--vscode-textLink-foreground"));
	}

	@Test
	void textColoursThatCannotBeReadOnTheViewAreLeftToTheStylesheet() {
		// A dark view whose registry still has the light theme's colours.
		ChatTheme.Palette p = new ChatTheme.Palette(DARK, PALE, DARK, new RGB(45, 45, 45), PALE, HIGHLIGHT, WHITE,
				new RGB(80, 80, 80), BLUE, new RGB(0, 0, 128), new RGB(120, 0, 0), new RGB(60, 60, 60), null, null);

		Map<String, String> vars = ChatTheme.variables(p);

		assertFalse(vars.containsKey("--vscode-textLink-foreground"), vars.toString());
		assertFalse(vars.containsKey("--vscode-textLink-activeForeground"), vars.toString());
		assertFalse(vars.containsKey("--vscode-errorForeground"), vars.toString());
		assertFalse(vars.containsKey("--vscode-descriptionForeground"), vars.toString());
		// The system is dark too, so its colours are welcome.
		assertEquals("#2d2d2d", vars.get("--vscode-input-background"));
		assertEquals("#dcdcdc", vars.get("--vscode-input-foreground"));
		assertEquals("#505050", vars.get("--vscode-widget-border"));
	}

	@Test
	void coloursEclipseDoesNotDefineAreLeftToTheStylesheet() {
		ChatTheme.Palette p = new ChatTheme.Palette(WHITE, BLACK, null, null, null, null, null, null, null, null, null,
				null, null, null);

		assertEquals(Set.of("--vscode-sideBar-background", "--vscode-foreground"), ChatTheme.variables(p).keySet());
	}

	@Test
	void contrastIsTheWcagRatio() {
		assertEquals(21.0, ChatTheme.contrast(BLACK, WHITE), 0.01);
		assertEquals(1.0, ChatTheme.contrast(MID_GREY, MID_GREY), 0.0001);
		assertEquals(ChatTheme.contrast(BLUE, WHITE), ChatTheme.contrast(WHITE, BLUE), 0.0001);
	}
}
