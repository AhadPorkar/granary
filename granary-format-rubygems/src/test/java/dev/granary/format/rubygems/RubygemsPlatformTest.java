package dev.granary.format.rubygems;

import static org.assertj.core.api.Assertions.assertThat;

import dev.granary.format.rubygems.marshal.Ruby;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** {@link GemPlatform} against what RubyGems 3.5's {@code Gem::Platform.new} answers for the same strings. */
class RubygemsPlatformTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', nullValues = "nil", value = {
            "x86_64-linux|x86_64-linux|x86_64|linux|nil",
            "x86_64-linux-gnu|x86_64-linux-gnu|x86_64|linux|gnu",
            "x86_64-linux-musl|x86_64-linux-musl|x86_64|linux|musl",
            "arm64-darwin|arm64-darwin|arm64|darwin|nil",
            "arm64-darwin-23|arm64-darwin-23|arm64|darwin|23",
            "universal-darwin-22|universal-darwin-22|universal|darwin|22",
            "x86-mswin32-60|x86-mswin32-60|x86|mswin32|60",
            "x86-mingw32|x86-mingw32|x86|mingw32|nil",
            "x64-mingw-ucrt|x64-mingw-ucrt|x64|mingw|ucrt",
            "x64-mingw32|x64-mingw32|x64|mingw32|nil",
            "java|java|nil|java|nil",
            "jruby|java|nil|java|nil",
            "universal-java-17|universal-java-17|universal|java|17",
            "i386-linux|x86-linux|x86|linux|nil",
            "i686-linux|x86-linux|x86|linux|nil",
            "aarch64-linux|aarch64-linux|aarch64|linux|nil",
            "powerpc-aix-7.1|powerpc-aix-7.1|powerpc|aix|7.1",
            "x86_64-freebsd-13|x86_64-freebsd-13|x86_64|freebsd|13",
            "x86_64-openbsd7.3|x86_64-openbsd-7.3|x86_64|openbsd|7.3",
            "sparc-solaris-2.10|sparc-solaris-2.10|sparc|solaris|2.10",
            "x86-cygwin|x86-cygwin|x86|cygwin|nil",
            "wasm32-wasi|wasm32-wasi|wasm32|wasi|nil",
            "universal-dotnet|universal-dotnet|universal|dotnet|nil",
            "universal-dotnet2.0|universal-dotnet-2.0|universal|dotnet|2.0",
            "arm-linux-gnueabihf|arm-linux-gnueabihf|arm|linux|gnueabihf",
            "mswin32|x86-mswin32|x86|mswin32|nil",
            "armv7l-linux-eabihf|armv7l-linux-eabihf|armv7l|linux|eabihf",
            "x86_64-darwin19|x86_64-darwin-19|x86_64|darwin|19",
            "i386-mingw32|x86-mingw32|x86|mingw32|nil",
            "dalvik|dalvik|nil|dalvik|nil",
            "x86_64-netbsdelf|x86_64-netbsdelf|x86_64|netbsdelf|nil",
            "unknown-thing|unknown-unknown|unknown|unknown|nil",
            "x86_64-linux-android|x86_64-linux-android|x86_64|linux|android",
            "i386-java1.6|x86-java-1.6|x86|java|1.6",
            "universal-macruby|universal-macruby|universal|macruby|nil",
    })
    void parsesLikeRubyGems(String input, String toS, String cpu, String os, String version) {
        GemPlatform p = GemPlatform.parse(input);
        assertThat(p.toString()).isEqualTo(toS);
        assertThat(p.cpu()).isEqualTo(cpu);
        assertThat(p.os()).isEqualTo(os);
        assertThat(p.version()).isEqualTo(version);
        assertThat(GemPlatform.normalise(input)).isEqualTo(toS);
    }

    @Test
    void rubyIsThePlatformOfPureRubyGems() {
        assertThat(GemPlatform.isRuby("ruby")).isTrue();
        assertThat(GemPlatform.isRuby(null)).isTrue();
        assertThat(GemPlatform.isRuby("")).isTrue();
        assertThat(GemPlatform.isRuby("java")).isFalse();
        assertThat(GemPlatform.normalise("ruby")).isEqualTo("ruby");
        assertThat(GemPlatform.normalise(null)).isEqualTo("ruby");
    }

    @ParameterizedTest
    @ValueSource(strings = {"x86_64-linux", "universal-darwin-22", "x64-mingw-ucrt", "java"})
    void acceptsRealPlatforms(String platform) {
        assertThat(GemPlatform.isValid(platform)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "../etc", "x86 64", "a/b", "-linux", "linux-", "x\\y", "plätform"})
    void refusesPlatformsThatCannotBeFileNames(String platform) {
        assertThat(GemPlatform.isValid(platform)).isFalse();
    }

    @Test
    void marshalsAsAPlainObjectAndBack() {
        GemPlatform p = GemPlatform.parse("x86_64-linux-gnu");
        Ruby.RObject o = p.toMarshal();
        assertThat(o.className()).isEqualTo("Gem::Platform");
        assertThat(o.ivars()).containsEntry("@cpu", "x86_64").containsEntry("@os", "linux").containsEntry("@version", "gnu");
        assertThat(GemPlatform.fromMarshal(o)).isEqualTo("x86_64-linux-gnu");
        assertThat(GemPlatform.fromMarshal("ruby")).isEqualTo("ruby");
        assertThat(GemPlatform.fromMarshal("i686-linux")).isEqualTo("x86-linux");
        assertThat(GemPlatform.fromMarshal(42L)).isEqualTo("ruby");
        Map<String, Object> javaIvars = new LinkedHashMap<>();
        javaIvars.put("@cpu", null);
        javaIvars.put("@os", "java");
        javaIvars.put("@version", null);
        assertThat(GemPlatform.fromMarshal(new Ruby.RObject("Gem::Platform", javaIvars))).isEqualTo("java");
    }
}
