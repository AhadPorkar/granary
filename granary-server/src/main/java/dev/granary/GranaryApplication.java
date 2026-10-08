package dev.granary;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Granary – a universal, offline-first artifact repository manager.
 *
 * <p>This class sits in the root package on purpose: component scanning, entity scanning and Spring Data
 * repository detection then cover every module ({@code dev.granary.core}, {@code dev.granary.format.*}).
 */
@SpringBootApplication
public class GranaryApplication {

    public static void main(String[] args) {
        SpringApplication.run(GranaryApplication.class, args);
    }
}
