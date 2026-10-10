package dev.granary.format.raw;

import dev.granary.core.storage.StorageService;

/** The component a plain file belongs to when the profile cannot derive anything better. */
final class RawComponents {

    /** Name of the component root-level files are attached to. */
    static final String ROOT = "/";
    /** Column sizes of the components table: namespace and name 500, version 200 characters. */
    static final int MAX_NAMESPACE = 500;
    static final int MAX_NAME = 500;
    static final int MAX_VERSION = 200;

    private RawComponents() {
    }

    /** {@code namespace=""}, {@code name=directory}, {@code version=file name}; root-level files use {@value #ROOT}. */
    static StorageService.ComponentCoordinates generic(String path) {
        String directory = RawPaths.withoutTrailingSlash(RawPaths.directory(path));
        return new StorageService.ComponentCoordinates("", directory.isEmpty() ? ROOT : directory, RawPaths.fileName(path));
    }

    /**
     * The coordinates when they fit the components table, otherwise {@code null}: a file under a directory longer
     * than 500 characters, or with a file name longer than 200, is stored without a component instead of failing
     * the upload.
     */
    static StorageService.ComponentCoordinates fit(StorageService.ComponentCoordinates c) {
        if (c == null || c.name() == null || c.version() == null || c.name().isEmpty() || c.version().isEmpty()) {
            return null;
        }
        return c.namespace().length() <= MAX_NAMESPACE && c.name().length() <= MAX_NAME && c.version().length() <= MAX_VERSION
                ? c : null;
    }
}
