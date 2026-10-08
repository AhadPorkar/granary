package dev.granary.core.format;

import dev.granary.core.GranaryException;
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
}
