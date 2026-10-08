package dev.granary.core.security;

import dev.granary.core.model.Role;
import dev.granary.core.model.User;
import dev.granary.core.settings.SettingsService;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns credentials into a {@link GranaryPrincipal}. Build tools send either a username + password, a
 * username + API token (as the password), or a bare Bearer token; all three end up here.
 */
@Service
public class AuthenticationService {

    private final UserService userService;
    private final ApiTokenService tokenService;
    private final RoleService roleService;
    private final SettingsService settings;

    public AuthenticationService(UserService userService, ApiTokenService tokenService, RoleService roleService,
                                 SettingsService settings) {
        this.userService = userService;
        this.tokenService = tokenService;
        this.roleService = roleService;
        this.settings = settings;
    }

    @Transactional(readOnly = true)
    public Optional<GranaryPrincipal> authenticate(String username, String password) {
        if (ApiTokenService.looksLikeToken(password)) {
            return authenticateToken(password);
        }
        return userService.find(username)
                .filter(User::isEnabled)
                .filter(user -> userService.passwordMatches(user, password))
                .map(user -> GranaryPrincipal.of(user, GranaryPrincipal.AuthMethod.PASSWORD));
    }

    @Transactional(readOnly = true)
    public Optional<GranaryPrincipal> authenticateToken(String secret) {
        return tokenService.authenticate(secret)
                .map(user -> GranaryPrincipal.of(user, GranaryPrincipal.AuthMethod.API_TOKEN));
    }

    @Transactional(readOnly = true)
    public Optional<GranaryPrincipal> principalFor(String username) {
        return userService.find(username)
                .filter(User::isEnabled)
                .map(user -> GranaryPrincipal.of(user, GranaryPrincipal.AuthMethod.SESSION));
    }

    /** The anonymous principal carries the privileges of the {@code anonymous} role when anonymous access is on. */
    @Transactional(readOnly = true)
    public GranaryPrincipal anonymous() {
        if (!settings.isAnonymousReadEnabled()) {
            return GranaryPrincipal.anonymous(Set.of());
        }
        return GranaryPrincipal.anonymous(roleService.find(RoleService.ANONYMOUS)
                .map(Role::getPrivileges).orElse(Set.of()));
    }
}
