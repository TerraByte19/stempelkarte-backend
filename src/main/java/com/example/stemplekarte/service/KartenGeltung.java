package com.example.stemplekarte.service;

import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.model.ShopOrt;
import com.example.stemplekarte.repository.CardRepository;
import com.example.stemplekarte.repository.CustomerCardRepository;
import com.example.stemplekarte.repository.ShopOrtRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Kette: das Hauptkonto legt je Karte fest, in welchen Filialen sie gilt
 * (Card.giltIn). Ohne Auswahl gilt sie ueberall - so wie jede Karte bisher.
 *
 * Geprueft wird an der Kasse: das Geraet haengt an einer Filiale
 * (StaffToken.ortId, null = Hauptstandort). Gilt die Karte dort nicht, wird
 * nicht gestempelt, gebucht oder eingeloest.
 *
 * Fuer alle Laeden ausserhalb des Tarifs Kette ist das hier ein sofortiges
 * "ja" ohne Datenbankzugriff.
 */
@Service
public class KartenGeltung {

    public static final String GILT_NICHT = "Diese Karte gilt in dieser Filiale nicht.";

    private final CustomerCardRepository customerCardRepo;
    private final CardRepository cardRepo;
    private final ShopOrtRepository ortRepo;

    public KartenGeltung(CustomerCardRepository customerCardRepo, CardRepository cardRepo,
                         ShopOrtRepository ortRepo) {
        this.customerCardRepo = customerCardRepo;
        this.cardRepo = cardRepo;
        this.ortRepo = ortRepo;
    }

    // ── Pruefung an der Kasse ───────────────────────────────────────────

    /**
     * @param ortId Filiale des Geraets oder Logins, null = Hauptstandort
     * Ein unlesbarer oder unbekannter QR-Code ist hier kein Fehler: den
     * meldet danach der eigentliche Dienst mit seinem gewohnten Text.
     */
    @Transactional(readOnly = true)
    public void pruefe(Shop shop, String ortId, String qrPayload) {
        if (shop == null || !OrteService.kette(shop)) return;
        Card card;
        try {
            QrPayload qr = QrPayload.parse(qrPayload, customerCardRepo);
            card = cardRepo.findById(qr.cardId()).orElse(null);
        } catch (RuntimeException e) {
            return;
        }
        ablehnenWennFremd(card, ortId);
    }

    /** Wie oben, wenn statt des QR-Codes schon die Kundenkarte bekannt ist. */
    @Transactional(readOnly = true)
    public void pruefeKundenkarte(Shop shop, String ortId, String customerCardId) {
        if (shop == null || !OrteService.kette(shop) || customerCardId == null) return;
        Card card = customerCardRepo.findById(customerCardId).map(cc -> cc.getCard()).orElse(null);
        ablehnenWennFremd(card, ortId);
    }

    private static void ablehnenWennFremd(Card card, String ortId) {
        if (card != null && !card.giltIn(ortId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, GILT_NICHT);
        }
    }

    // ── Einstellung durch das Hauptkonto ────────────────────────────────

    /** alle = keine Auswahl, die Karte gilt ueberall. filialen: IDs, HAUPT = Hauptstandort. */
    public record Stand(boolean alle, List<String> filialen) {}

    public static Stand stand(Card card) {
        Set<String> wahl = card.getGiltIn();
        return wahl == null ? new Stand(true, List.of()) : new Stand(false, new ArrayList<>(wahl));
    }

    /**
     * Setzt die Auswahl. Unbekannte oder fremde Filialen werden abgelehnt,
     * eine leere Auswahl auch - eine Karte, die nirgends gilt, will niemand.
     * Die Karte muss der Aufrufer speichern.
     */
    @Transactional(readOnly = true)
    public Stand setzen(Shop shop, Card card, boolean alle, List<String> filialen) {
        if (!OrteService.kette(shop)) throw new IllegalArgumentException(OrteService.NUR_KETTE);
        if (alle) {
            card.setGiltIn(null);
            return stand(card);
        }
        Set<String> wahl = new LinkedHashSet<>();
        for (String roh : filialen == null ? List.<String>of() : filialen) {
            if (roh == null || roh.isBlank()) continue;
            String id = roh.trim();
            if (FilialenService.HAUPT.equalsIgnoreCase(id)) {
                wahl.add(FilialenService.HAUPT);
                continue;
            }
            ShopOrt ort = ortRepo.findByIdAndShop(id, shop)
                    .orElseThrow(() -> new IllegalArgumentException("Diese Filiale gibt es nicht."));
            wahl.add(ort.getId());
        }
        if (wahl.isEmpty()) {
            throw new IllegalArgumentException("Bitte mindestens eine Filiale wählen.");
        }
        card.setGiltIn(wahl);
        return stand(card);
    }
}
