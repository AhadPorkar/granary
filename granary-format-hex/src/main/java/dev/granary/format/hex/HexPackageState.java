package dev.granary.format.hex;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * What a hosted repository knows about one package beyond its tarballs: every release with its checksums,
 * dependencies, retirement, publisher and descriptive metadata. Kept as JSON at
 * {@code .hex/packages/<name>.json}; the signed {@code packages/<name>} document and the API answers are
 * built from it, so nothing has to re-read tarballs.
 *
 * @param name       package name
 * @param insertedAt when the first release was published (ISO-8601)
 * @param updatedAt  when anything about the package last changed (ISO-8601)
 * @param releases   releases in ascending version order
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HexPackageState(String name, String insertedAt, String updatedAt, List<Release> releases) {

    static final String DIRECTORY = ".hex/packages/";
    /** Lenient on read: a state written before a field existed must still load (absent booleans are false). */
    private static final JsonMapper JSON = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .build();

    public HexPackageState {
        releases = releases == null ? List.of() : releases.stream()
                .sorted(Comparator.comparing(Release::version, HexVersion.ORDER)).toList();
    }

    /**
     * @param innerChecksum hex SHA-256 of VERSION + metadata.config + contents.tar.gz
     * @param outerChecksum hex SHA-256 of the tarball
     * @param publisher     the user who published the release
     * @param retirement    {@code null} for a release in good standing
     * @param size          tarball size in bytes
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Release(String version, String innerChecksum, String outerChecksum, String insertedAt,
                          String updatedAt, String publisher, List<Dependency> dependencies, Retirement retirement,
                          Meta meta, boolean hasDocs, long size) {
        public Release {
            dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
            meta = meta == null ? Meta.EMPTY : meta;
        }

        public Instant inserted() {
            return parseInstant(insertedAt);
        }

        public Release withRetirement(Retirement value, Instant now) {
            return new Release(version, innerChecksum, outerChecksum, insertedAt, now.toString(), publisher,
                    dependencies, value, meta, hasDocs, size);
        }

        public Release withDocs(boolean docs, Instant now) {
            return new Release(version, innerChecksum, outerChecksum, insertedAt, now.toString(), publisher,
                    dependencies, retirement, meta, docs, size);
        }

        byte[] innerChecksumBytes() {
            return decodeHex(innerChecksum);
        }

        byte[] outerChecksumBytes() {
            return decodeHex(outerChecksum);
        }
    }

    /** One requirement, as the release's metadata.config declared it. {@code repository} may be {@code null}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Dependency(String name, String app, String requirement, boolean optional, String repository) {
    }

    /** @param reason the lower-case reason key ({@code security}, {@code deprecated}, …) */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Retirement(String reason, String message) {
        public HexRegistry.RetirementReason reasonCode() {
            return HexRegistry.RetirementReason.fromKey(reason).orElse(HexRegistry.RetirementReason.OTHER);
        }
    }

    /** The descriptive part of metadata.config the API reports. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Meta(String app, String description, List<String> licenses, Map<String, String> links,
                       List<String> buildTools, String elixir) {
        static final Meta EMPTY = new Meta(null, null, List.of(), Map.of(), List.of(), null);

        public Meta {
            licenses = licenses == null ? List.of() : List.copyOf(licenses);
            links = links == null ? Map.of() : new LinkedHashMap<>(links);
            buildTools = buildTools == null ? List.of() : List.copyOf(buildTools);
        }

        static Meta of(HexMetadata metadata) {
            return new Meta(metadata.app(), metadata.description(), metadata.licenses(), metadata.links(),
                    metadata.buildTools(), metadata.elixir());
        }
    }

    public Optional<Release> release(String version) {
        return releases.stream().filter(r -> r.version().equals(version)).findFirst();
    }

    /** The state with {@code release} added or replacing the release of the same version. */
    public HexPackageState with(Release release, Instant now) {
        List<Release> list = new ArrayList<>(releases.stream().filter(r -> !r.version().equals(release.version())).toList());
        list.add(release);
        return new HexPackageState(name, insertedAt == null ? now.toString() : insertedAt, now.toString(), list);
    }

    public HexPackageState without(String version, Instant now) {
        return new HexPackageState(name, insertedAt, now.toString(),
                releases.stream().filter(r -> !r.version().equals(version)).toList());
    }

    public List<String> versions() {
        return releases.stream().map(Release::version).toList();
    }

    /** The release the API describes the package by: the highest stable version, else the highest of all. */
    public Optional<Release> latest() {
        Optional<String> version = HexVersion.latestStable(versions()).or(() -> HexVersion.latest(versions()));
        return version.flatMap(this::release);
    }

    static String path(String name) {
        return DIRECTORY + name + ".json";
    }

    byte[] toJson() {
        return JSON.writeValueAsBytes(this);
    }

    static HexPackageState fromJson(byte[] json) {
        try {
            HexPackageState state = JSON.readValue(json, HexPackageState.class);
            if (state == null || state.name() == null) {
                throw new IllegalArgumentException("package state has no name");
            }
            return state;
        } catch (JacksonException e) {
            throw new IllegalArgumentException("unreadable package state: " + e.getOriginalMessage(), e);
        }
    }

    static Instant parseInstant(String text) {
        if (text == null) {
            return Instant.EPOCH;
        }
        try {
            return Instant.parse(text);
        } catch (java.time.format.DateTimeParseException e) {
            return Instant.EPOCH;
        }
    }

    private static byte[] decodeHex(String hex) {
        if (hex == null || hex.isEmpty()) {
            return new byte[0];
        }
        try {
            return HexFormat.of().parseHex(hex);
        } catch (IllegalArgumentException e) {
            return new byte[0];
        }
    }
}
