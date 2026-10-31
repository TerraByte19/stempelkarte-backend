package com.example.stemplekarte.service;

import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.repository.CustomerCardRepository;
import com.example.stemplekarte.wallet.ApnsPushService;
import com.example.stemplekarte.wallet.GoogleWalletService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Bringt ein geaendertes Design der gesperrten Karte auf die Handys der
 * Kunden, deren Geschenk noch offen ist. Still: der gesperrte Pass traegt
 * kein changeMessage, Google bekommt nur den Patch ohne Mitteilung.
 *
 * Bewusst ohne umschliessende Transaktion: jedes save ist committet, bevor
 * der Push rausgeht. Kaeme der Push vor dem Commit, fragte das iPhone nach
 * geaenderten Paessen und bekaeme noch den alten Zeitstempel - und holte
 * nichts.
 *
 * Jeder Push zaehlt bei Apple gegen das Tageslimit des Passes (rund 3).
 * Deshalb ruft der Controller das nur, wenn sich das Design wirklich
 * geaendert hat, nicht bei jedem Speichern.
 */
@Service
public class GesperrteKartenAuffrischer {

    private static final Logger log = LoggerFactory.getLogger(GesperrteKartenAuffrischer.class);

    private final CustomerCardRepository customerCardRepo;
    private final ApnsPushService apnsPushService;
    private final GoogleWalletService googleWalletService;

    public GesperrteKartenAuffrischer(CustomerCardRepository customerCardRepo,
                                      ApnsPushService apnsPushService,
                                      GoogleWalletService googleWalletService) {
        this.customerCardRepo = customerCardRepo;
        this.apnsPushService = apnsPushService;
        this.googleWalletService = googleWalletService;
    }

    /** @return wie viele gesperrte Karten aufgefrischt wurden */
    public int auffrischen(Card card) {
        Instant jetzt = Instant.now();
        List<String> ids = new ArrayList<>();
        for (CustomerCard cc : customerCardRepo.findByCardAndWelcomePendingTrue(card)) {
            // updatedAt bewegen: sonst liefert der Pass-Cache den alten Pass,
            // und iOS sieht keinen Grund, ihn neu zu holen.
            cc.passAuffrischen(jetzt);
            customerCardRepo.save(cc);
            ids.add(cc.getId());
        }
        // Ein fehlgeschlagener Push bricht nichts ab - gespeichert ist schon alles.
        for (String id : ids) {
            try { apnsPushService.notifyUpdate(id); }
            catch (Exception e) { log.warn("[WILLKOMMEN] APNs-Auffrischen fehlgeschlagen {}: {}", id, e.getMessage()); }
            try { googleWalletService.notifyUpdate(id); }
            catch (Exception e) { log.warn("[WILLKOMMEN] Google-Auffrischen fehlgeschlagen {}: {}", id, e.getMessage()); }
        }
        log.info("[WILLKOMMEN] Design geaendert, {} gesperrte Paesse still aufgefrischt (Karte {})",
                ids.size(), card.getId());
        return ids.size();
    }
}
