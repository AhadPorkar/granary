package dev.granary.format.rubygems.marshal;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The Java side of the Ruby object model that {@link MarshalReader} produces and {@link MarshalWriter} consumes.
 *
 * <p>Plain values map onto the obvious Java types: {@code nil} is {@code null}, {@code true}/{@code false} are
 * {@link Boolean}, integers are {@link Long} (or {@link java.math.BigInteger} beyond 64 bits), floats are
 * {@link Double}, strings are {@link String} (or {@code byte[]} for binary strings when writing), arrays are
 * {@link List} and hashes are {@link Map} with insertion order preserved. Everything else is one of the records
 * declared here.
 */
public final class Ruby {

    private Ruby() {
    }

    /** A Ruby symbol such as {@code :runtime}. */
    public record Symbol(String name) {
        public Symbol {
            Objects.requireNonNull(name, "name");
        }
    }

    /**
     * A US-ASCII string ({@code I"<bytes>} with {@code E: false}), as Ruby writes literals such as the {@code zone}
     * of a {@code Time}; plain Java strings are written as UTF-8 strings.
     */
    public record AsciiString(String value) {
        public AsciiString {
            Objects.requireNonNull(value, "value");
        }
    }

    /** A plain object ({@code o}): its class and instance variables, names including the leading {@code @}. */
    public record RObject(String className, Map<String, Object> ivars) {
        public RObject {
            Objects.requireNonNull(className, "className");
            ivars = ivars == null ? new LinkedHashMap<>() : ivars;
        }

        public Object ivar(String name) {
            return ivars.get(name);
        }
    }

    /** An object dumped through {@code marshal_dump} ({@code U}), e.g. {@code Gem::Version} and {@code Gem::Requirement}. */
    public record UserMarshal(String className, Object data) {
        public UserMarshal {
            Objects.requireNonNull(className, "className");
        }
    }

    /** An object dumped through {@code _dump} ({@code u}), e.g. {@code Gem::Specification} and {@code Time}. */
    public record UserDefined(String className, byte[] data, Map<String, Object> ivars) {
        public UserDefined {
            Objects.requireNonNull(className, "className");
            data = data == null ? new byte[0] : data;
            ivars = ivars == null ? Collections.emptyMap() : ivars;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof UserDefined u && u.className.equals(className)
                    && java.util.Arrays.equals(u.data, data) && u.ivars.equals(ivars);
        }

        @Override
        public int hashCode() {
            return Objects.hash(className, java.util.Arrays.hashCode(data), ivars);
        }

        @Override
        public String toString() {
            return "UserDefined[" + className + ", " + data.length + " bytes, " + ivars + "]";
        }
    }

    /** A {@code Struct} instance ({@code S}). */
    public record Struct(String className, Map<String, Object> members) {
    }

    /** A regular expression ({@code /}); only carried through, never interpreted. */
    public record Regexp(String source, int options) {
    }

    /** A reference to a class or module ({@code c}, {@code m}, {@code M}). */
    public record ClassRef(String name, boolean module) {
    }

    /** A hash with a default value ({@code }}). */
    public record HashWithDefault(Map<Object, Object> entries, Object defaultValue) {
    }

    public static Symbol symbol(String name) {
        return new Symbol(name);
    }

    /** {@code Gem::Version.new(version)} as RubyGems marshals it: {@code U:Gem::Version [version]}. */
    public static UserMarshal gemVersion(String version) {
        return new UserMarshal("Gem::Version", List.of(version));
    }

    /** The version string of a marshalled {@code Gem::Version}, or {@code null} when {@code value} is not one. */
    public static String gemVersionString(Object value) {
        if (value instanceof UserMarshal u && u.className().equals("Gem::Version")
                && u.data() instanceof List<?> list && !list.isEmpty() && list.getFirst() instanceof String s) {
            return s;
        }
        if (value instanceof RObject o && o.className().equals("Gem::Version") && o.ivar("@version") instanceof String s) {
            // very old indexes marshalled versions as plain objects
            return s;
        }
        return null;
    }
}
