package com.example.stemplekarte.security;

import com.example.stemplekarte.model.ApiKey;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.repository.ApiKeyRepository;
import com.example.stemplekarte.service.ApiKeyService;
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

/**
 * Externe API: Header X-Api-Key. Der Schluessel wird gehasht nachgeschlagen,
 * der Laden muss aktiv und vom Admin fuer die API freigeschaltet sein.
 * Ergebnis: ROLE_API mit ApiPrincipal(shop, label).
 */
public class ApiKeyFilter extends OncePerRequestFilter {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ApiKeyFilter.class);
    public static final String HEADER = "X-Api-Key";
    static final Duration BENUTZT_ABSTAND = Duration.ofMinutes(10);

    private final ApiKeyRepository repo;

    public ApiKeyFilter(ApiKeyRepository repo) {
        this.repo = repo;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        String header = req.getHeader(HEADER);
        if (header != null && !header.isBlank()) {
            repo.findAktivByHash(ApiKeyService.hash(header.trim())).ifPresent(key -> {
                Shop shop = key.getShop();
                if (shop == null || !shop.isActive() || !shop.isApiFreigeschaltet()) return;
                var auth = new UsernamePasswordAuthenticationToken(
                        new ApiPrincipal(shop, key.getLabel()), null,
                        List.of(new SimpleGrantedAuthority("ROLE_API")));
                SecurityContextHolder.getContext().setAuthentication(auth);
                merkeBenutzt(key);
            });
        }
        chain.doFilter(req, resp);
    }

    /** Gedrosselt und nie blockierend - ein Datenbankfehler haelt keine Anfrage auf. */
    private void merkeBenutzt(ApiKey key) {
        try {
            Instant jetzt = Instant.now();
            Instant zuletzt = key.getLastUsedAt();
            if (zuletzt == null || zuletzt.isBefore(jetzt.minus(BENUTZT_ABSTAND))) {
                key.benutzt(jetzt);
                repo.save(key);
            }
        } catch (Exception e) {
            log.debug("[API] zuletzt benutzt nicht gespeichert: {}", e.getMessage());
        }
    }

    public record ApiPrincipal(Shop shop, String label) {}
}
