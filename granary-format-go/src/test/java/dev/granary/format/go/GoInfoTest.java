package dev.granary.format.go;

import static dev.granary.format.go.GoTestSupport.MAPPER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class GoInfoTest {

    @Test
    void rendersTheShapeTheGoCommandExpects() {
        GoInfo info = new GoInfo("v1.2.3", Instant.parse("2020-01-02T03:04:05.678Z"));
        assertThat(info.toJsonString(MAPPER)).isEqualTo("{\"Version\":\"v1.2.3\",\"Time\":\"2020-01-02T03:04:05Z\"}");
        assertThat(new GoInfo("v1.2.3", null).toJsonString(MAPPER)).isEqualTo("{\"Version\":\"v1.2.3\"}");
        assertThat(GoInfo.now("v0.1.0").time()).isBeforeOrEqualTo(Instant.now());
    }

    @Test
    void parsesUpstreamDocumentsLeniently() {
        assertThat(GoInfo.parse(MAPPER, bytes("{\"Version\":\"v1.5.2\",\"Time\":\"2018-02-14T15:44:20Z\",\"Origin\":{\"VCS\":\"git\"}}")))
                .contains(new GoInfo("v1.5.2", Instant.parse("2018-02-14T15:44:20Z")));
        assertThat(GoInfo.parse(MAPPER, bytes("{\"Version\":\"v1.5.2\"}"))).contains(new GoInfo("v1.5.2", null));
        assertThat(GoInfo.parse(MAPPER, bytes("{\"Version\":\" v1.5.2 \",\"Time\":\"\"}"))).contains(new GoInfo("v1.5.2", null));
        assertThat(GoInfo.parse(MAPPER, bytes("{\"Version\":\"v1.5.2\",\"Time\":\"2018-02-14T15:44:20+01:00\"}")))
                .contains(new GoInfo("v1.5.2", Instant.parse("2018-02-14T14:44:20Z")));
    }

    @Test
    void rejectsDocumentsWithoutAVersionOrWithABadTime() {
        assertThat(GoInfo.parse(MAPPER, bytes("{}"))).isEmpty();
        assertThat(GoInfo.parse(MAPPER, bytes("[]"))).isEmpty();
        assertThat(GoInfo.parse(MAPPER, bytes("not json"))).isEmpty();
        assertThat(GoInfo.parse(MAPPER, bytes(""))).isEmpty();
        assertThat(GoInfo.parse(MAPPER, bytes("{\"Version\":42}"))).isEmpty();
        assertThat(GoInfo.parse(MAPPER, bytes("{\"Version\":\"\"}"))).isEmpty();
        assertThat(GoInfo.parse(MAPPER, bytes("{\"Version\":\"v1\",\"Time\":\"yesterday\"}"))).isEmpty();
    }

    @Test
    void uploadsAreValidatedAgainstTheUrl() {
        byte[] normalised = GoInfo.validateUpload(MAPPER, bytes("{\"Time\":\"2020-01-01T00:00:00Z\",\"Version\":\"v1.0.0\"}"), "v1.0.0");
        assertThat(new String(normalised, StandardCharsets.UTF_8)).isEqualTo("{\"Version\":\"v1.0.0\",\"Time\":\"2020-01-01T00:00:00Z\"}");
        assertThatThrownBy(() -> GoInfo.validateUpload(MAPPER, bytes("{\"Version\":\"v1.0.1\"}"), "v1.0.0"))
                .isInstanceOf(GoError.class).hasMessageContaining("names version v1.0.1");
        assertThatThrownBy(() -> GoInfo.validateUpload(MAPPER, bytes("{nope"), "v1.0.0"))
                .isInstanceOf(GoError.class).hasMessageContaining("JSON object");
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
