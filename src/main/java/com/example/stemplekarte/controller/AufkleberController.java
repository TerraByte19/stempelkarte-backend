package com.example.stemplekarte.controller;

import com.example.stemplekarte.config.AppProperties;
import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.security.JwtAuthFilter;
import com.example.stemplekarte.service.AufkleberService;
import com.example.stemplekarte.service.CardService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Aufkleber fuer Liefertueten: A4-Bogen oder einzelnes Bild mit QR-Code
 * zur Anmeldeseite der Karte. Zugang wie ShopController ueber das JWT.
 */
@RestController
@RequestMapping("/api/shop/cards/{cardId}/aufkleber")
public class AufkleberController {

    private final CardService cardService;
    private final AufkleberService aufkleberService;
    private final AppProperties props;

    public AufkleberController(CardService cardService, AufkleberService aufkleberService, AppProperties props) {
        this.cardService = cardService;
        this.aufkleberService = aufkleberService;
        this.props = props;
    }

    private Shop currentShop(Authentication auth) {
        return ((JwtAuthFilter.ShopPrincipal) auth.getPrincipal()).shop();
    }

    @Operation(summary = "Aufkleber-Bogen (A4, 300 dpi) oder einzelner Aufkleber als PNG",
            description = "groesse KLEIN (45 mm, 24 je Bogen) oder GROSS (70 mm, 6 je Bogen); "
                    + "titel (max. 30) und zeile (max. 70) sind frei, leer = Standardtexte; einzeln=true = ein Aufkleber.")
    @GetMapping(produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> aufkleber(@PathVariable String cardId,
                                            @RequestParam(defaultValue = "") String titel,
                                            @RequestParam(defaultValue = "") String zeile,
                                            @RequestParam(defaultValue = "KLEIN") String groesse,
                                            @RequestParam(defaultValue = "false") boolean einzeln,
                                            Authentication auth) throws Exception {
        Shop shop = currentShop(auth);
        Card card = cardService.getByIdAndShop(cardId, shop);
        String link = com.example.stemplekarte.config.KundenLink.anmeldung(props.baseUrl(), card.getId());
        AufkleberService.Texte texte = AufkleberService.texte(titel, zeile, shop.getName(), card.getName());
        byte[] png = einzeln
                ? aufkleberService.einzeln(link, texte)
                : aufkleberService.bogen(link, texte, AufkleberService.Groesse.von(groesse));
        String datei = (einzeln ? "aufkleber-" : "aufkleber-bogen-") + card.getId().toLowerCase() + ".png";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + datei + "\"")
                .contentType(MediaType.IMAGE_PNG)
                .body(png);
    }
}
