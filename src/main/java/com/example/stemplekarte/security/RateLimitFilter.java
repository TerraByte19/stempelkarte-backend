package com.example.stemplekarte.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Einfacher In-Memory Rate-Limiter pro IP-Adresse.
 *
 * Schützt öffentliche Endpoints davor, dass jemand sie automatisiert
 * "überrennt" — z.B. massenhaft Registrierungen auslöst, um das
 * Mail-Kontingent zu verbrennen oder die Domain-Reputation zu schädigen.
 *
 * Funktionsweise: pro IP wird gezählt, wie viele Anfragen in einem
 * Zeitfenster (1 Minute) kommen. Wird das Limit überschritten, gibt es
 * für den Rest des Fensters HTTP 429 (Too Many Requests). Echte Nutzer
 * merken davon nichts — die Limits sind großzügig genug für normale Nutzung.
 *
 * Hinweis: Der Zähler liegt im Arbeitsspeicher. Bei einem Neustart des
 * Servers (z.B. Render-Deploy) wird er zurückgesetzt — das ist unkritisch.
 * Für einen einzelnen Server (wie hier) ist das völlig ausreichend.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    // Zeitfenster in Millisekunden (1 Minute)
    private static final long WINDOW_MS = 60_000L;

    // Limits pro Minute je nach Endpoint-Typ
    private static final int LIMIT_MAIL = 3;    // Mail-auslösende Endpoints (streng)
    private static final int LIMIT_SCAN = 70;   // Scanner (Mitarbeiter scannen schnell)
    private static final int LIMIT_DEFAULT = 60; // sonstige öffentliche Endpoints

    // Ein Zähler-Eintrag pro IP+Kategorie
    private record Counter(AtomicInteger count, long windowStart) {}

    private final Map<String, Counter> buckets = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();

        // Nur öffentliche Endpoints begrenzen; alles andere (eingeloggte
        // Shop-/Admin-Bereiche) ist bereits durch Auth geschützt.
        Integer limit = limitFor(path);
        if (limit == null) {
            filterChain.doFilter(request, response);
            return;
        }

        String ip = clientIp(request);
        String key = categoryFor(path) + "|" + ip;

        if (isOverLimit(key, limit)) {
            log.warn("Rate-Limit überschritten: IP={} Pfad={}", ip, path);
            response.setStatus(429); // Too Many Requests
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                    "{\"error\":\"Zu viele Anfragen. Bitte versuche es in einer Minute erneut.\"}");
            return;
        }

        filterChain.doFilter(request, response);
    }

    /** Bestimmt das Limit für einen Pfad, oder null wenn nicht begrenzt. */
    private Integer limitFor(String path) {
        if (path.startsWith("/mail/") || path.startsWith("/karte-neu")) return LIMIT_MAIL;
        if (path.startsWith("/api/scan")) return LIMIT_SCAN;
        if (path.startsWith("/karte/") || path.startsWith("/wallet/")
                || path.startsWith("/api/customer") || path.startsWith("/logos/")
                || path.equals("/api/auth/login") || path.equals("/api/admin/login")
                || path.equals("/api/auth/register")) return LIMIT_DEFAULT;
        return null;
    }

    /** Kategorie für den Bucket-Key (damit Mail/Scan/Default getrennt zählen). */
    private String categoryFor(String path) {
        if (path.startsWith("/mail/") || path.startsWith("/karte-neu")) return "mail";
        if (path.startsWith("/api/scan")) return "scan";
        return "default";
    }

    /** Zählt die Anfrage und prüft, ob das Limit im aktuellen Fenster überschritten ist. */
    private boolean isOverLimit(String key, int limit) {
        long now = System.currentTimeMillis();
        Counter counter = buckets.compute(key, (k, existing) -> {
            if (existing == null || now - existing.windowStart() >= WINDOW_MS) {
                // Neues Zeitfenster starten
                return new Counter(new AtomicInteger(0), now);
            }
            return existing;
        });
        int current = counter.count().incrementAndGet();
        return current > limit;
    }

    /**
     * Ermittelt die echte Client-IP. Hinter Render/Proxies steht die echte
     * IP im X-Forwarded-For Header (erste Adresse der Liste).
     */
    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr();
    }
}