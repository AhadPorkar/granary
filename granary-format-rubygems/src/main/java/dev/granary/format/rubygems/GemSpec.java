package dev.granary.format.rubygems;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * What the indexes need to know about one gem version: the subset of {@code Gem::Specification} that its marshalled
 * form ({@code quick/Marshal.4.8/…gemspec.rz}), the compact index and the JSON API carry.
 *
 * @param name                    gem name
 * @param version                 version as {@code Gem::Version#to_s} prints it
 * @param platform                {@code ruby} or a platform such as {@code x86_64-linux}
 * @param summary                 one-line summary
 * @param description             longer description, may be {@code null}
 * @param authors                 author names
 * @param email                   e-mail addresses
 * @param emailIsList             whether the gemspec gave {@code email} as a list (kept so the marshalled spec matches)
 * @param homepage                homepage URL, may be {@code null}
 * @param licenses                license identifiers
 * @param metadata                the {@code metadata} hash (URIs and flags)
 * @param dependencies            runtime and development dependencies
 * @param requiredRubyVersion     {@code required_ruby_version}
 * @param requiredRubygemsVersion {@code required_rubygems_version}
 * @param rubygemsVersion         the RubyGems version that built the gem
 * @param specificationVersion    the gemspec format version, 4 for every current RubyGems
 * @param date                    the gemspec date (day resolution, UTC)
 */
public record GemSpec(String name, String version, String platform, String summary, String description,
                      List<String> authors, List<String> email, boolean emailIsList, String homepage,
                      List<String> licenses, Map<String, String> metadata, List<GemDependency> dependencies,
                      GemRequirement requiredRubyVersion, GemRequirement requiredRubygemsVersion,
                      String rubygemsVersion, int specificationVersion, LocalDate date) {

    public static final String DEFAULT_RUBYGEMS_VERSION = "3.5.22";

    public GemSpec {
        platform = GemPlatform.isRuby(platform) ? GemPlatform.RUBY : platform;
        summary = summary == null ? "" : summary;
        authors = authors == null ? List.of() : List.copyOf(authors);
        email = email == null ? List.of() : List.copyOf(email);
        licenses = licenses == null ? List.of() : List.copyOf(licenses);
        metadata = metadata == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(metadata));
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        requiredRubyVersion = requiredRubyVersion == null ? GemRequirement.any() : requiredRubyVersion;
        requiredRubygemsVersion = requiredRubygemsVersion == null ? GemRequirement.any() : requiredRubygemsVersion;
        rubygemsVersion = rubygemsVersion == null || rubygemsVersion.isBlank() ? DEFAULT_RUBYGEMS_VERSION : rubygemsVersion;
        specificationVersion = specificationVersion <= 0 ? 4 : specificationVersion;
        date = date == null ? LocalDate.of(1980, 1, 2) : date;
    }

    /** {@code name-version[-platform]}. */
    public String fullName() {
        return GemNames.fullName(name, version, platform);
    }

    /** {@code version[-platform]}, the key of this version in the compact index. */
    public String versionKey() {
        return GemNames.versionKey(version, platform);
    }

    public boolean isPrerelease() {
        return GemVersion.isPrerelease(version);
    }

    public List<GemDependency> runtimeDependencies() {
        return dependencies.stream().filter(GemDependency::isRuntime).toList();
    }

    public List<GemDependency> developmentDependencies() {
        return dependencies.stream().filter(d -> !d.isRuntime()).toList();
    }
}
