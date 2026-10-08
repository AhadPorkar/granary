package dev.granary.format.nuget;

import java.time.Instant;
import java.util.Comparator;

/**
 * One stored package version as the registration and search documents describe it: the manifest plus
 * the facts only the server knows (when it was published, how often it was downloaded).
 *
 * @param nuspec    the manifest; {@link Nuspec#minimal} when the stored nuspec could not be read
 * @param version   the normalised version (the manifest's {@link Nuspec#version()} is the raw string)
 * @param published when the package was stored
 * @param listed    whether the version is visible to clients
 * @param downloads how many times the package has been downloaded from this repository
 */
public record PackageEntry(Nuspec nuspec, NuGetVersion version, Instant published, boolean listed, long downloads) {

    public static final Comparator<PackageEntry> BY_VERSION = Comparator.comparing(PackageEntry::version);

    public String id() {
        return nuspec.id();
    }

    public String lowerCaseId() {
        return nuspec.lowerCaseId();
    }
}
