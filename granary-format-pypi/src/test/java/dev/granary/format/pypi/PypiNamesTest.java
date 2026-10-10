package dev.granary.format.pypi;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class PypiNamesTest {

    @ParameterizedTest
    @CsvSource({
            "Django, django",
            "zope.interface, zope-interface",
            "zope_interface, zope-interface",
            "Zope__Interface, zope-interface",
            "friendly-bard, friendly-bard",
            "FRIENDLY-BARD, friendly-bard",
            "friendly.-_bard, friendly-bard",
            "six, six",
            "' Six ', six",
            "typing_extensions, typing-extensions"})
    void normalisesPerPep503(String input, String expected) {
        assertThat(PypiNames.normalise(input)).isEqualTo(expected);
    }

    @Test
    void normalisingNothingIsEmpty() {
        assertThat(PypiNames.normalise(null)).isEmpty();
        assertThat(PypiNames.normalise("")).isEmpty();
        assertThat(PypiNames.normalise("   ")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"six", "Django", "zope.interface", "a", "A1", "typing_extensions", "scikit-learn", "x" })
    void acceptsValidNames(String name) {
        assertThat(PypiNames.isValidName(name)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "-six", "six-", ".six", "six.", "_six", "si x", "six/seven", "..", "sixé", "a b", "six;rm"})
    void rejectsInvalidNames(String name) {
        assertThat(PypiNames.isValidName(name)).isFalse();
    }

    @Test
    void rejectsOverlongNames() {
        assertThat(PypiNames.isValidName("a".repeat(255))).isTrue();
        assertThat(PypiNames.isValidName("a".repeat(256))).isFalse();
        assertThat(PypiNames.isValidName(null)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"six-1.16.0.tar.gz", "six-1.16.0-py2.py3-none-any.whl", "ab", "cd", "60a1b2c3", "1.0", "2.7", "source"})
    void safeSegments(String segment) {
        assertThat(PypiNames.isSafeSegment(segment)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", ".", "..", "a/b", "a\\b", ".hidden", "a b", "a%2Fb", "a\nb", "a?b", "a#b"})
    void unsafeSegments(String segment) {
        assertThat(PypiNames.isSafeSegment(segment)).isFalse();
    }

    @Test
    void parsesWheelFileNames() {
        PypiNames.Distribution d = PypiNames.parseFilename("six-1.16.0-py2.py3-none-any.whl").orElseThrow();
        assertThat(d.name()).isEqualTo("six");
        assertThat(d.version()).isEqualTo("1.16.0");
        assertThat(d.type()).isEqualTo(PypiNames.DistributionType.WHEEL);
        assertThat(d.pythonTag()).isEqualTo("py2.py3");
        assertThat(d.normalisedName()).isEqualTo("six");

        d = PypiNames.parseFilename("numpy-2.1.0-1-cp312-cp312-manylinux_2_17_x86_64.manylinux2014_x86_64.whl").orElseThrow();
        assertThat(d.name()).isEqualTo("numpy");
        assertThat(d.version()).isEqualTo("2.1.0");
        assertThat(d.pythonTag()).isEqualTo("cp312");

        d = PypiNames.parseFilename("acme_demo-1.0.0-py3-none-any.whl").orElseThrow();
        assertThat(d.normalisedName()).isEqualTo("acme-demo");

        d = PypiNames.parseFilename("torch-2.4.0+cpu-cp311-cp311-linux_x86_64.whl").orElseThrow();
        assertThat(d.version()).isEqualTo("2.4.0+cpu");
    }

    @Test
    void parsesSdistFileNames() {
        PypiNames.Distribution d = PypiNames.parseFilename("six-1.16.0.tar.gz").orElseThrow();
        assertThat(d.name()).isEqualTo("six");
        assertThat(d.version()).isEqualTo("1.16.0");
        assertThat(d.type()).isEqualTo(PypiNames.DistributionType.SDIST);
        assertThat(d.pythonTag()).isEqualTo("source");

        d = PypiNames.parseFilename("zope.interface-5.4.0.zip").orElseThrow();
        assertThat(d.name()).isEqualTo("zope.interface");
        assertThat(d.normalisedName()).isEqualTo("zope-interface");
        assertThat(d.version()).isEqualTo("5.4.0");

        d = PypiNames.parseFilename("python-dateutil-2.9.0.post0.tar.gz").orElseThrow();
        assertThat(d.name()).isEqualTo("python-dateutil");
        assertThat(d.version()).isEqualTo("2.9.0.post0");

        assertThat(PypiNames.parseFilename("acme_demo-1.0.0.tar.gz").orElseThrow().normalisedName()).isEqualTo("acme-demo");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "README.md", "six.tar.gz", "six-1.0.0.whl", "six-1.0.0.tar.gz.metadata", "six-1.0.0.egg",
            "../six-1.0.0.tar.gz", "six-1.0.0.tar.gz/", "a b-1.0.tar.gz"})
    void rejectsNonDistributionFileNames(String name) {
        assertThat(PypiNames.parseFilename(name)).isEmpty();
        assertThat(PypiNames.isDistributionFile(name)).isFalse();
    }

    @Test
    void sidecarsMapBackToTheirDistribution() {
        assertThat(PypiNames.distributionOfSidecar("six-1.16.0.tar.gz.metadata")).contains("six-1.16.0.tar.gz");
        assertThat(PypiNames.distributionOfSidecar("six-1.16.0.tar.gz")).isEmpty();
        assertThat(PypiNames.distributionOfSidecar("README.metadata")).isEmpty();
        assertThat(PypiNames.distributionOfSidecar(null)).isEmpty();
    }

    @Test
    void distributionTypesMapToUploadFiletypes() {
        assertThat(PypiNames.DistributionType.fromUploadName("sdist")).contains(PypiNames.DistributionType.SDIST);
        assertThat(PypiNames.DistributionType.fromUploadName("bdist_wheel")).contains(PypiNames.DistributionType.WHEEL);
        assertThat(PypiNames.DistributionType.fromUploadName("BDIST_WHEEL")).contains(PypiNames.DistributionType.WHEEL);
        assertThat(PypiNames.DistributionType.fromUploadName("bdist_egg")).isEqualTo(Optional.empty());
        assertThat(PypiNames.DistributionType.fromUploadName(null)).isEmpty();
        assertThat(PypiNames.DistributionType.WHEEL.uploadName()).isEqualTo("bdist_wheel");
    }
}
