package dev.granary.format.swift;

import static org.assertj.core.api.Assertions.assertThat;

import dev.granary.format.swift.SwiftManifests.Alternate;
import java.util.List;
import org.junit.jupiter.api.Test;

class SwiftManifestsTest {

    private static final String URL = "https://granary.test/repository/swift/mona/LinkedList/1.1.1/Package.swift";

    @Test
    void manifestFileNames() {
        assertThat(SwiftManifests.isManifestFileName("Package.swift")).isTrue();
        assertThat(SwiftManifests.isAlternateFileName("Package@swift-5.swift")).isTrue();
        assertThat(SwiftManifests.isAlternateFileName("Package@swift-5.9.swift")).isTrue();
        assertThat(SwiftManifests.isAlternateFileName("Package@swift-5.9.1.swift")).isTrue();
        assertThat(SwiftManifests.isAlternateFileName("Package@swift-5.9.1.2.swift")).isFalse();
        assertThat(SwiftManifests.isAlternateFileName("Package@swift-.swift")).isFalse();
        assertThat(SwiftManifests.isAlternateFileName("package@swift-5.swift")).isFalse();
        assertThat(SwiftManifests.isAlternateFileName("Package.swift")).isFalse();
        assertThat(SwiftManifests.isManifestFileName("Package.resolved")).isFalse();
        assertThat(SwiftManifests.isAlternateFileName(null)).isFalse();
        assertThat(SwiftManifests.swiftVersionOf("Package@swift-5.9.swift")).contains("5.9");
        assertThat(SwiftManifests.swiftVersionOf("Package.swift")).isEmpty();
        assertThat(SwiftManifests.swiftVersionOf(null)).isEmpty();
    }

    @Test
    void toolsVersionLine() {
        assertThat(SwiftManifests.toolsVersion("// swift-tools-version:5.9\nimport PackageDescription")).contains("5.9");
        assertThat(SwiftManifests.toolsVersion("// swift-tools-version: 5.10.1\n")).contains("5.10.1");
        assertThat(SwiftManifests.toolsVersion("//swift-tools-version:6.0")).contains("6.0");
        assertThat(SwiftManifests.toolsVersion("// Swift-Tools-Version : 05.07")).contains("5.7");
        assertThat(SwiftManifests.toolsVersion("\uFEFF\n\n  // swift-tools-version:5.8\n")).contains("5.8");
        assertThat(SwiftManifests.toolsVersion("// swift-tools-version:5.9;(experimentalFeatures)")).contains("5.9");
        assertThat(SwiftManifests.toolsVersion("// swift-tools-version:5\n")).isEmpty();
        assertThat(SwiftManifests.toolsVersion("import PackageDescription\n// swift-tools-version:5.9")).isEmpty();
        assertThat(SwiftManifests.toolsVersion("")).isEmpty();
        assertThat(SwiftManifests.toolsVersion(null)).isEmpty();
    }

    @Test
    void normalisesSwiftVersions() {
        assertThat(SwiftManifests.normalise("5")).contains("5.0.0");
        assertThat(SwiftManifests.normalise("5.9")).contains("5.9.0");
        assertThat(SwiftManifests.normalise(" 5.09.1 ")).contains("5.9.1");
        assertThat(SwiftManifests.normalise("5.9.0.1")).isEmpty();
        assertThat(SwiftManifests.normalise("five")).isEmpty();
        assertThat(SwiftManifests.normalise(null)).isEmpty();
    }

    @Test
    void selectsTheRequestedVariant() {
        Alternate v58 = new Alternate("Package@swift-5.8.swift", "5.8", null);
        Alternate v5 = new Alternate("Package@swift-5.swift", "5.3", null);
        List<Alternate> alternates = List.of(v58, v5);
        assertThat(SwiftManifests.select(alternates, "5.8")).contains(v58);
        assertThat(SwiftManifests.select(alternates, "5.8.0")).contains(v58);
        assertThat(SwiftManifests.select(alternates, "5")).contains(v5);
        assertThat(SwiftManifests.select(alternates, "5.0.0")).contains(v5);
        assertThat(SwiftManifests.select(alternates, "5.3")).contains(v5); // by declared tools version
        assertThat(SwiftManifests.select(alternates, "5.9")).isEmpty();
        assertThat(SwiftManifests.select(alternates, "nonsense")).isEmpty();
        assertThat(SwiftManifests.select(alternates, " ")).isEmpty();
        assertThat(SwiftManifests.select(alternates, null)).isEmpty();
    }

    @Test
    void linkHeaderInTheShapeSwiftPmParses() {
        String header = SwiftManifests.linkHeader(URL, List.of(
                new Alternate("Package@swift-4.swift", "4.0", null),
                new Alternate("Package@swift-4.2.swift", "4.2", null))).orElseThrow();
        assertThat(header).isEqualTo(
                "<" + URL + "?swift-version=4>; rel=\"alternate\"; filename=\"Package@swift-4.swift\"; swift-tools-version=\"4.0\", "
                        + "<" + URL + "?swift-version=4.2>; rel=\"alternate\"; filename=\"Package@swift-4.2.swift\"; swift-tools-version=\"4.2\"");
        for (String entry : header.split(",")) {
            assertThat(entry.split(";")).hasSize(4);
        }
        assertThat(SwiftManifests.linkHeader(URL, List.of())).isEmpty();
        assertThat(SwiftManifests.linkHeader(URL, List.of(new Alternate("Package@swift-5.swift", "5\"; x", null)))).isEmpty();
    }

    @Test
    void parsesAnUpstreamLinkHeader() {
        String header = "<https://up.test/a/b/1.0.0/Package.swift?swift-version=4>; rel=\"alternate\"; filename=\"Package@swift-4.swift\"; swift-tools-version=\"4.0\", "
                + "<https://up.test/a/b/1.0.0/Package.swift?swift-version=4.2,x>; rel=alternate; filename=Package@swift-4.2.swift; swift-tools-version=4.2, "
                + "<https://up.test/a/b/1.0.0>; rel=\"latest-version\", "
                + "<https://evil.test/x>; rel=\"alternate\"; filename=\"../../etc/passwd\"; swift-tools-version=\"4.0\", "
                + "garbage";
        List<Alternate> alternates = SwiftManifests.parseLinkHeader(header);
        assertThat(alternates).containsExactly(
                new Alternate("Package@swift-4.swift", "4.0", "https://up.test/a/b/1.0.0/Package.swift?swift-version=4"),
                new Alternate("Package@swift-4.2.swift", "4.2", "https://up.test/a/b/1.0.0/Package.swift?swift-version=4.2,x"));
        assertThat(alternates.get(1).swiftVersion()).isEqualTo("4.2");
        assertThat(SwiftManifests.parseLinkHeader(null)).isEmpty();
        assertThat(SwiftManifests.parseLinkHeader(" ")).isEmpty();
    }

    @Test
    void roundTripsItsOwnHeader() {
        List<Alternate> alternates = List.of(new Alternate("Package@swift-5.8.swift", "5.8", null));
        String header = SwiftManifests.linkHeader(URL, alternates).orElseThrow();
        assertThat(SwiftManifests.parseLinkHeader(header))
                .containsExactly(new Alternate("Package@swift-5.8.swift", "5.8", URL + "?swift-version=5.8"));
    }
}
