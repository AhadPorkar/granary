package dev.granary.core.format;

/**
 * A ready-to-paste client configuration shown on a repository's "Connect" page.
 *
 * @param id       stable identifier, unique within the format (e.g. {@code pip-conf})
 * @param title    short English title, e.g. "pip (pip.conf)"
 * @param language syntax hint for highlighting: {@code shell}, {@code ini}, {@code xml}, {@code json},
 *                 {@code yaml}, {@code toml}, {@code text}
 * @param filename the file the snippet belongs in, or {@code null} for a command line
 * @param code     the snippet itself; credentials are placeholders unless the context supplied real ones
 */
public record ConnectSnippet(String id, String title, String language, String filename, String code) {

    public static ConnectSnippet shell(String id, String title, String code) {
        return new ConnectSnippet(id, title, "shell", null, code);
    }

    public static ConnectSnippet file(String id, String title, String language, String filename, String code) {
        return new ConnectSnippet(id, title, language, filename, code);
    }
}
