package com.example.stemplekarte.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hoechstens n Versuche je Schluessel (meist die IP) in einem Zeitfenster.
 * Fuer offene Endpunkte, hinter denen eine teure Anfrage steckt (Google,
 * Apple). Im Speicher, je Instanz - reicht fuer einen Render-Dienst.
 */
public final class Drossel {

    public static final String TEXT = "Zu viele Versuche. Bitte in ein paar Minuten noch einmal.";

    private final int max;
    private final Duration fenster;
    private final Map<String, Deque<Instant>> versuche = new ConcurrentHashMap<>();

    public Drossel(int max, Duration fenster) {
        this.max = max;
        this.fenster = fenster;
    }

    /** Wirft IllegalArgumentException (400) bei zu vielen Versuchen. */
    public void pruefe(String key, Instant jetzt) {
        String k = (key == null || key.isBlank()) ? "?" : key;
        Deque<Instant> zeiten = versuche.computeIfAbsent(k, x -> new ArrayDeque<>());
        synchronized (zeiten) {
            Instant grenze = jetzt.minus(fenster);
            while (!zeiten.isEmpty() && zeiten.peekFirst().isBefore(grenze)) zeiten.pollFirst();
            if (zeiten.size() >= max) throw new IllegalArgumentException(TEXT);
            zeiten.addLast(jetzt);
        }
        // Alte Schluessel nicht ewig behalten.
        if (versuche.size() > 5000) versuche.clear();
    }
}
