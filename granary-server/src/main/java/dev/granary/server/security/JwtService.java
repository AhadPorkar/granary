package dev.granary.server.security;

import dev.granary.core.config.GranaryProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Optional;
import javax.crypto.SecretKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Issues and verifies the short-lived HS256 tokens the web UI uses as its session. The signing secret
 * comes from {@code granary.security.jwt-secret}; when that is empty a random secret is generated once
 * and kept under the data directory, so sessions survive restarts without any configuration.
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    public record SessionToken(String token, Instant expiresAt) {
    }

    private final SecretKey key;
    private final GranaryProperties.Security config;

    public JwtService(GranaryProperties properties) {
        this.config = properties.getSecurity();
        this.key = Keys.hmacShaKeyFor(resolveSecret(properties));
    }

    public SessionToken issue(String username) {
        Instant now = Instant.now();
        Instant expiry = now.plus(config.getSessionTokenTtl());
        String token = Jwts.builder()
                .subject(username)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .issuer("granary")
                .signWith(key)
                .compact();
        return new SessionToken(token, expiry);
    }

    /** The subject (username) of a valid, unexpired token; empty when the token cannot be trusted. */
    public Optional<String> subjectOf(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            return Optional.ofNullable(claims.getSubject());
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static byte[] resolveSecret(GranaryProperties properties) {
        String configured = properties.getSecurity().getJwtSecret();
        if (configured != null && !configured.isBlank()) {
            byte[] bytes = configured.getBytes(StandardCharsets.UTF_8);
            if (bytes.length < 32) {
                throw new IllegalStateException("granary.security.jwt-secret must be at least 32 bytes long");
            }
            return bytes;
        }
        Path file = properties.getDataDir().resolve("jwt.secret");
        try {
            if (Files.exists(file)) {
                return Base64.getDecoder().decode(Files.readString(file).trim());
            }
            byte[] random = new byte[64];
            new SecureRandom().nextBytes(random);
            Files.createDirectories(file.getParent());
            Files.writeString(file, Base64.getEncoder().encodeToString(random));
            log.info("generated a new session signing secret at {}", file.toAbsolutePath());
            return random;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read or create " + file, e);
        }
    }
}
