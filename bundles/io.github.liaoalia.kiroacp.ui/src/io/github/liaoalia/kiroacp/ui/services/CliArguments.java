package io.github.liaoalia.kiroacp.ui.services;

import java.util.ArrayList;
import java.util.List;

/**
 * The CLI arguments preference: one line of text for a list of arguments.
 *
 * <p>Arguments are separated by whitespace. Text between a pair of double or
 * single quotes is taken as it is, spaces included, and the quotes are dropped;
 * a quoted part may sit inside an argument ({@code --name="a b"}) and the other
 * kind of quote is literal inside it. An empty pair of quotes is an empty
 * argument. A quote that is never closed runs to the end of the line.
 *
 * <p>The backslash is an ordinary character: these are often Windows paths,
 * and a rule that ate backslashes would break them silently.
 */
public final class CliArguments {

	private CliArguments() {
	}

	/** @return the arguments in the line; empty for a blank or missing line */
	public static List<String> split(String line) {
		List<String> arguments = new ArrayList<>();
		if (line == null) {
			return List.of();
		}
		StringBuilder current = new StringBuilder();
		// A quoted empty string is an argument although it has no characters.
		boolean started = false;
		char quote = 0;
		for (int i = 0; i < line.length(); i++) {
			char c = line.charAt(i);
			if (quote != 0) {
				if (c == quote) {
					quote = 0;
				} else {
					current.append(c);
				}
			} else if (c == '"' || c == '\'') {
				quote = c;
				started = true;
			} else if (Character.isWhitespace(c)) {
				if (started) {
					arguments.add(current.toString());
					current.setLength(0);
					started = false;
				}
			} else {
				current.append(c);
				started = true;
			}
		}
		if (started) {
			arguments.add(current.toString());
		}
		return List.copyOf(arguments);
	}

	/** @return a line that {@link #split} turns back into these arguments */
	public static String join(List<String> arguments) {
		StringBuilder line = new StringBuilder();
		for (String argument : arguments) {
			if (line.length() > 0) {
				line.append(' ');
			}
			line.append(quote(argument));
		}
		return line.toString();
	}

	private static String quote(String argument) {
		boolean hasDouble = argument.indexOf('"') >= 0;
		boolean hasSingle = argument.indexOf('\'') >= 0;
		boolean plain = !argument.isEmpty() && !hasDouble && !hasSingle
				&& argument.chars().noneMatch(Character::isWhitespace);
		if (plain) {
			return argument;
		}
		if (!hasDouble) {
			return '"' + argument + '"';
		}
		if (!hasSingle) {
			return '\'' + argument + '\'';
		}
		// Both kinds: quote each run with the kind it does not contain.
		StringBuilder quoted = new StringBuilder();
		int start = 0;
		while (start < argument.length()) {
			char first = argument.charAt(start);
			char with = first == '"' ? '\'' : '"';
			int end = start;
			while (end < argument.length() && argument.charAt(end) != with) {
				end++;
			}
			quoted.append(with).append(argument, start, end).append(with);
			start = end;
		}
		return quoted.toString();
	}
}
