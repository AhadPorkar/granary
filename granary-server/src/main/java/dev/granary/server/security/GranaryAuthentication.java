package dev.granary.server.security;

import dev.granary.core.security.GranaryPrincipal;
import java.util.Collection;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** Spring Security view of a {@link GranaryPrincipal}; privilege strings double as granted authorities. */
public final class GranaryAuthentication implements Authentication {

    private final GranaryPrincipal principal;
    private final List<GrantedAuthority> authorities;

    public GranaryAuthentication(GranaryPrincipal principal) {
        this.principal = principal;
        this.authorities = principal.privileges().stream()
                .map(SimpleGrantedAuthority::new)
                .map(GrantedAuthority.class::cast)
                .toList();
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public Object getDetails() {
        return principal.authMethod();
    }

    @Override
    public GranaryPrincipal getPrincipal() {
        return principal;
    }

    @Override
    public boolean isAuthenticated() {
        return !principal.anonymous();
    }

    @Override
    public void setAuthenticated(boolean isAuthenticated) {
        throw new UnsupportedOperationException("immutable");
    }

    @Override
    public String getName() {
        return principal.username();
    }
}
