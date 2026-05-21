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
        config.addAllowedOrigin("http://192.168.178.163:5173");
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
                        .requestMatchers(
                                "/swagger-ui.html", "/swagger-ui/**",
                                "/v3/api-docs", "/v3/api-docs/**",
                                "/swagger-resources/**", "/webjars/**"
                        ).permitAll()
                        .requestMatchers("/api/customer", "/api/customer/**").permitAll()
                        .requestMatchers("/wallet/**").permitAll()
                        .requestMatchers("/karte/**", "/karte-neu/**", "/logos/**").permitAll()
                        .requestMatchers("/h2/**", "/actuator/health").permitAll()
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers("/api/scan").permitAll()
                        .requestMatchers("/api/shop/logos/**").permitAll()
                        .requestMatchers("/api/shop/logo").permitAll()
                        .requestMatchers("/api/admin/login").permitAll()
                        .requestMatchers("/api/admin/**").permitAll()
                        .requestMatchers("/api/shop/**").hasRole("SHOP")
                        .anyRequest().permitAll()
                )
                .headers(h -> h.frameOptions(f -> f.disable()))
                .addFilterBefore(new JwtAuthFilter(jwtService, shopRepo),
                        UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(new StaffTokenFilter(staffTokenRepo),
                        JwtAuthFilter.class);

        return http.build();
    }
}