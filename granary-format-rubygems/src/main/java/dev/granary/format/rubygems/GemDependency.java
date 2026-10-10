package dev.granary.format.rubygems;

import dev.granary.format.rubygems.marshal.Ruby;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A {@code Gem::Dependency} of a gem version.
 *
 * @param name        the gem depended on
 * @param type        {@code runtime} or {@code development}
 * @param requirement the versions that satisfy it
 */
public record GemDependency(String name, String type, GemRequirement requirement) {

    public static final String RUNTIME = "runtime";
    public static final String DEVELOPMENT = "development";

    public GemDependency {
        type = DEVELOPMENT.equals(type) ? DEVELOPMENT : RUNTIME;
        requirement = requirement == null ? GemRequirement.any() : requirement;
    }

    public boolean isRuntime() {
        return RUNTIME.equals(type);
    }

    /** As RubyGems marshals it inside a {@code Gem::Specification}. */
    public Ruby.RObject toMarshal() {
        Map<String, Object> ivars = new LinkedHashMap<>();
        ivars.put("@name", name);
        ivars.put("@requirement", requirement.toMarshal());
        ivars.put("@type", Ruby.symbol(type));
        ivars.put("@prerelease", Boolean.FALSE);
        ivars.put("@version_requirements", requirement.toMarshal());
        ivars.put("@force_ruby_platform", Boolean.FALSE);
        return new Ruby.RObject("Gem::Dependency", ivars);
    }

    /** Reads a marshalled {@code Gem::Dependency}, or {@code null} when it is not one. */
    public static GemDependency fromMarshal(Object value) {
        if (!(value instanceof Ruby.RObject o) || !o.className().equals("Gem::Dependency")
                || !(o.ivar("@name") instanceof String name)) {
            return null;
        }
        Object requirement = o.ivar("@requirement") != null ? o.ivar("@requirement") : o.ivar("@version_requirements");
        String type = o.ivar("@type") instanceof Ruby.Symbol s ? s.name() : RUNTIME;
        return new GemDependency(name, type, GemRequirement.fromMarshal(requirement));
    }
}
