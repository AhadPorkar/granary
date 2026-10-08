package dev.granary.server.web;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the single-page application shell for the site root. The shell must never be cached by browsers,
 * because it references fingerprinted assets that change with every release; the assets themselves are
 * served by {@link SpaConfig} with the default caching.
 */
@RestController
public class IndexController {

    private static final Resource INDEX = new ClassPathResource("/static/index.html");

    @GetMapping({"/", "/index.html"})
    public ResponseEntity<Resource> index() {
        if (!INDEX.exists()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache())
                .contentType(MediaType.TEXT_HTML)
                .body(INDEX);
    }
}
