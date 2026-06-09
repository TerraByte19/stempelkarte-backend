package com.example.stemplekarte.config;

import org.springframework.context.annotation.Configuration;

/**
 * CORS wird vollständig in SecurityConfig.corsConfigurationSource() konfiguriert.
 * Diese Klasse bleibt leer — zwei parallele CORS-Konfigurationen (WebMvcConfigurer
 * + Spring Security) widersprechen sich und führen zu unvorhersehbarem Verhalten.
 */
@Configuration
public class WebConfig {
    // Intentionally empty — see SecurityConfig
}