package dev.granary.format.docker;

/** The error codes of the distribution specification, with the default message each one carries. */
public enum OciErrorCode {
    BLOB_UNKNOWN("blob unknown to registry"),
    BLOB_UPLOAD_INVALID("blob upload invalid"),
    BLOB_UPLOAD_UNKNOWN("blob upload unknown to registry"),
    DIGEST_INVALID("provided digest did not match uploaded content"),
    MANIFEST_BLOB_UNKNOWN("manifest references a manifest or blob unknown to registry"),
    MANIFEST_INVALID("manifest invalid"),
    MANIFEST_UNKNOWN("manifest unknown to registry"),
    NAME_INVALID("invalid repository name"),
    NAME_UNKNOWN("repository name not known to registry"),
    SIZE_INVALID("provided length did not match content length"),
    TAG_INVALID("manifest tag did not match URI"),
    UNAUTHORIZED("authentication required"),
    DENIED("requested access to the resource is denied"),
    UNSUPPORTED("the operation is unsupported"),
    TOOMANYREQUESTS("too many requests");

    private final String defaultMessage;

    OciErrorCode(String defaultMessage) {
        this.defaultMessage = defaultMessage;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
