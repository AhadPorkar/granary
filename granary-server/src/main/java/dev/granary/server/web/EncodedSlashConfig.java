package dev.granary.server.web;

import org.springframework.boot.tomcat.TomcatConnectorCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.web.firewall.HttpFirewall;
import org.springframework.security.web.firewall.StrictHttpFirewall;

/**
 * Lets an encoded slash through to the repository dispatcher.
 *
 * <p>npm addresses a scoped package as {@code /@scope%2Fname} (one path segment with the separator
 * percent-encoded). Both Tomcat and Spring Security's firewall reject such URLs out of the box; the
 * dispatcher decodes the path itself and the storage layer refuses {@code ..} segments, so letting the
 * encoded slash in is safe here.
 */
@Configuration
public class EncodedSlashConfig {

    @Bean
    TomcatConnectorCustomizer encodedSolidusDecoding() {
        return connector -> connector.setEncodedSolidusHandling("decode");
    }

    @Bean
    HttpFirewall encodedSlashFirewall() {
        StrictHttpFirewall firewall = new StrictHttpFirewall();
        firewall.setAllowUrlEncodedSlash(true);
        return firewall;
    }
}
