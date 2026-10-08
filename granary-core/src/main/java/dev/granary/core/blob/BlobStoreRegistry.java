package dev.granary.core.blob;

import dev.granary.core.config.GranaryProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Holds every configured {@link BlobStore}. A store named {@value #DEFAULT} always exists and
 * lives under {@code <data-dir>/blobs}; additional stores come from {@code granary.storage.blob-stores}.
 */
@Component
public class BlobStoreRegistry {

    public static final String DEFAULT = "default";

    private final Map<String, BlobStore> stores = new LinkedHashMap<>();

    public BlobStoreRegistry(GranaryProperties properties) {
        Path dataDir = properties.getDataDir();
        try {
            stores.put(DEFAULT, new FileBlobStore(DEFAULT, dataDir.resolve("blobs")));
            for (Map.Entry<String, GranaryProperties.BlobStoreProperties> entry
                    : properties.getStorage().getBlobStores().entrySet()) {
                String name = entry.getKey();
                if (DEFAULT.equals(name)) {
                    continue;
                }
                Path path = entry.getValue().getPath() != null
                        ? entry.getValue().getPath()
                        : dataDir.resolve("blobs-" + name);
                stores.put(name, new FileBlobStore(name, path));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not initialise blob stores under " + dataDir, e);
        }
    }

    public BlobStore defaultStore() {
        return stores.get(DEFAULT);
    }

    public Optional<BlobStore> find(String name) {
        return Optional.ofNullable(stores.get(name));
    }

    public BlobStore require(String name) {
        return find(name).orElseThrow(() -> new IllegalArgumentException("unknown blob store: " + name));
    }

    public Collection<BlobStore> all() {
        return Collections.unmodifiableCollection(stores.values());
    }
}
