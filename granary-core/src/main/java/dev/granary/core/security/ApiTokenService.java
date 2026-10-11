package dev.granary.core.security;

import dev.granary.core.GranaryException;
import dev.granary.core.dao.ApiTokenDao;
import dev.granary.core.dao.UserDao;
import dev.granary.core.model.ApiToken;
import dev.granary.core.model.User;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Personal access tokens for build tools. A token looks like {@code gry_…} and can be used either as a
 * Bearer token or as the password in HTTP Basic authentication (with any username).
 */
@Service
public class ApiTokenService {

    public static final String PREFIX = "gry_";

    private static final SecureRandom RANDOM = new SecureRandom();

    /** The clear-text token is only available in this result, right after creation. */
    public record IssuedToken(ApiToken token, String secret) {
    }

    private final ApiTokenDao tokens;
    private final UserDao users;

    public ApiTokenService(ApiTokenDao tokens, UserDao users) {
        this.tokens = tokens;
        this.users = users;
    }

    @Transactional
    public IssuedToken issue(User user, String name, Instant expiresAt) {
        // a NUL character cannot be stored on PostgreSQL; refuse it like a name that is too long
        if (name == null || name.isBlank() || name.length() > 200 || name.indexOf('\0') >= 0) {
            throw GranaryException.badRequest("token.name.invalid",
                    "Token name must be 1-200 characters without NUL characters");
        }
        if (expiresAt != null && !expiresAt.isAfter(Instant.now())) {
            throw GranaryException.badRequest("token.expiry.past", "Expiry must be in the future");
        }
        byte[] random = new byte[32];
        RANDOM.nextBytes(random);
        String secret = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        ApiToken token = new ApiToken();
        token.setUserId(user.getId());
        token.setName(name.trim());
        token.setTokenHash(hash(secret));
        token.setTokenPrefix(secret.substring(0, Math.min(12, secret.length())));
        token.setExpiresAt(expiresAt);
        return new IssuedToken(tokens.save(token), secret);
    }

    @Transactional(readOnly = true)
    public List<ApiToken> list(User user) {
        return tokens.findByUserIdOrderByCreatedAtDesc(user.getId());
    }

    @Transactional
    public void revoke(User user, UUID tokenId) {
        ApiToken token = tokens.findById(tokenId).orElseThrow(() ->
                GranaryException.notFound("token.notFound", "Token does not exist"));
        if (!token.getUserId().equals(user.getId())) {
            throw GranaryException.notFound("token.notFound", "Token does not exist");
        }
        tokens.delete(token);
    }

    /**
     * Resolves a presented secret to its enabled owner, or empty if unknown, expired or disabled, and records when
     * the token was last used. Callers invoke it outside a transaction (it starts its own): it used to start a new
     * transaction from inside a read-only one, which needed a second pooled connection while the first stayed held,
     * so enough concurrent token-authenticated requests exhausted the pool and failed after its time-out.
     */
    @Transactional
    public Optional<User> authenticate(String secret) {
        if (secret == null || !secret.startsWith(PREFIX)) {
            return Optional.empty();
        }
        Optional<ApiToken> found = tokens.findByTokenHash(hash(secret));
        if (found.isEmpty() || found.get().isExpired(Instant.now())) {
            return Optional.empty();
        }
        ApiToken token = found.get();
        Optional<User> user = users.findById(token.getUserId()).filter(User::isEnabled);
        if (user.isPresent()) {
            token.setLastUsedAt(Instant.now());
            tokens.save(token);
        }
        return user;
    }

    public static boolean looksLikeToken(String value) {
        return value != null && value.startsWith(PREFIX) && value.length() > PREFIX.length() + 20;
    }

    static String hash(String secret) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(secret.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
