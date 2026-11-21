package com.example.stemplekarte.controller;

import com.example.stemplekarte.i18n.Texts;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.repository.CustomerCardRepository;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import static com.example.stemplekarte.controller.LandingController.escapeHtml;

/**
 * Alte Bewertungslinks (/bewertung/{customerId}/{cardId}) stehen noch auf
 * Wallet-Karten von frueher. Die private Rueckmeldung an den Laden gibt es
 * nicht mehr - die Seite fuehrt nur noch zur Google-Bewertung (falls der
 * Laden einen Link hinterlegt hat) und zurueck zur Karte.
 */
@Tag(name = "Bewertung", description = "Weiterleitung alter Bewertungslinks")
@RestController
public class BewertungController {

    private final CustomerCardRepository customerCardRepo;

    public BewertungController(CustomerCardRepository customerCardRepo) {
        this.customerCardRepo = customerCardRepo;
    }

    @GetMapping(value = "/bewertung/{customerId}/{cardId}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> seite(@PathVariable String customerId, @PathVariable String cardId) {
        CustomerCard cc = customerCardRepo.findByCustomer_IdAndCard_Id(customerId, cardId).orElse(null);
        if (cc == null) {
            return ResponseEntity.ok(hinweisSeite(Texts.STANDARD, Texts.t(Texts.STANDARD, "card.not_found"), ""));
        }
        Shop shop = cc.getCard().getShop();
        String lang = Texts.sprache(cc.getCustomer(), shop);
        String zurueck = "<a href='/karte/" + escapeHtml(customerId) + "/" + escapeHtml(cardId) + "'>"
                + Texts.t(lang, "review.back") + "</a>";
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(hinweisSeite(lang, shop.getName(), LandingController.bewertungsLink(lang, shop) + zurueck));
    }

    private static String hinweisSeite(String lang, String titel, String linkHtml) {
        return "<html" + Texts.htmlAttrs(lang) + "><head><meta charset='UTF-8'>"
                + "<meta name='viewport' content='width=device-width, initial-scale=1.0'></head>"
                + "<body style='font-family:sans-serif;padding:40px;text-align:center'><h2>"
                + escapeHtml(titel) + "</h2><p style='margin-top:16px'>" + linkHtml + "</p></body></html>";
    }
}
