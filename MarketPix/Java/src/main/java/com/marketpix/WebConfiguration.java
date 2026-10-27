package com.marketpix;

import org.springframework.context.annotation.Configuration;
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

    public WebConfiguration(SessionService sessions) {
        this.sessions = sessions;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SessionInterceptor(sessions))
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/health", "/api/auth/login");
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/", "/Home.html");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path uiDirectory = resolveUiDirectory();
        if (!Files.isDirectory(uiDirectory)) {
            return;
        }
        String location = uiDirectory.toUri().toString();
        if (!location.endsWith("/")) {
            location = location + "/";
        }
        registry.addResourceHandler("/**").addResourceLocations(location);
    }

    private static Path resolveUiDirectory() {
        String configured = System.getenv("MARKETPIX_UI_DIR");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        Path sibling = Path.of("..", "UserInterface").toAbsolutePath().normalize();
        if (Files.isDirectory(sibling)) {
            return sibling;
        }
        return Path.of("UserInterface").toAbsolutePath().normalize();
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns(
                        "null",
                        "http://localhost:*",
                        "http://127.0.0.1:*",
                        "https://*.up.railway.app")
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("Content-Type", "Authorization")
                .maxAge(3600);
    }
}
