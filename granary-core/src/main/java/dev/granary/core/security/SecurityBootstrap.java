package dev.granary.core.security;

import dev.granary.core.config.GranaryProperties;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Creates the built-in roles and the admin user on first start. Idempotent. */
@Component
@Order(1)
public class SecurityBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SecurityBootstrap.class);

    private final RoleService roles;
    private final UserService users;
    private final GranaryProperties properties;

    public SecurityBootstrap(RoleService roles, UserService users, GranaryProperties properties) {
        this.roles = roles;
        this.users = users;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        bootstrap();
    }

    public void bootstrap() {
        roles.ensureBuiltin(RoleService.ADMIN, "Full administrative access", Set.of(Privileges.SYSTEM_ADMIN));
        roles.ensureBuiltin(RoleService.DEVELOPER, "Read and publish to every repository",
                Set.of(Privileges.REPOSITORY_READ_ALL, Privileges.REPOSITORY_WRITE_ALL));
        roles.ensureBuiltin(RoleService.READER, "Read every repository", Set.of(Privileges.REPOSITORY_READ_ALL));
        roles.ensureBuiltin(RoleService.ANONYMOUS, "Privileges of unauthenticated clients when anonymous access is enabled",
                Set.of(Privileges.REPOSITORY_READ_ALL));
        if (users.ensureAdmin(properties.getSecurity().getAdminPassword())) {
            log.warn("Created built-in user 'admin'. Change its password right away (Settings → Users).");
        }
    }
}
