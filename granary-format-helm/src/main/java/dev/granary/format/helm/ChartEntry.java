package dev.granary.format.helm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * One version of one chart in an index ({@code ChartVersion} in Helm's terms): the chart's metadata plus
 * {@code urls}, {@code digest} and {@code created}. Fields Granary does not interpret are carried through
 * unchanged, so nothing an upstream index states is lost on the way to the client.
 */
public final class ChartEntry {

    public static final String URLS = "urls";
    public static final String DIGEST = "digest";
    public static final String CREATED = "created";

    private final Map<String, Object> fields;

    private ChartEntry(Map<String, Object> fields) {
        this.fields = fields;
    }

    /** An entry from a parsed map; the map is copied. */
    public static ChartEntry of(Map<String, Object> fields) {
        return new ChartEntry(new LinkedHashMap<>(fields));
    }

    public String name() {
        return string("name");
    }

    public String version() {
        return string("version");
    }

    public String digest() {
        return string(DIGEST);
    }

    public String created() {
        return string(CREATED);
    }

    /** The download URLs as written; non-string items are ignored. */
    public List<String> urls() {
        Object value = fields.get(URLS);
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> urls = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof String s && !s.isBlank()) {
                urls.add(s.trim());
            }
        }
        return urls;
    }

    public String string(String key) {
        Object value = fields.get(key);
        return value instanceof String s ? s : value == null ? null : value.toString();
    }

    public Object get(String key) {
        return fields.get(key);
    }

    public ChartEntry with(String key, Object value) {
        Map<String, Object> copy = new LinkedHashMap<>(fields);
        if (value == null) {
            copy.remove(key);
        } else {
            copy.put(key, value);
        }
        return new ChartEntry(copy);
    }

    public ChartEntry withName(String name) {
        return with("name", name);
    }

    public ChartEntry withUrls(List<String> urls) {
        return with(URLS, List.copyOf(urls));
    }

    public Map<String, Object> fields() {
        return Collections.unmodifiableMap(fields);
    }

    /** A deep copy with every mapping's keys sorted, the way {@code helm repo index} writes entries. */
    public Map<String, Object> sortedCopy() {
        @SuppressWarnings("unchecked")
        Map<String, Object> sorted = (Map<String, Object>) sortDeep(fields);
        return sorted;
    }

    @SuppressWarnings("unchecked")
    private static Object sortDeep(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                sorted.put(String.valueOf(e.getKey()), sortDeep(e.getValue()));
            }
            return sorted;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) {
                copy.add(sortDeep(item));
            }
            return copy;
        }
        return value;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ChartEntry other && fields.equals(other.fields);
    }

    @Override
    public int hashCode() {
        return fields.hashCode();
    }

    @Override
    public String toString() {
        return name() + " " + version();
    }
}
