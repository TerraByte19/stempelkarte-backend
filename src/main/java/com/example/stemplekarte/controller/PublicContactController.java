package com.example.stemplekarte.controller;

import com.example.stemplekarte.service.EmailService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Kontaktformular der Landing-Page. Offener Endpunkt, kein Login.
 *
 * WICHTIG: /api/public/** muss in der SecurityConfig mit permitAll()
 * freigegeben sein — die Kette endet auf anyRequest().denyAll().
 */
@Tag(name = "Kontakt", description = "Kontaktanfragen von der Landing-Page")
@RestController
@RequestMapping("/api/public")
public class PublicContactController {

    private static final Logger log = LoggerFactory.getLogger(PublicContactController.class);

    private static final Duration COOLDOWN = Duration.ofMinutes(2);
    private static final int MAX_MESSAGE = 4000;
    private static final int MAX_FIELD = 200;
    /** Deckel gegen unbegrenztes Wachsen der Sperrliste bei verteilten Anfragen. */
    private static final int MAX_TRACKED = 5000;

    private final EmailService emailService;
    private final Map<String, Instant> lastRequest = new ConcurrentHashMap<>();

    public PublicContactController(EmailService emailService) {
        this.emailService = emailService;
    }

    public record ContactRequest(String name, String shop, String email,
                                 String message, String website) {}

    @PostMapping("/contact")
    public ResponseEntity<Void> contact(@RequestBody ContactRequest req,
                                        @RequestHeader(value = "X-Forwarded-For", required = false) String forwarded) {
        // Honigtopf: nur Bots fuellen dieses Feld. Antwort ist 200, damit der
        // Bot nicht merkt, dass er erkannt wurde, und es nicht anders versucht.
        if (req.website() != null && !req.website().isBlank()) {
            log.info("Kontaktanfrage verworfen (Honigtopf)");
            return ResponseEntity.ok().build();
        }

        if (isBlank(req.name()) || isBlank(req.shop())
                || isBlank(req.email()) || isBlank(req.message())) {
            return ResponseEntity.badRequest().build();
        }
        if (!looksLikeEmail(req.email())
                || req.message().length() > MAX_MESSAGE
                || req.name().length() > MAX_FIELD
                || req.shop().length() > MAX_FIELD
                || req.email().length() > MAX_FIELD) {
            return ResponseEntity.badRequest().build();
        }

        if (!reserveSlot(clientKey(forwarded))) {
            return ResponseEntity.status(429).build();
        }

        emailService.sendContactRequestMail(req.name(), req.shop(), req.email(), req.message());
        return ResponseEntity.ok().build();
    }

    /**
     * Eine Anfrage alle zwei Minuten pro Absender. Der Zaehler liegt im
     * Arbeitsspeicher — nach einem Neustart ist er leer, was hier unkritisch
     * ist. Alte Eintraege werden beim Ueberlaufen weggeraeumt.
     */
    private synchronized boolean reserveSlot(String key) {
        Instant now = Instant.now();
        Instant previous = lastRequest.get(key);
        if (previous != null && Duration.between(previous, now).compareTo(COOLDOWN) < 0) {
            return false;
        }
        if (lastRequest.size() >= MAX_TRACKED) {
            Iterator<Map.Entry<String, Instant>> it = lastRequest.entrySet().iterator();
            while (it.hasNext()) {
                if (Duration.between(it.next().getValue(), now).compareTo(COOLDOWN) >= 0) it.remove();
            }
        }
        lastRequest.put(key, now);
        return true;
    }

    private static String clientKey(String forwarded) {
        if (forwarded == null || forwarded.isBlank()) return "unbekannt";
        return forwarded.split(",")[0].trim();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static boolean looksLikeEmail(String s) {
        int at = s.indexOf('@');
        return at > 0 && at < s.length() - 1 && s.indexOf('.', at) > at + 1 && !s.contains(" ");
    }
}
