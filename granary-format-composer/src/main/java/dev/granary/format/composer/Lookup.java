package dev.granary.format.composer;

/**
 * The outcome of looking a package's metadata up in one repository.
 *
 * @param state    what is known
 * @param document the document when {@link State#FOUND}, otherwise {@code null}
 */
public record Lookup(State state, P2Document document) {

    public enum State {
        /** The repository has the document. */
        FOUND,
        /** The repository definitely does not have it: a hosted miss, or upstream answered 404. */
        MISSING,
        /**
         * Upstream cannot be asked right now, but it answered 404 the last time it was asked; offline, this is the
         * same answer a reachable upstream would give.
         */
        MISSING_OFFLINE,
        /** Nothing cached and upstream cannot be asked. */
        UNKNOWN
    }

    private static final Lookup MISSING_LOOKUP = new Lookup(State.MISSING, null);
    private static final Lookup MISSING_OFFLINE_LOOKUP = new Lookup(State.MISSING_OFFLINE, null);
    private static final Lookup UNKNOWN_LOOKUP = new Lookup(State.UNKNOWN, null);

    public static Lookup found(P2Document document) {
        return new Lookup(State.FOUND, document);
    }

    public static Lookup missing() {
        return MISSING_LOOKUP;
    }

    public static Lookup missingOffline() {
        return MISSING_OFFLINE_LOOKUP;
    }

    public static Lookup unknown() {
        return UNKNOWN_LOOKUP;
    }

    public boolean isFound() {
        return state == State.FOUND;
    }
}
