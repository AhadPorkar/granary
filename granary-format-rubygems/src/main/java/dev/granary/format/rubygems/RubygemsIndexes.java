package dev.granary.format.rubygems;

import dev.granary.core.model.Repository;
import dev.granary.core.repository.GroupResolver;
import dev.granary.core.storage.StoredContent;
import dev.granary.format.rubygems.marshal.MarshalException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The index documents and files of any RubyGems repository, whatever its type: hosted repositories derive them from
 * their records, proxies cache upstream's, and groups merge their members' (hosted members first; a version present
 * in several members is described by the first one).
 *
 * <p>A group whose documents come from a single member serves that member's copy unchanged. Merged documents are
 * kept in the group's storage with a fingerprint of the member documents they were built from, so they are only
 * rebuilt when a member changes.
 */
@Component
public class RubygemsIndexes {

    private static final Logger log = LoggerFactory.getLogger(RubygemsIndexes.class);

    private final RubygemsHosted hosted;
    private final RubygemsProxy proxy;
    private final GroupResolver groups;
    private final DerivedDocuments derived;

    public RubygemsIndexes(RubygemsHosted hosted, RubygemsProxy proxy, GroupResolver groups, DerivedDocuments derived) {
        this.hosted = hosted;
        this.proxy = proxy;
        this.groups = groups;
        this.derived = derived;
    }

    /** A member's copy of a document, for merging. */
    private record Source(Repository member, StoredContent content) {
        InputStream open() throws IOException {
            return content.open();
        }

        String part() {
            return member.getId() + " " + content.asset().getBlobSha256();
        }
    }

    /** {@code info/<name>}: either a stored document or, for a group with several sources, the merged text. */
    public record Info(StoredContent stored, String text) {
        public String document() throws IOException {
            return stored != null ? stored.readUtf8() : text;
        }

        public String md5() {
            return stored != null ? GemResponses.md5(stored) : CompactIndex.md5(text);
        }
    }

    // --- compact index ------------------------------------------------------------------------------

    public Optional<StoredContent> versions(Repository repo) throws IOException {
        return switch (repo.getType()) {
            case HOSTED -> Optional.of(hosted.versions(repo));
            case PROXY -> proxy.document(repo, CompactIndex.VERSIONS);
            case GROUP -> mergedVersions(repo);
        };
    }

    public Optional<StoredContent> names(Repository repo) throws IOException {
        return switch (repo.getType()) {
            case HOSTED -> Optional.of(hosted.names(repo));
            case PROXY -> proxy.document(repo, CompactIndex.NAMES);
            case GROUP -> mergedNames(repo);
        };
    }

    public Optional<Info> info(Repository repo, String name) throws IOException {
        return switch (repo.getType()) {
            case HOSTED -> hosted.info(repo, name).map(c -> new Info(c, null));
            case PROXY -> proxy.document(repo, CompactIndex.INFO_PREFIX + name).map(c -> new Info(c, null));
            case GROUP -> {
                List<Info> found = new ArrayList<>();
                for (Repository member : groups.leafMembers(repo)) {
                    info(member, name).ifPresent(found::add);
                }
                if (found.size() <= 1) {
                    yield found.stream().findFirst();
                }
                List<String> documents = new ArrayList<>();
                for (Info i : found) {
                    documents.add(i.document());
                }
                yield Optional.of(new Info(null, CompactIndex.mergeInfo(documents)));
            }
        };
    }

    private Optional<StoredContent> mergedVersions(Repository group) throws IOException {
        List<Source> sources = new ArrayList<>();
        for (Repository member : groups.leafMembers(group)) {
            versions(member).ifPresent(c -> sources.add(new Source(member, c)));
        }
        if (sources.size() <= 1) {
            return sources.stream().findFirst().map(Source::content);
        }
        return Optional.of(mergeWithFallback(group, CompactIndex.VERSIONS, sources, GemResponses.TEXT_UTF8,
                (candidates, out) -> {
                    Source base = largest(candidates);
                    Map<String, String> md5s = new HashMap<>();
                    Function<String, String> merged = name -> md5s.computeIfAbsent(name, n -> {
                        try {
                            return info(group, n).map(Info::md5).orElse(null);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
                    List<Map<String, CompactIndex.Entry>> others = new ArrayList<>();
                    for (Source s : candidates) {
                        if (s != base) {
                            try (InputStream in = s.open()) {
                                others.add(CompactIndex.parseVersions(in));
                            } catch (IOException e) {
                                log.warn("skipping unreadable versions file of {} while merging {}: {}",
                                        s.member().getName(), group.getName(), e.getMessage());
                            }
                        }
                    }
                    try (InputStream in = base.open()) {
                        CompactIndex.mergeVersions(in, CompactIndex.union(others, merged), merged, out);
                    } catch (UncheckedIOException e) {
                        throw e.getCause();
                    }
                }));
    }

    private Optional<StoredContent> mergedNames(Repository group) throws IOException {
        List<Source> sources = new ArrayList<>();
        for (Repository member : groups.leafMembers(group)) {
            names(member).ifPresent(c -> sources.add(new Source(member, c)));
        }
        if (sources.size() <= 1) {
            return sources.stream().findFirst().map(Source::content);
        }
        return Optional.of(mergeWithFallback(group, CompactIndex.NAMES, sources, GemResponses.TEXT_UTF8,
                (candidates, out) -> {
                    TreeSet<String> names = new TreeSet<>();
                    for (Source s : candidates) {
                        try (BufferedReader reader = new BufferedReader(new InputStreamReader(s.open(), StandardCharsets.UTF_8))) {
                            boolean body = false;
                            String line;
                            while ((line = reader.readLine()) != null) {
                                if (!body) {
                                    body = line.equals(CompactIndex.SEPARATOR);
                                } else if (!line.isBlank()) {
                                    names.add(line.strip());
                                }
                            }
                        }
                    }
                    out.write(CompactIndex.names(names).getBytes(StandardCharsets.UTF_8));
                }));
    }

    // --- specs indexes ------------------------------------------------------------------------------

    public Optional<StoredContent> specs(Repository repo, String which) throws IOException {
        return switch (repo.getType()) {
            case HOSTED -> Optional.of(hosted.specs(repo, which));
            case PROXY -> proxy.document(repo, which);
            case GROUP -> mergedSpecs(repo, which);
        };
    }

    private Optional<StoredContent> mergedSpecs(Repository group, String which) throws IOException {
        List<Source> sources = new ArrayList<>();
        for (Repository member : groups.leafMembers(group)) {
            specs(member, which).ifPresent(c -> sources.add(new Source(member, c)));
        }
        if (sources.size() <= 1) {
            return sources.stream().findFirst().map(Source::content);
        }
        return Optional.of(mergeWithFallback(group, which, sources, GemResponses.OCTET_STREAM, (candidates, out) -> {
            if (which.equals(SpecsIndex.LATEST_SPECS)) {
                List<SpecTuple> all = new ArrayList<>();
                for (Source s : candidates) {
                    readTuples(group, s, all);
                }
                SpecsIndex.write(SpecsIndex.latest(all), out);
                return;
            }
            Source base = largest(candidates);
            List<SpecTuple> others = new ArrayList<>();
            for (Source s : candidates) {
                if (s != base) {
                    readTuples(group, s, others);
                }
            }
            SpecsIndex.mergeOnto(base::open, others, out);
        }));
    }

    private static void readTuples(Repository group, Source s, List<SpecTuple> into) {
        List<SpecTuple> tuples = new ArrayList<>();
        try (InputStream in = s.open()) {
            SpecsIndex.forEach(in, tuples::add);
            into.addAll(tuples);
        } catch (IOException | MarshalException e) {
            log.warn("skipping unreadable {} of {} while merging {}: {}", s.content().asset().getPath(),
                    s.member().getName(), group.getName(), e.getMessage());
        }
    }

    // --- files --------------------------------------------------------------------------------------

    /** A {@code .gem} or quick spec: hosted storage, the proxy cache, or the first group member that has it. */
    public Optional<StoredContent> file(Repository repo, String path) {
        return switch (repo.getType()) {
            case HOSTED -> hosted.file(repo, path);
            case PROXY -> proxy.file(repo, path);
            case GROUP -> {
                for (Repository member : groups.leafMembers(repo)) {
                    Optional<StoredContent> hit = file(member, path);
                    if (hit.isPresent()) {
                        yield hit;
                    }
                }
                yield Optional.empty();
            }
        };
    }

    // --- merging ------------------------------------------------------------------------------------

    @FunctionalInterface
    private interface Merge {
        void write(List<Source> candidates, java.io.OutputStream out) throws IOException;
    }

    /**
     * Builds a merged document; when a member's copy turns out to be unreadable it is left out and the merge is
     * tried again with the others, so one corrupt upstream document cannot take a whole group down.
     */
    private StoredContent mergeWithFallback(Repository group, String path, List<Source> sources, String contentType,
                                            Merge merge) throws IOException {
        List<Source> candidates = new ArrayList<>(sources);
        while (true) {
            List<String> parts = new ArrayList<>();
            parts.add(path);
            candidates.forEach(s -> parts.add(s.part()));
            List<Source> current = List.copyOf(candidates);
            try {
                if (current.size() == 1) {
                    return current.getFirst().content();
                }
                return derived.get(group, path, DerivedDocuments.fingerprint(parts), contentType,
                        out -> merge.write(current, out));
            } catch (MarshalException | java.util.zip.ZipException | java.io.EOFException e) {
                Source base = largest(current);
                log.warn("leaving {} of {} out of the merged {} of {}: {}", base.content().asset().getPath(),
                        base.member().getName(), path, group.getName(), e.getMessage());
                candidates.remove(base);
            }
        }
    }

    private static Source largest(List<Source> sources) {
        return sources.stream().max(Comparator.comparingLong(s -> s.content().size())).orElseThrow();
    }
}
