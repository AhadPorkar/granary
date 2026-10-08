package dev.granary.core.security;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Privilege strings. They are deliberately simple text so roles stay readable in the database and the UI:
 * <ul>
 *   <li>{@code system:admin} – everything</li>
 *   <li>{@code repositories:manage} – create, change and delete repository definitions</li>
 *   <li>{@code users:manage} – manage users, roles and other people's tokens</li>
 *   <li>{@code settings:manage} – change global settings</li>
 *   <li>{@code repository:read:*} / {@code repository:read:<name>} – download from all / one repository</li>
 *   <li>{@code repository:write:*} / {@code repository:write:<name>} – upload to all / one repository (implies read)</li>
 * </ul>
 */
public final class Privileges {

    public static final String SYSTEM_ADMIN = "system:admin";
    public static final String REPOSITORIES_MANAGE = "repositories:manage";
    public static final String USERS_MANAGE = "users:manage";
    public static final String SETTINGS_MANAGE = "settings:manage";
    public static final String REPOSITORY_READ_ALL = "repository:read:*";
    public static final String REPOSITORY_WRITE_ALL = "repository:write:*";

    private static final Pattern VALID = Pattern.compile(
            "^(system:admin|repositories:manage|users:manage|settings:manage|repository:(read|write):(\\*|[a-zA-Z0-9][a-zA-Z0-9._-]{0,99}))$");

    private Privileges() {
    }

    public static String repositoryRead(String name) {
        return "repository:read:" + name;
    }

    public static String repositoryWrite(String name) {
        return "repository:write:" + name;
    }

    public static boolean isValid(String privilege) {
        return privilege != null && VALID.matcher(privilege).matches();
    }

    public static List<String> wellKnown() {
        return List.of(SYSTEM_ADMIN, REPOSITORIES_MANAGE, USERS_MANAGE, SETTINGS_MANAGE,
                REPOSITORY_READ_ALL, REPOSITORY_WRITE_ALL);
    }

    public static boolean canRead(Set<String> privileges, String repositoryName) {
        return privileges.contains(SYSTEM_ADMIN)
                || privileges.contains(REPOSITORY_READ_ALL)
                || privileges.contains(REPOSITORY_WRITE_ALL)
                || privileges.contains(repositoryRead(repositoryName))
                || privileges.contains(repositoryWrite(repositoryName));
    }

    public static boolean canWrite(Set<String> privileges, String repositoryName) {
        return privileges.contains(SYSTEM_ADMIN)
                || privileges.contains(REPOSITORY_WRITE_ALL)
                || privileges.contains(repositoryWrite(repositoryName));
    }

    public static boolean has(Set<String> privileges, String privilege) {
        return privileges.contains(SYSTEM_ADMIN) || privileges.contains(privilege);
    }
}
