package com.example.stemplekarte.service;

import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.model.ShopOrt;
import com.example.stemplekarte.model.Tarif;
import com.example.stemplekarte.repository.ShopOrtRepository;
import com.example.stemplekarte.repository.ShopRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * Weitere Orte eines Ladens (ShopOrt). Eigener Dienst statt ShopService,
 * damit dessen Konstruktor und die Tests darauf unangetastet bleiben.
 *
 * Zwei Regeln nebeneinander:
 * - Basic/Pro: hoechstens neun weitere Orte, Anzahl vom Admin (Shop.maxOrte).
 *   Es zaehlen die ersten "erlaubt" in Anlagereihenfolge.
 * - Kette: beliebig viele (Notbremse KETTE_MAX). Es zaehlen die Orte, die
 *   das Hauptkonto eingeschaltet hat (ShopOrt.aktiv).
 * Welche Orte gelten, steht an EINER Stelle: {@link #geltende}.
 */
@Service
public class OrteService {

    /** Apple erlaubt zehn Orte pro Pass, einer davon ist der Hauptort. */
    public static final int MAX_WEITERE = 9;
    /** Notbremse fuer Ketten, keine Tarifgrenze. */
    public static final int KETTE_MAX = 500;
    static final String NUR_KETTE = "Das gibt es nur im Tarif Kette.";

    private final ShopOrtRepository ortRepo;
    private final ShopRepository shopRepo;
    // Zugaenge, die an einem geloeschten Ort haengen, zurueck auf den
    // Hauptstandort setzen. Null in Tests, die den Dienst von Hand bauen.
    private final com.example.stemplekarte.repository.StaffTokenRepository staffTokenRepo;

    public OrteService(ShopOrtRepository ortRepo, ShopRepository shopRepo) {
        this(ortRepo, shopRepo, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public OrteService(ShopOrtRepository ortRepo, ShopRepository shopRepo,
                       com.example.stemplekarte.repository.StaffTokenRepository staffTokenRepo) {
        this.ortRepo = ortRepo;
        this.shopRepo = shopRepo;
        this.staffTokenRepo = staffTokenRepo;
    }

    public List<ShopOrt> liste(Shop shop) {
        return ortRepo.findByShopOrderByCreatedAtAsc(shop);
    }

    public static boolean kette(Shop shop) {
        return shop.getTarif() == Tarif.KETTE;
    }

    /**
     * Wie viele weitere Orte der Laden anlegen darf. Basic: keine. Pro: vom
     * Admin freigeschaltet, nie mehr als Apple zulaesst. Kette: KETTE_MAX.
     */
    public static int erlaubt(Shop shop) {
        if (!TarifPruefung.darf(shop, Tarif.Funktion.FILIALEN)) return 0;
        if (kette(shop)) return KETTE_MAX;
        return Math.min(MAX_WEITERE, shop.getMaxOrte());
    }

    /**
     * Die Orte, die fuer Wallet-Karte, Geo-Pruefung und Zuordnung der Scans
     * zaehlen. "alle" in Anlagereihenfolge (findByShopOrderByCreatedAtAsc).
     */
    public static List<ShopOrt> geltende(Shop shop, List<ShopOrt> alle) {
        if (kette(shop)) return alle.stream().filter(ShopOrt::isAktiv).toList();
        int erlaubt = erlaubt(shop);
        return alle.size() <= erlaubt ? alle : alle.subList(0, Math.max(0, erlaubt));
    }

    private static String labelSauber(String label) {
        String l = (label == null) ? "" : label.replaceAll("\\p{Cntrl}", " ").trim();
        if (l.isEmpty()) throw new IllegalArgumentException("Bitte dem Ort einen Namen geben");
        if (l.length() > ShopOrt.LABEL_MAX) {
            throw new IllegalArgumentException("Name darf höchstens " + ShopOrt.LABEL_MAX + " Zeichen haben");
        }
        return l;
    }

    private static void pruefeKoordinaten(Double latitude, Double longitude) {
        if (latitude == null || longitude == null
                || latitude.isNaN() || longitude.isNaN()
                || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
            throw new IllegalArgumentException("Ungültige Koordinaten");
        }
    }

    private Shop laden(String shopId) {
        return shopRepo.findById(shopId).orElseThrow(() -> new NoSuchElementException("Shop nicht gefunden"));
    }

    @Transactional
    public List<ShopOrt> hinzufuegen(String shopId, String label, Double latitude, Double longitude) {
        String l = labelSauber(label);
        pruefeKoordinaten(latitude, longitude);
        Shop shop = laden(shopId);
        TarifPruefung.pruefe(shop, Tarif.Funktion.FILIALEN);
        int erlaubt = erlaubt(shop);
        if (erlaubt == 0) {
            throw new IllegalArgumentException("Weitere Orte sind in deinem Tarif nicht enthalten.");
        }
        if (ortRepo.countByShop(shop) >= erlaubt) {
            throw new IllegalArgumentException("Dein Tarif erlaubt " + erlaubt + " weitere Orte.");
        }
        ortRepo.save(ShopOrt.create(shop, l, latitude, longitude));
        return liste(shop);
    }

    /**
     * Ort aendern. Null = bleibt, wie es ist. Lage nur mit beiden Werten.
     * Ein- und Ausschalten gibt es nur fuer Ketten: bei allen anderen
     * entscheidet weiter die Zahl des Admins.
     */
    @Transactional
    public List<ShopOrt> aendern(String shopId, String ortId, String label,
                                 Double latitude, Double longitude, Boolean aktiv) {
        Shop shop = laden(shopId);
        ShopOrt ort = ortRepo.findByIdAndShop(ortId, shop)
                .orElseThrow(() -> new NoSuchElementException("Ort nicht gefunden"));
        if (label != null) ort.umbenennen(labelSauber(label));
        if (latitude != null || longitude != null) {
            pruefeKoordinaten(latitude, longitude);
            ort.verlegen(latitude, longitude);
        }
        if (aktiv != null) {
            if (!kette(shop)) throw new IllegalArgumentException(NUR_KETTE);
            ort.setAktiv(aktiv);
        }
        ortRepo.save(ort);
        return liste(shop);
    }

    /** Eine Zeile der Sammel-Anlage, schon mit Koordinaten (aus der Vorschau). */
    public record Neu(String label, String adresse, Double latitude, Double longitude) {}

    /**
     * Viele Filialen auf einmal (Tarif Kette). Alles oder nichts: ist eine
     * Zeile falsch, wird keine gespeichert. Neue Filialen sind AUS - das
     * Hauptkonto schaltet sie ein, wenn es so weit ist.
     */
    @Transactional
    public List<ShopOrt> sammel(String shopId, List<Neu> zeilen) {
        Shop shop = laden(shopId);
        if (!kette(shop)) throw new IllegalArgumentException(NUR_KETTE);
        if (zeilen == null || zeilen.isEmpty()) {
            throw new IllegalArgumentException("Die Liste ist leer.");
        }
        if (ortRepo.countByShop(shop) + zeilen.size() > KETTE_MAX) {
            throw new IllegalArgumentException("Mehr als " + KETTE_MAX + " Filialen gehen nicht.");
        }
        List<ShopOrt> neu = new java.util.ArrayList<>();
        int nr = 0;
        for (Neu z : zeilen) {
            nr++;
            try {
                String l = labelSauber(z.label());
                pruefeKoordinaten(z.latitude(), z.longitude());
                ShopOrt ort = ShopOrt.create(shop, l, z.latitude(), z.longitude());
                ort.setAdresse(z.adresse());
                ort.setAktiv(false);
                neu.add(ort);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Zeile " + nr + ": " + e.getMessage());
            }
        }
        ortRepo.saveAll(neu);
        return liste(shop);
    }

    /** Zaehlt der Hauptstandort als Filiale? Nur Tarif Kette. */
    @Transactional
    public Shop hauptIstFiliale(String shopId, boolean ja) {
        Shop shop = laden(shopId);
        if (!kette(shop)) throw new IllegalArgumentException(NUR_KETTE);
        shop.setHauptIstFiliale(ja);
        return shopRepo.save(shop);
    }

    @Transactional
    public List<ShopOrt> loeschen(String shopId, String ortId) {
        Shop shop = laden(shopId);
        ShopOrt ort = ortRepo.findByIdAndShop(ortId, shop)
                .orElseThrow(() -> new NoSuchElementException("Ort nicht gefunden"));
        ortRepo.delete(ort);
        if (staffTokenRepo != null) {
            for (com.example.stemplekarte.model.StaffToken t : staffTokenRepo.findByShop(shop)) {
                if (ortId.equals(t.getOrtId())) {
                    t.ort(null);
                    staffTokenRepo.save(t);
                }
            }
        }
        return liste(shop);
    }
}
