package dev.granary.format.raw;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CranPackagesTest {

    static final String DESCRIPTION = "Package: praise\n"
            + "Title: Praise Users\n"
            + "Version: 1.0.0\n"
            + "Depends: R (>= 3.0.0)\n"
            + "Imports: utils,\n"
            + "    stats\n"
            + "Suggests: testthat\n"
            + "License: MIT + file LICENSE\n"
            + "NeedsCompilation: no\n"
            + "Encoding: UTF-8\n";

    @Test
    void dcfParsingKeepsContinuationLinesAndStopsAtTheFirstRecord() {
        Map<String, String> fields = CranPackages.parseDcf(DESCRIPTION + "\nPackage: other\n");
        assertThat(fields).containsEntry("Package", "praise").containsEntry("Version", "1.0.0")
                .containsEntry("Imports", "utils,\nstats").containsEntry("Encoding", "UTF-8");
        assertThat(fields.keySet()).first().isEqualTo("Package");
        assertThat(CranPackages.parseDcf("")).isEmpty();
        assertThat(CranPackages.parseDcf("   continuation without key\nnot a field\n")).isEmpty();
        assertThat(CranPackages.parseDcf("Key:value-without-space\r\nOther: x \r\n")).containsEntry("Key", "value-without-space").containsEntry("Other", "x");
    }

    @Test
    void packagesFilesAreWrittenInCranOrderAndParsedBack() {
        Map<String, String> record = CranPackages.parseDcf(DESCRIPTION);
        record.put("MD5sum", "abc");
        String written = CranPackages.write(List.of(record, Map.of("Package", "zed", "Version", "2.0", "Title", "dropped")));
        assertThat(written).isEqualTo("Package: praise\n"
                + "Version: 1.0.0\n"
                + "Depends: R (>= 3.0.0)\n"
                + "Imports: utils,\n        stats\n"
                + "Suggests: testthat\n"
                + "License: MIT + file LICENSE\n"
                + "MD5sum: abc\n"
                + "NeedsCompilation: no\n"
                + "\n"
                + "Package: zed\n"
                + "Version: 2.0\n"
                + "\n");
        List<Map<String, String>> records = CranPackages.parseRecords(written);
        assertThat(records).hasSize(2);
        assertThat(records.get(0)).containsEntry("Imports", "utils,\nstats");
        assertThat(records.get(1)).containsEntry("Package", "zed");
        assertThat(CranPackages.write(List.of())).isEmpty();
    }

    @Test
    void sourceAndBinaryPathsYieldCoordinates() {
        assertThat(CranPackages.coordinates("src/contrib/praise_1.0.0.tar.gz")).get()
                .satisfies(c -> {
                    assertThat(c.name()).isEqualTo("praise");
                    assertThat(c.version()).isEqualTo("1.0.0");
                });
        assertThat(CranPackages.coordinates("src/contrib/data.table_1.15.4-1.tar.gz")).get()
                .satisfies(c -> assertThat(c.version()).isEqualTo("1.15.4-1"));
        assertThat(CranPackages.coordinates("bin/windows/contrib/4.5/praise_1.0.0.zip")).get()
                .satisfies(c -> assertThat(c.name()).isEqualTo("praise"));
        assertThat(CranPackages.coordinates("bin/macosx/big-sur-arm64/contrib/4.5/praise_1.0.0.tgz")).isPresent();
        assertThat(CranPackages.coordinates("src/contrib/PACKAGES")).isEmpty();
        assertThat(CranPackages.coordinates("src/contrib/Archive/praise/praise_0.9.tar.gz")).isEmpty();
        assertThat(CranPackages.coordinates("src/contrib/1bad_1.0.tar.gz")).isEmpty();
        assertThat(CranPackages.isSourcePackage("src/contrib/praise_1.0.0.tar.gz")).isTrue();
        assertThat(CranPackages.isSourcePackage("bin/windows/contrib/4.5/praise_1.0.0.zip")).isFalse();
        assertThat(CranPackages.archivedFile("src/contrib/Archive/praise/praise_0.9.tar.gz")).contains("praise_0.9.tar.gz");
        assertThat(CranPackages.archivedFile("src/contrib/praise_0.9.tar.gz")).isEmpty();
    }

    @Test
    void descriptionIsReadFromTheTarball() throws IOException {
        byte[] tgz = TarFixtures.tarGz("praise/R/praise.R", "f <- function() 1", "praise/DESCRIPTION", DESCRIPTION, "praise/inst/DESCRIPTION", "Package: wrong\n");
        assertThat(CranPackages.description(new ByteArrayInputStream(tgz))).get().satisfies(d -> assertThat(d).containsEntry("Package", "praise"));
        byte[] none = TarFixtures.tarGz("praise/R/praise.R", "f <- function() 1");
        assertThat(CranPackages.description(new ByteArrayInputStream(none))).isEmpty();
    }

    @Test
    void versionsCompareNumerically() {
        assertThat(CranPackages.compareVersions("1.10.0", "1.9.9")).isGreaterThan(0);
        assertThat(CranPackages.compareVersions("1.0-1", "1.0")).isGreaterThan(0);
        assertThat(CranPackages.compareVersions("1.0.0", "1.0")).isZero();
        assertThat(CranPackages.compareVersions("0.9", "1.0.0")).isLessThan(0);
    }

    @Test
    void mergeIsTheUnionOfVersionsWithTheFirstMemberWinningAVersion() {
        List<Map<String, String>> hosted = CranPackages.parseRecords(
                "Package: praise\nVersion: 1.0.0\nMD5sum: hosted\n\nPackage: internal\nVersion: 0.1\nMD5sum: i\n");
        List<Map<String, String>> upstream = CranPackages.parseRecords(
                "Package: praise\nVersion: 1.0.0\nMD5sum: upstream\n\nPackage: praise\nVersion: 1.1.0\nMD5sum: newer\n\n"
                        + "Package: cli\nVersion: 3.6.3\nImports: utils\nRepository: CRAN\nLinkingTo: Rcpp,\n    cpp11\n\n"
                        + "Version: 9\n");
        List<Map<String, String>> merged = CranPackages.merge(List.of(hosted, upstream));
        assertThat(merged).extracting(r -> r.get("Package") + "@" + r.get("Version"))
                .containsExactly("praise@1.0.0", "internal@0.1", "praise@1.1.0", "cli@3.6.3");
        assertThat(merged.get(0).get("MD5sum")).isEqualTo("hosted");

        String written = CranPackages.writeAll(merged);
        assertThat(written).contains("Package: cli\nVersion: 3.6.3\nImports: utils\nRepository: CRAN\nLinkingTo: Rcpp,\n        cpp11\n\n");
        assertThat(CranPackages.parseRecords(written)).hasSize(4);
        assertThat(CranPackages.writeAll(List.of())).isEmpty();
    }
}
