package dev.granary.format.pypi;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.web.util.HtmlUtils;

/**
 * Just enough HTML to read a PEP 503 page: anchors with their attributes and text, and {@code <meta>} tags.
 * Tolerant of unquoted attributes, {@code >} inside quoted values, upper-case tag names and missing closing
 * tags, because the indexes in the wild (pypi.org, devpi, Artifactory, hand-written {@code --find-links}
 * pages) agree on very little beyond the anchor itself.
 */
final class SimpleHtml {

    /** A parsed start tag with, for anchors, the text up to the closing tag. */
    record Tag(Map<String, String> attributes, String text) {

        String attribute(String name) {
            return attributes.get(name.toLowerCase(Locale.ROOT));
        }
    }

    private SimpleHtml() {
    }

    /** Every {@code <a>} element in document order; attribute names are lower-cased, values and text unescaped. */
    static List<Tag> anchors(String html) {
        return tags(html, "a", true);
    }

    /** The {@code content} of {@code <meta name="…">}, if present. */
    static Optional<String> meta(String html, String name) {
        for (Tag meta : tags(html, "meta", false)) {
            if (name.equalsIgnoreCase(meta.attribute("name"))) {
                return Optional.ofNullable(meta.attribute("content"));
            }
        }
        return Optional.empty();
    }

    private static List<Tag> tags(String html, String tagName, boolean withText) {
        List<Tag> result = new ArrayList<>();
        int length = html.length();
        int i = 0;
        while (i < length) {
            int lt = html.indexOf('<', i);
            if (lt < 0) {
                break;
            }
            if (html.startsWith("<!--", lt)) {
                int end = html.indexOf("-->", lt + 4);
                i = end < 0 ? length : end + 3;
                continue;
            }
            int nameEnd = lt + 1 + tagName.length();
            if (nameEnd > length || !html.regionMatches(true, lt + 1, tagName, 0, tagName.length())
                    || (nameEnd < length && !isTagNameEnd(html.charAt(nameEnd)))) {
                i = lt + 1;
                continue;
            }
            Map<String, String> attributes = new LinkedHashMap<>();
            int pos = parseAttributes(html, nameEnd, attributes);
            String text = "";
            if (withText) {
                int close = indexOfIgnoreCase(html, "</" + tagName, pos);
                int nextOpen = indexOfIgnoreCase(html, "<" + tagName, pos);
                int textEnd = close < 0 ? length : close;
                if (nextOpen >= 0 && (close < 0 || nextOpen < close)) {
                    textEnd = nextOpen; // unclosed anchor: the text ends where the next one begins
                }
                text = HtmlUtils.htmlUnescape(stripTags(html.substring(pos, textEnd))).trim();
                pos = textEnd;
            }
            result.add(new Tag(attributes, text));
            i = pos;
        }
        return result;
    }

    /** Parses attributes starting at {@code pos} (just after the tag name) and returns the index after the closing {@code >}. */
    private static int parseAttributes(String html, int pos, Map<String, String> into) {
        int length = html.length();
        int i = pos;
        while (i < length) {
            char c = html.charAt(i);
            if (Character.isWhitespace(c) || c == '/') {
                i++;
                continue;
            }
            if (c == '>') {
                return i + 1;
            }
            int nameStart = i;
            while (i < length && !Character.isWhitespace(html.charAt(i)) && html.charAt(i) != '=' && html.charAt(i) != '>'
                    && html.charAt(i) != '/') {
                i++;
            }
            String name = html.substring(nameStart, i).toLowerCase(Locale.ROOT);
            while (i < length && Character.isWhitespace(html.charAt(i))) {
                i++;
            }
            String value = "";
            if (i < length && html.charAt(i) == '=') {
                i++;
                while (i < length && Character.isWhitespace(html.charAt(i))) {
                    i++;
                }
                if (i < length && (html.charAt(i) == '"' || html.charAt(i) == '\'')) {
                    char quote = html.charAt(i);
                    int end = html.indexOf(quote, i + 1);
                    if (end < 0) {
                        end = length;
                    }
                    value = html.substring(i + 1, end);
                    i = Math.min(end + 1, length);
                } else {
                    int start = i;
                    while (i < length && !Character.isWhitespace(html.charAt(i)) && html.charAt(i) != '>') {
                        i++;
                    }
                    value = html.substring(start, i);
                }
            }
            if (!name.isEmpty()) {
                into.putIfAbsent(name, HtmlUtils.htmlUnescape(value));
            }
        }
        return length;
    }

    private static boolean isTagNameEnd(char c) {
        return Character.isWhitespace(c) || c == '>' || c == '/';
    }

    private static int indexOfIgnoreCase(String haystack, String needle, int from) {
        int max = haystack.length() - needle.length();
        for (int i = Math.max(from, 0); i <= max; i++) {
            if (haystack.regionMatches(true, i, needle, 0, needle.length())) {
                return i;
            }
        }
        return -1;
    }

    private static String stripTags(String s) {
        if (s.indexOf('<') < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        boolean inTag = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<') {
                inTag = true;
            } else if (c == '>' && inTag) {
                inTag = false;
            } else if (!inTag) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Escapes text or an attribute value for HTML output. */
    static String escape(String s) {
        return s == null ? "" : HtmlUtils.htmlEscape(s, "UTF-8");
    }
}
