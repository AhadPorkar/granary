package dev.granary.core.model;

/** The three repository kinds every format supports, mirroring the well-known hosted/proxy/group model. */
public enum RepositoryType {
    /** Artifacts are uploaded by clients and stored locally. */
    HOSTED,
    /** Artifacts are fetched from a remote registry on demand and cached locally. */
    PROXY,
    /** A virtual repository that merges the content of several member repositories under one URL. */
    GROUP
}
