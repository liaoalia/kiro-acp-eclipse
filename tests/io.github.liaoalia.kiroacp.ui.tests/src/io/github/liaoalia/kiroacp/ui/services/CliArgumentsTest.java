package io.github.liaoalia.kiroacp.ui.services;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class CliArgumentsTest {

	@Test
	void aBlankLineHasNoArguments() {
		assertEquals(List.of(), CliArguments.split(null));
		assertEquals(List.of(), CliArguments.split(""));
		assertEquals(List.of(), CliArguments.split(" \t "));
	}

	@Test
	void whitespaceSeparatesArguments() {
		assertEquals(List.of("acp", "--agent-engine=v3", "-v"), CliArguments.split("  acp --agent-engine=v3\t-v  "));
	}

	@Test
	void quotesKeepSpacesAndAreDropped() {
		assertEquals(List.of("--agent", "my agent"), CliArguments.split("--agent \"my agent\""));
		assertEquals(List.of("--agent", "my agent"), CliArguments.split("--agent 'my agent'"));
	}

	@Test
	void aQuotedPartMayBeInsideAnArgument() {
		assertEquals(List.of("--name=my agent", "x"), CliArguments.split("--name=\"my agent\" x"));
		assertEquals(List.of("ab c"), CliArguments.split("a\"b c\""));
	}

	@Test
	void theOtherKindOfQuoteIsLiteralInsideQuotes() {
		assertEquals(List.of("it's here"), CliArguments.split("\"it's here\""));
		assertEquals(List.of("say \"hi\""), CliArguments.split("'say \"hi\"'"));
	}

	@Test
	void anEmptyPairOfQuotesIsAnEmptyArgument() {
		assertEquals(List.of("--prefix", "", "x"), CliArguments.split("--prefix \"\" x"));
	}

	@Test
	void backslashesAreOrdinaryCharacters() {
		assertEquals(List.of("--config", "C:\\Users\\me\\kiro.json"),
				CliArguments.split("--config C:\\Users\\me\\kiro.json"));
		assertEquals(List.of("C:\\Program Files\\kiro\\"), CliArguments.split("\"C:\\Program Files\\kiro\\\""));
	}

	@Test
	void anUnclosedQuoteRunsToTheEndOfTheLine() {
		assertEquals(List.of("--agent", "my agent "), CliArguments.split("--agent \"my agent "));
	}

	@Test
	void joiningGivesALineThatSplitsBackIntoTheSameArguments() {
		List<String> arguments = List.of("acp", "my agent", "", "it's", "say \"hi\"", "both ' and \"", "C:\\a b\\");

		String line = CliArguments.join(arguments);

		assertEquals(arguments, CliArguments.split(line), line);
		assertEquals("acp --flag", CliArguments.join(List.of("acp", "--flag")));
	}
}
