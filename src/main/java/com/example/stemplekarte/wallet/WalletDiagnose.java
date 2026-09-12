package com.example.stemplekarte.wallet;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Merkt sich pro Karte, wie oft und wann zuletzt ein Apple-Push rausging.
 *
 * Hintergrund: bleibt nach einem erfolgreichen Push der Pass-Abruf durch das
 * iPhone aus, sieht das im Log immer gleich aus - egal ob
 *
 *   a) das Handy gerade kein Netz hatte,
 *   b) Apple den Push wegen des Tageslimits (~3 Pass-Pushes/Tag/Karte)
 *      still verworfen hat, oder
 *   c) der Pass laengst geloescht wurde.
 *
 * Mit den zwei Zahlen hier lassen sich die Faelle auseinanderhalten:
 *
 *   - Push-Zaehler des Tages: ab dem 4. Push ist Fall (b) wahrscheinlich.
 *   - Abstand zwischen Push und Pass-Abruf: kommt der Abruf verspaetet, war
 *     das Handy offline (Fall a) und APNs hat nachgeliefert - kommt er nie,
 *     bleiben (b) und (c).
 *
 * In-Memory, eine Instanz, ueberlebt keinen Neustart. Reicht fuer Diagnose;
 * bewusst keine DB-Tabelle dafuer.
 */
@Service
public class WalletDiagnose {

    private record Eintrag(LocalDate tag, int pushesHeute, Instant letzterPush) {}

    private static final int MAX_KARTEN = 5000;

    private final Map<String, Eintrag> proKarte = new ConcurrentHashMap<>();

    /** Zaehlt einen rausgegangenen Push und liefert die Anzahl fuer HEUTE. */
    public int pushGezaehlt(String serialNumber) {
        if (proKarte.size() >= MAX_KARTEN) proKarte.clear();
        LocalDate heute = LocalDate.now(ZoneOffset.UTC);
        Eintrag neu = proKarte.compute(serialNumber, (k, alt) -> {
            if (alt == null || !alt.tag().equals(heute)) {
                return new Eintrag(heute, 1, Instant.now());
            }
            return new Eintrag(heute, alt.pushesHeute() + 1, Instant.now());
        });
        return neu.pushesHeute();
    }

    /** Wie lange ist der letzte Push fuer diese Karte her? Leer = kein Push bekannt. */
    public Optional<Duration> seitLetztemPush(String serialNumber) {
        Eintrag e = proKarte.get(serialNumber);
        if (e == null || e.letzterPush() == null) return Optional.empty();
        return Optional.of(Duration.between(e.letzterPush(), Instant.now()));
    }

    /** Push-Anzahl des heutigen Tages, ohne zu zaehlen. */
    public int pushesHeute(String serialNumber) {
        Eintrag e = proKarte.get(serialNumber);
        if (e == null || !e.tag().equals(LocalDate.now(ZoneOffset.UTC))) return 0;
        return e.pushesHeute();
    }
}
