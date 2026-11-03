package com.example.stemplekarte.service;

import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.model.ShopOrt;
import com.example.stemplekarte.repository.ShopOrtRepository;
import com.example.stemplekarte.repository.ShopRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * Weitere Orte eines Ladens (ShopOrt). Eigener Dienst statt ShopService,
 * damit dessen Konstruktor und die Tests darauf unangetastet bleiben.
 */
@Service
public class OrteService {

    /** Apple erlaubt zehn Orte pro Pass, einer davon ist der Hauptort. */
    public static final int MAX_WEITERE = 9;

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

    @Transactional
    public List<ShopOrt> hinzufuegen(String shopId, String label, Double latitude, Double longitude) {
        String l = (label == null) ? "" : label.replaceAll("\\p{Cntrl}", " ").trim();
        if (l.isEmpty()) throw new IllegalArgumentException("Bitte dem Ort einen Namen geben");
        if (l.length() > ShopOrt.LABEL_MAX) {
            throw new IllegalArgumentException("Name darf höchstens " + ShopOrt.LABEL_MAX + " Zeichen haben");
        }
        if (latitude == null || longitude == null
                || latitude.isNaN() || longitude.isNaN()
                || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
            throw new IllegalArgumentException("Ungültige Koordinaten");
        }
        Shop shop = shopRepo.findById(shopId).orElseThrow(() -> new NoSuchElementException("Shop nicht gefunden"));
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

    /** Vom Admin freigeschaltete Anzahl, nie mehr als Apple zulaesst. */
    public static int erlaubt(Shop shop) {
        return Math.min(MAX_WEITERE, shop.getMaxOrte());
    }

    @Transactional
    public List<ShopOrt> loeschen(String shopId, String ortId) {
        Shop shop = shopRepo.findById(shopId).orElseThrow(() -> new NoSuchElementException("Shop nicht gefunden"));
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
