package dev.granary.core.storage;

import dev.granary.core.blob.BlobStore;
import dev.granary.core.model.Asset;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** An {@link Asset} together with the store that can open its bytes. */
public record StoredContent(Asset asset, BlobStore blobStore) {

    public InputStream open() throws IOException {
        return blobStore.open(asset.getBlobSha256()).orElseThrow(() ->
                new IOException("blob " + asset.getBlobSha256() + " missing for " + asset.getPath()));
    }

    public byte[] readAllBytes() throws IOException {
        try (InputStream in = open()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.toIntExact(Math.min(asset.getSize(), Integer.MAX_VALUE - 16)));
            in.transferTo(out);
            return out.toByteArray();
        }
    }

    public String readUtf8() throws IOException {
        return new String(readAllBytes(), StandardCharsets.UTF_8);
    }

    public long size() {
        return asset.getSize();
    }

    public String contentType() {
        return asset.getContentType() == null ? ContentTypes.OCTET_STREAM : asset.getContentType();
    }
}
