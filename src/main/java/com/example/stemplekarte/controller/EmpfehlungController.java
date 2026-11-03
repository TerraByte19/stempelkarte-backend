package com.example.stemplekarte.controller;

import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.security.JwtAuthFilter;
import com.example.stemplekarte.service.CardService;
import com.example.stemplekarte.service.EmpfehlungService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Kunde wirbt Kunde, je Karte. Zugang ueber das JWT wie ShopController. */
@RestController
@RequestMapping("/api/shop/cards/{cardId}/empfehlung")
public class EmpfehlungController {

    private final EmpfehlungService dienst;
    private final CardService cardService;

    public EmpfehlungController(EmpfehlungService dienst, CardService cardService) {
        this.dienst = dienst;
        this.cardService = cardService;
    }

    private Shop currentShop(Authentication auth) {
        return ((JwtAuthFilter.ShopPrincipal) auth.getPrincipal()).shop();
    }

    @Operation(summary = "Kunde wirbt Kunde: Stand einer Karte",
            description = "Schalter, Bonus, wie viele geworben und wie viele davon belohnt, die fuenf fleissigsten Werber.")
    @GetMapping
    public EmpfehlungService.Stand stand(@PathVariable String cardId, Authentication auth) {
        return dienst.stand(cardService.getByIdAndShop(cardId, currentShop(auth)));
    }

    public record EinstellenRequest(boolean aktiv, int bonus) {}

    @Operation(summary = "Kunde wirbt Kunde ein-/ausschalten",
            description = "bonus = Stempel (Stempelkarte, kleiner als die Schwelle) bzw. ganze Punkte, 1 bis 10. "
                    + "Gilt fuer beide Seiten, beim ersten Besuch des Geworbenen.")
    @PutMapping
    public EmpfehlungService.Stand einstellen(@PathVariable String cardId,
                                              @RequestBody EinstellenRequest req,
                                              Authentication auth) {
        Card card = cardService.getByIdAndShop(cardId, currentShop(auth));
        dienst.einstellen(card, req.aktiv(), req.bonus());
        cardService.save(card);
        return dienst.stand(card);
    }
}
