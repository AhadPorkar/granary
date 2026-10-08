package dev.granary.core.security;

import static org.assertj.core.api.Assertions.assertThat;

import dev.granary.core.model.Role;
import dev.granary.core.model.User;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PrivilegesTest {

    @Test
    void validatesPrivilegeSyntax() {
        assertThat(Privileges.isValid("system:admin")).isTrue();
        assertThat(Privileges.isValid("repository:read:*")).isTrue();
        assertThat(Privileges.isValid("repository:write:maven-releases")).isTrue();
        assertThat(Privileges.isValid("repository:delete:x")).isFalse();
        assertThat(Privileges.isValid("repository:read:")).isFalse();
        assertThat(Privileges.isValid("repository:read:../x")).isFalse();
        assertThat(Privileges.isValid(null)).isFalse();
    }

    @Test
    void adminImpliesEverything() {
        Set<String> admin = Set.of(Privileges.SYSTEM_ADMIN);
        assertThat(Privileges.canRead(admin, "any")).isTrue();
        assertThat(Privileges.canWrite(admin, "any")).isTrue();
        assertThat(Privileges.has(admin, Privileges.USERS_MANAGE)).isTrue();
    }

    @Test
    void writeImpliesReadForTheSameScope() {
        assertThat(Privileges.canRead(Set.of("repository:write:npm-hosted"), "npm-hosted")).isTrue();
        assertThat(Privileges.canRead(Set.of("repository:write:npm-hosted"), "other")).isFalse();
        assertThat(Privileges.canWrite(Set.of("repository:read:*"), "npm-hosted")).isFalse();
        assertThat(Privileges.canRead(Set.of(Privileges.REPOSITORY_WRITE_ALL), "x")).isTrue();
    }

    @Test
    void specificPrivilegesAreScopedToOneRepository() {
        Set<String> p = Set.of("repository:read:a");
        assertThat(Privileges.canRead(p, "a")).isTrue();
        assertThat(Privileges.canRead(p, "b")).isFalse();
    }

    @Test
    void principalFlattensRolesIntoPrivileges() {
        Role dev = new Role();
        dev.setName("developer");
        dev.setPrivileges(Set.of(Privileges.REPOSITORY_READ_ALL, Privileges.REPOSITORY_WRITE_ALL));
        Role managers = new Role();
        managers.setName("managers");
        managers.setPrivileges(Set.of(Privileges.REPOSITORIES_MANAGE));
        User user = new User();
        user.setUsername("jane");
        user.setRoles(Set.of(dev, managers));

        GranaryPrincipal principal = GranaryPrincipal.of(user, GranaryPrincipal.AuthMethod.PASSWORD);

        assertThat(principal.roles()).containsExactlyInAnyOrder("developer", "managers");
        assertThat(principal.canWrite("anything")).isTrue();
        assertThat(principal.has(Privileges.REPOSITORIES_MANAGE)).isTrue();
        assertThat(principal.has(Privileges.USERS_MANAGE)).isFalse();
        assertThat(principal.isAdmin()).isFalse();
        assertThat(principal.anonymous()).isFalse();
    }

    @Test
    void anonymousPrincipalCarriesOnlyWhatItIsGiven() {
        GranaryPrincipal none = GranaryPrincipal.anonymous(Set.of());
        assertThat(none.anonymous()).isTrue();
        assertThat(none.canRead("x")).isFalse();
        GranaryPrincipal reader = GranaryPrincipal.anonymous(Set.of(Privileges.REPOSITORY_READ_ALL));
        assertThat(reader.canRead("x")).isTrue();
        assertThat(reader.canWrite("x")).isFalse();
        assertThat(reader.username()).isEqualTo("anonymous");
    }
}
