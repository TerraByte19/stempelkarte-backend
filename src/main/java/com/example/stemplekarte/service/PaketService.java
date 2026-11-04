package com.example.stemplekarte.service;

import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.repository.CardRepository;
import com.example.stemplekarte.repository.CustomerCardRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.NoSuchElementException;

/**
 * 10er-Karte: Pakete aufladen und die Texte dazu. Abgebucht wird im
 * CustomerService#processScan - derselbe Weg wie der Stempel, mit denselben
 * Sperren (Laden, aktiv, Standort).
 *
 * Aufladen heisst: der Kunde hat ein neues Paket bezahlt. Der Rest wird
 * addiert, nicht ersetzt. Keine Rollen-Sperre fuer Aushilfen: ein Paket
 * verkaufen ist Alltag an der Theke, das Personal steht im Protokoll.
 */
@Service
public class PaketService {

    private static final Logger log = LoggerFactory.getLogger(PaketService.class);

    public static final int REST_MAX = 500;

    private final CardRepository cardRepo;
    private final CustomerCardRepository customerCardRepo;
    private final CustomerService customerService;

    @Autowired(required = false)
    private WebhookService webhooks;

    public PaketService(CardRepository cardRepo, CustomerCardRepository customerCardRepo,
                        CustomerService customerService) {
        this.cardRepo = cardRepo;
        this.customerCardRepo = customerCardRepo;
        this.customerService = customerService;
    }

    void webhooks(WebhookService webhooks) { this.webhooks = webhooks; }

    // ── Texte ───────────────────────────────────────────────────────────

    /** "Noch 7 Kurse übrig", "Noch 1 übrig", "Paket leer". */
    public static String restText(Card card, CustomerCard cc) {
        int rest = cc.getPaketRest();
        if (rest <= 0) return "Paket leer";
        return "N" + nochText(card, rest).substring(1);
    }

    /** "noch 7 Kurse übrig" bzw. "noch 1 übrig" - die Einheit hat nur eine Mehrzahl. */
    private static String nochText(Card card, int rest) {
        return rest == 1 ? "noch 1 übrig" : "noch " + rest + " " + card.getPaketEinheit() + " übrig";
    }

    /** Meldung im Scanner nach dem Abbuchen. */
    public static String abgebuchtText(Card card, CustomerCard cc, int anzahl) {
        String vorn = anzahl == 1 ? "Abgebucht" : anzahl + " abgebucht";
        if (cc.getPaketRest() <= 0) return vorn + " · Paket leer, neues Paket an der Kasse";
        return vorn + " · " + nochText(card, cc.getPaketRest());
    }

    /** Meldung im Scanner, wenn nichts mehr uebrig ist. */
    public static String leerText(Card card) {
        return "Paket leer – neues Paket (" + card.paketText() + ") aufladen?";
    }

    /** Meldung im Scanner nach dem Aufladen. */
    public static String aufgeladenText(Card card, CustomerCard cc) {
        return "Paket aufgeladen · " + nochText(card, cc.getPaketRest());
    }

    /** Groesse und Einheit pruefen (Anlegen und Aendern). */
    public record Einstellung(int groesse, String einheit) {}

    public static Einstellung pruefe(Integer groesse, String einheit) {
        if (groesse == null || groesse < 1 || groesse > Card.PAKET_MAX) {
            throw new IllegalArgumentException("Paketgröße muss zwischen 1 und " + Card.PAKET_MAX + " liegen");
        }
        String e = einheit == null ? "" : einheit.trim();
        if (e.length() > Card.PAKET_EINHEIT_MAX) {
            throw new IllegalArgumentException("Einheit darf höchstens " + Card.PAKET_EINHEIT_MAX + " Zeichen haben");
        }
        if (e.contains("<") || e.contains(">")) {
            throw new IllegalArgumentException("Einheit darf kein < oder > enthalten");
        }
        return new Einstellung(groesse, e.isEmpty() ? null : e);
    }

    // ── Aufladen ────────────────────────────────────────────────────────

    /** Scanner: Karte per QR. Dieselben Sperren wie beim Stempeln. */
    @Transactional
    public CustomerCard aufladen(String qrPayload, Shop shop, String staffLabel) {
        QrPayload qr = QrPayload.parse(qrPayload, customerCardRepo);
        Card card = cardRepo.findById(qr.cardId())
                .orElseThrow(() -> new NoSuchElementException("Karte nicht gefunden"));
        if (!card.getShop().getId().equals(shop.getId())) {
            throw new IllegalArgumentException("Diese Karte gehoert nicht zu deinem Shop");
        }
        if (!card.isActive()) {
            throw new IllegalArgumentException("Diese Karte ist nicht mehr aktiv");
        }
        if (!card.isPackage()) {
            throw new IllegalArgumentException("Aufladen gibt es nur bei der 10er-Karte");
        }
        CustomerCard cc = customerService.getOrCreateCustomerCard(qr.customerId(), qr.cardId());
        return aufladen(cc, card, shop, staffLabel, Instant.now());
    }

    /** Kundenliste: Karte per ID. Fremde Karten gelten als nicht vorhanden. */
    @Transactional
    public CustomerCard aufladen(Shop shop, String customerCardId) {
        CustomerCard cc = customerCardRepo.findById(customerCardId)
                .filter(c -> c.getCard().getShop().getId().equals(shop.getId()))
                .orElseThrow(() -> new NoSuchElementException("Kundenkarte nicht gefunden"));
        if (!cc.getCard().isPackage()) {
            throw new IllegalArgumentException("Aufladen gibt es nur bei der 10er-Karte");
        }
        return aufladen(cc, cc.getCard(), shop, null, Instant.now());
    }

    private CustomerCard aufladen(CustomerCard cc, Card card, Shop shop, String staffLabel, Instant jetzt) {
        if (cc.getPaketRest() + card.getRewardThreshold() > REST_MAX) {
            throw new IllegalArgumentException("Mehr als " + REST_MAX + " übrige Besuche gehen nicht");
        }
        cc.paketAufladen(card.getRewardThreshold(), jetzt);
        customerCardRepo.save(cc);
        if (webhooks != null) {
            webhooks.melde(shop, "package.topup", WebhookService.daten(
                    "customerId", cc.getCustomer().getId(), "cardId", card.getId(),
                    "customerCardId", cc.getId(), "packageSize", card.getRewardThreshold(),
                    "remaining", cc.getPaketRest(), "packagesBought", cc.getPaketeGekauft(),
                    "staff", staffLabel));
        }
        log.info("[PAKET] aufgeladen karte={} laden={} groesse={} rest={} via={}",
                cc.getId(), shop.getId(), card.getRewardThreshold(), cc.getPaketRest(), staffLabel);
        return cc;
    }
}
