package dev.granary.server.security;

import dev.granary.core.GranaryException;
import dev.granary.core.security.GranaryPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Access to the caller of the current request. */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static GranaryPrincipal principal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof GranaryAuthentication ga) {
            return ga.getPrincipal();
        }
        return GranaryPrincipal.anonymous(java.util.Set.of());
    }

    public static GranaryPrincipal requireUser() {
        GranaryPrincipal p = principal();
        if (p.anonymous()) {
            throw new GranaryException(401, "auth.required", "Authentication required");
        }
        return p;
    }
}
