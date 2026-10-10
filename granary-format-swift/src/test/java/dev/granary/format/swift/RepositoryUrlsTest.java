package dev.granary.format.swift;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class RepositoryUrlsTest {

    @Test
    void spellingsOfOneRepositoryAgree() {
        for (String url : List.of(
                "https://github.com/mona/LinkedList",
                "https://github.com/mona/LinkedList.git",
                "https://github.com/mona/LinkedList/",
                "HTTPS://GitHub.com/mona/linkedlist.git/",
                "http://github.com/mona/LinkedList",
                "git@github.com:mona/LinkedList.git",
                "ssh://git@github.com/mona/LinkedList.git",
                "git+ssh://git@github.com/mona/LinkedList",
                "https://user:secret@github.com/mona/LinkedList",
                "https://github.com/mona/LinkedList?tab=readme#top",
                "github.com/mona/LinkedList",
                "https://github.com//mona//LinkedList")) {
            assertThat(RepositoryUrls.normalise(url)).as(url).contains("github.com/mona/linkedlist");
        }
    }

    @Test
    void portsAreKept() {
        assertThat(RepositoryUrls.normalise("https://git.example.com:8443/mona/x.git")).contains("git.example.com:8443/mona/x");
        assertThat(RepositoryUrls.normalise("git.example.com:8443/mona/x")).contains("git.example.com:8443/mona/x");
        assertThat(RepositoryUrls.normalise("git@git.example.com:mona/x")).contains("git.example.com/mona/x");
    }

    @Test
    void differentRepositoriesDiffer() {
        assertThat(RepositoryUrls.normalise("https://github.com/mona/LinkedList"))
                .isNotEqualTo(RepositoryUrls.normalise("https://gitlab.com/mona/LinkedList"));
    }

    @Test
    void nothingUsable() {
        assertThat(RepositoryUrls.normalise(null)).isEmpty();
        assertThat(RepositoryUrls.normalise("  ")).isEmpty();
        assertThat(RepositoryUrls.normalise("https://")).isEmpty();
        assertThat(RepositoryUrls.normalise("/")).isEmpty();
        assertThat(RepositoryUrls.normalise("x".repeat(RepositoryUrls.MAX_LENGTH + 1))).isEmpty();
    }
}
