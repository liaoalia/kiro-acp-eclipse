package io.github.liaoalia.kiroacp.ui.chat;

import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.jface.preference.JFacePreferences;
import org.eclipse.jface.resource.ColorRegistry;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;

/**
 * Eclipse's colours and fonts as the {@code --vscode-*} variables the chat
 * page's stylesheet reads.
 *
 * <p>{@code theme.css} has a complete light and a complete dark set of
 * defaults. A variable is overridden only where Eclipse has a colour that
 * means the same thing, and only when that colour fits the page it would land
 * on: Eclipse's dark theme restyles its own widgets but leaves the operating
 * system's colours light, so a system colour is used only while the system and
 * the view agree about light or dark, and a text colour only when it can be
 * read on the view's background.
 */
final class ChatTheme {

	/** Below this contrast ratio text is not legible (WCAG's floor for large text). */
	private static final double READABLE = 3.0;

	/**
	 * Everything the mapping reads, so it can be computed without a display.
	 * Any colour or font except the first two may be {@code null} (not defined).
	 *
	 * @param background       the view's own background, which decides light or dark
	 * @param systemBackground the operating system's widget background
	 * @param secondary        the colour of less important text (JFace's qualifier colour)
	 */
	record Palette(RGB background, RGB foreground, RGB systemBackground, RGB listBackground, RGB listForeground,
			RGB selection, RGB selectionText, RGB border, RGB link, RGB activeLink, RGB error, RGB secondary,
			FontData uiFont, FontData textFont) {
	}

	private ChatTheme() {
	}

	/** Reads the palette for a chat whose surroundings are {@code parent}. */
	static Palette read(Control parent) {
		Display display = parent.getDisplay();
		ColorRegistry colors = JFaceResources.getColorRegistry();
		return new Palette(parent.getBackground().getRGB(), parent.getForeground().getRGB(),
				system(display, SWT.COLOR_WIDGET_BACKGROUND), system(display, SWT.COLOR_LIST_BACKGROUND),
				system(display, SWT.COLOR_LIST_FOREGROUND), system(display, SWT.COLOR_LIST_SELECTION),
				system(display, SWT.COLOR_LIST_SELECTION_TEXT), system(display, SWT.COLOR_WIDGET_NORMAL_SHADOW),
				colors.getRGB(JFacePreferences.HYPERLINK_COLOR), colors.getRGB(JFacePreferences.ACTIVE_HYPERLINK_COLOR),
				colors.getRGB(JFacePreferences.ERROR_COLOR), colors.getRGB(JFacePreferences.QUALIFIER_COLOR),
				first(JFaceResources.getDefaultFont().getFontData()), first(JFaceResources.getTextFont().getFontData()));
	}

	static boolean isDark(RGB background) {
		return luminance(background) < 0.5;
	}

	/** @return the variables to set on the page, name to CSS value */
	static Map<String, String> variables(Palette p) {
		Map<String, String> vars = new LinkedHashMap<>();
		RGB background = p.background();
		boolean dark = isDark(background);
		vars.put("--vscode-sideBar-background", css(background));
		vars.put("--vscode-foreground", css(p.foreground()));

		if (p.uiFont() != null) {
			vars.put("--vscode-font-family", "\"" + cssName(p.uiFont().getName()) + "\", system-ui, sans-serif");
			vars.put("--vscode-font-size", px(p.uiFont()) + "px");
		}
		if (p.textFont() != null) {
			vars.put("--vscode-editor-font-family", "\"" + cssName(p.textFont().getName()) + "\", monospace");
			vars.put("--vscode-editor-font-size", px(p.textFont()) + "px");
		}

		// From JFace's registry, which an Eclipse theme redefines along with its widgets.
		putText(vars, "--vscode-textLink-foreground", p.link(), background);
		putText(vars, "--vscode-textLink-activeForeground", p.activeLink(), background);
		putText(vars, "--vscode-errorForeground", p.error(), background);
		putText(vars, "--vscode-descriptionForeground", p.secondary(), background);

		if (p.systemBackground() != null && isDark(p.systemBackground()) == dark) {
			RGB field = p.listBackground();
			RGB fieldText = p.listForeground();
			if (field != null && fieldText != null && isDark(field) == dark && contrast(fieldText, field) >= READABLE) {
				vars.put("--vscode-input-background", css(field));
				vars.put("--vscode-input-foreground", css(fieldText));
				vars.put("--vscode-dropdown-background", css(field));
				vars.put("--vscode-dropdown-foreground", css(fieldText));
			}
			if (p.selection() != null && p.selectionText() != null
					&& contrast(p.selectionText(), p.selection()) >= READABLE) {
				vars.put("--vscode-list-activeSelectionBackground", css(p.selection()));
				vars.put("--vscode-list-activeSelectionForeground", css(p.selectionText()));
			}
			if (p.border() != null) {
				vars.put("--vscode-widget-border", css(p.border()));
				vars.put("--vscode-input-border", css(p.border()));
				vars.put("--vscode-dropdown-border", css(p.border()));
			}
		}
		return vars;
	}

	private static void putText(Map<String, String> vars, String name, RGB color, RGB background) {
		if (color != null && contrast(color, background) >= READABLE) {
			vars.put(name, css(color));
		}
	}

	private static RGB system(Display display, int id) {
		return display.getSystemColor(id).getRGB();
	}

	private static FontData first(FontData[] data) {
		return data != null && data.length > 0 ? data[0] : null;
	}

	/** A font name goes inside a quoted CSS string. */
	private static String cssName(String name) {
		return name.replace("\\", "\\\\").replace("\"", "\\\"");
	}

	/** Font heights are in points; CSS wants pixels at 96 dpi. */
	private static int px(FontData font) {
		return Math.max(10, Math.round(font.getHeight() * 96f / 72f));
	}

	static String css(RGB rgb) {
		return String.format("#%02x%02x%02x", rgb.red, rgb.green, rgb.blue);
	}

	private static double luminance(RGB rgb) {
		return (0.2126 * rgb.red + 0.7152 * rgb.green + 0.0722 * rgb.blue) / 255.0;
	}

	/** WCAG contrast ratio, 1 (none) to 21 (black on white). */
	static double contrast(RGB a, RGB b) {
		double la = relativeLuminance(a);
		double lb = relativeLuminance(b);
		return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
	}

	private static double relativeLuminance(RGB rgb) {
		return 0.2126 * linear(rgb.red) + 0.7152 * linear(rgb.green) + 0.0722 * linear(rgb.blue);
	}

	private static double linear(int channel) {
		double c = channel / 255.0;
		return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
	}
}
