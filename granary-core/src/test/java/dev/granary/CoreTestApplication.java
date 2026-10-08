package dev.granary;

import dev.granary.core.format.AccessLevel;
import dev.granary.core.format.FormatHandler;
import dev.granary.core.format.RepositoryRequest;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.ResponseEntity;

/**
 * Boots the core module on its own for service-level tests: H2 in memory, Flyway, a temporary blob store
 * and one fake format so repositories can be created.
 */
@SpringBootApplication
public class CoreTestApplication {

    public static final String FAKE_FORMAT = "fake";

    @Bean
    FormatHandler fakeFormat() {
        return new FormatHandler() {
            @Override
            public String format() {
                return FAKE_FORMAT;
            }

            @Override
            public String displayName() {
                return "Fake";
            }

            @Override
            public AccessLevel requiredAccess(RepositoryRequest request) {
                return AccessLevel.READ;
            }

            @Override
            public boolean isMetadataPath(String path) {
                return path.endsWith("index.json");
            }

            @Override
            public ResponseEntity<?> handle(RepositoryRequest request) {
                return ResponseEntity.ok().build();
            }
        };
    }
}
