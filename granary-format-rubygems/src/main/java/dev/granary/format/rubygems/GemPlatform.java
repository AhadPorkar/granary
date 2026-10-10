package dev.granary.format.rubygems;

import dev.granary.format.rubygems.marshal.Ruby;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A gem platform with the parsing rules of {@code Gem::Platform.new(String)}: {@code x86_64-linux} becomes
 * cpu {@code x86_64}, os {@code linux}; {@code i686-darwin10} becomes {@code x86-darwin-10}; {@code java} has no
 * cpu. {@code ruby} is the pure-Ruby platform and has no parts.
 */
public record GemPlatform(String cpu, String os, String version) {

    public static final String RUBY = "ruby";

    private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9_.+-]{1,64}$");
    private static final Pattern NUMERIC = Pattern.compile("\\d+(\\.\\d+)?$");
    private static final Pattern NUMERIC_ANCHORED = Pattern.compile("^\\d+(\\.\\d+)?$");

    /** Whether a platform string is acceptable in a file name and an index line. */
    public static boolean isValid(String platform) {
        return platform != null && VALID.matcher(platform).matches() && !platform.startsWith("-") && !platform.endsWith("-");
    }

    public static boolean isRuby(String platform) {
        return platform == null || platform.isEmpty() || platform.equals(RUBY);
    }

    /** The platform as RubyGems prints it in file names ({@code Gem::Platform#to_s}); {@code ruby} stays {@code ruby}. */
    public static String normalise(String platform) {
        if (isRuby(platform)) {
            return RUBY;
        }
        return parse(platform).toString();
    }

    public static GemPlatform parse(String platform) {
        List<String> arch = new ArrayList<>(Arrays.asList(platform.split("-", -1)));
        if (arch.size() > 2 && !NUMERIC.matcher(arch.getLast()).find()) {
            String extra = arch.removeLast();
            arch.set(arch.size() - 1, arch.getLast() + "-" + extra);
        }
        String first = arch.removeFirst();
        String cpu = first.matches(".*i\\d86.*") ? "x86" : first;
        if (arch.size() == 2 && NUMERIC_ANCHORED.matcher(arch.getLast()).matches()) {
            return new GemPlatform(cpu, arch.get(0), arch.get(1));
        }
        String os = arch.isEmpty() ? null : arch.getFirst();
        if (os == null) {
            cpu = null;
            os = first;
        }
        String[] osVersion = osAndVersion(os);
        if (osVersion[2] != null) {
            cpu = osVersion[2];
        }
        if (cpu == null && osVersion[0].matches("mswin\\d+") && osVersion[0].endsWith("32")) {
            cpu = "x86";
        }
        return new GemPlatform(cpu, osVersion[0], osVersion[1]);
    }

    /** The {@code case os} of {@code Gem::Platform#initialize}; a third element overrides the cpu when set. */
    private static String[] osAndVersion(String os) {
        Matcher m;
        if ((m = find("aix(\\d+)?", os)) != null) {
            return of("aix", m.group(1));
        }
        if (find("cygwin", os) != null) {
            return of("cygwin", null);
        }
        if ((m = find("darwin(\\d+)?", os)) != null) {
            return of("darwin", m.group(1));
        }
        if (os.equals("macruby")) {
            return of("macruby", null);
        }
        if ((m = find("freebsd(\\d+)?", os)) != null) {
            return of("freebsd", m.group(1));
        }
        if (os.equals("java") || os.equals("jruby")) {
            return of("java", null);
        }
        if ((m = find("^java([\\d.]*)", os)) != null) {
            return of("java", m.group(1));
        }
        if ((m = find("^dalvik(\\d+)?$", os)) != null) {
            return of("dalvik", m.group(1));
        }
        if (os.equals("dotnet")) {
            return of("dotnet", null);
        }
        if ((m = find("^dotnet([\\d.]*)", os)) != null) {
            return of("dotnet", m.group(1));
        }
        if ((m = find("linux-?(\\w+)?", os)) != null) {
            return of("linux", m.group(1));
        }
        if (find("mingw32", os) != null) {
            return of("mingw32", null);
        }
        if ((m = find("mingw-?(\\w+)?", os)) != null) {
            return of("mingw", m.group(1));
        }
        if ((m = find("(mswin\\d+)(_(\\d+))?", os)) != null) {
            return of(m.group(1), m.group(3));
        }
        if (find("netbsdelf", os) != null) {
            return of("netbsdelf", null);
        }
        if ((m = find("openbsd(\\d+\\.\\d+)?", os)) != null) {
            return of("openbsd", m.group(1));
        }
        if ((m = find("solaris(\\d+\\.\\d+)?", os)) != null) {
            return of("solaris", m.group(1));
        }
        if (find("wasi", os) != null) {
            return of("wasi", null);
        }
        if ((m = find("^(\\w+_platform)(\\d+)?", os)) != null) {
            return of(m.group(1), m.group(2));
        }
        return of("unknown", null);
    }

    private static Matcher find(String regex, String input) {
        Matcher m = Pattern.compile(regex).matcher(input);
        return m.find() ? m : null;
    }

    private static String[] of(String os, String version) {
        return new String[] {os, version == null || version.isEmpty() ? null : version, null};
    }

    /** {@code Gem::Platform#to_s}: the non-nil parts joined with {@code -}. */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (String part : new String[] {cpu, os, version}) {
            if (part != null) {
                if (!sb.isEmpty()) {
                    sb.append('-');
                }
                sb.append(part);
            }
        }
        return sb.toString();
    }

    /** {@code Gem::Platform} as RubyGems marshals it: a plain object with {@code @cpu}, {@code @os} and {@code @version}. */
    public Ruby.RObject toMarshal() {
        Map<String, Object> ivars = new LinkedHashMap<>();
        ivars.put("@cpu", cpu);
        ivars.put("@os", os);
        ivars.put("@version", version);
        return new Ruby.RObject("Gem::Platform", ivars);
    }

    /** The platform string of a marshalled {@code Gem::Platform} or platform string, or {@code ruby}. */
    public static String fromMarshal(Object value) {
        if (value instanceof String s) {
            return normalise(s);
        }
        if (value instanceof Ruby.RObject o && o.className().equals("Gem::Platform")) {
            return new GemPlatform(stringOrNull(o.ivar("@cpu")), stringOrNull(o.ivar("@os")),
                    stringOrNull(o.ivar("@version"))).toString();
        }
        return RUBY;
    }

    private static String stringOrNull(Object value) {
        return value instanceof String s ? s : null;
    }
}
