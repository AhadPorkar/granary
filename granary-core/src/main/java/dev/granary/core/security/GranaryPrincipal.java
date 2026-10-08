package dev.granary.core.security;

import dev.granary.core.model.Role;
import dev.granary.core.model.User;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * The authenticated (or anonymous) caller, flattened to a set of privilege strings so authorisation
 * checks never need the database.
 */
public record GranaryPrincipal(UUID userId, String username, Set<String> roles, Set<String> privileges,
                               boolean anonymous, AuthMethod authMethod) {

    public enum AuthMethod { NONE, PASSWORD, API_TOKEN, SESSION }

    public static final String ANONYMOUS_USERNAME = "anonymous";

    public GranaryPrincipal {
        roles = Set.copyOf(roles);
        privileges = Set.copyOf(privileges);
    }

    public static GranaryPrincipal anonymous(Set<String> privileges) {
        return new GranaryPrincipal(null, ANONYMOUS_USERNAME, Set.of("anonymous"), privileges, true, AuthMethod.NONE);
    }

    public static GranaryPrincipal of(User user, AuthMethod method) {
        Set<String> roles = new LinkedHashSet<>();
        Set<String> privileges = new LinkedHashSet<>();
        for (Role role : user.getRoles()) {
            roles.add(role.getName());
            privileges.addAll(role.getPrivileges());
        }
        return new GranaryPrincipal(user.getId(), user.getUsername(), roles, privileges, false, method);
    }

    public boolean canRead(String repositoryName) {
        return Privileges.canRead(privileges, repositoryName);
    }

    public boolean canWrite(String repositoryName) {
        return Privileges.canWrite(privileges, repositoryName);
    }

    public boolean has(String privilege) {
        return Privileges.has(privileges, privilege);
    }

    public boolean isAdmin() {
        return privileges.contains(Privileges.SYSTEM_ADMIN);
    }
}
