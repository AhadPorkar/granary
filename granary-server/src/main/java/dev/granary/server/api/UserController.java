package dev.granary.server.api;

import dev.granary.core.GranaryException;
import dev.granary.core.model.User;
import dev.granary.core.security.ApiTokenService;
import dev.granary.core.security.GranaryPrincipal;
import dev.granary.core.security.Privileges;
import dev.granary.core.security.RoleService;
import dev.granary.core.security.UserService;
import dev.granary.server.security.CurrentUser;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Users, roles and the caller's own API tokens. */
@RestController
@RequestMapping("/api/v1")
public class UserController {

    private static final String MANAGE = "hasAnyAuthority('system:admin','users:manage')";

    private final UserService users;
    private final RoleService roles;
    private final ApiTokenService tokens;

    public UserController(UserService users, RoleService roles, ApiTokenService tokens) {
        this.users = users;
        this.roles = roles;
        this.tokens = tokens;
    }

    // --- users ---

    @GetMapping("/users")
    @PreAuthorize(MANAGE)
    public List<Dtos.UserView> listUsers() {
        return users.list().stream().map(Dtos.UserView::of).toList();
    }

    @PostMapping("/users")
    @PreAuthorize(MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public Dtos.UserView createUser(@Valid @RequestBody Dtos.CreateUserRequest request) {
        return Dtos.UserView.of(users.create(request.username(), request.password(), request.displayName(),
                request.email(), request.roles()));
    }

    @GetMapping("/users/{username}")
    @PreAuthorize(MANAGE)
    public Dtos.UserView getUser(@PathVariable String username) {
        return Dtos.UserView.of(users.require(username));
    }

    @PutMapping("/users/{username}")
    @PreAuthorize(MANAGE)
    public Dtos.UserView updateUser(@PathVariable String username, @Valid @RequestBody Dtos.UpdateUserRequest request) {
        GranaryPrincipal me = CurrentUser.requireUser();
        if (me.username().equals(username) && Boolean.FALSE.equals(request.enabled())) {
            throw GranaryException.badRequest("user.self.disable", "You cannot disable your own account");
        }
        return Dtos.UserView.of(users.update(username, request.displayName(), request.email(), request.enabled(), request.roles()));
    }

    @PostMapping("/users/{username}/password")
    @PreAuthorize(MANAGE)
    public void setPassword(@PathVariable String username, @Valid @RequestBody Dtos.SetPasswordRequest request) {
        users.changePassword(username, request.password());
    }

    @DeleteMapping("/users/{username}")
    @PreAuthorize(MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteUser(@PathVariable String username) {
        if (CurrentUser.requireUser().username().equals(username)) {
            throw GranaryException.badRequest("user.self.delete", "You cannot delete your own account");
        }
        users.delete(username);
    }

    // --- roles ---

    @GetMapping("/roles")
    @PreAuthorize(MANAGE)
    public List<Dtos.RoleView> listRoles() {
        return roles.list().stream().map(Dtos.RoleView::of).toList();
    }

    @GetMapping("/privileges")
    @PreAuthorize(MANAGE)
    public List<String> privileges() {
        return Privileges.wellKnown();
    }

    @PostMapping("/roles")
    @PreAuthorize(MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public Dtos.RoleView createRole(@Valid @RequestBody Dtos.RoleRequest request) {
        return Dtos.RoleView.of(roles.create(request.name(), request.description(), request.privileges()));
    }

    @PutMapping("/roles/{name}")
    @PreAuthorize(MANAGE)
    public Dtos.RoleView updateRole(@PathVariable String name, @Valid @RequestBody Dtos.RoleRequest request) {
        return Dtos.RoleView.of(roles.update(name, request.description(), request.privileges()));
    }

    @DeleteMapping("/roles/{name}")
    @PreAuthorize(MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteRole(@PathVariable String name) {
        roles.delete(name);
    }

    // --- my tokens ---

    @GetMapping("/tokens")
    public List<Dtos.TokenView> listTokens() {
        return tokens.list(me()).stream().map(Dtos.TokenView::of).toList();
    }

    @PostMapping("/tokens")
    @ResponseStatus(HttpStatus.CREATED)
    public Dtos.IssuedTokenView createToken(@Valid @RequestBody Dtos.CreateTokenRequest request) {
        ApiTokenService.IssuedToken issued = tokens.issue(me(), request.name(), request.expiresAt());
        return new Dtos.IssuedTokenView(Dtos.TokenView.of(issued.token()), issued.secret());
    }

    @DeleteMapping("/tokens/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeToken(@PathVariable UUID id) {
        tokens.revoke(me(), id);
    }

    private User me() {
        return users.require(CurrentUser.requireUser().username());
    }
}
