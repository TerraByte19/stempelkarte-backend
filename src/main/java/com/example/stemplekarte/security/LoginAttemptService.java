package com.example.stemplekarte.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Schützt den Login vor Brute-Force-Angriffen (automatisiertes Durchprobieren
 * von Passwörtern). Zählt fehlgeschlagene Login-Versuche pro IP-Adresse und
 * sperrt eine IP nach zu vielen Fehlversuchen für eine bestimmte Zeit.
 *
 * - Nach MAX_ATTEMPTS Fehlversuchen → IP für BLOCK_MS gesperrt.
 * - Ein erfolgreicher Login setzt den Zähler für die IP zurück.
 * - Der Zähler liegt im Arbeitsspeicher; bei Server-Neustart (Render-Deploy)
 *   wird er zurückgesetzt (unkritisch).
 */
@Component
public class LoginAttemptService {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptService.class);

    private static final int MAX_ATTEMPTS = 10;          // erlaubte Fehlversuche
    private static final long BLOCK_MS = 10 * 60_000L;   // Sperrdauer: 10 Minuten

    private record Attempts(int count, long firstAttempt, long blockedUntil) {}

    private final Map<String, Attempts> byIp = new ConcurrentHashMap<>();

    /**
     * Prüft, ob eine IP aktuell gesperrt ist. Sollte VOR dem Login-Versuch
     * aufgerufen werden.
     */
    public boolean isBlocked(String ip) {
        Attempts a = byIp.get(ip);
        if (a == null) return false;
        if (a.blockedUntil() > System.currentTimeMillis()) {
            return true;
        }
        // Sperre abgelaufen → Eintrag aufräumen
        if (a.blockedUntil() > 0) {
            byIp.remove(ip);
        }
        return false;
    }

    /** Nach einem fehlgeschlagenen Login aufrufen. */
    public synchronized void loginFailed(String ip) {
        long now = System.currentTimeMillis();
        Attempts a = byIp.get(ip);

        if (a == null || a.blockedUntil() > 0) {
            // Neuer Zähler (oder nach abgelaufener Sperre neu starten)
            byIp.put(ip, new Attempts(1, now, 0));
            return;
        }

        int newCount = a.count() + 1;
        if (newCount >= MAX_ATTEMPTS) {
            // Limit erreicht → IP sperren
            byIp.put(ip, new Attempts(newCount, a.firstAttempt(), now + BLOCK_MS));
            log.warn("Login-Brute-Force-Schutz: IP {} nach {} Fehlversuchen für {} Min gesperrt",
                    ip, newCount, BLOCK_MS / 60_000);
        } else {
            byIp.put(ip, new Attempts(newCount, a.firstAttempt(), 0));
        }
    }

    /** Nach einem erfolgreichen Login aufrufen — setzt den Zähler zurück. */
    public void loginSucceeded(String ip) {
        byIp.remove(ip);
    }
}