package dev.granary.core.format;

/** The permission a repository request needs. */
public enum AccessLevel {
    /** Open to everyone, e.g. a registry's login endpoint. */
    NONE,
    READ,
    WRITE
}
