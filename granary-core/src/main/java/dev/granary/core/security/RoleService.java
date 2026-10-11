package dev.granary.core.security;

import dev.granary.core.GranaryException;
import dev.granary.core.StorableText;
import dev.granary.core.dao.RoleDao;
import dev.granary.core.dao.UserDao;
import dev.granary.core.model.Role;
import dev.granary.core.model.User;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RoleService {

    public static final String ADMIN = "admin";
    public static final String DEVELOPER = "developer";
    public static final String READER = "reader";
    public static final String ANONYMOUS = "anonymous";

    private static final Pattern NAME = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9._-]{0,99}$");

    /** Column lengths of {@code roles}. */
    static final int MAX_NAME = 100;
    static final int MAX_DESCRIPTION = 500;

    private final RoleDao roles;
    private final UserDao users;

    public RoleService(RoleDao roles, UserDao users) {
        this.roles = roles;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public List<Role> list() {
        return roles.findAllByOrderByNameAsc();
    }

    @Transactional(readOnly = true)
    public Optional<Role> find(String name) {
        // a name no role can have is not found, without asking PostgreSQL to compare a NUL character
        if (!StorableText.usableKey(name, MAX_NAME)) {
            return Optional.empty();
        }
        return roles.findByName(name);
    }

    @Transactional(readOnly = true)
    public Role require(String name) {
        return find(name).orElseThrow(() ->
                GranaryException.notFound("role.notFound", "Role '" + name + "' does not exist"));
    }

    @Transactional
    public Role create(String name, String description, Set<String> privileges) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw GranaryException.badRequest("role.name.invalid", "Role name must match " + NAME.pattern());
        }
        if (roles.findByName(name).isPresent()) {
            throw GranaryException.conflict("role.name.taken", "A role named '" + name + "' already exists");
        }
        StorableText.check("description", description, MAX_DESCRIPTION);
        Role role = new Role();
        role.setName(name);
        role.setDescription(description);
        role.setPrivileges(validated(privileges));
        return roles.save(role);
    }

    @Transactional
    public Role update(String name, String description, Set<String> privileges) {
        Role role = require(name);
        if (role.isBuiltin() && ADMIN.equals(name) && !privileges.contains(Privileges.SYSTEM_ADMIN)) {
            throw GranaryException.badRequest("role.admin.locked", "The admin role must keep system:admin");
        }
        StorableText.check("description", description, MAX_DESCRIPTION);
        role.setDescription(description);
        role.setPrivileges(validated(privileges));
        return roles.save(role);
    }

    @Transactional
    public void delete(String name) {
        Role role = require(name);
        if (role.isBuiltin()) {
            throw GranaryException.badRequest("role.builtin", "Built-in roles cannot be deleted");
        }
        for (User user : users.findAll()) {
            if (user.getRoles().remove(role)) {
                users.save(user);
            }
        }
        roles.delete(role);
    }

    /** Creates a built-in role if it is missing; existing roles are left untouched so admins can edit them. */
    @Transactional
    public Role ensureBuiltin(String name, String description, Set<String> privileges) {
        return roles.findByName(name).orElseGet(() -> {
            Role role = new Role();
            role.setName(name);
            role.setDescription(description);
            role.setBuiltin(true);
            role.setPrivileges(privileges);
            return roles.save(role);
        });
    }

    private static Set<String> validated(Set<String> privileges) {
        Set<String> result = new LinkedHashSet<>();
        for (String p : privileges == null ? Set.<String>of() : privileges) {
            String trimmed = p.trim();
            if (!Privileges.isValid(trimmed)) {
                throw GranaryException.badRequest("role.privilege.invalid",
                        "'" + trimmed + "' is not a valid privilege", Map.of("privilege", trimmed));
            }
            result.add(trimmed);
        }
        return result;
    }
}
