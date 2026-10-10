package dev.granary.format.raw;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class BowerRegistryTest {

    @Test
    void namesAndUrlsAreValidated() {
        assertThat(BowerRegistry.validName("jquery")).isTrue();
        assertThat(BowerRegistry.validName("my.widget_2-x")).isTrue();
        assertThat(BowerRegistry.validName("-bad")).isFalse();
        assertThat(BowerRegistry.validName("a/b")).isFalse();
        assertThat(BowerRegistry.validName("")).isFalse();
        assertThat(BowerRegistry.validName(null)).isFalse();
        assertThat(BowerRegistry.validUrl("https://github.com/jquery/jquery-dist.git")).isTrue();
        assertThat(BowerRegistry.validUrl("git://github.com/jquery/jquery.git")).isTrue();
        assertThat(BowerRegistry.validUrl("git+ssh://git@git.example.com/ui/x.git")).isTrue();
        assertThat(BowerRegistry.validUrl("git@github.com:jquery/jquery.git")).isTrue();
        assertThat(BowerRegistry.validUrl("/srv/git/widget.git")).isTrue();
        assertThat(BowerRegistry.validUrl("jquery/jquery")).isFalse();
        assertThat(BowerRegistry.validUrl("http://x y")).isFalse();
        assertThat(BowerRegistry.validUrl(null)).isFalse();
    }

    @Test
    void pathsAreRecognised() {
        assertThat(BowerRegistry.packageName("packages/jquery")).contains("jquery");
        assertThat(BowerRegistry.packageName("packages")).isEmpty();
        assertThat(BowerRegistry.packageName("packages/")).isEmpty();
        assertThat(BowerRegistry.packageName("packages/search/jq")).isEmpty();
        assertThat(BowerRegistry.packageName("packages/a/b")).isEmpty();
        assertThat(BowerRegistry.searchQuery("packages/search/jq")).contains("jq");
        assertThat(BowerRegistry.searchQuery("packages/search/")).isEmpty();
        assertThat(BowerRegistry.searchQuery("packages/jquery")).isEmpty();
        assertThat(BowerRegistry.packagePath("x")).isEqualTo("packages/x");
    }

    @Test
    void jsonRoundTripsAndToleratesGarbage() {
        BowerRegistry.Entry entry = new BowerRegistry.Entry("jquery", "https://github.com/jquery/jquery-dist.git");
        byte[] json = BowerRegistry.json(entry);
        assertThat(new String(json, StandardCharsets.UTF_8)).isEqualTo("{\"name\":\"jquery\",\"url\":\"https://github.com/jquery/jquery-dist.git\"}");
        assertThat(BowerRegistry.parse(json)).contains(entry);
        assertThat(BowerRegistry.parse("{not json".getBytes(StandardCharsets.UTF_8))).isEmpty();
        assertThat(BowerRegistry.parse("{\"name\":\"\",\"url\":\"x\"}".getBytes(StandardCharsets.UTF_8))).isEmpty();
        assertThat(BowerRegistry.parse("[]".getBytes(StandardCharsets.UTF_8))).isEmpty();

        byte[] list = BowerRegistry.json(List.of(entry, new BowerRegistry.Entry("b", "git://b")));
        assertThat(BowerRegistry.parseList(list)).hasSize(2);
        assertThat(BowerRegistry.parseList("[{\"name\":\"ok\",\"url\":\"u\"},{\"name\":5},\"x\",null]".getBytes(StandardCharsets.UTF_8)))
                .containsExactly(new BowerRegistry.Entry("ok", "u"));
        assertThat(BowerRegistry.parseList(json)).containsExactly(entry);
        assertThat(BowerRegistry.parseList("nope".getBytes(StandardCharsets.UTF_8))).isEmpty();
    }

    @Test
    void searchAndMerge() {
        List<BowerRegistry.Entry> entries = List.of(new BowerRegistry.Entry("jQuery-ui", "a"), new BowerRegistry.Entry("angular", "b"),
                new BowerRegistry.Entry("jquery", "c"));
        assertThat(BowerRegistry.search(entries, "JQ")).extracting(BowerRegistry.Entry::name).containsExactly("jQuery-ui", "jquery");
        assertThat(BowerRegistry.search(entries, "zzz")).isEmpty();
        List<BowerRegistry.Entry> merged = BowerRegistry.merge(List.of(
                List.of(new BowerRegistry.Entry("jquery", "hosted")),
                List.of(new BowerRegistry.Entry("jquery", "proxy"), new BowerRegistry.Entry("angular", "proxy"))));
        assertThat(merged).containsExactly(new BowerRegistry.Entry("angular", "proxy"), new BowerRegistry.Entry("jquery", "hosted"));
    }
}
