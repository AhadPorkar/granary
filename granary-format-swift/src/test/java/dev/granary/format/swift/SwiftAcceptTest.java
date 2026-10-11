package dev.granary.format.swift;

import static org.assertj.core.api.Assertions.assertThat;

import dev.granary.format.swift.SwiftAccept.Result;
import org.junit.jupiter.api.Test;

class SwiftAcceptTest {

    @Test
    void versionOneInEveryShape() {
        assertThat(SwiftAccept.check(SwiftAccept.JSON)).isEqualTo(Result.SUPPORTED);
        assertThat(SwiftAccept.check(SwiftAccept.ZIP)).isEqualTo(Result.SUPPORTED);
        assertThat(SwiftAccept.check(SwiftAccept.SWIFT)).isEqualTo(Result.SUPPORTED);
        assertThat(SwiftAccept.check("application/vnd.swift.registry.v1")).isEqualTo(Result.SUPPORTED);
        assertThat(SwiftAccept.check("application/vnd.swift.registry+json")).isEqualTo(Result.SUPPORTED);
        assertThat(SwiftAccept.check("application/vnd.swift.registry")).isEqualTo(Result.SUPPORTED);
        assertThat(SwiftAccept.check("Application/VND.Swift.Registry.V1+JSON; q=0.9")).isEqualTo(Result.SUPPORTED);
    }

    @Test
    void requestsWithoutRegistryMediaTypesGetVersionOne() {
        assertThat(SwiftAccept.check(null)).isEqualTo(Result.SUPPORTED);
        assertThat(SwiftAccept.check("")).isEqualTo(Result.SUPPORTED);
        assertThat(SwiftAccept.check("*/*")).isEqualTo(Result.SUPPORTED);
        assertThat(SwiftAccept.check("text/html,application/xhtml+xml")).isEqualTo(Result.SUPPORTED);
    }

    @Test
    void otherVersions() {
        assertThat(SwiftAccept.check("application/vnd.swift.registry.v2+json")).isEqualTo(Result.UNSUPPORTED_VERSION);
        assertThat(SwiftAccept.check("application/vnd.swift.registry.v99")).isEqualTo(Result.UNSUPPORTED_VERSION);
        assertThat(SwiftAccept.check("application/vnd.swift.registry.v2+json, application/vnd.swift.registry.v1+json"))
                .isEqualTo(Result.SUPPORTED);
    }

    @Test
    void malformedVersions() {
        assertThat(SwiftAccept.check("application/vnd.swift.registry.vx+json")).isEqualTo(Result.INVALID_VERSION);
        assertThat(SwiftAccept.check("application/vnd.swift.registry.v+json")).isEqualTo(Result.INVALID_VERSION);
        assertThat(SwiftAccept.check("application/vnd.swift.registry.1+json")).isEqualTo(Result.INVALID_VERSION);
        assertThat(SwiftAccept.check("application/vnd.swift.registry.v1+xml")).isEqualTo(Result.INVALID_VERSION);
        assertThat(SwiftAccept.check("application/vnd.swift.registryx")).isEqualTo(Result.INVALID_VERSION);
        assertThat(SwiftAccept.check("application/vnd.swift.registry.v\u0661+json")).isEqualTo(Result.INVALID_VERSION);
        assertThat(SwiftAccept.check("application/vnd.swift.registry.vx, application/vnd.swift.registry.v2"))
                .isEqualTo(Result.UNSUPPORTED_VERSION);
    }
}
