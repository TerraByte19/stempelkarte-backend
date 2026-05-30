package com.example.stemplekarte.security;

import com.example.stemplekarte.repository.ShopRepository;
import com.example.stemplekarte.repository.StaffTokenRepository;
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

@Configuration
public class SecurityConfig {

    private final JwtService jwtService;
    private final ShopRepository shopRepo;
    private final StaffTokenRepository staffTokenRepo;

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
        config.addAllowedOrigin("http://localhost:5173");
        config.addAllowedOrigin("http://localhost:5174");
        config.addAllowedOrigin("http://192.168.178.163:5173");
        config.addAllowedOrigin("http://192.168.178.163:5174");
        config.addAllowedOrigin("https://stempelkarte-frontend.onrender.com");
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
                        // Öffentliche Endpoints
                        .requestMatchers("/actuator/health").permitAll()
                        .requestMatchers("/api/auth/login", "/api/auth/register").permitAll()
                        .requestMatchers("/api/customer", "/api/customer/**").permitAll()
                        .requestMatchers("/api/scan").permitAll()
                        .requestMatchers("/api/shop/logos/**").permitAll()
                        .requestMatchers("/wallet/**").permitAll()
                        .requestMatchers("/karte/**", "/karte-neu/**", "/logos/**").permitAll()

                        // Admin: nur mit Admin-Login (wird durch AdminAuthFilter geprüft)
                        .requestMatchers("/api/admin/login").permitAll()
                        .requestMatchers("/api/admin/**").authenticated()

                        // Shop: nur eingeloggte Shops
                        .requestMatchers("/api/shop/**").hasRole("SHOP")

                        // Swagger: nur lokal (dev Profil)
                        .requestMatchers(
                                "/swagger-ui.html", "/swagger-ui/**",
                                "/v3/api-docs", "/v3/api-docs/**"
                        ).permitAll()

                        // Alles andere: verboten
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