package com.example.stemplekarte.controller;

import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.security.JwtAuthFilter;
import com.example.stemplekarte.service.CardService;
import com.example.stemplekarte.service.KartenGeltung;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Kette: in welchen Filialen gilt diese Karte? Stellt das Hauptkonto ein.
 * Unterkonten kommen hier nicht hin (/api/shop/** ist dem Inhaber vorbehalten).
 */
@RestController
@RequestMapping("/api/shop/cards/{cardId}/filialen")
public class KartenGeltungController {

    private final CardService cardService;
    private final KartenGeltung geltung;

    public KartenGeltungController(CardService cardService, KartenGeltung geltung) {
        this.cardService = cardService;
        this.geltung = geltung;
    }

    private Shop currentShop(Authentication auth) {
        return ((JwtAuthFilter.ShopPrincipal) auth.getPrincipal()).shop();
    }

    public record GeltungRequest(boolean alle, List<String> filialen) {}

    @Operation(summary = "In welchen Filialen gilt die Karte?",
            description = "alle = true: ueberall. Sonst die IDs der Filialen, HAUPT = Hauptstandort.")
    @GetMapping
    public KartenGeltung.Stand stand(@PathVariable String cardId, Authentication auth) {
        return KartenGeltung.stand(cardService.getByIdAndShop(cardId, currentShop(auth)));
    }

    @Operation(summary = "Filialen der Karte festlegen (nur Tarif Kette)",
            description = "alle = true hebt die Auswahl auf. Sonst mindestens eine Filiale. "
                    + "Wirkt sofort an der Kasse; Stempel der Kunden bleiben.")
    @PutMapping
    public KartenGeltung.Stand setzen(@PathVariable String cardId, @RequestBody GeltungRequest req,
                                      Authentication auth) {
        Shop shop = currentShop(auth);
        Card card = cardService.getByIdAndShop(cardId, shop);
        KartenGeltung.Stand neu = geltung.setzen(shop, card, req.alle(), req.filialen());
        cardService.save(card);
        return neu;
    }
}
