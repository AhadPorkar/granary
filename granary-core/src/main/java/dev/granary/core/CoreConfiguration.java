package dev.granary.core;

import dev.granary.core.config.GranaryProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Wires the core module. The server's {@code @SpringBootApplication} lives in {@code dev.granary},
 * so component scanning, entity scanning and Spring Data repositories cover this package automatically.
 */
@Configuration
@EnableConfigurationProperties(GranaryProperties.class)
@EnableScheduling
public class CoreConfiguration {
}
