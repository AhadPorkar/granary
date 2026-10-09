package dev.granary.server.repository;

import dev.granary.core.format.FormatRegistry;
import dev.granary.core.repository.RepositoryService;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers {@link RootRouteFilter} right after the Spring Security filter chain. */
@Configuration
public class RootRouteConfig {

    @Bean
    FilterRegistrationBean<RootRouteFilter> rootRouteFilter(FormatRegistry formats, RepositoryService repositories) {
        FilterRegistrationBean<RootRouteFilter> bean = new FilterRegistrationBean<>(new RootRouteFilter(formats, repositories));
        bean.setOrder(SecurityFilterProperties.DEFAULT_FILTER_ORDER + 10);
        bean.setDispatcherTypes(DispatcherType.REQUEST);
        bean.addUrlPatterns("/*");
        return bean;
    }
}
