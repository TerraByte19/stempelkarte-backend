package com.example.stemplekarte.security;

import com.example.stemplekarte.repository.ShopRepository;
import com.example.stemplekarte.repository.StaffTokenRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
public class SecurityConfig {

    private final JwtService jwtService;
    private final ShopRepository shopRepo;
    private final StaffTokenRepository staffTokenRepo;

    // Komma-getrennte Liste von erlaubten Frontend-URLs
    // z.B. FRONTEND_URL=https://stempelkarte-frontend.onrender.com
    @Value("${FRONTEND_URL:https://stempelkarte-frontend.vercel.app}")
    private String frontendUrl;

    public SecurityConfig(JwtService jwtService, ShopRepository shopRepo,
                          StaffTokenRepository staffTokenRepo) {
        this.jwtService = jwtService;
        this.shopRepo = shopRepo;
        this.staffTokenRepo = staffTokenRepo;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();

        // Feste, bekannte Origins (Komma-getrennte ENV-Variable + lokale Dev-URLs)
        config.setAllowedOrigins(List.of(
                frontendUrl,
                "http://localhost:5173",
                "http://localhost:5174",
                "http://192.168.178.163:5173",
                "http://192.168.178.163:5174"
        ));

        // Pattern-basierte Origins: deckt automatisch alle Subdomains von
        // stampit-app.de (z.B. www., app., zukünftige Subdomains) sowie
        // alle Vercel-Deployments (Produktion + Preview-Builds) ab,
        // ohne dass bei neuen Domains/Deployments der Code geändert werden muss.
        config.setAllowedOriginPatterns(List.of(
                "https://*.stampit-app.de",
                "https://stampit-app.de",
                "https://*.vercel.app"
        ));

        config.addAllowedHeader("*");
        config.addAllowedMethod("*");
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health").permitAll()
                        .requestMatchers("/api/auth/login", "/api/auth/register").permitAll()
                        .requestMatchers("/api/shop/card-logos/**").permitAll()
                        .requestMatchers("/api/shop/card-heroes/**").permitAll()
                        .requestMatchers("/api/customer", "/api/customer/**").permitAll()
                        .requestMatchers("/api/scan").permitAll()
                        .requestMatchers("/api/shop/logos/**").permitAll()
                        .requestMatchers("/api/shop/heroes/**").permitAll()
                        .requestMatchers("/api/shop/stamp-icons/**").permitAll()
                        .requestMatchers("/wallet/**").permitAll()
                        .requestMatchers("/karte/**", "/karte-neu/**", "/logos/**").permitAll()
                        .requestMatchers("/mail/**").permitAll()
                        .requestMatchers("/api/admin/login").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/shop/**").hasRole("SHOP")
                        .requestMatchers(
                                "/swagger-ui.html", "/swagger-ui/**",
                                "/v3/api-docs", "/v3/api-docs/**"
                        ).permitAll()
                        .anyRequest().denyAll()
                )
                .headers(h -> h.frameOptions(f -> f.disable()))
                .addFilterBefore(new JwtAuthFilter(jwtService, shopRepo),
                        UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(new StaffTokenFilter(staffTokenRepo),
                        JwtAuthFilter.class);

        return http.build();
    }
}