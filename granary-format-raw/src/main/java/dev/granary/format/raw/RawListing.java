package dev.granary.format.raw;

import dev.granary.core.model.Asset;
import dev.granary.core.proxy.UrlPaths;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.web.util.HtmlUtils;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Directory listings for paths ending with a slash: an HTML index page in the style of Apache's
 * {@code mod_autoindex} and Nexus, or a JSON document when the client asks for {@code application/json}.
 */
public final class RawListing {

    /** One child of a directory. Directories carry the newest modification time of their descendants and no size. */
    public record Entry(String name, boolean directory, long size, Instant lastModified) {

        public String href() {
            return UrlPaths.encodeSegment(name) + (directory ? "/" : "");
        }

        /** Directories before files, then by name. */
        static final Comparator<Entry> ORDER = Comparator.comparing((Entry e) -> !e.directory()).thenComparing(Entry::name);
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT).withZone(ZoneOffset.UTC);

    private RawListing() {
    }

    /**
     * The direct children of {@code prefix} ({@code ""} for the root, otherwise ending with {@code /}) among the
     * assets whose paths start with it.
     */
    public static List<Entry> children(List<Asset> assets, String prefix) {
        Map<String, Entry> byName = new LinkedHashMap<>();
        for (Asset asset : assets) {
            String path = asset.getPath();
            if (!path.startsWith(prefix) || path.length() == prefix.length()) {
                continue;
            }
            String rest = path.substring(prefix.length());
            int slash = rest.indexOf('/');
            Instant modified = asset.getUpdatedAt() != null ? asset.getUpdatedAt() : asset.getLastVerifiedAt();
            if (slash < 0) {
                byName.put(rest, new Entry(rest, false, asset.getSize(), modified));
            } else {
                String name = rest.substring(0, slash);
                Entry previous = byName.get(name + "/");
                Instant newest = newest(previous == null ? null : previous.lastModified(), modified);
                byName.put(name + "/", new Entry(name, true, 0, newest));
            }
        }
        List<Entry> entries = new ArrayList<>(byName.values());
        entries.sort(Entry.ORDER);
        return entries;
    }

    /** Unions listings of several sources; for files the first source that has the name wins. */
    public static List<Entry> merge(List<List<Entry>> listings) {
        Map<String, Entry> byKey = new LinkedHashMap<>();
        for (List<Entry> listing : listings) {
            for (Entry entry : listing) {
                String key = entry.name() + (entry.directory() ? "/" : "");
                Entry previous = byKey.get(key);
                if (previous == null) {
                    byKey.put(key, entry);
                } else if (entry.directory()) {
                    byKey.put(key, new Entry(entry.name(), true, 0, newest(previous.lastModified(), entry.lastModified())));
                }
            }
        }
        List<Entry> entries = new ArrayList<>(byKey.values());
        entries.sort(Entry.ORDER);
        return entries;
    }

    /** Whether the client prefers JSON: {@code application/json} is listed and HTML is not listed before it. */
    public static boolean wantsJson(String accept) {
        if (accept == null) {
            return false;
        }
        String lower = accept.toLowerCase(Locale.ROOT);
        int json = lower.indexOf("application/json");
        if (json < 0) {
            return false;
        }
        int html = lower.indexOf("text/html");
        return html < 0 || json < html;
    }

    public static String html(String repository, String path, List<Entry> entries) {
        String title = "/" + repository + "/" + path;
        StringBuilder sb = new StringBuilder(1024 + entries.size() * 160);
        sb.append("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\"><title>Index of ")
                .append(HtmlUtils.htmlEscape(title)).append("</title>")
                .append("<style>body{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;margin:2rem}")
                .append("table{border-collapse:collapse}th,td{text-align:left;padding:.15rem 1.5rem .15rem 0}")
                .append("th{border-bottom:1px solid #999}td.size{text-align:right}</style></head><body><h1>Index of ")
                .append(HtmlUtils.htmlEscape(title)).append("</h1><hr><table><thead><tr><th>Name</th><th>Last modified</th>")
                .append("<th>Size</th></tr></thead><tbody>");
        if (!path.isEmpty()) {
            sb.append("<tr><td><a href=\"../\">../</a></td><td></td><td class=\"size\">-</td></tr>");
        }
        for (Entry entry : entries) {
            String label = HtmlUtils.htmlEscape(entry.name() + (entry.directory() ? "/" : ""));
            sb.append("<tr><td><a href=\"").append(HtmlUtils.htmlEscape(entry.href())).append("\">").append(label)
                    .append("</a></td><td>").append(entry.lastModified() == null ? "" : DATE.format(entry.lastModified()))
                    .append("</td><td class=\"size\">").append(entry.directory() ? "-" : Long.toString(entry.size()))
                    .append("</td></tr>");
        }
        sb.append("</tbody></table><hr></body></html>");
        return sb.toString();
    }

    public static byte[] json(String repository, String path, List<Entry> entries) {
        ObjectNode root = JSON.createObjectNode();
        root.put("repository", repository);
        root.put("path", path);
        ArrayNode array = root.putArray("entries");
        for (Entry entry : entries) {
            ObjectNode node = array.addObject();
            node.put("name", entry.name());
            node.put("type", entry.directory() ? "directory" : "file");
            node.put("href", entry.href());
            if (!entry.directory()) {
                node.put("size", entry.size());
            }
            if (entry.lastModified() != null) {
                node.put("lastModified", entry.lastModified().toString());
            }
        }
        return JSON.writeValueAsString(root).getBytes(StandardCharsets.UTF_8);
    }

    private static Instant newest(Instant a, Instant b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.isAfter(b) ? a : b;
    }
}
