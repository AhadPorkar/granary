package dev.granary.server.api;

import dev.granary.core.GranaryException;
import dev.granary.core.model.User;
import dev.granary.core.security.AuthenticationService;
import dev.granary.core.security.GranaryPrincipal;
import dev.granary.core.security.UserService;
import dev.granary.server.security.CurrentUser;
import dev.granary.server.security.JwtService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthenticationService authentication;
    private final UserService users;
    private final JwtService jwt;

    public AuthController(AuthenticationService authentication, UserService users, JwtService jwt) {
        this.authentication = authentication;
        this.users = users;
        this.jwt = jwt;
    }

    @PostMapping("/login")
    public Dtos.LoginResponse login(@Valid @RequestBody Dtos.LoginRequest request) {
        GranaryPrincipal principal = authentication.authenticate(request.username(), request.password())
                .filter(p -> p.authMethod() == GranaryPrincipal.AuthMethod.PASSWORD)
                .orElseThrow(() -> new GranaryException(401, "auth.invalid", "Invalid username or password"));
        JwtService.SessionToken token = jwt.issue(principal.username());
        User user = users.require(principal.username());
        return new Dtos.LoginResponse(token.token(), token.expiresAt(), Dtos.UserView.of(user));
    }

    @GetMapping("/me")
    public Dtos.PrincipalView me() {
        return Dtos.PrincipalView.of(CurrentUser.requireUser());
    }

    @PostMapping("/password")
    public void changePassword(@Valid @RequestBody Dtos.ChangePasswordRequest request) {
        GranaryPrincipal principal = CurrentUser.requireUser();
        User user = users.require(principal.username());
        if (!users.passwordMatches(user, request.currentPassword())) {
            throw GranaryException.badRequest("auth.password.wrong", "Current password is wrong");
        }
        users.changePassword(user.getUsername(), request.newPassword());
    }
}
