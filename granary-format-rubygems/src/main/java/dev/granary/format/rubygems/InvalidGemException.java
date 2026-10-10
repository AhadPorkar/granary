package dev.granary.format.rubygems;

/** An uploaded file that is not a usable gem: not a tar, no metadata, unreadable gemspec, invalid name or version. */
public class InvalidGemException extends RuntimeException {

    public InvalidGemException(String message) {
        super(message);
    }

    public InvalidGemException(String message, Throwable cause) {
        super(message, cause);
    }
}
