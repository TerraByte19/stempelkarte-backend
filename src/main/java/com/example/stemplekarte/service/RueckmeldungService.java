package com.example.stemplekarte.service;

import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.Rueckmeldung;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.repository.CustomerCardRepository;
import com.example.stemplekarte.repository.RueckmeldungRepository;
import com.example.stemplekarte.repository.ShopRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Private Rueckmeldung vom Kunden an den Laden.
 *
 * Zwei Seiten: der Kunde gibt ab (offene Seite, nur mit gueltiger
 * Kundenkarte), der Laden liest (Dashboard). Eine Rueckmeldung pro Karte
 * und Tag - mehr braucht niemand, und es haelt die Liste sauber, falls
 * jemand den Link spammt.
 */
@Service
public class RueckmeldungService {

    static final Duration SPERRE = Duration.ofHours(24);
    static final Duration SCHNITT_FENSTER = Duration.ofDays(30);

    private final RueckmeldungRepository repo;
    private final CustomerCardRepository customerCardRepo;
    private final ShopRepository shopRepo;

    public RueckmeldungService(RueckmeldungRepository repo, CustomerCardRepository customerCardRepo,
                               ShopRepository shopRepo) {
        this.repo = repo;
        this.customerCardRepo = customerCardRepo;
        this.shopRepo = shopRepo;
    }

    /** Was der Kunde abgibt. Fehler als Ausnahme, der Controller uebersetzt. */
    public static class HeuteSchon extends RuntimeException {
        public HeuteSchon() { super("Heute schon eine Rückmeldung geschickt"); }
    }

    public static class Aus extends RuntimeException {
        public Aus() { super("Rückmeldungen sind hier nicht eingeschaltet"); }
    }

    /**
     * Speichert eine Rueckmeldung. Die Karte muss es geben (kein Anlegen -
     * sonst koennte jeder mit erfundenen IDs Zeilen erzeugen).
     *
     * @throws NoSuchElementException Karte unbekannt
     * @throws Aus Laden hat die Funktion nicht eingeschaltet
     * @throws HeuteSchon zweite Rueckmeldung innerhalb von 24 Stunden
     * @throws IllegalArgumentException Sterne oder Text ausserhalb des Rahmens
     */
    @Transactional
    public Rueckmeldung abgeben(String customerId, String cardId, int sterne, String text, Instant jetzt) {
        if (sterne < 1 || sterne > 5) throw new IllegalArgumentException("Sterne 1 bis 5");
        if (text != null && text.length() > Rueckmeldung.TEXT_MAX) {
            throw new IllegalArgumentException("Text höchstens " + Rueckmeldung.TEXT_MAX + " Zeichen");
        }
        CustomerCard cc = customerCardRepo.findByCustomer_IdAndCard_Id(customerId, cardId)
                .orElseThrow(() -> new NoSuchElementException("Karte nicht gefunden"));
        Shop shop = cc.getCard().getShop();
        if (!shop.isRueckmeldungAktiv()) throw new Aus();
        if (repo.existsByCustomerCardIdAndEingegangenAmAfter(cc.getId(), jetzt.minus(SPERRE))) {
            throw new HeuteSchon();
        }
        return repo.save(Rueckmeldung.neu(shop, cc.getId(), sterne, text, jetzt));
    }

    /** Eine Zeile fuers Dashboard. kunde/karte leer, wenn die Karte weg ist. */
    public record Eintrag(String id, int sterne, String text, String am, boolean gelesen,
                          String kunde, String karte) {}

    /** schnitt = Durchschnitt der letzten 30 Tage, 0 wenn nichts da. */
    public record Stand(boolean aktiv, long ungelesen, double schnitt, long anzahl30, List<Eintrag> items) {}

    @Transactional(readOnly = true)
    public Stand stand(Shop shop, Instant jetzt) {
        List<Rueckmeldung> letzte = repo.findTop50ByShopOrderByEingegangenAmDesc(shop);
        List<Rueckmeldung> fenster = repo.findByShopAndEingegangenAmAfter(shop, jetzt.minus(SCHNITT_FENSTER));
        double schnitt = fenster.isEmpty() ? 0
                : Math.round(fenster.stream().mapToInt(Rueckmeldung::getSterne).average().orElse(0) * 10) / 10.0;

        Map<String, CustomerCard> karten = new HashMap<>();
        customerCardRepo.findAllById(letzte.stream().map(Rueckmeldung::getCustomerCardId).distinct().toList())
                .forEach(cc -> karten.put(cc.getId(), cc));

        List<Eintrag> items = letzte.stream().map(r -> {
            CustomerCard cc = karten.get(r.getCustomerCardId());
            return new Eintrag(r.getId(), r.getSterne(), r.getText() != null ? r.getText() : "",
                    r.getEingegangenAm().toString(), r.isGelesen(),
                    cc != null ? cc.getCustomer().getName() : "",
                    cc != null ? cc.getCard().getName() : "");
        }).toList();

        return new Stand(shop.isRueckmeldungAktiv(), repo.countByShopAndGelesenFalse(shop),
                schnitt, fenster.size(), items);
    }

    @Transactional
    public void gelesen(Shop shop, String id) {
        Rueckmeldung r = repo.findByIdAndShop(id, shop)
                .orElseThrow(() -> new NoSuchElementException("Rückmeldung nicht gefunden"));
        r.gelesen();
    }

    @Transactional
    public boolean schalten(Shop shop, boolean aktiv) {
        Shop frisch = shopRepo.findById(shop.getId()).orElseThrow();
        frisch.setRueckmeldungAktiv(aktiv);
        shopRepo.save(frisch);
        return frisch.isRueckmeldungAktiv();
    }
}
