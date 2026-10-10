package dev.granary.format.hex;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The signed registry documents a Hex repository serves ({@code names}, {@code versions},
 * {@code packages/<name>}): their records, the protobuf codec for each and the gzip framing around
 * the {@code Signed} envelope, field for field as {@code hex_core}'s {@code names.proto},
 * {@code versions.proto}, {@code package.proto} and {@code signed.proto} define them (proto2, so
 * required fields are always written).
 */
public final class HexRegistry {

    /** Registry documents are small; anything bigger than this after decompression is refused. */
    static final int MAX_DOCUMENT_SIZE = 64 * 1024 * 1024;

    private HexRegistry() {
    }

    /** {@code Signed { required bytes payload = 1; optional bytes signature = 2; }} */
    public record Signed(byte[] payload, byte[] signature) {
        public Signed {
            payload = payload == null ? new byte[0] : payload;
            signature = signature == null ? new byte[0] : signature;
        }
    }

    /** {@code Timestamp { required int64 seconds = 1; required int32 nanos = 2; }} */
    public record Timestamp(long seconds, int nanos) {
        public static Timestamp of(java.time.Instant instant) {
            return new Timestamp(instant.getEpochSecond(), instant.getNano());
        }

        public java.time.Instant toInstant() {
            return java.time.Instant.ofEpochSecond(seconds, nanos);
        }
    }

    /** {@code Names.Package { required string name = 1; optional Timestamp updated_at = 3; }} */
    public record NamesEntry(String name, Timestamp updatedAt) {
        public NamesEntry {
            name = name == null ? "" : name;
        }
    }

    /** {@code Names { repeated Package packages = 1; required string repository = 2; }} */
    public record Names(String repository, List<NamesEntry> packages) {
        public Names {
            repository = repository == null ? "" : repository;
            packages = List.copyOf(packages);
        }

        public List<String> names() {
            return packages.stream().map(NamesEntry::name).toList();
        }
    }

    /**
     * One package in the {@code versions} document. {@code retired} and {@code withAdvisories} hold
     * zero-based indexes into {@code versions}.
     */
    public record VersionsEntry(String name, List<String> versions, List<Integer> retired, List<Integer> withAdvisories) {
        public VersionsEntry {
            name = name == null ? "" : name;
            versions = List.copyOf(versions);
            retired = List.copyOf(retired);
            withAdvisories = withAdvisories == null ? List.of() : List.copyOf(withAdvisories);
        }

        public VersionsEntry(String name, List<String> versions, List<Integer> retired) {
            this(name, versions, retired, List.of());
        }
    }

    /** {@code Versions { repeated Package packages = 1; required string repository = 2; }} */
    public record Versions(String repository, List<VersionsEntry> packages) {
        public Versions {
            repository = repository == null ? "" : repository;
            packages = List.copyOf(packages);
        }
    }

    /**
     * {@code Dependency}: {@code app} empty means "the same as the package name", {@code repository}
     * empty means "the same repository as the package that depends on it".
     */
    public record Dependency(String pkg, String requirement, boolean optional, String app, String repository) {
        public Dependency {
            pkg = pkg == null ? "" : pkg;
            app = app == null ? "" : app;
            repository = repository == null ? "" : repository;
            requirement = requirement == null ? "" : requirement;
        }
    }

    public enum RetirementReason {
        OTHER(0, "other"),
        INVALID(1, "invalid"),
        SECURITY(2, "security"),
        DEPRECATED(3, "deprecated"),
        RENAMED(4, "renamed");

        private final int code;
        private final String key;

        RetirementReason(int code, String key) {
            this.code = code;
            this.key = key;
        }

        public int code() {
            return code;
        }

        /** The lower-case name {@code mix hex.retire} sends and the API reports. */
        public String key() {
            return key;
        }

        public static RetirementReason fromCode(long code) {
            for (RetirementReason r : values()) {
                if (r.code == code) {
                    return r;
                }
            }
            return OTHER;
        }

        /** The names {@code mix hex.retire} accepts (also {@code RETIRED_SECURITY}); unknown names answer empty. */
        public static Optional<RetirementReason> fromKey(String key) {
            if (key == null) {
                return Optional.empty();
            }
            String k = key.trim().toLowerCase(Locale.ROOT);
            if (k.startsWith("retired_")) {
                k = k.substring("retired_".length());
            }
            for (RetirementReason r : values()) {
                if (r.key.equals(k)) {
                    return Optional.of(r);
                }
            }
            return Optional.empty();
        }
    }

    /** {@code RetirementStatus { required RetirementReason reason = 1; optional string message = 2; }} */
    public record Retirement(RetirementReason reason, String message) {
        public Retirement {
            reason = reason == null ? RetirementReason.OTHER : reason;
            message = message == null ? "" : message;
        }
    }

    /**
     * {@code Release}: {@code retired} is {@code null} for a release in good standing;
     * {@code advisoryIndexes} point into the package's advisories; {@code publishedAt} may be {@code null}.
     */
    public record Release(String version, byte[] innerChecksum, List<Dependency> dependencies, Retirement retired,
                          byte[] outerChecksum, List<Integer> advisoryIndexes, Timestamp publishedAt) {
        public Release {
            version = version == null ? "" : version;
            dependencies = List.copyOf(dependencies);
            innerChecksum = innerChecksum == null ? new byte[0] : innerChecksum;
            outerChecksum = outerChecksum == null ? new byte[0] : outerChecksum;
            advisoryIndexes = advisoryIndexes == null ? List.of() : List.copyOf(advisoryIndexes);
        }

        public Release(String version, byte[] innerChecksum, List<Dependency> dependencies, Retirement retired,
                       byte[] outerChecksum) {
            this(version, innerChecksum, dependencies, retired, outerChecksum, List.of(), null);
        }

        public boolean isRetired() {
            return retired != null;
        }

        public Release withAdvisoryIndexes(List<Integer> indexes) {
            return new Release(version, innerChecksum, dependencies, retired, outerChecksum, indexes, publishedAt);
        }
    }

    /**
     * {@code Package { repeated Release releases = 1; required string name = 2; required string repository = 3;
     * repeated SecurityAdvisory advisories = 4; }}; advisories are kept as their encoded messages.
     */
    public record Package(String name, String repository, List<Release> releases, List<byte[]> advisories) {
        public Package {
            name = name == null ? "" : name;
            repository = repository == null ? "" : repository;
            releases = List.copyOf(releases);
            advisories = advisories == null ? List.of() : List.copyOf(advisories);
        }

        public Package(String name, String repository, List<Release> releases) {
            this(name, repository, releases, List.of());
        }

        public Optional<Release> release(String version) {
            return releases.stream().filter(r -> r.version().equals(version)).findFirst();
        }
    }

    // --- Signed ----------------------------------------------------------------------------------

    public static byte[] encodeSigned(Signed signed) {
        Protobuf.Writer writer = new Protobuf.Writer().requiredBytes(1, signed.payload());
        if (signed.signature().length > 0) {
            writer.bytes(2, signed.signature());
        }
        return writer.toByteArray();
    }

    public static Signed decodeSigned(byte[] data) {
        Protobuf.Reader reader = new Protobuf.Reader(data);
        byte[] payload = new byte[0];
        byte[] signature = new byte[0];
        while (reader.hasNext()) {
            switch (reader.nextField()) {
                case 1 -> payload = reader.readBytes();
                case 2 -> signature = reader.readBytes();
                default -> reader.skip();
            }
        }
        return new Signed(payload, signature);
    }

    // --- Names -----------------------------------------------------------------------------------

    public static byte[] encodeNames(Names names) {
        Protobuf.Writer writer = new Protobuf.Writer();
        for (NamesEntry entry : names.packages()) {
            Protobuf.Writer pkg = new Protobuf.Writer().requiredString(1, entry.name());
            if (entry.updatedAt() != null) {
                pkg.message(3, encodeTimestamp(entry.updatedAt()));
            }
            writer.message(1, pkg.toByteArray());
        }
        return writer.requiredString(2, names.repository()).toByteArray();
    }

    public static Names decodeNames(byte[] payload) {
        Protobuf.Reader reader = new Protobuf.Reader(payload);
        List<NamesEntry> packages = new ArrayList<>();
        String repository = "";
        while (reader.hasNext()) {
            switch (reader.nextField()) {
                case 1 -> {
                    Protobuf.Reader pkg = reader.readMessage();
                    String name = "";
                    Timestamp updatedAt = null;
                    while (pkg.hasNext()) {
                        switch (pkg.nextField()) {
                            case 1 -> name = pkg.readString();
                            case 3 -> updatedAt = decodeTimestamp(pkg.readMessage());
                            default -> pkg.skip();
                        }
                    }
                    packages.add(new NamesEntry(name, updatedAt));
                }
                case 2 -> repository = reader.readString();
                default -> reader.skip();
            }
        }
        return new Names(repository, packages);
    }

    // --- Versions --------------------------------------------------------------------------------

    public static byte[] encodeVersions(Versions versions) {
        Protobuf.Writer writer = new Protobuf.Writer();
        for (VersionsEntry entry : versions.packages()) {
            Protobuf.Writer pkg = new Protobuf.Writer().requiredString(1, entry.name());
            for (String version : entry.versions()) {
                pkg.requiredString(2, version);
            }
            pkg.packedInts(3, entry.retired());
            pkg.packedInts(5, entry.withAdvisories());
            writer.message(1, pkg.toByteArray());
        }
        return writer.requiredString(2, versions.repository()).toByteArray();
    }

    public static Versions decodeVersions(byte[] payload) {
        Protobuf.Reader reader = new Protobuf.Reader(payload);
        List<VersionsEntry> packages = new ArrayList<>();
        String repository = "";
        while (reader.hasNext()) {
            switch (reader.nextField()) {
                case 1 -> {
                    Protobuf.Reader pkg = reader.readMessage();
                    String name = "";
                    List<String> versions = new ArrayList<>();
                    List<Integer> retired = new ArrayList<>();
                    List<Integer> advisories = new ArrayList<>();
                    while (pkg.hasNext()) {
                        switch (pkg.nextField()) {
                            case 1 -> name = pkg.readString();
                            case 2 -> versions.add(pkg.readString());
                            case 3 -> pkg.readInts(retired);
                            case 5 -> pkg.readInts(advisories);
                            default -> pkg.skip();
                        }
                    }
                    packages.add(new VersionsEntry(name, versions, retired, advisories));
                }
                case 2 -> repository = reader.readString();
                default -> reader.skip();
            }
        }
        return new Versions(repository, packages);
    }

    // --- Package ---------------------------------------------------------------------------------

    public static byte[] encodePackage(Package pkg) {
        Protobuf.Writer writer = new Protobuf.Writer();
        for (Release release : pkg.releases()) {
            writer.message(1, encodeRelease(release));
        }
        writer.requiredString(2, pkg.name()).requiredString(3, pkg.repository());
        for (byte[] advisory : pkg.advisories()) {
            writer.message(4, advisory);
        }
        return writer.toByteArray();
    }

    private static byte[] encodeRelease(Release release) {
        Protobuf.Writer writer = new Protobuf.Writer()
                .requiredString(1, release.version())
                .requiredBytes(2, release.innerChecksum());
        for (Dependency dependency : release.dependencies()) {
            Protobuf.Writer dep = new Protobuf.Writer()
                    .requiredString(1, dependency.pkg())
                    .requiredString(2, dependency.requirement());
            if (dependency.optional()) {
                dep.bool(3, true);
            }
            if (!dependency.app().isEmpty() && !dependency.app().equals(dependency.pkg())) {
                dep.string(4, dependency.app());
            }
            dep.string(5, dependency.repository());
            writer.message(3, dep.toByteArray());
        }
        if (release.retired() != null) {
            writer.message(4, new Protobuf.Writer()
                    .requiredVarint(1, release.retired().reason().code())
                    .string(2, release.retired().message())
                    .toByteArray());
        }
        writer.bytes(5, release.outerChecksum());
        for (int index : release.advisoryIndexes()) {
            writer.requiredVarint(6, index);
        }
        if (release.publishedAt() != null) {
            writer.message(7, encodeTimestamp(release.publishedAt()));
        }
        return writer.toByteArray();
    }

    public static Package decodePackage(byte[] payload) {
        Protobuf.Reader reader = new Protobuf.Reader(payload);
        String name = "";
        String repository = "";
        List<Release> releases = new ArrayList<>();
        List<byte[]> advisories = new ArrayList<>();
        while (reader.hasNext()) {
            switch (reader.nextField()) {
                case 1 -> releases.add(decodeRelease(reader.readMessage()));
                case 2 -> name = reader.readString();
                case 3 -> repository = reader.readString();
                case 4 -> advisories.add(reader.readBytes());
                default -> reader.skip();
            }
        }
        return new Package(name, repository, releases, advisories);
    }

    private static Release decodeRelease(Protobuf.Reader reader) {
        String version = "";
        byte[] inner = new byte[0];
        byte[] outer = new byte[0];
        List<Dependency> dependencies = new ArrayList<>();
        List<Integer> advisoryIndexes = new ArrayList<>();
        Retirement retired = null;
        Timestamp publishedAt = null;
        while (reader.hasNext()) {
            switch (reader.nextField()) {
                case 1 -> version = reader.readString();
                case 2 -> inner = reader.readBytes();
                case 3 -> dependencies.add(decodeDependency(reader.readMessage()));
                case 4 -> retired = decodeRetirement(reader.readMessage());
                case 5 -> outer = reader.readBytes();
                case 6 -> reader.readInts(advisoryIndexes);
                case 7 -> publishedAt = decodeTimestamp(reader.readMessage());
                default -> reader.skip();
            }
        }
        return new Release(version, inner, dependencies, retired, outer, advisoryIndexes, publishedAt);
    }

    private static Dependency decodeDependency(Protobuf.Reader reader) {
        String pkg = "";
        String requirement = "";
        boolean optional = false;
        String app = "";
        String repository = "";
        while (reader.hasNext()) {
            switch (reader.nextField()) {
                case 1 -> pkg = reader.readString();
                case 2 -> requirement = reader.readString();
                case 3 -> optional = reader.varintValue() != 0;
                case 4 -> app = reader.readString();
                case 5 -> repository = reader.readString();
                default -> reader.skip();
            }
        }
        return new Dependency(pkg, requirement, optional, app, repository);
    }

    private static Retirement decodeRetirement(Protobuf.Reader reader) {
        RetirementReason reason = RetirementReason.OTHER;
        String message = "";
        while (reader.hasNext()) {
            switch (reader.nextField()) {
                case 1 -> reason = RetirementReason.fromCode(reader.varintValue());
                case 2 -> message = reader.readString();
                default -> reader.skip();
            }
        }
        return new Retirement(reason, message);
    }

    private static byte[] encodeTimestamp(Timestamp timestamp) {
        return new Protobuf.Writer()
                .requiredVarint(1, timestamp.seconds())
                .requiredVarint(2, timestamp.nanos())
                .toByteArray();
    }

    private static Timestamp decodeTimestamp(Protobuf.Reader reader) {
        long seconds = 0;
        int nanos = 0;
        while (reader.hasNext()) {
            switch (reader.nextField()) {
                case 1 -> seconds = reader.varintValue();
                case 2 -> nanos = (int) reader.varintValue();
                default -> reader.skip();
            }
        }
        return new Timestamp(seconds, nanos);
    }

    // --- framing ---------------------------------------------------------------------------------

    /** payload → sign → {@code Signed} → gzip: the bytes a registry endpoint serves. */
    public static byte[] pack(byte[] payload, Function<byte[], byte[]> signer) {
        return gzip(encodeSigned(new Signed(payload, signer.apply(payload))));
    }

    public static byte[] gzip(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length / 2 + 32);
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(data);
        } catch (IOException e) {
            throw new IllegalStateException("in-memory gzip failed", e);
        }
        return out.toByteArray();
    }

    /** Decompresses a registry document, refusing anything that is not gzip or grows beyond {@link #MAX_DOCUMENT_SIZE}. */
    public static byte[] gunzip(byte[] data) {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(data))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, Math.min(data.length * 4, 1 << 20)));
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (out.size() + read > MAX_DOCUMENT_SIZE) {
                    throw new IllegalArgumentException("registry document exceeds " + MAX_DOCUMENT_SIZE + " bytes");
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalArgumentException("not a gzip document: " + e.getMessage(), e);
        }
    }

    /** gunzip → Signed → payload → decoder, for documents whose signature the caller does not need to check. */
    public static <T> T unpack(byte[] gzipped, Function<byte[], T> decoder) {
        return decoder.apply(decodeSigned(gunzip(gzipped)).payload());
    }
}
