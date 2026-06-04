package com.example.scraper.video;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TopicalAuthorityPromptTest {

    private static final EnrichmentMetadata SAMPLE_INPUT = new EnrichmentMetadata(
            "Amateur couple oral session",
            "An authentic bedroom encounter.",
            "Amateur",
            List.of("amateur", "couple", "oral"),
            null,
            null
    );

    // -------- parser --------

    @Test
    void parse_bareJson_returnsAllFields() {
        String response = """
                {
                  "title": "Refined Title",
                  "description": "A refined description.",
                  "category": "Amateur",
                  "tags": ["couple", "amateur", "oral"],
                  "slug": "refined-title",
                  "views": 250000
                }
                """;
        TopicalAuthorityResult r = TopicalAuthorityPrompt.parse(response, SAMPLE_INPUT);
        assertThat(r.title()).isEqualTo("Refined Title");
        assertThat(r.description()).isEqualTo("A refined description.");
        assertThat(r.category()).isEqualTo("Amateur");
        assertThat(r.tags()).containsExactly("couple", "amateur", "oral");
        assertThat(r.slug()).isEqualTo("refined-title");
        assertThat(r.views()).isEqualTo(250_000L);
    }

    @Test
    void parse_jsonWrappedInMarkdownFences_extractsContent() {
        String response = "Sure! Here's the result:\n```json\n{\"title\":\"X\",\"slug\":\"x\",\"tags\":[\"a\"],\"views\":42}\n```\nDone!";
        TopicalAuthorityResult r = TopicalAuthorityPrompt.parse(response, SAMPLE_INPUT);
        assertThat(r.title()).isEqualTo("X");
        assertThat(r.slug()).isEqualTo("x");
        assertThat(r.views()).isEqualTo(42L);
    }

    @Test
    void parse_proseBeforeAndAfterJson_extractsJsonBlock() {
        String response = "Okay here we go: { \"title\": \"In Braces\", \"slug\": \"in-braces\", \"views\": 1000 } and that's all.";
        TopicalAuthorityResult r = TopicalAuthorityPrompt.parse(response, SAMPLE_INPUT);
        assertThat(r.title()).isEqualTo("In Braces");
        assertThat(r.slug()).isEqualTo("in-braces");
        assertThat(r.views()).isEqualTo(1000L);
    }

    @Test
    void parse_viewsAsStringWithCommas_parsesLong() {
        String response = "{\"title\":\"X\",\"slug\":\"x\",\"views\":\"372,893\"}";
        TopicalAuthorityResult r = TopicalAuthorityPrompt.parse(response, SAMPLE_INPUT);
        assertThat(r.views()).isEqualTo(372_893L);
    }

    @Test
    void parse_tagsAsCsvString_convertsToList() {
        String response = "{\"title\":\"X\",\"slug\":\"x\",\"tags\":\"alpha, beta, gamma\"}";
        TopicalAuthorityResult r = TopicalAuthorityPrompt.parse(response, SAMPLE_INPUT);
        assertThat(r.tags()).containsExactly("alpha", "beta", "gamma");
    }

    @Test
    void parse_topLevelArray_takesFirstObject() {
        String response = "[{\"title\":\"First\",\"slug\":\"first\",\"views\":10},{\"title\":\"Second\"}]";
        TopicalAuthorityResult r = TopicalAuthorityPrompt.parse(response, SAMPLE_INPUT);
        assertThat(r.title()).isEqualTo("First");
        assertThat(r.slug()).isEqualTo("first");
    }

    @Test
    void parse_missingFields_fallBackToInput() {
        String response = "{\"title\":\"Only Title\"}";
        TopicalAuthorityResult r = TopicalAuthorityPrompt.parse(response, SAMPLE_INPUT);
        assertThat(r.title()).isEqualTo("Only Title");
        assertThat(r.description()).isEqualTo("An authentic bedroom encounter.");
        assertThat(r.category()).isEqualTo("Amateur");
        assertThat(r.tags()).containsExactly("amateur", "couple", "oral");
        assertThat(r.slug()).isEqualTo("amateur-couple-oral-session");
        assertThat(r.views()).isEqualTo(0L);
    }

    @Test
    void parse_unparseableResponse_usesFallbackEverywhere() {
        TopicalAuthorityResult r = TopicalAuthorityPrompt.parse("not even close to JSON", SAMPLE_INPUT);
        assertThat(r.title()).isEqualTo(SAMPLE_INPUT.titleOrFallback());
        assertThat(r.description()).isEqualTo("An authentic bedroom encounter.");
        assertThat(r.category()).isEqualTo("Amateur");
        assertThat(r.tags()).containsExactly("amateur", "couple", "oral");
        assertThat(r.slug()).isEqualTo("amateur-couple-oral-session");
        assertThat(r.views()).isEqualTo(0L);
    }

    @Test
    void parse_nullResponse_usesFallbackEverywhere() {
        TopicalAuthorityResult r = TopicalAuthorityPrompt.parse(null, SAMPLE_INPUT);
        assertThat(r.title()).isEqualTo(SAMPLE_INPUT.titleOrFallback());
        assertThat(r.views()).isEqualTo(0L);
    }

    @Test
    void parse_viewsMissing_defaultsToZero() {
        String response = "{\"title\":\"X\",\"slug\":\"x\"}";
        TopicalAuthorityResult r = TopicalAuthorityPrompt.parse(response, SAMPLE_INPUT);
        assertThat(r.views()).isEqualTo(0L);
    }

    @Test
    void parse_emptyTagsArray_fallsBackToInputTags() {
        String response = "{\"title\":\"X\",\"slug\":\"x\",\"tags\":[]}";
        TopicalAuthorityResult r = TopicalAuthorityPrompt.parse(response, SAMPLE_INPUT);
        assertThat(r.tags()).containsExactly("amateur", "couple", "oral");
    }

    @Test
    void parse_bracesInsideString_doNotConfuseTheMatcher() {
        // The string field has a literal '{' and '}' inside it. The matcher
        // must not treat those as a structural brace.
        String response = "{\"title\":\"Has { braces }\",\"slug\":\"ok\",\"views\":1}";
        TopicalAuthorityResult r = TopicalAuthorityPrompt.parse(response, SAMPLE_INPUT);
        assertThat(r.title()).isEqualTo("Has { braces }");
        assertThat(r.slug()).isEqualTo("ok");
        assertThat(r.views()).isEqualTo(1L);
    }

    // -------- prompt builder --------

    @Test
    void buildPrompt_includesCatalogSummaryAndInputMetadata() {
        String summary = "{\"size\":3,\"categories\":{\"Amateur\":3},\"topTags\":[],\"recentTitles\":[\"A\",\"B\",\"C\"]}";
        String p = TopicalAuthorityPrompt.buildPrompt(SAMPLE_INPUT, summary);
        assertThat(p).contains("Amateur couple oral session");
        assertThat(p).contains("An authentic bedroom encounter.");
        assertThat(p).contains("Amateur");
        assertThat(p).contains("amateur, couple, oral");
        assertThat(p).contains(summary);
    }

    @Test
    void buildPrompt_emptyCatalog_branchesToSeedMode() {
        String p = TopicalAuthorityPrompt.buildPrompt(SAMPLE_INPUT, "{\"size\":0}");
        assertThat(p).contains("seed entry");
        assertThat(p).contains("Amateur couple oral session");
    }

    @Test
    void buildPrompt_blankCatalog_branchesToSeedMode() {
        String p = TopicalAuthorityPrompt.buildPrompt(SAMPLE_INPUT, "");
        assertThat(p).contains("seed entry");
    }

    @Test
    void extractFirstJsonObject_braceStringEquivalence() {
        assertThat(TopicalAuthorityPrompt.extractFirstJsonObject("hello { \"a\": 1 } world"))
                .contains("\"a\": 1");
    }

    @Test
    void extractFirstJsonObject_ignoresBracesInsideStrings() {
        String text = "title=\"X { not a json object }\" then {\"real\":true}";
        String extracted = TopicalAuthorityPrompt.extractFirstJsonObject(text);
        assertThat(extracted).contains("\"real\":true");
    }

    @Test
    void extractFirstJsonObject_handlesNestedObjects() {
        String text = "{\"outer\":{\"inner\":42}}";
        assertThat(TopicalAuthorityPrompt.extractFirstJsonObject(text)).isEqualTo(text);
    }

    @Test
    void extractFirstJsonObject_returnsNullWhenNoJson() {
        assertThat(TopicalAuthorityPrompt.extractFirstJsonObject("no braces here")).isNull();
        assertThat(TopicalAuthorityPrompt.extractFirstJsonObject("{ unmatched")).isNull();
    }
}
