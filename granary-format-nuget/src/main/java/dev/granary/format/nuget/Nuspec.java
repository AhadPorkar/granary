package dev.granary.format.nuget;

import java.util.List;
import java.util.Locale;

/**
 * The metadata of a package as declared in its {@code .nuspec}. Only the fields the V3 protocol surfaces
 * (registration, search) are kept; everything else in the manifest stays in the stored nuspec file.
 *
 * @param id                       the package id as written by the author (ids are compared case-insensitively)
 * @param version                  the version string as written, not yet normalised
 * @param dependencyGroups         dependencies grouped by target framework; a group with a {@code null}
 *                                 framework applies to every framework
 * @param packageTypes             names from {@code <packageTypes>}, e.g. {@code Dependency}, {@code DotnetTool},
 *                                 {@code SymbolsPackage}
 */
public record Nuspec(String id, String version, String title, String description, String summary,
                     String authors, String owners, List<String> tags, String licenseUrl, String licenseExpression,
                     String projectUrl, String iconUrl, boolean requireLicenseAcceptance, String copyright,
                     String releaseNotes, List<DependencyGroup> dependencyGroups, List<String> packageTypes) {

    public Nuspec {
        tags = tags == null ? List.of() : List.copyOf(tags);
        dependencyGroups = dependencyGroups == null ? List.of() : List.copyOf(dependencyGroups);
        packageTypes = packageTypes == null ? List.of() : List.copyOf(packageTypes);
    }

    /** Dependencies that apply to one target framework ({@code null} = all frameworks). */
    public record DependencyGroup(String targetFramework, List<Dependency> dependencies) {
        public DependencyGroup {
            dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        }
    }

    /** One dependency; {@code versionRange} is the raw {@code version} attribute, which may be a range or absent. */
    public record Dependency(String id, String versionRange) {
    }

    /** The smallest manifest that still identifies a package, for files stored without a readable nuspec. */
    public static Nuspec minimal(String id, String version) {
        return new Nuspec(id, version, null, null, null, null, null, List.of(), null, null, null, null, false,
                null, null, List.of(), List.of());
    }

    public String lowerCaseId() {
        return id.toLowerCase(Locale.ROOT);
    }

    /** {@code .snupkg} symbol packages declare {@code <packageType name="SymbolsPackage"/>}. */
    public boolean isSymbolsPackage() {
        return packageTypes.stream().anyMatch(t -> t.equalsIgnoreCase("SymbolsPackage"));
    }

    /** Authors as a list, split on the commas NuGet uses to separate them. */
    public List<String> authorList() {
        return split(authors);
    }

    public List<String> ownerList() {
        return split(owners);
    }

    private static List<String> split(String commaSeparated) {
        if (commaSeparated == null || commaSeparated.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(commaSeparated.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
