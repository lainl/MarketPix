package com.marketpix;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Files;
import java.nio.file.Path;

@Configuration
public class WebConfiguration implements WebMvcConfigurer {

    private final SessionService sessions;
    private final Path uiDirectory;

    public WebConfiguration(SessionService sessions, @Value("${marketpix.ui-dir}") String uiDirectory) {
        this.sessions = sessions;
        this.uiDirectory = Path.of(uiDirectory.trim()).toAbsolutePath().normalize();
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SessionInterceptor(sessions))
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/health", "/api/auth/login", "/api/internal/**");
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/", "/Home.html");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        if (!Files.isDirectory(uiDirectory)) {
            return;
        }
        String location = uiDirectory.toUri().toString();
        if (!location.endsWith("/")) {
            location = location + "/";
        }
        // no-cache makes the browser check for a newer page each time (a cheap 304 when nothing changed),
        // so an updated interface shows up without a hard refresh.
        registry.addResourceHandler("/**").addResourceLocations(location).setCacheControl(CacheControl.noCache());
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns(
                        "null",
                        "http://localhost:*",
                        "http://127.0.0.1:*")
                .allowedMethods("GET", "POST", "DELETE", "OPTIONS")
                .allowedHeaders("Content-Type", "Authorization")
                .maxAge(3600);
    }
}
