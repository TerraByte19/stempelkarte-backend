package com.example.stemplekarte.wallet;

import com.example.stemplekarte.service.GeoPruefung;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Welche Filialen einer Kette stehen im Apple-Pass EINES Kunden? Apple nimmt
 * hoechstens zehn Orte, eine Kette hat mehr. Die Karte lernt deshalb aus
 * den Besuchen des Kunden:
 *
 * - Noch kein Stempel: keine Orte. (Beim gemeinsamen Anmelde-QR weiss
 *   niemand, in welcher Filiale der Kunde steht.)
 * - Besuchte Filialen zuerst, die meistbesuchte vorn; bei Gleichstand die
 *   zuletzt besuchte.
 * - Freie Plaetze fuellen die Filialen, die der meistbesuchten am naechsten
 *   liegen (Luftlinie).
 *
 * Reine Rechnung ohne Datenbank, damit sie sich einzeln pruefen laesst.
 */
final class KettenOrte {

    private KettenOrte() {}

    /** Eine waehlbare Filiale. id HAUPT fuer den Hauptstandort. */
    record Filiale(String id, double latitude, double longitude) {}

    /** Besuche des Kunden in einer Filiale. id wie oben. */
    record Besuch(String id, long anzahl, Instant zuletzt) {}

    /**
     * @param waehlbar nur Filialen, die zaehlen (eingeschaltet, vorhanden)
     * @param besuche  alle Besuche des Kunden, auch in Filialen, die es nicht mehr gibt
     */
    static List<Filiale> waehle(List<Filiale> waehlbar, List<Besuch> besuche, int max) {
        if (max <= 0 || waehlbar.isEmpty() || besuche.isEmpty()) return List.of();
        Map<String, Filiale> jeId = new LinkedHashMap<>();
        for (Filiale f : waehlbar) jeId.putIfAbsent(f.id(), f);

        List<Besuch> gueltig = besuche.stream()
                .filter(b -> b.anzahl() > 0 && jeId.containsKey(b.id()))
                .sorted(Comparator.comparingLong(Besuch::anzahl).reversed()
                        .thenComparing(Besuch::zuletzt, Comparator.nullsFirst(Comparator.<Instant>naturalOrder()).reversed()))
                .toList();
        // Nur Besuche in Filialen, die nicht mehr zaehlen: nichts zu lernen.
        if (gueltig.isEmpty()) return List.of();

        List<Filiale> wahl = new ArrayList<>();
        for (Besuch b : gueltig) {
            if (wahl.size() >= max) return wahl;
            wahl.add(jeId.get(b.id()));
        }
        Filiale stamm = wahl.get(0);
        List<Filiale> rest = jeId.values().stream()
                .filter(f -> !wahl.contains(f))
                .sorted(Comparator.comparingLong(f -> GeoPruefung.distanzMeter(
                        stamm.latitude(), stamm.longitude(), f.latitude(), f.longitude())))
                .toList();
        for (Filiale f : rest) {
            if (wahl.size() >= max) break;
            wahl.add(f);
        }
        return wahl;
    }
}
