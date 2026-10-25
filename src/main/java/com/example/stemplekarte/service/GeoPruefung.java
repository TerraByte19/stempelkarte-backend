package com.example.stemplekarte.service;

import com.example.stemplekarte.model.GeoVorfall;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.repository.GeoVorfallRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Prueft, ob ein Scan dort passiert ist, wo der Laden steht.
 *
 * <p>Der Ort des Ladens liegt schon in der Datenbank - er versorgt die
 * Ortsfelder im Wallet-Pass, damit die Karte auf dem Sperrbildschirm
 * auftaucht. Dieselben Koordinaten dienen hier als Mittelpunkt.
 *
 * <p>Seit 25.10.2026 ein harter Riegel: ohne Ort oder ausserhalb des
 * Radius wird gar nicht erst gestempelt. Vorher lief alles durch und wurde
 * nur gemeldet - wer die Ortsabfrage im Browser wegklickte, stempelte
 * trotzdem, und genau so wurde es umgangen. Der Radius ist grosszuegig,
 * weil ein Tablet ohne GPS ueber das WLAN schaetzt.
 */
@Service
public class GeoPruefung {

    private static final Logger log = LoggerFactory.getLogger(GeoPruefung.class);

    /**
     * Bis hierhin gilt ein Scan als "im Laden", solange der Inhaber nichts
     * anderes waehlt. Derselbe Radius wie die Sperrbildschirm-Meldung im
     * Wallet-Pass (ApplePassService).
     */
    public static final long RADIUS_METER = 150;

    /** Zur Auswahl im Profil. Mehr nicht: groesser waere kein "im Laden" mehr. */
    public static final java.util.List<Integer> ERLAUBTE_RADIEN = java.util.List.of(150, 200, 250);

    /** Radius des Ladens. Fehlt er oder ist er keiner der erlaubten, gilt der Standard. */
    public static long radiusFuer(Shop shop) {
        Integer r = shop.getScanRadiusMeter();
        return (r != null && ERLAUBTE_RADIEN.contains(r)) ? r : RADIUS_METER;
    }

    private static final double ERDRADIUS_METER = 6_371_000;

    private final GeoVorfallRepository vorfaelle;

    public GeoPruefung(GeoVorfallRepository vorfaelle) {
        this.vorfaelle = vorfaelle;
    }

    /**
     * Luftlinie zwischen zwei Punkten in Metern (Haversine).
     *
     * <p>Ohne Hoehe und ohne Erdabplattung - auf den paar hundert Metern,
     * um die es hier geht, liegt der Fehler im Zentimeterbereich und damit
     * weit unter dem, was ein Handy-GPS ohnehin schwankt.
     */
    public static long distanzMeter(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return Math.round(ERDRADIUS_METER * c);
    }

    /**
     * Entscheidet, ob ein Scan auffaellt. Null heisst: alles in Ordnung,
     * nichts zu protokollieren.
     *
     * <p>Ohne hinterlegten Ladenort ist die Pruefung aus - sonst waere jeder
     * Scan jedes Ladens ohne Koordinaten ein Vorfall.
     */
    public static GeoVorfall.Art pruefe(Double ladenLat, Double ladenLon,
                                        Double scanLat, Double scanLon, long radiusMeter) {
        if (ladenLat == null || ladenLon == null) return null;
        if (scanLat == null || scanLon == null) return GeoVorfall.Art.OHNE_ORT;
        long distanz = distanzMeter(ladenLat, ladenLon, scanLat, scanLon);
        return distanz > radiusMeter ? GeoVorfall.Art.WEIT_WEG : null;
    }

    /**
     * Entscheidet vor dem Stempel, ob der Scan durch darf. Null heisst ja,
     * sonst kommt der Text fuer das Personal zurueck und der Versuch steht
     * als Vorfall in der Datenbank.
     */
    public String sperre(Shop shop, String staffLabel, Double scanLat, Double scanLon) {
        GeoVorfall.Art art = pruefe(shop.getLatitude(), shop.getLongitude(),
                scanLat, scanLon, radiusFuer(shop));
        if (art == null) return null;

        Long distanz = (art == GeoVorfall.Art.WEIT_WEG)
                ? distanzMeter(shop.getLatitude(), shop.getLongitude(), scanLat, scanLon)
                : null;

        try {
            vorfaelle.save(GeoVorfall.create(shop.getId(), null, null, staffLabel,
                    art, scanLat, scanLon, distanz));
        } catch (Exception e) {
            // Gesperrt wird trotzdem. Das Protokoll ist nur die Spur dazu.
            log.error("[GEO] VORFALL-FEHLER laden={}", shop.getId(), e);
        }
        log.warn("[GEO] GESPERRT laden={} art={} distanzMeter={} kasse={}",
                shop.getId(), art, distanz, staffLabel);
        return sperrText(art, distanz);
    }

    public static String sperrText(GeoVorfall.Art art, Long distanz) {
        if (art == GeoVorfall.Art.WEIT_WEG) {
            return "Scan abgelehnt: Das Gerät ist rund " + distanz + " m vom Laden entfernt. "
                    + "Gescannt werden kann nur im Laden.";
        }
        return "Scan abgelehnt: Das Gerät hat keinen Standort geschickt. "
                + "Standort für diese Seite erlauben und erneut scannen.";
    }
}
