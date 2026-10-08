package dev.granary.core.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.granary.core.CoreServiceTest;
import dev.granary.core.GranaryException;
import dev.granary.core.model.ApiToken;
import dev.granary.core.model.Role;
import dev.granary.core.model.User;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class SecurityServicesTest extends CoreServiceTest {

    @Autowired
    UserService users;

    @Autowired
    RoleService roles;

    @Autowired
    ApiTokenService tokens;

    @Autowired
    AuthenticationService authentication;

    @Autowired
    SecurityBootstrap bootstrap;

    @AfterEach
    void cleanUsersAndRoles() {
        users.list().stream().filter(u -> !u.isBuiltin()).forEach(u -> users.delete(u.getUsername()));
        roles.list().stream().filter(r -> !r.isBuiltin()).forEach(r -> roles.delete(r.getName()));
        for (ApiToken t : tokens.list(users.require("admin"))) {
            tokens.revoke(users.require("admin"), t.getId());
        }
    }

    @Test
    void bootstrapCreatesBuiltInsOnceAndIsIdempotent() {
        bootstrap.bootstrap();
        bootstrap.bootstrap();

        assertThat(roles.list()).extracting(Role::getName).contains("admin", "developer", "reader", "anonymous");
        User admin = users.require("admin");
        assertThat(admin.isBuiltin()).isTrue();
        assertThat(users.passwordMatches(admin, "admin123")).isTrue();
        assertThat(roles.require("anonymous").getPrivileges()).containsExactly(Privileges.REPOSITORY_READ_ALL);
    }

    @Test
    void passwordAuthenticationYieldsAPrincipalWithRolePrivileges() {
        users.create("jane", "s3cret-pass", "Jane", "jane@example.org", Set.of("developer"));

        GranaryPrincipal p = authentication.authenticate("jane", "s3cret-pass").orElseThrow();

        assertThat(p.username()).isEqualTo("jane");
        assertThat(p.roles()).containsExactly("developer");
        assertThat(p.canWrite("anything")).isTrue();
        assertThat(p.authMethod()).isEqualTo(GranaryPrincipal.AuthMethod.PASSWORD);
        assertThat(authentication.authenticate("jane", "wrong")).isEmpty();
        assertThat(authentication.authenticate("nobody", "x")).isEmpty();
    }

    @Test
    void disabledUsersCannotAuthenticate() {
        users.create("bob", "password1", null, null, Set.of("reader"));
        users.update("bob", null, null, false, null);
        assertThat(authentication.authenticate("bob", "password1")).isEmpty();
        assertThat(authentication.principalFor("bob")).isEmpty();
    }

    @Test
    void userValidation() {
        assertThatThrownBy(() -> users.create("bad name", "password1", null, null, Set.of()))
                .extracting("code").isEqualTo("user.username.invalid");
        assertThatThrownBy(() -> users.create("anonymous", "password1", null, null, Set.of()))
                .extracting("code").isEqualTo("user.username.reserved");
        assertThatThrownBy(() -> users.create("short", "1234567", null, null, Set.of()))
                .extracting("code").isEqualTo("user.password.tooShort");
        assertThatThrownBy(() -> users.create("admin", "password1", null, null, Set.of()))
                .extracting("code").isEqualTo("user.username.taken");
        assertThatThrownBy(() -> users.create("x", "password1", null, null, Set.of("ghost")))
                .extracting("code").isEqualTo("role.notFound");
    }

    @Test
    void builtInAdminIsProtected() {
        assertThatThrownBy(() -> users.delete("admin")).extracting("code").isEqualTo("user.builtin");
        assertThatThrownBy(() -> users.update("admin", null, null, false, null)).extracting("code").isEqualTo("user.builtin");
        assertThatThrownBy(() -> users.update("admin", null, null, null, Set.of("reader"))).extracting("code").isEqualTo("user.builtin");
        assertThatThrownBy(() -> roles.delete("admin")).extracting("code").isEqualTo("role.builtin");
        assertThatThrownBy(() -> roles.update("admin", "x", Set.of(Privileges.REPOSITORY_READ_ALL)))
                .extracting("code").isEqualTo("role.admin.locked");
    }

    @Test
    void changePasswordTakesEffectImmediately() {
        users.create("carl", "password1", null, null, Set.of("reader"));
        users.changePassword("carl", "password2");
        assertThat(authentication.authenticate("carl", "password1")).isEmpty();
        assertThat(authentication.authenticate("carl", "password2")).isPresent();
    }

    @Test
    void rolesValidatePrivilegesAndAreRemovedFromUsersOnDelete() {
        assertThatThrownBy(() -> roles.create("r", null, Set.of("repository:fly:*")))
                .extracting("code").isEqualTo("role.privilege.invalid");
        assertThatThrownBy(() -> roles.create("bad name", null, Set.of()))
                .extracting("code").isEqualTo("role.name.invalid");
        roles.create("npm-publishers", "publish npm", Set.of(" repository:write:npm-hosted "));
        assertThat(roles.require("npm-publishers").getPrivileges()).containsExactly("repository:write:npm-hosted");
        assertThatThrownBy(() -> roles.create("npm-publishers", null, Set.of())).extracting("code").isEqualTo("role.name.taken");

        users.create("dana", "password1", null, null, Set.of("npm-publishers"));
        assertThat(authentication.authenticate("dana", "password1").orElseThrow().canWrite("npm-hosted")).isTrue();
        roles.delete("npm-publishers");
        assertThat(users.require("dana").getRoles()).isEmpty();
        assertThat(authentication.authenticate("dana", "password1").orElseThrow().canRead("npm-hosted")).isFalse();
    }

    @Test
    void apiTokensAuthenticateAsBearerOrAsPassword() {
        User admin = users.require("admin");
        ApiTokenService.IssuedToken issued = tokens.issue(admin, "ci", null);

        assertThat(issued.secret()).startsWith("gry_");
        assertThat(issued.token().getTokenPrefix()).isEqualTo(issued.secret().substring(0, 12));
        assertThat(ApiTokenService.looksLikeToken(issued.secret())).isTrue();
        assertThat(ApiTokenService.looksLikeToken("gry_short")).isFalse();

        GranaryPrincipal viaToken = authentication.authenticateToken(issued.secret()).orElseThrow();
        assertThat(viaToken.username()).isEqualTo("admin");
        assertThat(viaToken.authMethod()).isEqualTo(GranaryPrincipal.AuthMethod.API_TOKEN);
        GranaryPrincipal viaPassword = authentication.authenticate("anything", issued.secret()).orElseThrow();
        assertThat(viaPassword.username()).isEqualTo("admin");
        assertThat(tokens.list(admin)).singleElement().satisfies(t -> assertThat(t.getLastUsedAt()).isNotNull());
        assertThat(authentication.authenticateToken("gry_" + "x".repeat(40))).isEmpty();
    }

    @Test
    void expiredAndRevokedTokensAreRejected() {
        User admin = users.require("admin");
        assertThatThrownBy(() -> tokens.issue(admin, "old", Instant.now().minusSeconds(1)))
                .extracting("code").isEqualTo("token.expiry.past");
        assertThatThrownBy(() -> tokens.issue(admin, " ", null)).extracting("code").isEqualTo("token.name.invalid");

        ApiTokenService.IssuedToken issued = tokens.issue(admin, "ci", Instant.now().plusSeconds(60));
        assertThat(authentication.authenticateToken(issued.secret())).isPresent();
        tokens.revoke(admin, issued.token().getId());
        assertThat(authentication.authenticateToken(issued.secret())).isEmpty();
        assertThatThrownBy(() -> tokens.revoke(admin, issued.token().getId())).extracting("code").isEqualTo("token.notFound");
    }

    @Test
    void tokensOfOtherUsersCannotBeRevoked() {
        User admin = users.require("admin");
        User eve = users.create("eve", "password1", null, null, Set.of("reader"));
        ApiTokenService.IssuedToken issued = tokens.issue(admin, "ci", null);
        assertThatThrownBy(() -> tokens.revoke(eve, issued.token().getId())).extracting("code").isEqualTo("token.notFound");
    }

    @Test
    void deletingAUserRevokesItsTokens() {
        User tmp = users.create("tmp", "password1", null, null, Set.of("reader"));
        ApiTokenService.IssuedToken issued = tokens.issue(tmp, "ci", null);
        users.delete("tmp");
        assertThat(authentication.authenticateToken(issued.secret())).isEmpty();
    }

    @Test
    void anonymousPrincipalFollowsTheSetting() {
        settings.setAnonymousReadEnabled(true);
        assertThat(authentication.anonymous().canRead("x")).isTrue();
        settings.setAnonymousReadEnabled(false);
        assertThat(authentication.anonymous().canRead("x")).isFalse();
        assertThat(authentication.anonymous().anonymous()).isTrue();
    }

    @Test
    void settingsRoundTrip() {
        settings.setBaseUrl("https://granary.example.org/");
        assertThat(settings.baseUrl()).contains("https://granary.example.org");
        settings.setBaseUrl("");
        assertThat(settings.baseUrl()).isEmpty();
        assertThat(settings.snapshot()).containsKeys("anonymousRead", "offlineMode", "baseUrl");
        assertThat(List.of(settings.get("unknown.key"))).containsExactly(java.util.Optional.empty());
    }

    @Test
    void granaryExceptionCarriesStatusCodeAndDetails() {
        GranaryException e = GranaryException.badRequest("x.y", "msg", java.util.Map.of("field", "f"));
        assertThat(e.status()).isEqualTo(400);
        assertThat(e.code()).isEqualTo("x.y");
        assertThat(e.details()).containsEntry("field", "f");
        assertThat(GranaryException.conflict("c", "m").status()).isEqualTo(409);
        assertThat(GranaryException.forbidden("c", "m").status()).isEqualTo(403);
        assertThat(GranaryException.notFound("c", "m").status()).isEqualTo(404);
    }
}
