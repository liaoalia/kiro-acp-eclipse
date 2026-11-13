package io.github.liaoalia.kiroacp.core.pure.usage;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.liaoalia.kiroacp.core.model.SessionUsage;
import io.github.liaoalia.kiroacp.core.pure.usage.Usage.ContextNumbers;

/**
 * The cases of the reference's {@code test/features-usage.test.ts} (its
 * {@code formatTokens} and {@code usageText} cases are about the page, which
 * is the reference's own) and {@code test/features-contextResult.test.ts}.
 */
class UsageTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final SessionUsage EMPTY = SessionUsage.EMPTY;

	private static JsonNode json(String text) {
		try {
			return MAPPER.readTree(text.replace('\'', '"'));
		} catch (JsonProcessingException e) {
			throw new IllegalArgumentException(text, e);
		}
	}

	// --- features-usage.test.ts ---

	@Test
	void usageUpdateSetsTheContextNumbersThePercentageAndTheCost() {
		SessionUsage next = Usage.applyUsageUpdate(EMPTY,
				json("{'sessionUpdate':'usage_update','used':8400,'size':200000,'cost':{'amount':0.1234,'currency':'USD'}}"));
		assertEquals(new SessionUsage(4.0, 8400L, 200_000L, null, new SessionUsage.Cost(0.1234, "USD")), next);
	}

	@Test
	void kiroContextUsageCarriesUsagePercentageInMetaKiro() {
		JsonNode update = json("{'sessionUpdate':'session_info_update','_meta':{'kiro':{'kind':'context_usage','usagePercentage':42.6}}}");
		SessionUsage next = Usage.applyUsageUpdate(EMPTY, update);
		assertEquals(43.0, next.contextPercent());
		assertNull(next.contextUsed());
		assertNull(Usage.applyUsageUpdate(next, update), "the same value again is nothing new");
	}

	@Test
	void contextUsagePercentagesAreClampedAndMayComeWithTokenCounts() {
		SessionUsage next = Usage.applyUsageUpdate(EMPTY, json("{'sessionUpdate':'session_info_update','_meta':{'kiro':"
				+ "{'kind':'context_usage','usagePercentage':140,'usedTokens':10,'contextWindow':100}}}"));
		assertEquals(new SessionUsage(100.0, 10L, 100L, null, null), next);
		SessionUsage derived = Usage.applyUsageUpdate(EMPTY, json("{'sessionUpdate':'session_info_update','_meta':{'kiro':"
				+ "{'kind':'context_usage','usedTokens':25,'contextWindow':100}}}"));
		assertEquals(25.0, derived.contextPercent(), "derived from the counts when no percentage is given");
	}

	@Test
	void turnCompletionSummariesAddUpAcrossTurns() {
		SessionUsage one = Usage.applyUsageUpdate(EMPTY, json("{'sessionUpdate':'session_info_update','_meta':{'kiro':{'kind':'turn_completion',"
				+ "'promptTurnSummaries':[{'inputTokens':1200,'outputTokens':300},{'usage':{'input_tokens':10,'output_tokens':5}}]}}}"));
		assertEquals(1515L, one.tokensSpent());
		SessionUsage two = Usage.applyUsageUpdate(one, json("{'sessionUpdate':'session_info_update','_meta':{'kiro':{'kind':'turn_completion',"
				+ "'promptTurnSummaries':[{'totalTokens':100,'inputTokens':90,'outputTokens':10}]}}}"));
		assertEquals(1615L, two.tokensSpent(), "totalTokens wins over the parts");
		assertNull(Usage.applyUsageUpdate(two,
				json("{'sessionUpdate':'session_info_update','_meta':{'kiro':{'kind':'turn_completion','promptTurnSummaries':[]}}}")),
				"an empty summary changes nothing");
		// Beyond the reference's cases: a single summary object, and the summaries beside the meta.
		assertEquals(7L, Usage.applyUsageUpdate(EMPTY, json("{'sessionUpdate':'session_info_update','_meta':{'kiro':{'kind':'turn_completion',"
				+ "'promptTurnSummaries':{'tokens':7}}}}")).tokensSpent());
		assertEquals(9L, Usage.applyUsageUpdate(EMPTY, json("{'sessionUpdate':'session_info_update','promptTurnSummaries':[{'outputTokens':9}],"
				+ "'_meta':{'kiro':{'kind':'turn_completion'}}}")).tokensSpent());
	}

	@Test
	void tokenTotalIgnoresNonTokenNumbersAndStopsAtDepthThree() {
		assertEquals(12, Usage.tokenTotal(json("{'durationMs':500,'inputTokens':3,'nested':{'outputTokens':4,'deeper':{'cacheReadTokens':5}}}")));
		assertEquals(0, Usage.tokenTotal(json("{'a':{'b':{'c':{'d':{'inputTokens':99}}}}}")));
		assertEquals(0, Usage.tokenTotal(json("'nope'")));
	}

	@Test
	void updatesWithoutUsageInformationAreIgnored() {
		assertNull(Usage.applyUsageUpdate(EMPTY, json("{'sessionUpdate':'agent_message_chunk','content':{'type':'text','text':'hi'}}")));
		assertNull(Usage.applyUsageUpdate(EMPTY, json("{'sessionUpdate':'session_info_update','title':'T'}")));
		assertNull(Usage.applyUsageUpdate(EMPTY, json("{'sessionUpdate':'session_info_update','_meta':{'kiro':{'kind':'steering_injected'}}}")));
		assertNull(Usage.applyUsageUpdate(EMPTY, json("{'sessionUpdate':'usage_update','used':'8400','size':0}")));
		assertNull(Usage.applyUsageUpdate(EMPTY, null));
		assertNull(Usage.applyUsageUpdate(EMPTY, json("null")));
	}

	@Test
	void aUsageUpdateWithoutACurrencyHasAnEmptyOneAndKeepsWhatWasKnown() {
		SessionUsage before = new SessionUsage(42.0, 1L, 2L, 1500L, null);
		SessionUsage next = Usage.applyUsageUpdate(before, json("{'sessionUpdate':'usage_update','cost':{'amount':2}}"));
		assertEquals(new SessionUsage(42.0, 1L, 2L, 1500L, new SessionUsage.Cost(2, "")), next);
	}

	// --- features-contextResult.test.ts ---

	@Test
	void aSessionContextReplyIsReadFromNestedKeys() {
		assertEquals(new ContextNumbers(37.0, 74_800L, 200_000L), Usage.contextFromResult(
				json("{'ok':true,'contextWindow':{'usagePercentage':37.4,'usedTokens':74800,'contextWindowSize':200000}}")));
	}

	@Test
	void thePercentageIsDerivedFromCountsAlsoInsideArraysAndTheFakeAgentsEchoYieldsNothing() {
		assertEquals(new ContextNumbers(25.0, 50_000L, 200_000L),
				Usage.contextFromResult(json("{'items':[{'name':'files','count':1}],'total':{'tokens':50000,'limit':200000}}")));
		assertNull(Usage.contextFromResult(json("{'ok':true,'echoed':{'sessionId':'s1'}}")));
		assertEquals(new ContextNumbers(25.0, 20L, 80L),
				Usage.contextFromResult(json("{'summary':{'tokens':9},'window':{'usedTokens':20,'contextWindow':80}}")),
				"a specific key deeper down beats a generic one higher up");
		assertNull(Usage.contextFromResult(json("'plain text without numbers'")));
		assertNull(Usage.contextFromResult(json("null")));
		assertNull(Usage.contextFromResult(null));
	}

	@Test
	void textRepliesAreParsed() {
		assertEquals(new ContextNumbers(42.0, 84_000L, 200_000L),
				Usage.contextFromResult(json("{'content':'Context window: 42% used (84k of 200k tokens)'}")));
		assertEquals(new ContextNumbers(1.0, 12_345L, 1_500_000L),
				Usage.contextFromResult(json("[{'type':'text','text':'12,345 / 1.5M tokens in context'}]")));
		assertEquals(new ContextNumbers(7.0, null, null), Usage.contextFromResult(json("'Usage: 7%'")));
	}

	@Test
	void applyContextResultKeepsTheTokenTotalsAndReportsNoChangeForTheSameNumbers() {
		SessionUsage before = new SessionUsage(null, null, null, 1500L, null);
		SessionUsage next = Usage.applyContextResult(before, json("{'usagePercentage':7,'contextWindowSize':200000}"));
		assertEquals(new SessionUsage(7.0, null, 200_000L, 1500L, null), next);
		assertNull(Usage.applyContextResult(next, json("{'usagePercentage':7,'contextWindowSize':200000}")));
		assertNull(Usage.applyContextResult(next, json("{'ok':true}")), "nothing usable is no change");
		assertDoesNotThrow(() -> Usage.applyContextResult(next, json("{'a':{'b':{'c':{'d':{'e':{'usagePercentage':99}}}}}}")));
	}

	@Test
	void theFakeAgentsReplyGivesThePercentageAndTheWindow() {
		assertEquals(new ContextNumbers(7.0, null, 200_000L), Usage.contextFromResult(
				json("{'ok':true,'calls':1,'contextWindow':{'usagePercentage':7,'contextWindowSize':200000}}")));
	}
}
