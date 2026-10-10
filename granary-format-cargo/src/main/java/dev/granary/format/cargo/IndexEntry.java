package dev.granary.format.cargo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * One line of a sparse index file: one published version of a crate. The original text is kept, so a line
 * from upstream reaches cargo byte for byte, whatever fields a newer crates.io adds.
 *
 * @param line   the JSON text without its line terminator
 * @param name   the crate name with its published case
 * @param vers   the version
 * @param cksum  SHA-256 of the {@code .crate} file, lower-case hex
 * @param yanked whether the version is yanked
 */
record IndexEntry(String line, String name, String vers, String cksum, boolean yanked) {

    /** Parses one line; empty for blank lines and for anything cargo could not use either. */
    static Optional<IndexEntry> parse(String line) {
        if (line == null) {
            return Optional.empty();
        }
        String text = line.strip();
        if (text.isEmpty()) {
            return Optional.empty();
        }
        return CargoJson.parseObject(text).flatMap(node -> of(text, node));
    }

    static Optional<IndexEntry> of(ObjectNode node) {
        return of(CargoJson.write(node), node);
    }

    private static Optional<IndexEntry> of(String text, ObjectNode node) {
        String name = CargoJson.string(node, "name");
        String vers = CargoJson.string(node, "vers");
        if (name == null || name.isEmpty() || vers == null || vers.isEmpty()) {
            return Optional.empty();
        }
        String cksum = CargoJson.string(node, "cksum");
        JsonNode yanked = node.get("yanked");
        boolean isYanked = yanked != null && yanked.isBoolean() && yanked.booleanValue();
        return Optional.of(new IndexEntry(text, name, vers, cksum == null ? "" : cksum, isYanked));
    }

    /** The parsed JSON of this line, for edits such as a yank. */
    ObjectNode json() {
        return CargoJson.parseObject(line).orElseThrow(() -> new IllegalStateException("index line is not JSON: " + line));
    }

    /** A copy with the {@code yanked} flag changed, all other fields untouched. */
    IndexEntry withYanked(boolean value) {
        ObjectNode node = json();
        node.put("yanked", value);
        return of(node).orElseThrow();
    }

    /** All usable lines of an index file, in file order. */
    static List<IndexEntry> parseFile(String text) {
        List<IndexEntry> entries = new ArrayList<>();
        if (text == null) {
            return entries;
        }
        for (String line : text.split("\n")) {
            parse(line).ifPresent(entries::add);
        }
        return entries;
    }

    /** An index file: one line per entry, each terminated by a newline. */
    static String render(Collection<IndexEntry> entries) {
        StringBuilder sb = new StringBuilder();
        for (IndexEntry e : entries) {
            sb.append(e.line()).append('\n');
        }
        return sb.toString();
    }

    /** Finds the entry for {@code version}: an exact match, else the only one that differs just in build metadata. */
    static Optional<IndexEntry> find(List<IndexEntry> entries, String version) {
        for (IndexEntry e : entries) {
            if (e.vers().equals(version)) {
                return Optional.of(e);
            }
        }
        Optional<CargoVersion> wanted = CargoVersion.parse(version);
        if (wanted.isEmpty()) {
            return Optional.empty();
        }
        IndexEntry match = null;
        for (IndexEntry e : entries) {
            Optional<CargoVersion> v = CargoVersion.parse(e.vers());
            if (v.isPresent() && v.get().samePrecedence(wanted.get())) {
                if (match != null) {
                    return Optional.empty();
                }
                match = e;
            }
        }
        return Optional.ofNullable(match);
    }

    /**
     * Merges the index files of several sources (group members, in precedence order): the union of their
     * versions, where the first source that lists a version wins it, ordered by semver precedence. Lines that
     * are not semver keep their relative order at the end.
     *
     * @param sources each source's entries, in precedence order
     * @param origin  maps a source to the value recorded as the winner's origin
     */
    static <S, O> List<Sourced<O>> merge(List<S> sources, Function<S, List<IndexEntry>> entriesOf, Function<S, O> origin) {
        Map<String, Sourced<O>> byVersion = new LinkedHashMap<>();
        for (S source : sources) {
            for (IndexEntry e : entriesOf.apply(source)) {
                byVersion.putIfAbsent(e.vers(), new Sourced<>(e, origin.apply(source)));
            }
        }
        List<Sourced<O>> merged = new ArrayList<>(byVersion.values());
        merged.sort(Comparator.comparing((Sourced<O> s) -> s.entry().vers(), CargoVersion.STRING_ORDER));
        return merged;
    }

    /** An entry together with where it came from. */
    record Sourced<O>(IndexEntry entry, O origin) {
    }
}
