package dev.granary.format.cocoapods;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Source archives of {@code http} podspec sources: the archive types {@code cocoapods-downloader} knows, how it
 * infers them from a URL, and the file name an archive gets under {@code sources/<Name>/<version>/}.
 */
public final class SourceArchive {

    /** File names are reduced to these characters so the rewritten URL survives every client and HTTP firewall. */
    private static final Pattern UNSAFE = Pattern.compile("[^A-Za-z0-9._+~-]");
    private static final int MAX_FILENAME = 200;
    public static final String FALLBACK_NAME = "source";

    /** The archive types of {@code Pod::Downloader::RemoteFile}, with the suffix each is recognised by. */
    public enum Type {
        ZIP("zip", Pattern.compile("\\.zip$"), "zip"),
        TGZ("tgz", Pattern.compile("\\.(tgz|tar\\.gz)$"), "tar.gz"),
        TAR("tar", Pattern.compile("\\.tar$"), "tar"),
        TBZ("tbz", Pattern.compile("\\.(tbz|tar\\.bz2)$"), "tar.bz2"),
        TXZ("txz", Pattern.compile("\\.(txz|tar\\.xz)$"), "tar.xz"),
        DMG("dmg", Pattern.compile("\\.dmg$"), "dmg");

        private final String option;
        private final Pattern suffix;
        private final String extension;

        Type(String option, Pattern suffix, String extension) {
            this.option = option;
            this.suffix = suffix;
            this.extension = extension;
        }

        /** The value of the podspec's {@code source.type}. */
        public String option() {
            return option;
        }

        /** The usual file extension, without the dot. */
        public String extension() {
            return extension;
        }

        public static Optional<Type> ofOption(String option) {
            if (option == null) {
                return Optional.empty();
            }
            return Arrays.stream(values()).filter(t -> t.option.equals(option.strip().toLowerCase(Locale.ROOT))).findFirst();
        }

        /** What {@code type_with_url} infers from a URL path (case-sensitive, like the downloader). */
        public static Optional<Type> ofPath(String path) {
            if (path == null) {
                return Optional.empty();
            }
            return Arrays.stream(values()).filter(t -> t.suffix.matcher(path).find()).findFirst();
        }
    }

    private SourceArchive() {
    }

    /** The decoded path of an absolute http(s) URL; empty for anything else. */
    public static Optional<String> httpPath(String url) {
        try {
            URI uri = new URI(url.strip());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if ((!scheme.equals("http") && !scheme.equals("https")) || uri.getHost() == null) {
                return Optional.empty();
            }
            return Optional.of(uri.getPath() == null ? "" : uri.getPath());
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
    }

    /** Whether {@code url} is an absolute http(s) URL Granary can download from. */
    public static boolean isHttpUrl(String url) {
        return url != null && httpPath(url).isPresent();
    }

    /** The archive type CocoaPods will assume: the explicit {@code source.type}, else the one the URL implies. */
    public static Optional<Type> effectiveType(String url, String explicitType) {
        if (explicitType != null && !explicitType.isBlank()) {
            return Type.ofOption(explicitType);
        }
        return httpPath(url).flatMap(Type::ofPath);
    }

    /**
     * The file name an archive downloaded from {@code url} is kept under: the URL's last path segment with anything
     * outside {@code [A-Za-z0-9._+~-]} replaced by {@code _}, so the archive suffix (which tells CocoaPods the type)
     * survives. A segment that is empty, a dot name or too long becomes {@code source.<extension>}.
     */
    public static String filename(String url, String explicitType) {
        String path = httpPath(url).orElse("");
        String last = path.substring(path.lastIndexOf('/') + 1);
        return sanitise(last, effectiveType(url, explicitType).orElse(null));
    }

    /** {@link #filename} for an uploaded file name. */
    public static String sanitise(String name, Type type) {
        String base = name == null ? "" : name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        String candidate = UNSAFE.matcher(base).replaceAll("_");
        if (candidate.isEmpty() || candidate.startsWith(".") || candidate.length() > MAX_FILENAME) {
            return type == null ? FALLBACK_NAME : FALLBACK_NAME + "." + type.extension();
        }
        return candidate;
    }

    /** Whether the first bytes of an archive fit its type; DMG images carry their signature at the end and always pass. */
    public static boolean looksLike(Type type, byte[] head) {
        return switch (type) {
            case ZIP -> startsWith(head, 0, "PK\u0003\u0004") || startsWith(head, 0, "PK\u0005\u0006")
                    || startsWith(head, 0, "PK\u0007\u0008");
            case TGZ -> head.length >= 2 && (head[0] & 0xff) == 0x1f && (head[1] & 0xff) == 0x8b;
            case TBZ -> startsWith(head, 0, "BZh");
            case TXZ -> head.length >= 6 && (head[0] & 0xff) == 0xfd && startsWith(head, 1, "7zXZ") && head[5] == 0;
            case TAR -> startsWith(head, 257, "ustar");
            case DMG -> true;
        };
    }

    private static boolean startsWith(byte[] data, int offset, String ascii) {
        byte[] expected = ascii.getBytes(StandardCharsets.ISO_8859_1);
        if (data.length < offset + expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if (data[offset + i] != expected[i]) {
                return false;
            }
        }
        return true;
    }
}
