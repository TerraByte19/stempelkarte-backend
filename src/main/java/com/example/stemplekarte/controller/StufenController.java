package com.example.stemplekarte.controller;

import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.repository.CustomerCardRepository;
import com.example.stemplekarte.security.JwtAuthFilter;
import com.example.stemplekarte.service.CardService;
import com.example.stemplekarte.service.Stufen;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/** Stufen (Bronze, Silber, Gold) einer Stempelkarte. Zugang ueber das JWT wie ShopController. */
@RestController
@RequestMapping("/api/shop/cards/{cardId}/stufen")
public class StufenController {

    private final CardService cardService;
    private final CustomerCardRepository customerCardRepo;

    public StufenController(CardService cardService, CustomerCardRepository customerCardRepo) {
        this.cardService = cardService;
        this.customerCardRepo = customerCardRepo;
    }

    private Shop currentShop(Authentication auth) {
        return ((JwtAuthFilter.ShopPrincipal) auth.getPrincipal()).shop();
    }

    public record StufeStand(int index, String name, int ab, long anzahl) {}
    public record Stand(boolean aktiv, boolean moeglich, List<StufeStand> stufen) {}

    @Operation(summary = "Stufen einer Karte",
            description = "Schalter, drei Stufen mit Name und Besuchen ab, und wie viele Kundenkarten in jeder Stufe stehen. "
                    + "moeglich = Stempelkarte (Punktekarten haben keine Stufen).")
    @GetMapping
    public Stand stand(@PathVariable String cardId, Authentication auth) {
        return stand(cardService.getByIdAndShop(cardId, currentShop(auth)));
    }

    public record EinstellenRequest(boolean aktiv, String stufe1Name, String stufe2Name, int stufe2Ab,
                                    String stufe3Name, int stufe3Ab) {}

    @Operation(summary = "Stufen einstellen",
            description = "Nur Stempelkarten. Namen leer = Standard (Bronze, Silber, Gold). Stufe 3 muss spaeter kommen als Stufe 2.")
    @PutMapping
    public Stand einstellen(@PathVariable String cardId, @RequestBody EinstellenRequest req, Authentication auth) {
        Card card = cardService.getByIdAndShop(cardId, currentShop(auth));
        if (card.isPoints()) {
            throw new IllegalArgumentException("Stufen gibt es nur für Stempelkarten");
        }
        Stufen.Einstellung e = Stufen.pruefe(req.stufe1Name(), req.stufe2Name(), req.stufe2Ab(),
                req.stufe3Name(), req.stufe3Ab());
        card.updateStufen(req.aktiv(), e.name1(), e.name2(), e.ab2(), e.name3(), e.ab3());
        cardService.save(card);
        return stand(card);
    }

    private Stand stand(Card card) {
        String[] namen = card.getStufenNamen();
        int[] ab = {0, card.getStufe2Ab(), card.getStufe3Ab()};
        long[] anzahl = new long[3];
        if (!card.isPoints()) {
            for (CustomerCard cc : customerCardRepo.findByCard(card)) {
                anzahl[Stufen.vonBesuchen(card, Stufen.besuche(card, cc)).index() - 1]++;
            }
        }
        List<StufeStand> stufen = new ArrayList<>();
        for (int i = 0; i < 3; i++) stufen.add(new StufeStand(i + 1, namen[i], ab[i], anzahl[i]));
        return new Stand(card.isStufenAktiv(), !card.isPoints(), stufen);
    }
}
