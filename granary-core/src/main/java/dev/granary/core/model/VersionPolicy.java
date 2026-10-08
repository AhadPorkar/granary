package dev.granary.core.model;

/** Which kinds of versions a hosted repository accepts. Mostly meaningful for Maven. */
public enum VersionPolicy {
    RELEASE,
    SNAPSHOT,
    MIXED
}
