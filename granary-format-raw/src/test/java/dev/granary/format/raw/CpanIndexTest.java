package dev.granary.format.raw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CpanIndexTest {

    @Test
    void distributionPathsAreParsed() {
        Optional<CpanIndex.Dist> dist = CpanIndex.dist("authors/id/F/FO/FOOLISH/Acme-Meow-0.01.tar.gz");
        assertThat(dist).isPresent();
        assertThat(dist.get().author()).isEqualTo("FOOLISH");
        assertThat(dist.get().name()).isEqualTo("Acme-Meow");
        assertThat(dist.get().version()).isEqualTo("0.01");
        assertThat(dist.get().file()).isEqualTo("F/FO/FOOLISH/Acme-Meow-0.01.tar.gz");

        assertThat(CpanIndex.dist("authors/id/M/MI/MIYAGAWA/sub/libwww-perl-6.77.tgz")).get()
                .satisfies(d -> {
                    assertThat(d.name()).isEqualTo("libwww-perl");
                    assertThat(d.version()).isEqualTo("6.77");
                    assertThat(d.file()).isEqualTo("M/MI/MIYAGAWA/sub/libwww-perl-6.77.tgz");
                });
        assertThat(CpanIndex.dist("authors/id/E/ET/ETHER/Moose-2.2207-TRIAL.tar.gz")).get()
                .satisfies(d -> assertThat(d.version()).isEqualTo("2.2207-TRIAL"));
        assertThat(CpanIndex.dist("authors/id/E/ET/ETHER/Foo-Bar-v1.2.3.zip")).get()
                .satisfies(d -> {
                    assertThat(d.name()).isEqualTo("Foo-Bar");
                    assertThat(d.version()).isEqualTo("v1.2.3");
                });
        assertThat(CpanIndex.dist("authors/id/E/ET/ETHER/Foo-0.01_01.tar.bz2")).get()
                .satisfies(d -> assertThat(d.version()).isEqualTo("0.01_01"));
        assertThat(CpanIndex.dist("authors/id/E/ET/ETHER/CHECKSUMS")).isEmpty();
        assertThat(CpanIndex.dist("authors/id/E/ET/ETHER/Foo.tar.gz")).isEmpty();
        assertThat(CpanIndex.dist("authors/id/E/ET/Foo-1.0.tar.gz")).isEmpty();
        assertThat(CpanIndex.dist("modules/02packages.details.txt.gz")).isEmpty();
        assertThat(CpanIndex.dist("authors/id/E/ET/ETHER/README")).isEmpty();
    }

    @Test
    void modulesComeFromMetaJsonProvidesWhenPresent() throws IOException {
        byte[] tgz = TarFixtures.tarGz(
                "Acme-Hello-1.00/META.json", "{\"name\":\"Acme-Hello\",\"provides\":{\"Acme::Hello\":{\"file\":\"lib/Acme/Hello.pm\",\"version\":\"1.00\"},"
                        + "\"Acme::Hello::Util\":{\"file\":\"lib/Acme/Hello/Util.pm\"}}}",
                "Acme-Hello-1.00/lib/Acme/Hello.pm", "package Acme::Hello;\nour $VERSION = '9.99';\n1;\n");
        List<CpanIndex.Module> modules = CpanIndex.modules(new ByteArrayInputStream(tgz), "Acme-Hello-1.00.tar.gz");
        assertThat(modules).containsExactly(new CpanIndex.Module("Acme::Hello", "1.00"), new CpanIndex.Module("Acme::Hello::Util", "undef"));
    }

    @Test
    void modulesAreScannedFromLibWhenThereIsNoProvides() throws IOException {
        byte[] tgz = TarFixtures.tarGz(
                "Acme-Hello-1.00/META.json", "{\"name\":\"Acme-Hello\",\"version\":\"1.00\"}",
                "Acme-Hello-1.00/lib/Acme/Hello.pm", "use strict;\npackage Acme::Hello;\n\nour $VERSION = '1.00';\n\npackage Acme::Hello::Inner {\n}\npackage main;\n1;\n",
                "Acme-Hello-1.00/lib/Acme/Old.pm", "package Acme'Old 0.5;\n$Acme::Old::VERSION = \"0.5\";\n",
                "Acme-Hello-1.00/lib/Acme/NoVersion.pm", "package Acme::NoVersion;\n1;\n",
                "Acme-Hello-1.00/t/lib/Test/Helper.pm", "package Test::Helper;\n",
                "Acme-Hello-1.00/script/tool", "package Script::Tool;\n");
        List<CpanIndex.Module> modules = CpanIndex.modules(new ByteArrayInputStream(tgz), "Acme-Hello-1.00.tar.gz");
        assertThat(modules).containsExactly(
                new CpanIndex.Module("Acme::Hello", "1.00"),
                new CpanIndex.Module("Acme::Hello::Inner", "1.00"),
                new CpanIndex.Module("Acme::Old", "0.5"),
                new CpanIndex.Module("Acme::NoVersion", "undef"));
    }

    @Test
    void zipDistributionsAndUnsupportedOrBrokenArchives() throws IOException {
        byte[] zip = TarFixtures.zip("Foo-1.0/lib/Foo.pm", "package Foo;\nour $VERSION = \"1.0\";\n");
        assertThat(CpanIndex.modules(new ByteArrayInputStream(zip), "Foo-1.0.zip"))
                .containsExactly(new CpanIndex.Module("Foo", "1.0"));
        assertThat(CpanIndex.modules(new ByteArrayInputStream(new byte[10]), "Foo-1.0.tar.bz2")).isEmpty();
        assertThatThrownBy(() -> CpanIndex.modules(new ByteArrayInputStream("not gzip".getBytes(StandardCharsets.UTF_8)), "Foo-1.0.tar.gz"))
                .isInstanceOf(IOException.class);
        assertThat(CpanIndex.provides("{not json".getBytes(StandardCharsets.UTF_8))).isEmpty();
        assertThat(CpanIndex.provides("{\"provides\":[]}".getBytes(StandardCharsets.UTF_8))).isEmpty();
    }

    @Test
    void indexIsSortedAlignedAndParseable() {
        byte[] index = CpanIndex.packagesDetails(List.of(
                new CpanIndex.Provided("Zeta::Mod", "2.0", "Z/ZE/ZETA/Zeta-Mod-2.0.tar.gz"),
                new CpanIndex.Provided("Acme::Hello", null, "A/AC/ACME/Acme-Hello-1.00.tar.gz"),
                new CpanIndex.Provided("Acme::Hello::Util", "1.00", "A/AC/ACME/Acme-Hello-1.00.tar.gz")),
                Instant.parse("2026-10-09T10:00:00Z"));
        String text = new String(index, StandardCharsets.UTF_8);
        assertThat(text).startsWith("File:         02packages.details.txt\n");
        assertThat(text).contains("Written-By:   Granary\n");
        assertThat(text).contains("Line-Count:   3\n");
        assertThat(text).contains("Last-Updated: Fri, 9 Oct 2026 10:00:00 GMT\n\n");
        String[] lines = text.split("\n\n", 2)[1].split("\n");
        assertThat(lines).hasSize(3);
        assertThat(lines[0]).matches("Acme::Hello\\s+undef  A/AC/ACME/Acme-Hello-1.00.tar.gz");
        assertThat(lines[1]).matches("Acme::Hello::Util\\s+1.00  A/AC/ACME/Acme-Hello-1.00.tar.gz");
        assertThat(lines[2]).matches("Zeta::Mod\\s+2.0  Z/ZE/ZETA/Zeta-Mod-2.0.tar.gz");
        List<CpanIndex.Provided> parsed = CpanIndex.parsePackagesDetails(text);
        assertThat(parsed).extracting(CpanIndex.Provided::module).containsExactly("Acme::Hello", "Acme::Hello::Util", "Zeta::Mod");
        assertThat(parsed.get(0).version()).isEqualTo("undef");
        assertThat(parsed.get(2).file()).isEqualTo("Z/ZE/ZETA/Zeta-Mod-2.0.tar.gz");
        assertThat(CpanIndex.parsePackagesDetails("no blank line")).isEmpty();
    }

    @Test
    void perlVersionsCompareLikeVersionPm() {
        assertThat(CpanIndex.compareVersions("1.23", "1.3")).isLessThan(0);
        assertThat(CpanIndex.compareVersions("1.230", "1.23")).isZero();
        assertThat(CpanIndex.compareVersions("v1.2.3", "1.2.3")).isZero();
        assertThat(CpanIndex.compareVersions("1.002003", "v1.2.3")).isZero();
        assertThat(CpanIndex.compareVersions("0.01_01", "0.01")).isGreaterThan(0);
        assertThat(CpanIndex.compareVersions("2.0", "10.0")).isLessThan(0);
        assertThat(CpanIndex.compareVersions("undef", "0.001")).isLessThan(0);
        assertThat(CpanIndex.compareVersions(null, "undef")).isZero();
        assertThat(CpanIndex.compareVersions("1.0-TRIAL", "1.0")).isZero();
        assertThat(CpanIndex.compareVersions("garbage", "1.0")).isLessThan(0);
        assertThat(CpanIndex.components("1.2")).containsExactly(java.math.BigInteger.ONE, java.math.BigInteger.valueOf(200));
    }

    @Test
    void mergeKeepsTheFirstIndexThatListsAModule() {
        List<CpanIndex.Provided> hosted = List.of(
                new CpanIndex.Provided("Acme::Internal", "1.00", "C/CO/CORP/Acme-Internal-1.00.tar.gz"),
                new CpanIndex.Provided("Moose", "0.01", "C/CO/CORP/Moose-Fork-0.01.tar.gz"));
        List<CpanIndex.Provided> upstream = List.of(
                new CpanIndex.Provided("Moose", "2.2207", "E/ET/ETHER/Moose-2.2207.tar.gz"),
                new CpanIndex.Provided("Acme::Internal", "99.0", "E/EV/EVIL/Acme-Internal-99.0.tar.gz"),
                new CpanIndex.Provided("Acme::Meow", "0.01", "F/FO/FOO/Acme-Meow-0.01.tar.gz"));
        List<CpanIndex.Provided> merged = CpanIndex.merge(List.of(hosted, upstream));
        assertThat(merged).extracting(CpanIndex.Provided::module).containsExactly("Acme::Internal", "Moose", "Acme::Meow");
        assertThat(merged.get(0).file()).isEqualTo("C/CO/CORP/Acme-Internal-1.00.tar.gz");
        assertThat(merged.get(1).file()).isEqualTo("C/CO/CORP/Moose-Fork-0.01.tar.gz");
        assertThat(CpanIndex.merge(List.of())).isEmpty();

        String text = new String(CpanIndex.packagesDetails(merged, Instant.parse("2026-10-09T10:00:00Z")), StandardCharsets.UTF_8);
        assertThat(text).contains("Line-Count:   3\n");
        assertThat(CpanIndex.parsePackagesDetails(text)).extracting(CpanIndex.Provided::module)
                .containsExactly("Acme::Internal", "Acme::Meow", "Moose");
    }

    @Test
    void renderingAQuarterMillionModulesIsFast() {
        List<CpanIndex.Provided> many = new java.util.ArrayList<>();
        for (int i = 0; i < 250_000; i++) {
            many.add(new CpanIndex.Provided("Mod" + i + "::Name", "1." + i, "A/AU/AUTHOR/Dist-" + i + ".tar.gz"));
        }
        long start = System.nanoTime();
        byte[] text = CpanIndex.packagesDetails(many, Instant.parse("2026-10-09T10:00:00Z"));
        long millis = (System.nanoTime() - start) / 1_000_000;
        assertThat(CpanIndex.parsePackagesDetails(new String(text, StandardCharsets.UTF_8))).hasSize(250_000);
        assertThat(millis).isLessThan(10_000);
    }
}
