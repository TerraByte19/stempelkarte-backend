package com.example.stemplekarte.service;

import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.FilialZugang;
import com.example.stemplekarte.model.KorrekturLog;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.repository.CustomerCardRepository;
import com.example.stemplekarte.repository.KorrekturLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Stempel-Korrektur durch eine Filiale (Tarif Kette). Die Karte wird
 * gescannt, nicht gesucht: ein Unterkonto bekommt keine Kundenliste und
 * keinen Kundennamen zu sehen. Jede Korrektur steht mit Filiale und Login
 * im Protokoll (KorrekturLog), das das Hauptkonto einsehen kann.
 *
 * Die Regeln selbst (nur Stempelkarten, 0 bis Schwelle, nicht bei offenem
 * Willkommensgeschenk) stehen im KundenService und gelten hier genauso.
 */
@Service
public class FilialKorrekturService {

    private final CustomerCardRepository customerCardRepo;
    private final KundenService kundenService;
    private final KorrekturLogRepository logRepo;
    private final com.example.stemplekarte.repository.ShopOrtRepository ortRepo;

    public FilialKorrekturService(CustomerCardRepository customerCardRepo, KundenService kundenService,
                                  KorrekturLogRepository logRepo,
                                  com.example.stemplekarte.repository.ShopOrtRepository ortRepo) {
        this.customerCardRepo = customerCardRepo;
        this.kundenService = kundenService;
        this.logRepo = logRepo;
        this.ortRepo = ortRepo;
    }

    /** Stand einer Karte, ohne Kundenname. hinweis != null: hier nicht korrigierbar. */
    public record Ansicht(String customerCardId, String karte, int stempel, int schwelle,
                          boolean korrigierbar, String hinweis) {}

    /** cc fuer die Wallet-Aktualisierung nach der Transaktion (wie KundenController). */
    public record Ergebnis(CustomerCard cc, Ansicht ansicht, boolean geaendert) {}

    @Transactional(readOnly = true)
    public Ansicht karte(Shop shop, String qrPayload) {
        QrPayload qr = QrPayload.parse(qrPayload, customerCardRepo);
        CustomerCard cc = customerCardRepo.findByCustomer_IdAndCard_Id(qr.customerId(), qr.cardId())
                .filter(c -> c.getCard().getShop().getId().equals(shop.getId()))
                .orElseThrow(() -> new NoSuchElementException("Karte nicht gefunden"));
        return ansicht(cc);
    }

    @Transactional
    public Ergebnis korrigieren(Shop shop, FilialZugang zugang, String customerCardId, int neu) {
        int vorher = customerCardRepo.findById(customerCardId)
                .filter(c -> c.getCard().getShop().getId().equals(shop.getId()))
                .orElseThrow(() -> new NoSuchElementException("Kundenkarte nicht gefunden"))
                .getStamps();
        CustomerCard cc = kundenService.korrigiereStempel(shop, customerCardId, neu);
        boolean geaendert = cc.getStamps() != vorher;
        if (geaendert) {
            logRepo.save(KorrekturLog.create(shop.getId(), zugang.getOrtId(), cc.getId(),
                    zugang.getBenutzername(), vorher, cc.getStamps()));
        }
        return new Ergebnis(cc, ansicht(cc), geaendert);
    }

    private static Ansicht ansicht(CustomerCard cc) {
        Card card = cc.getCard();
        String hinweis = null;
        if (card.isPoints()) hinweis = "Punkte korrigierst du im Scanner.";
        else if (card.isMember()) hinweis = "Eine Mitgliedskarte hat keine Stempel.";
        else if (card.isPackage()) hinweis = "Das Guthaben der 10er-Karte korrigiert die Zentrale.";
        else if (cc.isWillkommenOffen()) hinweis = "Erst das Willkommensgeschenk an der Kasse einlösen.";
        return new Ansicht(cc.getId(), card.getName(), cc.getStamps(), card.getRewardThreshold(),
                hinweis == null, hinweis);
    }

    /** Eine Zeile des Protokolls fuer das Hauptkonto. */
    public record Eintrag(String wann, String ortId, String ortLabel, String wer,
                          String customerCardId, int vorher, int nachher) {}

    @Transactional(readOnly = true)
    public List<Eintrag> protokoll(Shop shop) {
        Map<String, String> labels = new HashMap<>();
        for (var o : ortRepo.findByShopOrderByCreatedAtAsc(shop)) labels.put(o.getId(), o.getLabel());
        return logRepo.findTop100ByShopIdOrderByErstelltAmDesc(shop.getId()).stream()
                .map(k -> new Eintrag(k.getErstelltAm().toString(),
                        k.getOrtId() != null ? k.getOrtId() : FilialenService.HAUPT,
                        k.getOrtId() == null ? FilialenService.HAUPT_LABEL
                                : labels.getOrDefault(k.getOrtId(), FilialenService.GELOESCHT_LABEL),
                        k.getWer(), k.getCustomerCardId(), k.getVorher(), k.getNachher()))
                .toList();
    }
}
