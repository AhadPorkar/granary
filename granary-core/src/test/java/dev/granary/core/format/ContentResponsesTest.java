package dev.granary.core.format;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ContentResponsesTest {

    private static final String ETAG = "\"abc\"";

    @Test
    void ifNoneMatchUsesWeakComparisonAndWildcard() {
        assertThat(ContentResponses.matchesIfNoneMatch(null, ETAG)).isFalse();
        assertThat(ContentResponses.matchesIfNoneMatch(" ", ETAG)).isFalse();
        assertThat(ContentResponses.matchesIfNoneMatch("\"abc\"", ETAG)).isTrue();
        assertThat(ContentResponses.matchesIfNoneMatch("W/\"abc\"", ETAG)).isTrue();
        assertThat(ContentResponses.matchesIfNoneMatch("\"x\", W/\"abc\"", ETAG)).isTrue();
        assertThat(ContentResponses.matchesIfNoneMatch("*", ETAG)).isTrue();
        assertThat(ContentResponses.matchesIfNoneMatch("\"abcd\"", ETAG)).isFalse();
        assertThat(ContentResponses.matchesIfNoneMatch("\"ab\"", ETAG)).isFalse();
    }
}
