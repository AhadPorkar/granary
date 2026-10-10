package dev.granary.format.rubygems;

import static dev.granary.format.rubygems.RubygemsTestSupport.gem;
import static dev.granary.format.rubygems.RubygemsTestSupport.gemWithMetadata;
import static dev.granary.format.rubygems.RubygemsTestSupport.gemspecYaml;
import static dev.granary.format.rubygems.RubygemsTestSupport.gzip;
import static dev.granary.format.rubygems.RubygemsTestSupport.platformGem;
import static dev.granary.format.rubygems.RubygemsTestSupport.resource;
import static dev.granary.format.rubygems.RubygemsTestSupport.sha256;
import static dev.granary.format.rubygems.RubygemsTestSupport.tar;
import static dev.granary.format.rubygems.RubygemsTestSupport.tarHeader;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.granary.format.rubygems.marshal.MarshalException;
import dev.granary.format.rubygems.marshal.MarshalReader;
import dev.granary.format.rubygems.marshal.Ruby;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.InflaterInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RubygemsGemPackageTest {

    private static GemPackage.Contents read(byte[] gem) throws IOException {
        return GemPackage.read(new ByteArrayInputStream(gem));
    }

    // --- real gems ----------------------------------------------------------------------------------

    @Test
    void readsAGemBuiltByGemBuild() throws IOException {
        byte[] bytes = resource("acme_hello-0.1.0.gem");
        GemPackage.Contents contents = read(bytes);
        GemSpec spec = contents.spec();

        assertThat(contents.entries()).containsExactly("metadata.gz", "data.tar.gz", "checksums.yaml.gz");
        assertThat(spec.name()).isEqualTo("acme_hello");
        assertThat(spec.version()).isEqualTo("0.1.0");
        assertThat(spec.platform()).isEqualTo("ruby");
        assertThat(spec.fullName()).isEqualTo("acme_hello-0.1.0");
        assertThat(spec.summary()).isEqualTo("A tiny greeting gem");
        assertThat(spec.description()).isEqualTo("Says hello; used to verify a Granary RubyGems repository.");
        assertThat(spec.authors()).containsExactly("Acme Developers");
        assertThat(spec.email()).containsExactly("dev@acme.example");
        assertThat(spec.emailIsList()).isFalse();
        assertThat(spec.homepage()).isEqualTo("https://acme.example/hello");
        assertThat(spec.licenses()).containsExactly("MIT");
        assertThat(spec.runtimeDependencies()).singleElement().satisfies(d -> {
            assertThat(d.name()).isEqualTo("rake");
            assertThat(d.requirement().toString()).isEqualTo(">= 12.0");
        });
        assertThat(spec.developmentDependencies()).isEmpty();
        assertThat(spec.requiredRubyVersion().toString()).isEqualTo(">= 2.7");
        assertThat(spec.requiredRubygemsVersion().isAny()).isTrue();
        assertThat(spec.rubygemsVersion()).isEqualTo("3.5.22");
        assertThat(spec.date()).isEqualTo(LocalDate.of(2026, 10, 10));
        assertThat(contents.yaml()).startsWith("--- !ruby/object:Gem::Specification");
        assertThat(GemPackage.sha256(new ByteArrayInputStream(bytes)))
                .isEqualTo("a8b38fb64a3678aad435a1e1524cadea1746fa17aa9340db8bd1c5354a2c221f");
    }

    @Test
    void readsSyntheticGemsWithPlatformsAndDependencies() throws IOException {
        GemSpec spec = read(platformGem("native_ext", "2.0.0.rc1", "x86_64-linux", "racc:~> 1.4", "mini_portile2:>= 2.8")).spec();
        assertThat(spec.fullName()).isEqualTo("native_ext-2.0.0.rc1-x86_64-linux");
        assertThat(spec.versionKey()).isEqualTo("2.0.0.rc1-x86_64-linux");
        assertThat(spec.isPrerelease()).isTrue();
        assertThat(spec.metadata()).containsEntry("source_code_uri", "https://acme.example/native_ext/src");
        assertThat(spec.dependencies()).extracting(GemDependency::name).containsExactly("racc", "mini_portile2");
    }

    // --- gemspec YAML -------------------------------------------------------------------------------

    @Test
    void parsesTheOldStylePlatformObjectAndLicenseString() {
        String yaml = gemspecYaml("old", "1.0", "ruby")
                .replace("platform: ruby\n", "platform: !ruby/object:Gem::Platform\n  cpu: x86\n  os: mswin32\n  version: '60'\n")
                .replace("licenses:\n- MIT\n", "license: BSD-2-Clause\n");
        GemSpec spec = GemspecYaml.parse(yaml);
        assertThat(spec.platform()).isEqualTo("x86-mswin32-60");
        assertThat(spec.licenses()).containsExactly("BSD-2-Clause");
    }

    @Test
    void keepsEveryValueAStringAndDecodesBinaryScalars() {
        String yaml = gemspecYaml("typed", "1.0", "ruby")
                .replace("summary: A gem called typed\n", "summary: yes\n")
                .replace("description: The typed gem.\n", "description: !binary |-\n  w5xuw69jw7Zkw6kgdGV4dA==\n")
                .replace("- Acme Developers\n", "- 1234\n- 'on'\n");
        GemSpec spec = GemspecYaml.parse(yaml);
        assertThat(spec.summary()).isEqualTo("yes");
        assertThat(spec.description()).isEqualTo("Ünïcödé text");
        assertThat(spec.authors()).containsExactly("1234", "on");
    }

    @Test
    void readsEmailListsAndRequirementStrings() {
        String yaml = gemspecYaml("mail", "1.0", "ruby")
                .replace("email: dev@acme.example\n", "email:\n- a@acme.example\n- b@acme.example\n")
                .replace("dependencies: []\n", "dependencies:\n- !ruby/object:Gem::Dependency\n  name: rack\n"
                        + "  requirement: \">= 2.0, < 4\"\n  type: :development\n");
        GemSpec spec = GemspecYaml.parse(yaml);
        assertThat(spec.email()).containsExactly("a@acme.example", "b@acme.example");
        assertThat(spec.emailIsList()).isTrue();
        assertThat(spec.developmentDependencies()).singleElement()
                .satisfies(d -> assertThat(d.requirement().toString()).isEqualTo(">= 2.0, < 4"));
    }

    @Test
    void normalisesVersionsAndDates() {
        String yaml = gemspecYaml("norm", "01.2", "ruby").replace("date: 2026-10-10 00:00:00.000000000 Z\n", "date: 2001-02-03\n");
        GemSpec spec = GemspecYaml.parse(yaml);
        assertThat(spec.version()).isEqualTo("01.2");
        assertThat(spec.date()).isEqualTo(LocalDate.of(2001, 2, 3));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "",
        "just a string",
        "- a\n- list\n",
        "--- !ruby/object:Gem::Specification\nversion: 1.0\n",
        "--- !ruby/object:Gem::Specification\nname: '123'\nversion: 1.0\n",
        "--- !ruby/object:Gem::Specification\nname: '../evil'\nversion: 1.0\n",
        "--- !ruby/object:Gem::Specification\nname: _private\nversion: 1.0\n",
        "--- !ruby/object:Gem::Specification\nname: -dash\nversion: 1.0\n",
        "--- !ruby/object:Gem::Specification\nname: .dot\nversion: 1.0\n",
        "--- !ruby/object:Gem::Specification\nname: ok\n",
        "--- !ruby/object:Gem::Specification\nname: ok\nversion: not a version\n",
        "--- !ruby/object:Gem::Specification\nname: ok\nversion: '1.0'\nplatform: '../x'\n",
        "--- !ruby/object:Gem::Specification\nname: ok\nversion: '1.0'\ndependencies:\n- just a string\n",
        "--- !ruby/object:Gem::Specification\nname: ok\nversion: '1.0'\ndependencies:\n- name: 'a b'\n",
        "--- !ruby/object:Gem::Specification\nname: ok\nversion: '1.0'\nrequired_ruby_version:\n  requirements:\n  - - '=~'\n    - '1'\n",
        "--- !ruby/object:Gem::Specification\nname: ok\nversion: '1.0'\nrequired_ruby_version: [1, 2]\n",
        "name: [unclosed\n",
        "{a: 1, a: [}\n",
    })
    void refusesWhatIsNotAUsableGemspec(String yaml) {
        assertThatThrownBy(() -> GemspecYaml.parse(yaml)).isInstanceOf(InvalidGemException.class);
    }

    @Test
    void refusesAliasBombsAndDeepNesting() {
        StringBuilder bomb = new StringBuilder("a0: &a0 [x, x, x, x, x, x, x, x, x]\n");
        for (int i = 1; i < 12; i++) {
            bomb.append("a").append(i).append(": &a").append(i).append(" [");
            for (int j = 0; j < 9; j++) {
                bomb.append(j == 0 ? "" : ", ").append("*a").append(i - 1);
            }
            bomb.append("]\n");
        }
        assertThatThrownBy(() -> GemspecYaml.parse(bomb.toString())).isInstanceOf(InvalidGemException.class);
        String deep = "name: " + "[".repeat(5000) + "]".repeat(5000) + "\n";
        assertThatThrownBy(() -> GemspecYaml.parse(deep)).isInstanceOf(InvalidGemException.class);
    }

    @Test
    void neverInstantiatesTaggedClasses() {
        String global = gemspecYaml("tags", "1.0", "ruby").replace("summary: A gem called tags\n",
                "summary: !!javax.script.ScriptEngineManager [!!java.net.URLClassLoader [[!!java.net.URL [\"http://x\"]]]]\n");
        assertThatThrownBy(() -> GemspecYaml.parse(global)).isInstanceOf(InvalidGemException.class)
                .hasMessageContaining("Global tag is not allowed");
        String local = gemspecYaml("tags", "1.0", "ruby").replace("summary: A gem called tags\n",
                "summary: !java.lang.ProcessBuilder [touch, /tmp/x]\n");
        assertThat(GemspecYaml.parse(local).summary()).isEmpty();
    }

    // --- gem package --------------------------------------------------------------------------------

    @Test
    void refusesGemsWhoseChecksumsDoNotMatch() {
        byte[] metadata = gzip(gemspecYaml("bad", "1.0", "ruby").getBytes(StandardCharsets.UTF_8));
        byte[] data = gzip(tar(Map.of("lib/bad.rb", "x".getBytes(StandardCharsets.UTF_8))));
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("metadata.gz", metadata);
        entries.put("data.tar.gz", data);
        entries.put("checksums.yaml.gz", gzip(("---\nSHA256:\n  metadata.gz: " + sha256(metadata) + "\n  data.tar.gz: "
                + "0".repeat(64) + "\n").getBytes(StandardCharsets.UTF_8)));
        assertThatThrownBy(() -> read(tar(entries)))
                .isInstanceOf(InvalidGemException.class)
                .hasMessageContaining("data.tar.gz does not match");
    }

    @Test
    void checksSha512WhenThereIsNoSha256Section() throws IOException {
        byte[] metadata = gzip(gemspecYaml("five", "1.0", "ruby").getBytes(StandardCharsets.UTF_8));
        byte[] data = gzip(tar(Map.of("lib/five.rb", "x".getBytes(StandardCharsets.UTF_8))));
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("metadata.gz", metadata);
        entries.put("data.tar.gz", data);
        entries.put("checksums.yaml.gz", gzip(("---\nSHA512:\n  metadata.gz: " + "f".repeat(128) + "\n")
                .getBytes(StandardCharsets.UTF_8)));
        assertThatThrownBy(() -> read(tar(entries))).hasMessageContaining("metadata.gz does not match");
        entries.put("checksums.yaml.gz", gzip(("---\nSHA512:\n  metadata.gz: " + GemPackage.hex("SHA-512", metadata) + "\n")
                .getBytes(StandardCharsets.UTF_8)));
        assertThat(read(tar(entries)).spec().name()).isEqualTo("five");
    }

    @Test
    void acceptsGemsWithoutChecksumsAndWithExtraEntries() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("metadata.gz", gzip(gemspecYaml("old", "0.1", "ruby").getBytes(StandardCharsets.UTF_8)));
        entries.put("data.tar.gz", gzip(tar(Map.of("lib/old.rb", new byte[0]))));
        entries.put("metadata.gz.sig", new byte[] {1, 2, 3});
        GemPackage.Contents contents = read(tar(entries));
        assertThat(contents.spec().fullName()).isEqualTo("old-0.1");
        assertThat(contents.entries()).containsExactly("metadata.gz", "data.tar.gz", "metadata.gz.sig");
    }

    @Test
    void followsGnuLongNamesAndSkipsPaxHeaders() throws IOException {
        byte[] gem = gem("longname", "1.0");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] pax = "30 path=some/very/long/name.txt\n".getBytes(StandardCharsets.US_ASCII);
        out.writeBytes(tarHeader("PaxHeader", pax.length, 'x'));
        out.writeBytes(Arrays.copyOf(pax, 512));
        byte[] longName = ("x".repeat(150) + "\0").getBytes(StandardCharsets.US_ASCII);
        out.writeBytes(tarHeader("././@LongLink", longName.length, 'L'));
        out.writeBytes(Arrays.copyOf(longName, 512));
        out.writeBytes(tarHeader("short", 0, '0'));
        out.writeBytes(gem);
        GemPackage.Contents contents = read(out.toByteArray());
        assertThat(contents.entries()).first().isEqualTo("x".repeat(150));
        assertThat(contents.spec().name()).isEqualTo("longname");
    }

    @Test
    void refusesWhatIsNotAGem() {
        assertThatThrownBy(() -> read(new byte[0])).hasMessageContaining("not a gem");
        assertThatThrownBy(() -> read("hello world".getBytes(StandardCharsets.UTF_8))).hasMessageContaining("truncated");
        assertThatThrownBy(() -> read(gzip(gem("x", "1.0")))).isInstanceOf(InvalidGemException.class);
        byte[] garbage = new byte[1024];
        Arrays.fill(garbage, (byte) 'A');
        assertThatThrownBy(() -> read(garbage)).isInstanceOf(InvalidGemException.class);
    }

    @Test
    void refusesTruncatedGems() {
        byte[] gem = gem("cut", "1.0");
        assertThatThrownBy(() -> read(Arrays.copyOf(gem, 700))).isInstanceOf(InvalidGemException.class);
        assertThatThrownBy(() -> read(Arrays.copyOf(gem, 300))).isInstanceOf(InvalidGemException.class);
    }

    @Test
    void refusesGemsWithoutMetadataOrData() {
        assertThatThrownBy(() -> read(tar(Map.of("data.tar.gz", gzip(new byte[0])))))
                .hasMessageContaining("no metadata.gz");
        assertThatThrownBy(() -> read(tar(Map.of("metadata.gz", gzip(gemspecYaml("m", "1", "ruby").getBytes(StandardCharsets.UTF_8))))))
                .hasMessageContaining("no data.tar.gz");
    }

    @Test
    void refusesMetadataThatIsNotGzipOrNotAGemspec() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("metadata.gz", "plain text".getBytes(StandardCharsets.UTF_8));
        entries.put("data.tar.gz", gzip(new byte[0]));
        assertThatThrownBy(() -> read(tar(entries))).isInstanceOf(InvalidGemException.class).hasMessageContaining("metadata.gz");
        assertThatThrownBy(() -> read(gemWithMetadata("--- just a string\n", "s"))).isInstanceOf(InvalidGemException.class);
    }

    @Test
    void refusesCorruptTarHeaders() {
        byte[] metadata = gzip(gemspecYaml("hdr", "1.0", "ruby").getBytes(StandardCharsets.UTF_8));
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("metadata.gz", metadata);
        entries.put("data.tar.gz", gzip(new byte[0]));
        byte[] gem = tar(entries);
        byte[] firstCorrupt = gem.clone();
        firstCorrupt[3] ^= 0x20;
        assertThatThrownBy(() -> read(firstCorrupt)).isInstanceOf(InvalidGemException.class).hasMessageContaining("not a gem");
        byte[] secondCorrupt = gem.clone();
        secondCorrupt[512 + (metadata.length + 511) / 512 * 512 + 3] ^= 0x20;
        assertThatThrownBy(() -> read(secondCorrupt)).isInstanceOf(InvalidGemException.class)
                .hasMessageContaining("corrupt tar header");
    }

    @Test
    void parsesOctalAndBase256Sizes() {
        byte[] header = new byte[512];
        byte[] octal = "00000001750\0".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(octal, 0, header, 124, octal.length);
        assertThat(GemPackage.octal(header, 124, 12)).isEqualTo(1000);
        byte[] binary = new byte[512];
        binary[124] = (byte) 0x80;
        binary[134] = 0x01;
        binary[135] = 0x00;
        assertThat(GemPackage.octal(binary, 124, 12)).isEqualTo(256);
    }

    // --- quick specs --------------------------------------------------------------------------------

    @Test
    void quickSpecsRoundTrip() throws IOException {
        GemSpec spec = read(resource("acme_hello-0.1.0.gem")).spec();
        byte[] rz = QuickSpec.deflated(spec);
        GemSpec back = QuickSpec.read(rz);
        assertThat(back).isEqualTo(spec);
    }

    @Test
    void quickSpecsHaveTheShapeRubygemsOrgServes() throws IOException {
        for (String name : List.of("rake-13.2.1.gemspec.rz", "nokogiri-1.16.0-x86_64-linux.gemspec.rz")) {
            byte[] upstream = resource(name);
            GemSpec spec = QuickSpec.read(upstream);
            byte[] regenerated = QuickSpec.marshal(spec);
            // compared after loading, as Ruby would: rubygems.org links some repeated strings, we write them twice
            assertThat(fields(regenerated)).as(name).isEqualTo(fields(inflate(upstream)));
        }
    }

    @Test
    void readsRealQuickSpecs() {
        GemSpec rake = QuickSpec.read(resource("rake-13.2.1.gemspec.rz"));
        assertThat(rake.fullName()).isEqualTo("rake-13.2.1");
        assertThat(rake.dependencies()).isEmpty();
        assertThat(rake.licenses()).containsExactly("MIT");
        assertThat(rake.metadata()).containsKey("source_code_uri");

        GemSpec nokogiri = QuickSpec.read(resource("nokogiri-1.16.0-x86_64-linux.gemspec.rz"));
        assertThat(nokogiri.fullName()).isEqualTo("nokogiri-1.16.0-x86_64-linux");
        assertThat(nokogiri.platform()).isEqualTo("x86_64-linux");
        assertThat(nokogiri.runtimeDependencies()).singleElement()
                .satisfies(d -> assertThat(d.requirement().toString()).isEqualTo("~> 1.4"));
        assertThat(nokogiri.requiredRubyVersion().toCompactIndex()).isEqualTo("< 3.4.dev&>= 3.0");
        assertThat(nokogiri.date()).isEqualTo(LocalDate.of(2023, 12, 27));
    }

    @Test
    void writesTheSpecificationDateAsRubyTime() {
        Ruby.UserDefined time = QuickSpec.time(LocalDate.of(2026, 10, 10));
        // Marshal.dump(Time.utc(2026, 10, 10)), zone included as the US-ASCII string Ruby writes
        assertThat(dev.granary.format.rubygems.marshal.MarshalWriter.dump(time)).isEqualTo(RubygemsTestSupport.hex(
                "040849753a0954696d650d40a51fc000000000063a097a6f6e65492208555443063a064546"));
        assertThat(time.className()).isEqualTo("Time");
        assertThat(QuickSpec.date(time)).isEqualTo(LocalDate.of(2026, 10, 10));
        assertThat(QuickSpec.date(QuickSpec.time(LocalDate.of(1800, 1, 1)))).isEqualTo(LocalDate.of(1980, 1, 2));
        assertThat(QuickSpec.date("not a time")).isNull();
    }

    @Test
    void refusesWhatIsNotAQuickSpec() {
        assertThatThrownBy(() -> QuickSpec.read("plain".getBytes(StandardCharsets.UTF_8))).isInstanceOf(MarshalException.class);
        assertThatThrownBy(() -> QuickSpec.read(QuickSpec.deflate(new byte[] {4, 8, '0'}))).isInstanceOf(MarshalException.class);
        byte[] array = QuickSpec.deflate(dev.granary.format.rubygems.marshal.MarshalWriter.dump(List.of("a")));
        assertThatThrownBy(() -> QuickSpec.read(array)).isInstanceOf(MarshalException.class);
    }

    @Test
    void refusesDecompressionBombs() {
        byte[] bomb = QuickSpec.deflate(new byte[QuickSpec.MAX_INFLATED + 1]);
        assertThatThrownBy(() -> QuickSpec.read(bomb)).isInstanceOf(MarshalException.class);
    }

    private static byte[] inflate(byte[] deflated) throws IOException {
        return new InflaterInputStream(new ByteArrayInputStream(deflated)).readAllBytes();
    }

    private static Object fields(byte[] marshalled) {
        Ruby.UserDefined spec = (Ruby.UserDefined) MarshalReader.load(marshalled);
        assertThat(spec.className()).isEqualTo("Gem::Specification");
        return MarshalReader.load(spec.data());
    }
}
