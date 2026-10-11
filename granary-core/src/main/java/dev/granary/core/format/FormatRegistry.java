package dev.granary.core.format;

import dev.granary.core.GranaryException;
import dev.granary.core.model.RepositoryType;
import dev.granary.core.proxy.UpstreamCredentials;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/** All {@link FormatHandler} beans on the classpath, by format id. Adding a format is adding a module. */
@Component
public class FormatRegistry {

    private final Map<String, FormatHandler> handlers = new LinkedHashMap<>();

    public FormatRegistry(List<FormatHandler> discovered) {
        for (FormatHandler handler : discovered) {
            FormatHandler previous = handlers.putIfAbsent(handler.format(), handler);
            if (previous != null) {
                throw new IllegalStateException("two handlers registered for format " + handler.format());
            }
        }
    }

    public Optional<FormatHandler> find(String format) {
        return Optional.ofNullable(format == null ? null : handlers.get(format));
    }

    public FormatHandler require(String format) {
        return find(format).orElseThrow(() ->
                GranaryException.badRequest("repository.format.unknown", "Unknown format '" + format + "'"));
    }

    public Set<String> names() {
        return Collections.unmodifiableSet(handlers.keySet());
    }

    public Collection<FormatHandler> all() {
        return Collections.unmodifiableCollection(handlers.values());
    }

    /**
     * The settings a repository of this format offers: the format's own ({@link FormatHandler#settings()}) followed
     * by the settings core implements once for every format, such as {@value UpstreamCredentials#HOSTS_SETTING}
     * for proxies.
     */
    public List<SettingDescriptor> settings(FormatHandler handler) {
        List<SettingDescriptor> all = new ArrayList<>(handler.settings());
        if (handler.supportedTypes().contains(RepositoryType.PROXY)
                && all.stream().noneMatch(s -> s.key().equals(UpstreamCredentials.HOSTS_SETTING))) {
            all.add(UpstreamCredentials.HOSTS_DESCRIPTOR);
        }
        return List.copyOf(all);
    }
}
