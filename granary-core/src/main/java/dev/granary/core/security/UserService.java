package dev.granary.core.security;

import dev.granary.core.GranaryException;
import dev.granary.core.dao.ApiTokenDao;
import dev.granary.core.dao.UserDao;
import dev.granary.core.model.Role;
import dev.granary.core.model.User;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    public static final String ADMIN_USERNAME = "admin";
    public static final int MIN_PASSWORD_LENGTH = 8;

    private static final Pattern USERNAME = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9._@-]{0,99}$");

    private final UserDao users;
    private final ApiTokenDao tokens;
    private final RoleService roleService;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserDao users, ApiTokenDao tokens, RoleService roleService, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.tokens = tokens;
        this.roleService = roleService;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional(readOnly = true)
    public List<User> list() {
        return users.findAllByOrderByUsernameAsc();
    }

    @Transactional(readOnly = true)
    public Optional<User> find(String username) {
        return users.findByUsername(username);
    }

    @Transactional(readOnly = true)
    public Optional<User> find(UUID id) {
        return users.findById(id);
    }

    @Transactional(readOnly = true)
    public User require(String username) {
        return users.findByUsername(username).orElseThrow(() ->
                GranaryException.notFound("user.notFound", "User '" + username + "' does not exist"));
    }

    @Transactional
    public User create(String username, String password, String displayName, String email, Set<String> roleNames) {
        if (username == null || !USERNAME.matcher(username).matches()) {
            throw GranaryException.badRequest("user.username.invalid", "Username must match " + USERNAME.pattern());
        }
        if (GranaryPrincipal.ANONYMOUS_USERNAME.equalsIgnoreCase(username)) {
            throw GranaryException.badRequest("user.username.reserved", "'anonymous' is reserved");
        }
        if (users.existsByUsername(username)) {
            throw GranaryException.conflict("user.username.taken", "A user named '" + username + "' already exists");
        }
        checkPassword(password);
        User user = new User();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setDisplayName(displayName);
        user.setEmail(email);
        user.setRoles(resolveRoles(roleNames));
        return users.save(user);
    }

    @Transactional
    public User update(String username, String displayName, String email, Boolean enabled, Set<String> roleNames) {
        User user = require(username);
        user.setDisplayName(displayName);
        user.setEmail(email);
        if (enabled != null) {
            if (!enabled && user.isBuiltin()) {
                throw GranaryException.badRequest("user.builtin", "The built-in admin user cannot be disabled");
            }
            user.setEnabled(enabled);
        }
        if (roleNames != null) {
            Set<Role> roles = resolveRoles(roleNames);
            if (user.isBuiltin() && roles.stream().noneMatch(r -> RoleService.ADMIN.equals(r.getName()))) {
                throw GranaryException.badRequest("user.builtin", "The built-in admin user must keep the admin role");
            }
            user.setRoles(roles);
        }
        return users.save(user);
    }

    @Transactional
    public void changePassword(String username, String newPassword) {
        User user = require(username);
        checkPassword(newPassword);
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        users.save(user);
    }

    @Transactional
    public void delete(String username) {
        User user = require(username);
        if (user.isBuiltin()) {
            throw GranaryException.badRequest("user.builtin", "The built-in admin user cannot be deleted");
        }
        tokens.deleteByUserId(user.getId());
        users.delete(user);
    }

    public boolean passwordMatches(User user, String rawPassword) {
        return rawPassword != null && passwordEncoder.matches(rawPassword, user.getPasswordHash());
    }

    /** Creates the built-in admin on first start. Returns {@code true} when it was created now. */
    @Transactional
    public boolean ensureAdmin(String initialPassword) {
        if (users.existsByUsername(ADMIN_USERNAME)) {
            return false;
        }
        User admin = new User();
        admin.setUsername(ADMIN_USERNAME);
        admin.setDisplayName("Administrator");
        admin.setBuiltin(true);
        admin.setPasswordHash(passwordEncoder.encode(initialPassword));
        admin.setRoles(Set.of(roleService.require(RoleService.ADMIN)));
        users.save(admin);
        return true;
    }

    private Set<Role> resolveRoles(Set<String> roleNames) {
        Set<Role> roles = new LinkedHashSet<>();
        for (String name : roleNames == null ? Set.<String>of() : roleNames) {
            roles.add(roleService.require(name));
        }
        return roles;
    }

    private static void checkPassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw GranaryException.badRequest("user.password.tooShort",
                    "Password must have at least " + MIN_PASSWORD_LENGTH + " characters");
        }
    }
}
