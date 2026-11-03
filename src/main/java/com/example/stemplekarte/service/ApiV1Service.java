package com.example.stemplekarte.service;

import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.Customer;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.repository.CardRepository;
import com.example.stemplekarte.repository.CustomerCardRepository;
import com.example.stemplekarte.repository.CustomerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;

/**
 * Lesezugriffe der externen API. Die Ansichten werden INNERHALB der
 * Transaktion gefuellt (open-in-view ist aus), der Controller reicht sie
 * nur weiter. Nur Karten DIESES Ladens - ein Kunde kann bei mehreren
 * Laeden sein, die anderen gehen den Aufrufer nichts an.
 */
@Service
public class ApiV1Service {

    private final CustomerRepository customerRepo;
    private final CustomerCardRepository customerCardRepo;
    private final CardRepository cardRepo;

    public ApiV1Service(CustomerRepository customerRepo, CustomerCardRepository customerCardRepo,
                        CardRepository cardRepo) {
        this.customerRepo = customerRepo;
        this.customerCardRepo = customerCardRepo;
        this.cardRepo = cardRepo;
    }

    public record KarteView(String id, String name, String type, int rewardThreshold, String rewardText, boolean active) {}
    public record KundenkarteView(String customerCardId, String cardId, String cardName, String type,
                                  int stamps, int rewardThreshold, int totalRewards, long pointsX100,
                                  String lastVisitAt, String createdAt, boolean giftPending, String gruppe,
                                  // 10er-Karte: Rest des Pakets; sonst null.
                                  Integer remaining) {}
    public record KundeView(String customerId, String name, String email, boolean emailConfirmed,
                            List<KundenkarteView> cards) {}

    @Transactional(readOnly = true)
    public List<KarteView> karten(Shop shop) {
        return cardRepo.findByShop(shop).stream()
                .map(c -> new KarteView(c.getId(), c.getName(), c.getType().name(), c.getRewardThreshold(),
                        c.getRewardText(), c.isActive()))
                .toList();
    }

    @Transactional(readOnly = true)
    public KundeView kundeNachEmail(Shop shop, String email) {
        if (email == null || email.isBlank()) throw new IllegalArgumentException("email fehlt");
        Customer c = customerRepo.findByEmail(email.trim().toLowerCase(Locale.ROOT))
                .orElseThrow(() -> new NoSuchElementException("Kunde nicht gefunden"));
        return kunde(shop, c);
    }

    @Transactional(readOnly = true)
    public KundeView kundeNachId(Shop shop, String customerId) {
        Customer c = customerRepo.findById(customerId)
                .orElseThrow(() -> new NoSuchElementException("Kunde nicht gefunden"));
        return kunde(shop, c);
    }

    private KundeView kunde(Shop shop, Customer c) {
        java.time.Instant jetzt = java.time.Instant.now();
        Kundengruppe.Regeln regeln = Kundengruppe.Regeln.von(shop);
        List<KundenkarteView> karten = customerCardRepo.findByCustomer(c).stream()
                .filter(cc -> cc.getCard() != null && cc.getCard().getShop() != null
                        && shop.getId().equals(cc.getCard().getShop().getId()))
                .map(cc -> karte(cc, regeln, jetzt))
                .toList();
        // Ein Kunde ohne Karte bei diesem Laden "gibt es" fuer den Laden nicht.
        if (karten.isEmpty()) throw new NoSuchElementException("Kunde nicht gefunden");
        return new KundeView(c.getId(), c.getName(), c.getEmail(), c.isEmailConfirmed(), karten);
    }

    private static KundenkarteView karte(CustomerCard cc, Kundengruppe.Regeln regeln, java.time.Instant jetzt) {
        Card card = cc.getCard();
        int besuche = card.isPoints() ? 0 : Kundengruppe.besucheStempelkarte(card, cc);
        return new KundenkarteView(cc.getId(), card.getId(), card.getName(), card.getType().name(),
                cc.getStamps(), card.getRewardThreshold(), cc.getTotalRewards(), cc.getPointsX100(),
                cc.getLastVisitAt() != null ? cc.getLastVisitAt().toString() : null,
                cc.getCreatedAt() != null ? cc.getCreatedAt().toString() : null,
                cc.isWillkommenOffen(),
                Kundengruppe.von(cc, besuche, regeln, jetzt).name(),
                card.isPackage() ? cc.getPaketRest() : null);
    }
}
