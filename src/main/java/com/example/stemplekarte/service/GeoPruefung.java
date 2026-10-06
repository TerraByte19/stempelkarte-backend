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
 * <p>Nichts wird abgelehnt. Der Ort kommt aus dem Browser des Personals und
 * ist dort nur so gut wie das Geraet: ein Tablet ohne GPS schaetzt ueber das
 * WLAN. Ein harter Riegel wuerde Stempel verhindern, die voellig in Ordnung
 * sind - und davon haette der Laden mehr Aerger als vom Missbrauch.
 */
@Service
public class GeoPruefung {

    private static final Logger log = LoggerFactory.getLogger(GeoPruefung.class);

    /** Bis hierhin gilt ein Scan als "im Laden". */
    public static final long RADIUS_METER = 500;

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
     * Protokolliert einen auffaelligen Scan. Laeuft nach dem Stempel: ein
     * Fehler hier darf eine Buchung nicht nachtraeglich kaputtmachen.
     */
    public void protokolliere(Shop shop, String cardId, String customerId, String staffLabel,
                              Double scanLat, Double scanLon) {
        try {
            GeoVorfall.Art art = pruefe(shop.getLatitude(), shop.getLongitude(),
                    scanLat, scanLon, RADIUS_METER);
            if (art == null) return;

            Long distanz = (art == GeoVorfall.Art.WEIT_WEG)
                    ? distanzMeter(shop.getLatitude(), shop.getLongitude(), scanLat, scanLon)
                    : null;

            vorfaelle.save(GeoVorfall.create(shop.getId(), cardId, customerId, staffLabel,
                    art, scanLat, scanLon, distanz));

            log.warn("[GEO] VORFALL laden={} art={} distanzMeter={} kasse={}",
                    shop.getId(), art, distanz, staffLabel);
        } catch (Exception e) {
            // Der Stempel ist laengst gesetzt. Ein Protokollfehler darf dem
            // Kunden an der Kasse nicht als Fehlermeldung begegnen.
            log.error("[GEO] VORFALL-FEHLER laden={}", shop.getId(), e);
        }
    }
}
