package dev.granary.format.nuget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class NuspecReaderTest {

    @Test
    void readsTheManifestOutOfANupkgBuiltWithZipOutputStream() throws IOException {
        byte[] nupkg = TestNupkgs.nuspec("Acme.Lib", "1.0.0")
                .description("Acme library")
                .authors("Acme, Inc.")
                .title("Acme Lib")
                .tags("acme tools")
                .licenseUrl("https://example.com/license")
                .projectUrl("https://example.com/acme")
                .dependency("net8.0", "Newtonsoft.Json", "13.0.1")
                .dependency("net8.0", "Serilog", "[3.0.0, 4.0.0)")
                .dependency("netstandard2.0", "Newtonsoft.Json", "12.0.3")
                .nupkg();

        NuspecReader.NupkgContents contents = NuspecReader.readNupkg(new ByteArrayInputStream(nupkg));

        assertThat(contents.nuspecFileName()).isEqualTo("Acme.Lib.nuspec");
        assertThat(new String(contents.nuspecXml(), StandardCharsets.UTF_8)).contains("<id>Acme.Lib</id>");
        Nuspec nuspec = contents.nuspec();
        assertThat(nuspec.id()).isEqualTo("Acme.Lib");
        assertThat(nuspec.version()).isEqualTo("1.0.0");
        assertThat(nuspec.description()).isEqualTo("Acme library");
        assertThat(nuspec.authors()).isEqualTo("Acme, Inc.");
        assertThat(nuspec.authorList()).containsExactly("Acme", "Inc.");
        assertThat(nuspec.title()).isEqualTo("Acme Lib");
        assertThat(nuspec.tags()).containsExactly("acme", "tools");
        assertThat(nuspec.licenseUrl()).isEqualTo("https://example.com/license");
        assertThat(nuspec.projectUrl()).isEqualTo("https://example.com/acme");
        assertThat(nuspec.dependencyGroups()).hasSize(2);
        Nuspec.DependencyGroup net8 = nuspec.dependencyGroups().get(0);
        assertThat(net8.targetFramework()).isEqualTo("net8.0");
        assertThat(net8.dependencies()).containsExactly(
                new Nuspec.Dependency("Newtonsoft.Json", "13.0.1"),
                new Nuspec.Dependency("Serilog", "[3.0.0, 4.0.0)"));
        assertThat(nuspec.dependencyGroups().get(1).targetFramework()).isEqualTo("netstandard2.0");
        assertThat(nuspec.isSymbolsPackage()).isFalse();
    }

    @Test
    void ungroupedDependenciesBecomeAFrameworkAgnosticGroup() {
        Nuspec nuspec = NuspecReader.parse(TestNupkgs.nuspec("Flat", "2.0")
                .flatDependency("A", "1.0")
                .flatDependency("B", null)
                .xml().getBytes(StandardCharsets.UTF_8));

        assertThat(nuspec.dependencyGroups()).hasSize(1);
        assertThat(nuspec.dependencyGroups().get(0).targetFramework()).isNull();
        assertThat(nuspec.dependencyGroups().get(0).dependencies()).containsExactly(
                new Nuspec.Dependency("A", "1.0"), new Nuspec.Dependency("B", null));
    }

    @Test
    void recognisesSymbolPackagesByPackageType() {
        Nuspec nuspec = NuspecReader.parse(TestNupkgs.nuspec("Acme.Lib", "1.0.0")
                .packageType("SymbolsPackage").xml().getBytes(StandardCharsets.UTF_8));
        assertThat(nuspec.packageTypes()).containsExactly("SymbolsPackage");
        assertThat(nuspec.isSymbolsPackage()).isTrue();
    }

    @Test
    void worksWithoutANamespaceAndWithTheOldOne() {
        String noNamespace = "<package><metadata><id>Old</id><version>0.1</version><authors>x</authors>"
                + "<description>d</description><license type=\"expression\">MIT</license></metadata></package>";
        Nuspec a = NuspecReader.parse(noNamespace.getBytes(StandardCharsets.UTF_8));
        assertThat(a.id()).isEqualTo("Old");
        assertThat(a.licenseExpression()).isEqualTo("MIT");

        String oldNamespace = "<package xmlns=\"http://schemas.microsoft.com/packaging/2010/07/nuspec.xsd\"><metadata>"
                + "<id>Older</id><version>0.2</version><requireLicenseAcceptance>true</requireLicenseAcceptance></metadata></package>";
        Nuspec b = NuspecReader.parse(oldNamespace.getBytes(StandardCharsets.UTF_8));
        assertThat(b.id()).isEqualTo("Older");
        assertThat(b.requireLicenseAcceptance()).isTrue();
        assertThat(b.description()).isNull();
        assertThat(b.tags()).isEmpty();
    }

    @Test
    void rejectsAZipWithoutAManifest() {
        byte[] zip = TestNupkgs.zip(null, null);
        assertThatThrownBy(() -> NuspecReader.readNupkg(new ByteArrayInputStream(zip)))
                .isInstanceOf(NuspecReader.InvalidPackageException.class)
                .hasMessageContaining("no .nuspec");
    }

    @Test
    void ignoresManifestsThatAreNotAtTheRoot() {
        byte[] zip = TestNupkgs.zip("nested/Acme.nuspec", TestNupkgs.nuspec("Acme", "1.0").xml());
        assertThatThrownBy(() -> NuspecReader.readNupkg(new ByteArrayInputStream(zip)))
                .isInstanceOf(NuspecReader.InvalidPackageException.class);
    }

    @Test
    void rejectsSomethingThatIsNotAZip() {
        byte[] garbage = "definitely not a zip".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> NuspecReader.readNupkg(new ByteArrayInputStream(garbage)))
                .isInstanceOf(NuspecReader.InvalidPackageException.class);
    }

    @Test
    void rejectsManifestsWithoutIdOrVersionOrWithBrokenXml() {
        assertThatThrownBy(() -> NuspecReader.parse("<package><metadata><version>1.0</version></metadata></package>".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(NuspecReader.InvalidPackageException.class).hasMessageContaining("<id>");
        assertThatThrownBy(() -> NuspecReader.parse("<package><metadata><id>x</id></metadata></package>".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(NuspecReader.InvalidPackageException.class).hasMessageContaining("<version>");
        assertThatThrownBy(() -> NuspecReader.parse("<package><metadata>".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(NuspecReader.InvalidPackageException.class).hasMessageContaining("well-formed");
        assertThatThrownBy(() -> NuspecReader.parse("<nothing/>".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(NuspecReader.InvalidPackageException.class).hasMessageContaining("<package>");
    }

    @Test
    void refusesDoctypeDeclarationsSoEntityAttacksCannotReachTheServer() {
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE package [<!ENTITY xxe SYSTEM \"file:///etc/hostname\">]>"
                + "<package><metadata><id>&xxe;</id><version>1.0</version></metadata></package>";
        assertThatThrownBy(() -> NuspecReader.parse(xxe.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(NuspecReader.InvalidPackageException.class);
    }
}
