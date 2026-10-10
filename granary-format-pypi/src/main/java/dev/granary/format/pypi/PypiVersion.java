package dev.granary.format.pypi;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A PEP 440 version: {@code [N!]N(.N)*[{a|b|rc}N][.postN][.devN][+local]}, parsed leniently the way the
 * {@code packaging} library does ({@code 1.0-alpha-1}, {@code 1.0.post}, {@code v1.0} all parse) and compared
 * and printed in the normalised form.
 */
public final class PypiVersion implements Comparable<PypiVersion> {

    private static final Pattern VERSION = Pattern.compile(
            "^\\s*v?"
                    + "(?:(?<epoch>\\d+)!)?"
                    + "(?<release>\\d+(?:\\.\\d+)*)"
                    + "(?<pre>[-_.]?(?<preL>alpha|a|beta|b|preview|pre|c|rc)[-_.]?(?<preN>\\d+)?)?"
                    + "(?<post>(?:-(?<postN1>\\d+))|(?:[-_.]?(?<postL>post|rev|r)[-_.]?(?<postN2>\\d+)?))?"
                    + "(?<dev>[-_.]?(?<devL>dev)[-_.]?(?<devN>\\d+)?)?"
                    + "(?:\\+(?<local>[a-z0-9]+(?:[-_.][a-z0-9]+)*))?"
                    + "\\s*$",
            Pattern.CASE_INSENSITIVE);

    private final String original;
    private final long epoch;
    private final long[] release;
    private final String preLabel;   // a, b, rc or null
    private final long preNumber;
    private final boolean post;
    private final long postNumber;
    private final boolean dev;
    private final long devNumber;
    private final String[] local;    // normalised local segments or empty

    private PypiVersion(String original, long epoch, long[] release, String preLabel, long preNumber, boolean post,
                        long postNumber, boolean dev, long devNumber, String[] local) {
        this.original = original;
        this.epoch = epoch;
        this.release = release;
        this.preLabel = preLabel;
        this.preNumber = preNumber;
        this.post = post;
        this.postNumber = postNumber;
        this.dev = dev;
        this.devNumber = devNumber;
        this.local = local;
    }

    public static Optional<PypiVersion> tryParse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher m = VERSION.matcher(text);
        if (!m.matches()) {
            return Optional.empty();
        }
        try {
            long epoch = m.group("epoch") == null ? 0 : Long.parseLong(m.group("epoch"));
            long[] release = Arrays.stream(m.group("release").split("\\.")).mapToLong(Long::parseLong).toArray();
            String preLabel = null;
            long preNumber = 0;
            if (m.group("pre") != null) {
                preLabel = switch (m.group("preL").toLowerCase(Locale.ROOT)) {
                    case "a", "alpha" -> "a";
                    case "b", "beta" -> "b";
                    default -> "rc";
                };
                preNumber = m.group("preN") == null ? 0 : Long.parseLong(m.group("preN"));
            }
            boolean post = m.group("post") != null;
            long postNumber = 0;
            if (post) {
                String n = m.group("postN1") != null ? m.group("postN1") : m.group("postN2");
                postNumber = n == null ? 0 : Long.parseLong(n);
            }
            boolean dev = m.group("dev") != null;
            long devNumber = dev && m.group("devN") != null ? Long.parseLong(m.group("devN")) : 0;
            String[] local = m.group("local") == null
                    ? new String[0]
                    : Arrays.stream(m.group("local").toLowerCase(Locale.ROOT).split("[-_.]"))
                            .map(PypiVersion::normaliseLocalSegment).toArray(String[]::new);
            return Optional.of(new PypiVersion(text.trim(), epoch, release, preLabel, preNumber, post, postNumber,
                    dev, devNumber, local));
        } catch (NumberFormatException overflow) {
            return Optional.empty();
        }
    }

    public static PypiVersion parse(String text) {
        return tryParse(text).orElseThrow(() -> new IllegalArgumentException("'" + text + "' is not a PEP 440 version"));
    }

    public static boolean isValid(String text) {
        return tryParse(text).isPresent();
    }

    /** The text as it was given, whitespace trimmed. */
    public String original() {
        return original;
    }

    /** Pre-releases and development releases, which {@code pip} skips unless asked for them. */
    public boolean isPrerelease() {
        return preLabel != null || dev;
    }

    public boolean isDevRelease() {
        return dev;
    }

    public boolean isPostRelease() {
        return post;
    }

    public boolean hasLocal() {
        return local.length > 0;
    }

    /** The normalised spelling, e.g. {@code 1.0a1.post2.dev3+local}. */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        if (epoch != 0) {
            sb.append(epoch).append('!');
        }
        for (int i = 0; i < release.length; i++) {
            if (i > 0) {
                sb.append('.');
            }
            sb.append(release[i]);
        }
        if (preLabel != null) {
            sb.append(preLabel).append(preNumber);
        }
        if (post) {
            sb.append(".post").append(postNumber);
        }
        if (dev) {
            sb.append(".dev").append(devNumber);
        }
        if (local.length > 0) {
            sb.append('+').append(String.join(".", local));
        }
        return sb.toString();
    }

    @Override
    public int compareTo(PypiVersion o) {
        if (epoch != o.epoch) {
            return Long.compare(epoch, o.epoch);
        }
        int c = compareRelease(release, o.release);
        if (c != 0) {
            return c;
        }
        c = Integer.compare(preKey(), o.preKey());
        if (c != 0) {
            return c;
        }
        if (preLabel != null && o.preLabel != null) {
            c = preLabel.compareTo(o.preLabel);
            if (c != 0) {
                return c;
            }
            c = Long.compare(preNumber, o.preNumber);
            if (c != 0) {
                return c;
            }
        }
        c = Long.compare(post ? postNumber : -1, o.post ? o.postNumber : -1);
        if (c != 0) {
            return c;
        }
        c = Long.compare(dev ? devNumber : Long.MAX_VALUE, o.dev ? o.devNumber : Long.MAX_VALUE);
        if (c != 0) {
            return c;
        }
        return compareLocal(local, o.local);
    }

    /**
     * Pre-release ordering group: {@code .devN} without a pre-release sorts first, then the pre-release
     * itself, then the final release; a post-release of the final release sorts after it.
     */
    private int preKey() {
        if (preLabel != null) {
            return 1;
        }
        if (dev && !post) {
            return 0;
        }
        return 2;
    }

    private static int compareRelease(long[] a, long[] b) {
        int n = Math.max(a.length, b.length);
        for (int i = 0; i < n; i++) {
            long x = i < a.length ? a[i] : 0;
            long y = i < b.length ? b[i] : 0;
            if (x != y) {
                return Long.compare(x, y);
            }
        }
        return 0;
    }

    /** A numeric local segment without leading zeros ({@code 007} → {@code 7}), as {@code packaging} prints it. */
    private static String normaliseLocalSegment(String segment) {
        if (!isNumber(segment)) {
            return segment;
        }
        String stripped = segment.replaceFirst("^0+", "");
        return stripped.isEmpty() ? "0" : stripped;
    }

    private static boolean isNumber(String segment) {
        return !segment.isEmpty() && segment.chars().allMatch(c -> c >= '0' && c <= '9');
    }

    /**
     * Local segments compare numerically when both are numbers (of any length), numbers sort after text, and a
     * local version beats none.
     */
    private static int compareLocal(String[] a, String[] b) {
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            boolean an = isNumber(a[i]);
            boolean bn = isNumber(b[i]);
            int c;
            if (an && bn) {
                // both are normalised (no leading zeros), so the longer number is the larger one
                c = a[i].length() != b[i].length() ? Integer.compare(a[i].length(), b[i].length()) : a[i].compareTo(b[i]);
            } else if (an != bn) {
                c = an ? 1 : -1;
            } else {
                c = a[i].compareTo(b[i]);
            }
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(a.length, b.length);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PypiVersion v && compareTo(v) == 0;
    }

    /** Consistent with {@link #equals(Object)}: trailing zero release segments ({@code 1.0} = {@code 1.0.0}) do not count. */
    @Override
    public int hashCode() {
        int significant = release.length;
        while (significant > 1 && release[significant - 1] == 0) {
            significant--;
        }
        return java.util.Objects.hash(epoch, Arrays.hashCode(Arrays.copyOf(release, significant)), preLabel,
                preLabel == null ? 0 : preNumber, post ? postNumber : -1, dev ? devNumber : Long.MAX_VALUE,
                Arrays.hashCode(local));
    }

    /** Sorts version strings in PEP 440 order; strings that are not versions go last, in text order. */
    public static List<String> sort(java.util.Collection<String> versions) {
        List<String> parsed = new ArrayList<>();
        List<String> unparsed = new ArrayList<>();
        for (String v : versions) {
            (tryParse(v).isPresent() ? parsed : unparsed).add(v);
        }
        parsed.sort((x, y) -> {
            int c = parse(x).compareTo(parse(y));
            return c != 0 ? c : x.compareTo(y);
        });
        unparsed.sort(String::compareTo);
        parsed.addAll(unparsed);
        return parsed;
    }

    /** The version {@code pip} would pick: the highest final release, or the highest of all when there is none. */
    public static Optional<String> latest(java.util.Collection<String> versions) {
        PypiVersion bestFinal = null;
        String bestFinalText = null;
        PypiVersion bestAny = null;
        String bestAnyText = null;
        for (String text : versions) {
            Optional<PypiVersion> v = tryParse(text);
            if (v.isEmpty()) {
                continue;
            }
            if (bestAny == null || v.get().compareTo(bestAny) > 0) {
                bestAny = v.get();
                bestAnyText = text;
            }
            if (!v.get().isPrerelease() && (bestFinal == null || v.get().compareTo(bestFinal) > 0)) {
                bestFinal = v.get();
                bestFinalText = text;
            }
        }
        return Optional.ofNullable(bestFinalText != null ? bestFinalText : bestAnyText);
    }
}
