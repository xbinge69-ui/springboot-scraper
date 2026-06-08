package com.example.scraper.scraper;

import com.example.scraper.scraper.XhamsterTagExtractor.XhamsterTags;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link XhamsterTagExtractor}. Runs against a real
 * captured page ({@code xhamster-faye-reagan.html}) plus a couple of
 * synthetic snippets that exercise the JSON-location logic in isolation.
 */
class XhamsterTagExtractorTest {

    @Test
    void extractsAllTiersFromRealPage() throws IOException {
        Document doc = loadFixture("xhamster-faye-reagan.html");
        XhamsterTags tags = XhamsterTagExtractor.extract(doc);

        // The 4 actresses that the user listed (Faye Reagan + Dane Cross
        // appear twice each on the page; deduplicated to 2).
        assertThat(tags.pornstars())
                .containsExactly("Faye Reagan", "Dane Cross");
        assertThat(tags.channels())
                .containsExactly("LMAO GFs");
        assertThat(tags.categories())
                .containsExactly("Babe", "Big Ass", "Blowjob", "Facial", "HD Videos",
                        "Hardcore", "In English", "Pornstar", "Redhead", "Teen");
        assertThat(tags.brands())
                .containsExactly("FapHouse");

        // The "all" list should include every category, every regular tag,
        // and the pornstars — but NOT channels or brands. Spot-check the
        // first few and the last few entries to confirm ordering is
        // preserved (LinkedHashSet semantics).
        assertThat(tags.all())
                .startsWith("Faye Reagan", "Dane Cross", "Babe", "Big Ass",
                        "Blowjob", "Facial", "HD Videos", "Hardcore", "In English",
                        "Pornstar", "Redhead", "Teen", "Hottest", "Very very Hot",
                        "Cock", "Sucking");
        assertThat(tags.all())
                .contains("Hottest", "Very very Hot", "Cock", "Sucking", "Doggy",
                        "Just Hot", "Huge", "Most Hot", "Stripping", "Naked",
                        "Raw", "Clothed", "Cum", "Shaft", "Canalporno");
        // Channels and brands must NOT be in `all` — they are producing
        // entities, not content tags, and we don't want the LLM to echo
        // them.
        assertThat(tags.all())
                .doesNotContain("LMAO GFs", "FapHouse");
        // Deduplication: the page lists Faye Reagan + Dane Cross twice.
        assertThat(tags.pornstars()).doesNotHaveDuplicates();
    }

    @Test
    void emptyDocumentReturnsEmpty() {
        assertThat(XhamsterTagExtractor.extract(null)).isSameAs(XhamsterTagExtractor.EMPTY);
        assertThat(XhamsterTagExtractor.extract(Jsoup.parse(""))).isSameAs(XhamsterTagExtractor.EMPTY);
    }

    @Test
    void documentWithoutTagsArrayReturnsEmpty() {
        Document doc = Jsoup.parse("<html><body><p>no tags here</p></body></html>");
        assertThat(XhamsterTagExtractor.extract(doc)).isSameAs(XhamsterTagExtractor.EMPTY);
    }

    @Test
    void parsesMinimalSyntheticTagsArray() {
        // Synthesizes the structure xhamster ships: "tags":[{...}, ...]
        // inside a <script> tag.
        String html = """
                <html><head><script>
                window.__INITIAL_STATE__ = {
                    "tags":[
                      {"name":"Faye Reagan","isPornstar":true,"url":"https://xhamster.com/pornstars/faye-reagan"},
                      {"name":"Babe","isCategory":true,"url":"https://xhamster.com/categories/babe"},
                      {"name":"Hottest","isTag":true,"url":"https://xhamster.com/tags/hottest"},
                      {"name":"LMAO GFs","isChannel":true,"url":"https://xhamster.com/channels/lmao-gfs"},
                      {"name":"FapHouse","isBrand":true,"url":"https://xhamster.com/channels/faphouse"}
                    ],
                    "otherKey":[1,2,3]
                };
                </script></head><body></body></html>
                """;
        Document doc = Jsoup.parse(html);
        XhamsterTags tags = XhamsterTagExtractor.extract(doc);

        assertThat(tags.pornstars()).containsExactly("Faye Reagan");
        assertThat(tags.categories()).containsExactly("Babe");
        assertThat(tags.channels()).containsExactly("LMAO GFs");
        assertThat(tags.brands()).containsExactly("FapHouse");
        assertThat(tags.all())
                .containsExactly("Faye Reagan", "Babe", "Hottest");
    }

    @Test
    void isXhamsterMatchesHostsAndVariants() {
        assertThat(XhamsterTagExtractor.isXhamster("https://xhamster.com/videos/xh1")).isTrue();
        assertThat(XhamsterTagExtractor.isXhamster("https://xhamster.desi/videos/x")).isTrue();
        assertThat(XhamsterTagExtractor.isXhamster("https://xhwebsite.com/foo")).isFalse();
        assertThat(XhamsterTagExtractor.isXhamster("https://pornhub.com/x")).isFalse();
        assertThat(XhamsterTagExtractor.isXhamster(null)).isFalse();
    }

    @Test
    void ignoresNestedTagsKey() {
        // A page that has a nested object with its own "tags" key must not
        // accidentally pick it up. The extractor uses "largest array wins"
        // as a tiebreaker: xhamster's real top-level list has 40-100+
        // entries, while any nested "tags" array is typically 1-5. This
        // test makes the top-level list 3x larger than the nested ones
        // to make the heuristic unambiguous.
        String html = """
                <html><head><script>
                var inner = {"tags":[{"name":"WRONG","isTag":true}]};
                var outer = {
                  "other":[{"tags":[{"name":"ALSO WRONG","isTag":true}]}],
                  "tags":[
                    {"name":"Right1","isPornstar":true},
                    {"name":"Right2","isPornstar":true},
                    {"name":"Right3","isPornstar":true}
                  ]
                };
                </script></head><body></body></html>
                """;
        Document doc = Jsoup.parse(html);
        XhamsterTags tags = XhamsterTagExtractor.extract(doc);
        assertThat(tags.pornstars())
                .containsExactly("Right1", "Right2", "Right3");
        assertThat(tags.all())
                .containsExactlyInAnyOrder("Right1", "Right2", "Right3");
    }

    // ------------------------------------------------------------------

    private static Document loadFixture(String name) throws IOException {
        try (InputStream in = XhamsterTagExtractorTest.class
                .getClassLoader().getResourceAsStream(name)) {
            if (in == null) throw new IOException("Missing test fixture: " + name);
            byte[] bytes = in.readAllBytes();
            return Jsoup.parse(new String(bytes, StandardCharsets.UTF_8), "");
        }
    }
}
