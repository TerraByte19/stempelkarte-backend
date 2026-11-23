package com.example.stemplekarte.controller;

import com.example.stemplekarte.config.AppProperties;
import com.example.stemplekarte.config.KundenLink;
import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.NfcTag;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.model.ShopOrt;
import com.example.stemplekarte.repository.NfcTagRepository;
import com.example.stemplekarte.repository.ShopOrtRepository;
import com.example.stemplekarte.security.JwtAuthFilter;
import com.example.stemplekarte.service.CardService;
import com.example.stemplekarte.service.FilialenService;
import com.example.stemplekarte.service.OrteService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * Kette: NFC-Karten je Filiale. Das Hauptkonto legt je Stempelkarte beliebig
 * viele an, jede mit Filiale und Namen. Der Link steht nur in dieser
 * Verwaltung; angesprochen wird eine NFC-Karte ueber ihre id.
 */
@RestController
@RequestMapping("/api/shop/cards/{cardId}/nfc-tags")
public class NfcTagController {

    static final int MAX_JE_KARTE = 300;

    private final CardService cardService;
    private final NfcTagRepository tags;
    private final ShopOrtRepository orte;
    private final AppProperties props;

    public NfcTagController(CardService cardService, NfcTagRepository tags,
                            ShopOrtRepository orte, AppProperties props) {
        this.cardService = cardService;
        this.tags = tags;
        this.orte = orte;
        this.props = props;
    }

    private Shop currentShop(Authentication auth) {
        return ((JwtAuthFilter.ShopPrincipal) auth.getPrincipal()).shop();
    }

    public record NeuRequest(String label, String ortId) {}
    public record SperreRequest(boolean gesperrt) {}
    public record Eintrag(String id, String label, String ortId, String ortLabel,
                          String url, boolean gesperrt, String erstellt) {}

    private Eintrag eintrag(NfcTag t, Shop shop) {
        String ortLabel = t.getOrtId() == null ? FilialenService.HAUPT_LABEL
                : orte.findByIdAndShop(t.getOrtId(), shop).map(ShopOrt::getLabel)
                        .orElse(FilialenService.GELOESCHT_LABEL);
        return new Eintrag(t.getId(), t.getLabel(),
                t.getOrtId() != null ? t.getOrtId() : FilialenService.HAUPT, ortLabel,
                KundenLink.nfc(props.baseUrl(), t.getToken()), t.isGesperrt(),
                t.getCreatedAt().toString());
    }

    private Card stempelkarte(String cardId, Shop shop) {
        Card card = cardService.getByIdAndShop(cardId, shop);
        if (!OrteService.kette(shop)) throw new IllegalArgumentException("Das gibt es nur im Tarif Kette.");
        return card;
    }

    private NfcTag eigener(String id, String cardId, Shop shop) {
        return tags.findByIdAndShopId(id, shop.getId())
                .filter(t -> t.getCardId().equals(cardId))
                .orElseThrow(() -> new NoSuchElementException("NFC-Karte nicht gefunden"));
    }

    @Operation(summary = "NFC-Karten der Filialen fuer diese Stempelkarte")
    @GetMapping
    public List<Eintrag> liste(@PathVariable String cardId, Authentication auth) {
        Shop shop = currentShop(auth);
        Card card = cardService.getByIdAndShop(cardId, shop);
        return tags.findByCardIdOrderByCreatedAtAsc(card.getId()).stream().map(t -> eintrag(t, shop)).toList();
    }

    @Operation(summary = "NFC-Karte fuer eine Filiale anlegen (nur Tarif Kette)",
            description = "ortId = Filiale oder HAUPT. Nur Stempelkarten. Die Antwort enthaelt den Link fuer den NFC-Chip.")
    @PostMapping
    public Eintrag anlegen(@PathVariable String cardId, @RequestBody NeuRequest req, Authentication auth) {
        Shop shop = currentShop(auth);
        Card card = stempelkarte(cardId, shop);
        if (!card.isStamp()) {
            throw new IllegalArgumentException("NFC stempelt nur Stempelkarten.");
        }
        String label = req.label() == null ? "" : req.label().replaceAll("\\p{Cntrl}", " ").trim();
        if (label.isEmpty()) throw new IllegalArgumentException("Bitte der NFC-Karte einen Namen geben.");
        if (label.length() > NfcTag.LABEL_MAX) {
            throw new IllegalArgumentException("Der Name darf höchstens " + NfcTag.LABEL_MAX + " Zeichen haben.");
        }
        String ortId = null;
        if (req.ortId() != null && !req.ortId().isBlank() && !FilialenService.HAUPT.equalsIgnoreCase(req.ortId().trim())) {
            ortId = orte.findByIdAndShop(req.ortId().trim(), shop)
                    .orElseThrow(() -> new IllegalArgumentException("Diese Filiale gibt es nicht.")).getId();
        }
        if (!card.giltIn(ortId)) {
            throw new IllegalArgumentException("Die Karte gilt in dieser Filiale nicht (siehe „Gilt in“).");
        }
        if (tags.countByCardId(card.getId()) >= MAX_JE_KARTE) {
            throw new IllegalArgumentException("Mehr als " + MAX_JE_KARTE + " NFC-Karten je Stempelkarte gehen nicht.");
        }
        return eintrag(tags.save(NfcTag.create(shop.getId(), card.getId(), ortId, label)), shop);
    }

    @Operation(summary = "NFC-Karte sperren oder wieder freigeben",
            description = "Gesperrt stempelt sie nicht mehr - etwa wenn ihr Link die Runde macht.")
    @PutMapping("/{id}/sperre")
    public Eintrag sperre(@PathVariable String cardId, @PathVariable String id,
                          @RequestBody SperreRequest req, Authentication auth) {
        Shop shop = currentShop(auth);
        NfcTag t = eigener(id, cardId, shop);
        t.setGesperrt(req.gesperrt());
        return eintrag(tags.save(t), shop);
    }

    @Operation(summary = "NFC-Karte entfernen", description = "Ihr Link stempelt danach nie wieder.")
    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<Void> entfernen(@PathVariable String cardId, @PathVariable String id,
                                          Authentication auth) {
        tags.delete(eigener(id, cardId, currentShop(auth)));
        return ResponseEntity.noContent().build();
    }
}
