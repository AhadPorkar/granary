package dev.granary.format.pub;

import dev.granary.core.model.Repository;
import dev.granary.core.repository.GroupResolver;
import dev.granary.core.storage.StoredContent;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ObjectNode;

/**
 * Answers reads the same way for every repository type: hosted repositories from their own state, proxies
 * from cache and upstream, groups from their members in order.
 */
@Component
public class PubResolver {

    private final PubHosted hosted;
    private final PubProxy proxy;
    private final GroupResolver groups;

    public PubResolver(PubHosted hosted, PubProxy proxy, GroupResolver groups) {
        this.hosted = hosted;
        this.proxy = proxy;
        this.groups = groups;
    }

    /**
     * The package listing as the client at {@code baseUrl} must see it: every {@code archive_url} points at
     * {@code baseUrl}, which for a member of a group is the group's own URL.
     */
    public Optional<ObjectNode> listing(Repository repo, String name, String baseUrl) {
        return switch (repo.getType()) {
            case HOSTED -> hosted.listing(repo, name).map(doc -> PubListing.render(name, doc, baseUrl, false));
            case PROXY -> proxy.listing(repo, name).map(doc -> {
                ObjectNode rendered = PubListing.render(name, doc, baseUrl, true);
                if (rendered.has(PubListing.ADVISORIES_UPDATED) && !proxy.advisoriesAvailable(repo, name)) {
                    rendered.remove(PubListing.ADVISORIES_UPDATED);
                }
                return rendered;
            });
            case GROUP -> {
                List<ObjectNode> listings = new ArrayList<>();
                for (Repository member : groups.leafMembers(repo)) {
                    listing(member, name, baseUrl).ifPresent(listings::add);
                }
                yield listings.isEmpty() ? Optional.empty() : Optional.of(PubListing.merge(name, listings));
            }
        };
    }

    /** The security advisories of a package; hosted packages have none. */
    public Optional<ObjectNode> advisories(Repository repo, String name) {
        return switch (repo.getType()) {
            case HOSTED -> hosted.listing(repo, name).map(doc -> PubListing.noAdvisories());
            case PROXY -> proxy.advisories(repo, name);
            case GROUP -> {
                List<ObjectNode> docs = new ArrayList<>();
                for (Repository member : groups.leafMembers(repo)) {
                    advisories(member, name).ifPresent(docs::add);
                }
                yield docs.isEmpty() ? Optional.empty() : Optional.of(PubListing.mergeAdvisories(docs));
            }
        };
    }

    /**
     * The archive of one version. A group serves it from the member that owns the version in its merged
     * listing (the first member listing it), so the bytes always match the {@code archive_sha256} the client
     * was given; members that do not list the version are only asked after every member that does.
     */
    public Optional<StoredContent> archive(Repository repo, String name, String version) {
        return switch (repo.getType()) {
            case HOSTED -> hosted.archive(repo, name, version);
            case PROXY -> proxy.archive(repo, name, version);
            case GROUP -> {
                List<Repository> members = groups.leafMembers(repo);
                List<Repository> others = new ArrayList<>();
                for (Repository member : members) {
                    if (!lists(member, name, version)) {
                        others.add(member);
                        continue;
                    }
                    Optional<StoredContent> hit = archive(member, name, version);
                    if (hit.isPresent()) {
                        yield hit;
                    }
                }
                for (Repository member : others) {
                    Optional<StoredContent> hit = archive(member, name, version);
                    if (hit.isPresent()) {
                        yield hit;
                    }
                }
                yield Optional.empty();
            }
        };
    }

    /** Whether a leaf repository's own listing has the version. */
    private boolean lists(Repository member, String name, String version) {
        Optional<ObjectNode> doc = switch (member.getType()) {
            case HOSTED -> hosted.listing(member, name);
            case PROXY -> proxy.listing(member, name);
            case GROUP -> Optional.empty();
        };
        return doc.flatMap(d -> PubListing.find(d, version)).isPresent();
    }
}
