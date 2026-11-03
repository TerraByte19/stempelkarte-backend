package com.example.stemplekarte.security;

import com.example.stemplekarte.model.StaffToken;
import com.example.stemplekarte.repository.StaffTokenRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

public class StaffTokenFilter extends OncePerRequestFilter {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(StaffTokenFilter.class);
    /** "Zuletzt benutzt" hoechstens so oft schreiben - ein Scan pro Minute soll keinen Schreibzugriff pro Scan kosten. */
    static final Duration BENUTZT_ABSTAND = Duration.ofMinutes(10);

    private final StaffTokenRepository repo;

    public StaffTokenFilter(StaffTokenRepository repo) {
        this.repo = repo;
    }

    /**
     * "Zuletzt benutzt" fuer die Team-Uebersicht. Nur bei POST (stempeln,
     * buchen), gedrosselt, und nie so, dass ein Datenbankfehler den Scan
     * aufhaelt - deshalb alles im catch.
     */
    private void merkeBenutzt(StaffToken staff) {
        try {
            Instant jetzt = Instant.now();
            Instant zuletzt = staff.getLastUsedAt();
            if (zuletzt == null || zuletzt.isBefore(jetzt.minus(BENUTZT_ABSTAND))) {
                staff.benutzt(jetzt);
                repo.save(staff);
            }
        } catch (Exception e) {
            log.debug("[TEAM] zuletzt benutzt nicht gespeichert: {}", e.getMessage());
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                    HttpServletResponse resp,
                                    FilterChain chain) throws ServletException, IOException {
        String header = req.getHeader("X-Staff-Token");

        if (header != null && !header.isBlank()) {
            // MIT Laden laden: gleich danach ist die Sitzung zu, und was
            // hier in den SecurityContext geht, wird spaeter ohne
            // Transaktion gelesen (Standort-Pflicht, Geo-Pruefung).
            repo.findWithShopByToken(header.trim()).ifPresent(staff -> {
                var auth = new UsernamePasswordAuthenticationToken(
                        new StaffPrincipal(staff),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_STAFF"))
                );
                SecurityContextHolder.getContext().setAuthentication(auth);
                if ("POST".equalsIgnoreCase(req.getMethod())) merkeBenutzt(staff);
            });
        }

        chain.doFilter(req, resp);
    }

    public record StaffPrincipal(StaffToken staff) {}
}