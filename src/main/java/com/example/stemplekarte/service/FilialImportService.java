package com.example.stemplekarte.service;

import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.model.ShopOrt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Vorschau fuer die Sammel-Anlage von Filialen (Tarif Kette): eine Liste
 * einfuegen, das System sucht zu jeder Adresse den Ort. Gespeichert wird
 * hier NICHTS - der Mensch prueft die Treffer und schickt sie dann an
 * OrteService.sammel.
 *
 * Eine Zeile je Filiale, zwei Schreibweisen:
 *   Name, Strasse 5, 10115 Berlin        (Ort wird gesucht)
 *   Name; 52.5200; 13.4050               (Ort steht schon da)
 */
@Service
public class FilialImportService {

    /** Je Aufruf. Die Suche braucht rund eine Sekunde je Adresse. */
    public static final int MAX_ZEILEN = 40;
    /** Obergrenze fuer den eingefuegten Text insgesamt. */
    public static final int MAX_ZEICHEN = MAX_ZEILEN * 300;
    static final long PAUSE_MS = 1100;

    private final Geocoder geocoder;
    private final long pauseMs;

    @Autowired
    public FilialImportService(Geocoder geocoder) {
        this(geocoder, PAUSE_MS);
    }

    FilialImportService(Geocoder geocoder, long pauseMs) {
        this.geocoder = geocoder;
        this.pauseMs = pauseMs;
    }

    /** fehler = null: Zeile ist in Ordnung und kann gespeichert werden. */
    public record Zeile(int nr, String label, String adresse, Double latitude, Double longitude,
                        String gefunden, String fehler) {}

    public List<Zeile> vorschau(Shop shop, String text) {
        if (!OrteService.kette(shop)) throw new IllegalArgumentException(OrteService.NUR_KETTE);
        String zuViel = "Bitte höchstens " + MAX_ZEILEN + " Filialen auf einmal einfügen.";
        // Erst die Laenge, dann zerlegen: ein riesiger Text wird gar nicht
        // erst gelesen. Und beim Zaehlen sofort aufhoeren.
        if (text != null && text.length() > MAX_ZEICHEN) throw new IllegalArgumentException(zuViel);
        List<String> zeilen = new ArrayList<>();
        if (text != null) {
            for (String z : text.split("\\R")) {
                String s = z.replaceAll("\\p{Cntrl}", " ").trim();
                if (s.isEmpty()) continue;
                if (zeilen.size() >= MAX_ZEILEN) throw new IllegalArgumentException(zuViel);
                zeilen.add(s);
            }
        }
        if (zeilen.isEmpty()) throw new IllegalArgumentException("Die Liste ist leer.");
        List<Zeile> out = new ArrayList<>();
        boolean gesucht = false;
        int nr = 0;
        for (String z : zeilen) {
            nr++;
            if (z.contains(";")) {
                out.add(mitKoordinaten(nr, z));
                continue;
            }
            int komma = z.indexOf(',');
            if (komma < 0) {
                out.add(fehler(nr, z, null, "Bitte „Name, Adresse“ schreiben."));
                continue;
            }
            String label = z.substring(0, komma).trim();
            String adresse = z.substring(komma + 1).trim();
            String falsch = labelFehler(label);
            if (falsch != null) {
                out.add(fehler(nr, label, adresse, falsch));
                continue;
            }
            if (adresse.isEmpty()) {
                out.add(fehler(nr, label, null, "Die Adresse fehlt."));
                continue;
            }
            if (gesucht) pause();
            gesucht = true;
            Optional<Geocoder.Punkt> p = geocoder.suche(adresse);
            if (p.isEmpty()) {
                out.add(fehler(nr, label, adresse, "Adresse nicht gefunden. Bitte prüfen oder den Ort von Hand eintragen."));
            } else {
                out.add(new Zeile(nr, label, adresse, p.get().latitude(), p.get().longitude(),
                        p.get().anzeige(), null));
            }
        }
        return out;
    }

    private static Zeile mitKoordinaten(int nr, String z) {
        String[] t = z.split(";");
        String label = t[0].trim();
        if (t.length != 3) return fehler(nr, label, null, "Bitte „Name; Breite; Länge“ schreiben.");
        String falsch = labelFehler(label);
        if (falsch != null) return fehler(nr, label, null, falsch);
        try {
            double lat = Double.parseDouble(t[1].trim().replace(',', '.'));
            double lon = Double.parseDouble(t[2].trim().replace(',', '.'));
            if (Double.isNaN(lat) || Double.isNaN(lon) || lat < -90 || lat > 90 || lon < -180 || lon > 180) {
                return fehler(nr, label, null, "Ungültige Koordinaten");
            }
            return new Zeile(nr, label, null, lat, lon, null, null);
        } catch (NumberFormatException e) {
            return fehler(nr, label, null, "Ungültige Koordinaten");
        }
    }

    private static String labelFehler(String label) {
        if (label.isEmpty()) return "Der Name fehlt.";
        if (label.length() > ShopOrt.LABEL_MAX) {
            return "Name darf höchstens " + ShopOrt.LABEL_MAX + " Zeichen haben";
        }
        return null;
    }

    private static Zeile fehler(int nr, String label, String adresse, String text) {
        return new Zeile(nr, label, adresse, null, null, null, text);
    }

    private void pause() {
        if (pauseMs <= 0) return;
        try {
            Thread.sleep(pauseMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
