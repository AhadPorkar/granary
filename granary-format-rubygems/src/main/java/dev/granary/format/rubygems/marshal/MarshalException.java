package dev.granary.format.rubygems.marshal;

/** A Ruby Marshal stream that cannot be read (truncated, malformed, or using an unsupported construct). */
public class MarshalException extends RuntimeException {

    public MarshalException(String message) {
        super(message);
    }

    public MarshalException(String message, Throwable cause) {
        super(message, cause);
    }
}
