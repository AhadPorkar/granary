package dev.granary.format.conan;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A recipe reference, {@code name/version@user/channel}. User and channel are optional; the REST API spells
 * a missing one as {@code _}, which this record normalises to {@code null}.
 */
public record ConanReference(String name, String version, String user, String channel) {

    /** The URL placeholder for an absent user or channel. */
    public static final String NONE = "_";

    /**
     * The longest name, version, user, channel, revision or package id accepted. Conan itself allows 101 characters
     * per reference part; revisions and package ids are hex digests of at most 64. The cap keeps every storage path
     * and component field within its column, so an absurd request is a 400 rather than a database error.
     */
    public static final int MAX_SEGMENT_LENGTH = 128;

    static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9._+-]{0," + (MAX_SEGMENT_LENGTH - 1) + "}");

    public ConanReference {
        name = require(name);
        version = require(version);
        user = optional(user);
        channel = optional(channel);
    }

    private static String require(String value) {
        if (value == null || !SEGMENT.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid reference segment: " + value);
        }
        return value;
    }

    private static String optional(String value) {
        if (value == null || value.isEmpty() || NONE.equals(value)) {
            return null;
        }
        return require(value);
    }

    /** Whether a name, version, user, channel, revision or package id is acceptable in a URL and a storage path. */
    public static boolean isValidSegment(String value) {
        return value != null && SEGMENT.matcher(value).matches();
    }

    public String userSegment() {
        return user == null ? NONE : user;
    }

    public String channelSegment() {
        return channel == null ? NONE : channel;
    }

    /** {@code name/version@user/channel}, or {@code name/version} when there is neither user nor channel. */
    public String display() {
        if (user == null && channel == null) {
            return name + "/" + version;
        }
        return name + "/" + version + "@" + userSegment() + (channel == null ? "" : "/" + channel);
    }

    /** The component namespace: {@code user/channel}, {@code user} or empty. */
    public String namespace() {
        if (user == null) {
            return "";
        }
        return channel == null ? user : user + "/" + channel;
    }

    /**
     * Parses {@code name/version[@user[/channel]][#revision]}; the revision part is ignored. Empty when the text is
     * not a reference.
     */
    public static Optional<ConanReference> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String ref = text.trim();
        int hash = ref.indexOf('#');
        if (hash >= 0) {
            ref = ref.substring(0, hash);
        }
        String user = null;
        String channel = null;
        int at = ref.indexOf('@');
        if (at >= 0) {
            String userChannel = ref.substring(at + 1);
            ref = ref.substring(0, at);
            int slash = userChannel.indexOf('/');
            user = slash >= 0 ? userChannel.substring(0, slash) : userChannel;
            channel = slash >= 0 ? userChannel.substring(slash + 1) : null;
        }
        int slash = ref.indexOf('/');
        if (slash <= 0 || slash == ref.length() - 1) {
            return Optional.empty();
        }
        try {
            return Optional.of(new ConanReference(ref.substring(0, slash), ref.substring(slash + 1), user, channel));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    @Override
    public String toString() {
        return display();
    }
}
