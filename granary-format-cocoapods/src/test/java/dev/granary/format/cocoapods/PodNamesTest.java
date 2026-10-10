package dev.granary.format.cocoapods;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class PodNamesTest {

    /** Digests computed with Ruby's {@code Digest::MD5.hexdigest}, the function the CDN client shards with. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "AFNetworking|a75d452377f3996bdc4b623a5df25820",
            "Alamofire|da208d9cbd49253cc75271c6c269ebce",
            "Floater💩|d6ceda9954999c27766942601e46e587",
            "ID.me WebVerify|e5fc44a422e9a8ac84bb4ad49a8537a9",
            "!ProtoCompiler|aa836d62e74b89a64483ae71206fc027",
            "-|336d5ebc5436534e61d16e63ddfca327",
            "cocoapods制作|28cdbe2e186a69784467570de9105b58",
            "a|0cc175b9c0f1b6a831c399e269772661"})
    void md5MatchesRubyForAsciiUnicodeAndEmoji(String name, String md5) {
        assertThat(PodNames.md5Hex(name)).isEqualTo(md5);
    }

    @ParameterizedTest
    @ValueSource(strings = {"AFNetworking", "!ProtoCompiler", "+verify", "-", "ID.me WebVerify", "Floater💩", "cocoapods制作",
            "UIView+Shake", "a.b", "...a"})
    void realPodNamesAreSafeSegments(String name) {
        assertThat(PodNames.isSafeSegment(name)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", ".", "..", "a/b", "a\\b", "a\u0000b", "a\nb", "a\u007fb", "tab\there"})
    void separatorsDotSegmentsAndControlCharactersAreNot(String segment) {
        assertThat(PodNames.isSafeSegment(segment)).isFalse();
    }

    @Test
    void overlongSegmentsAreRefused() {
        assertThat(PodNames.isSafeSegment("a".repeat(255))).isTrue();
        assertThat(PodNames.isSafeSegment("a".repeat(256))).isFalse();
        assertThat(PodNames.isSafeSegment(null)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"AFNetworking", "UIView+Shake", "!ProtoCompiler", "-", "Floater💩", "My_Pod-2", "a.b"})
    void pushableNames(String name) {
        assertThat(PodNames.pushProblem(name)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "ID.me WebVerify|whitespace",
            "tab\tname|whitespace",
            "nbsp name|whitespace",
            ".hidden|period",
            "a/b|slash",
            "a\\b|slash",
            "50%|control characters",
            "what?|control characters",
            "hash#tag|control characters",
            "semi;colon|control characters",
            "be\u0007ll|control characters"})
    void namesTheLinterOrUrlsWouldTripOverAreRefused(String name, String reason) {
        assertThat(PodNames.pushProblem(name)).hasValueSatisfying(problem -> assertThat(problem).contains(reason));
    }

    @Test
    void emptyAndOverlongNamesAreRefused() {
        assertThat(PodNames.pushProblem(null)).isPresent();
        assertThat(PodNames.pushProblem("")).isPresent();
        assertThat(PodNames.pushProblem("a".repeat(200))).isEmpty();
        assertThat(PodNames.pushProblem("a".repeat(201))).hasValueSatisfying(p -> assertThat(p).contains("200"));
    }

    @Test
    void versionsMustBeCocoaPodsVersionsWithoutSurroundingWhitespace() {
        assertThat(PodNames.versionProblem("1.0.0")).isEmpty();
        assertThat(PodNames.versionProblem("2.0.0-beta.1+build")).isEmpty();
        assertThat(PodNames.versionProblem(null)).isPresent();
        assertThat(PodNames.versionProblem("")).isPresent();
        assertThat(PodNames.versionProblem(" 1.0")).isPresent();
        assertThat(PodNames.versionProblem("latest")).hasValueSatisfying(p -> assertThat(p).contains("not a valid CocoaPods version"));
        assertThat(PodNames.versionProblem("1." + "0".repeat(100))).hasValueSatisfying(p -> assertThat(p).contains("100"));
    }

    @Test
    void codePointOrderIsTheByteOrderOfUtf8() {
        List<String> names = new ArrayList<>(List.of("下拉图片放大", "🔒", "※ikemen", "b", "B", "!ProtoCompiler", "+verify", "-", "a", "ab", "￿"));
        names.sort(PodNames.CODE_POINT_ORDER);
        // UTF-16 order would put the emoji (a surrogate pair) before U+FFFF; UTF-8 and code points put it after
        assertThat(names).containsExactly("!ProtoCompiler", "+verify", "-", "B", "a", "ab", "b", "※ikemen", "下拉图片放大", "￿", "🔒");
        assertThat(PodNames.compareCodePoints("a", "a")).isZero();
        assertThat(PodNames.compareCodePoints("a", "ab")).isNegative();
        assertThat(PodNames.compareCodePoints("ab", "a")).isPositive();
    }
}
