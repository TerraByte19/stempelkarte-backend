package com.example.stemplekarte.wallet;

import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.service.CardEventHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Die drei Wege, auf denen eine Aenderung beim Kunden ankommt: SSE an die
 * offene Kartenseite, stiller APNs-Push ans iPhone, direktes Update des
 * Google-Loyalty-Objekts.
 *
 * Lag zweimal inline im ScanController. Mit den vier Punkte-Endpunkten
 * waeren daraus sechs Kopien geworden.
 *
 * Die Regel, die hier zusammengehalten wird: ein fehlgeschlagener Push
 * bricht den Vorgang NIE ab. Der Stempel beziehungsweise die Buchung ist zu
 * dem Zeitpunkt bereits gesetzt - eine durchschlagende Ausnahme meldete dem
 * Personal einen Fehler fuer etwas, das stattgefunden hat, und provoziert
 * die doppelte Buchung.
 */
@Component
public class WalletNotifier {

    private static final Logger log = LoggerFactory.getLogger(WalletNotifier.class);

    private final CardEventHub cardEventHub;
    private final ApnsPushService apnsPushService;
    private final GoogleWalletService googleWalletService;

    public WalletNotifier(CardEventHub cardEventHub,
                          ApnsPushService apnsPushService,
                          GoogleWalletService googleWalletService) {
        this.cardEventHub = cardEventHub;
        this.apnsPushService = apnsPushService;
        this.googleWalletService = googleWalletService;
    }

    /** Nach jeder Stempelaenderung (Scan, Einloesen, Reset). */
    public void nachStempelAenderung(CustomerCard cc) {
        sicher("SSE-Push", () -> cardEventHub.publishStamps(
                cc.getId(), cc.getStamps(), cc.getTotalRewards(),
                cc.getCard().getRewardThreshold()));
        walletWege(cc.getId());
    }

    /**
     * Nach jeder Punktebuchung. zielName darf null sein (leerer Katalog).
     *
     * Nimmt bewusst nur die ID, nicht die Entity: alle drei Wege brauchen von
     * der Karte nichts weiter, und der Aufrufer sitzt ausserhalb der
     * Transaktion. Eine CustomerCard hier zu verlangen hiesse, einen
     * Lazy-Proxy ueber die geschlossene Session zu tragen.
     */
    public void nachPunkteAenderung(String customerCardId, long pointsX100,
                                    String zielName, long fehlendX100) {
        sicher("SSE-Push", () -> cardEventHub.publishPoints(
                customerCardId, pointsX100, zielName, fehlendX100));
        walletWege(customerCardId);
    }

    private void walletWege(String customerCardId) {
        sicher("APNs Push", () -> apnsPushService.notifyUpdate(customerCardId));
        sicher("Google Wallet Update", () -> googleWalletService.notifyUpdate(customerCardId));
    }

    /** Zusaetzliche "Karte voll"-Meldung. Apple bekommt sie ueber das
     *  changeMessage im Pass, Google braucht den expliziten Aufruf. */
    public void googleKarteVoll(String customerCardId, String text) {
        sicher("Google Wallet 'Karte voll'",
                () -> googleWalletService.notifyCardFull(customerCardId, text));
    }

    private void sicher(String was, Runnable arbeit) {
        try {
            arbeit.run();
        } catch (Exception e) {
            log.warn("{} fehlgeschlagen (nicht kritisch): {}", was, e.getMessage());
        }
    }
}
