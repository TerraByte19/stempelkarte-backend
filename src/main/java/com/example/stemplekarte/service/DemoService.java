package com.example.stemplekarte.service;

import com.example.stemplekarte.config.AppProperties;
import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.Customer;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.ScanResult;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.repository.AppleDeviceRepository;
import com.example.stemplekarte.repository.CardRepository;
import com.example.stemplekarte.repository.CustomerCardRepository;
import com.example.stemplekarte.repository.ShopRepository;
import com.example.stemplekarte.wallet.GoogleWalletService;
import com.example.stemplekarte.wallet.WalletNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Demo-Karten fuer die Website: der Admin markiert einen Laden als Demo,
 * dessen Karten stehen oeffentlich unter /api/public/demo. Wer sich eine
 * holt, stempelt sich auf der Kartenseite selbst - ohne Scanner, ohne
 * Standort. Jeder Schritt macht das, was an der Kasse als Naechstes
 * passieren wuerde: Geschenk einloesen, Stempel, Einkauf buchen, Paket
 * aufladen.
 *
 * Testkarten, die aelter als eine Woche sind, raeumt der Dienst nachts weg
 * (Kunde mit, wenn er sonst keine Karte hat).
 */
@Service
public class DemoService {

    private static final Logger log = LoggerFactory.getLogger(DemoService.class);
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,60}");

    static final int MAX_JE_KARTE_UND_TAG = 30;
    static final Duration MIN_ABSTAND = Duration.ofSeconds(3);
    static final int AUFRAEUMEN_TAGE = 7;
    static final long DEMO_BETRAG_CENTS = 1250;
    static final String DEMO_LABEL = "Demo";

    private final ShopRepository shopRepo;
    private final CardRepository cardRepo;
    private final CustomerCardRepository customerCardRepo;
    private final AppleDeviceRepository appleDeviceRepo;
    private final CustomerService customerService;
    private final PointsService pointsService;
    private final PaketService paketService;
    private final WalletNotifier notifier;
    private final AppProperties props;

    // Kundenkarte -> Zeitpunkte der Demo-Schritte (nur die letzten 24 h).
    private final Map<String, Deque<Instant>> takt = new ConcurrentHashMap<>();

    public DemoService(ShopRepository shopRepo, CardRepository cardRepo, CustomerCardRepository customerCardRepo,
                       AppleDeviceRepository appleDeviceRepo, CustomerService customerService,
                       PointsService pointsService, PaketService paketService, WalletNotifier notifier,
                       AppProperties props) {
        this.shopRepo = shopRepo;
        this.cardRepo = cardRepo;
        this.customerCardRepo = customerCardRepo;
        this.appleDeviceRepo = appleDeviceRepo;
        this.customerService = customerService;
        this.pointsService = pointsService;
        this.paketService = paketService;
        this.notifier = notifier;
        this.props = props;
    }

    // ── Oeffentlich ─────────────────────────────────────────────────────

    public record DemoKarte(String cardId, String name, String description, String type, String shopName, String url) {}

    @Transactional(readOnly = true)
    public List<DemoKarte> karten() {
        List<DemoKarte> out = new ArrayList<>();
        for (Shop shop : shopRepo.findByDemoTrue()) {
            if (!shop.isActive()) continue;
            for (Card c : cardRepo.findByShopAndActiveTrue(shop)) {
                out.add(new DemoKarte(c.getId(), c.getName(), c.getDescription(), c.getType().name(),
                        shop.getName(), props.baseUrl() + "/karte-neu/" + c.getId()));
            }
        }
        return out;
    }

    /** Was auf der Kartenseite nach dem Schritt steht. */
    public record Ergebnis(String action, String message, int stamps, int totalRewards, int rewardThreshold,
                           long pointsX100, Integer paketRest) {}

    /** Ein Schritt "wie an der Kasse" auf einer Demo-Karte. Nur Demo-Laeden, gedrosselt. */
    public Ergebnis schritt(String customerId, String cardId, Instant jetzt) {
        if (customerId == null || !ID.matcher(customerId).matches()
                || cardId == null || !ID.matcher(cardId).matches()) {
            throw new IllegalArgumentException("Karte nicht gefunden");
        }
        Card card = cardRepo.findById(cardId).orElseThrow(() -> new NoSuchElementException("Karte nicht gefunden"));
        Shop shop = card.getShop();
        if (shop == null || !shop.isDemo() || !card.isActive()) {
            throw new IllegalArgumentException("Das geht nur bei einer Demo-Karte");
        }
        CustomerCard cc = customerService.getOrCreateCustomerCard(customerId, cardId);
        drosseln(cc.getId(), jetzt);
        String qr = "{\"cid\":\"" + customerId + "\",\"cardId\":\"" + cardId + "\"}";

        if (cc.isWillkommenOffen()) {
            cc = customerService.willkommenEinloesen(qr, shop);
            notifier.nachFreischaltung(cc);
            return ergebnis("welcome", "Willkommensgeschenk eingelöst, Karte freigeschaltet", cc);
        }
        if (card.isPoints()) {
            PointsService.PointsResult e = pointsService.earn(qr, shop, DEMO_BETRAG_CENTS, DEMO_LABEL);
            notifier.nachPunkteAenderung(e.customerCardId(), e.pointsX100(),
                    e.ziel() != null ? e.ziel().name() : null, e.fehlendX100());
            if (e.neuesZielErreicht()) notifier.googleKarteVoll(e.customerCardId(), "Praemie verfuegbar");
            return new Ergebnis("points", "Einkauf über 12,50 € gebucht · " + e.pointsText() + " Punkte",
                    0, 0, 0, e.pointsX100(), null);
        }
        if (card.isPackage() && cc.getPaketRest() <= 0) {
            cc = paketService.aufladen(qr, shop, DEMO_LABEL);
            notifier.nachStempelAenderung(cc, GoogleWalletService.Meldung.AUFGELADEN);
            return ergebnis("topup", PaketService.aufgeladenText(card, cc), cc);
        }
        ScanResult r = customerService.processScan(qr, shop, 1, DEMO_LABEL);
        cc = r.customerCard();
        GoogleWalletService.Meldung meldung = r.rewardsEarnedThisScan() > 0
                ? GoogleWalletService.Meldung.VOLL
                : r instanceof ScanResult.Stamped ? GoogleWalletService.Meldung.STEMPEL
                : r instanceof ScanResult.Visited ? GoogleWalletService.Meldung.BESUCH
                : r instanceof ScanResult.Debited ? GoogleWalletService.Meldung.PAKET
                : GoogleWalletService.Meldung.KEINE;
        notifier.nachStempelAenderung(cc, meldung);
        String action = switch (r) {
            case ScanResult.Stamped s -> "stamped";
            case ScanResult.Full f -> "full";
            case ScanResult.Redeemed d -> "redeemed";
            case ScanResult.Visited v -> "visited";
            case ScanResult.Debited d -> "debited";
            case ScanResult.Empty e -> "empty";
        };
        return ergebnis(action, r.message(), cc);
    }

    private static Ergebnis ergebnis(String action, String message, CustomerCard cc) {
        Card card = cc.getCard();
        return new Ergebnis(action, message, cc.getStamps(), cc.getTotalRewards(), card.getRewardThreshold(),
                cc.getPointsX100(), card.isPackage() ? cc.getPaketRest() : null);
    }

    /** Hoechstens alle paar Sekunden und 30-mal am Tag je Karte - eine Demo, kein Spielautomat. */
    void drosseln(String customerCardId, Instant jetzt) {
        Deque<Instant> zeiten = takt.computeIfAbsent(customerCardId, k -> new ArrayDeque<>());
        synchronized (zeiten) {
            Instant grenze = jetzt.minus(Duration.ofHours(24));
            while (!zeiten.isEmpty() && zeiten.peekFirst().isBefore(grenze)) zeiten.pollFirst();
            if (!zeiten.isEmpty() && zeiten.peekLast().isAfter(jetzt.minus(MIN_ABSTAND))) {
                throw new IllegalArgumentException("Kurz warten, dann noch einmal tippen.");
            }
            if (zeiten.size() >= MAX_JE_KARTE_UND_TAG) {
                throw new IllegalArgumentException("Genug für heute - morgen geht es weiter.");
            }
            zeiten.addLast(jetzt);
        }
    }

    // ── Aufraeumen ──────────────────────────────────────────────────────

    @Scheduled(cron = "0 20 4 * * *", zone = "Europe/Berlin")
    public void aufraeumen() {
        try {
            int n = aufraeumen(Instant.now());
            if (n > 0) log.info("[DEMO] {} Testkarten aufgeräumt", n);
        } catch (Exception e) {
            log.warn("[DEMO] Aufräumen abgebrochen: {}", e.getMessage());
        }
    }

    /** Testkarten aelter als AUFRAEUMEN_TAGE loeschen; den Kunden mit, wenn er sonst keine Karte hat. */
    @Transactional
    public int aufraeumen(Instant jetzt) {
        Instant grenze = jetzt.minus(Duration.ofDays(AUFRAEUMEN_TAGE));
        int geloescht = 0;
        for (Shop shop : shopRepo.findByDemoTrue()) {
            for (Card card : cardRepo.findByShop(shop)) {
                for (CustomerCard cc : customerCardRepo.findByCard(card)) {
                    if (cc.getCreatedAt() == null || !cc.getCreatedAt().isBefore(grenze)) continue;
                    Customer kunde = cc.getCustomer();
                    boolean andere = kunde != null && customerCardRepo.findByCustomer(kunde).stream()
                            .anyMatch(k -> k.getCard() != null && k.getCard().getShop() != null
                                    && !k.getCard().getShop().isDemo());
                    if (kunde != null && !andere) {
                        customerService.deleteCustomerData(kunde.getId());
                    } else {
                        appleDeviceRepo.deleteBySerialNumber(cc.getId());
                        customerCardRepo.delete(cc);
                    }
                    takt.remove(cc.getId());
                    geloescht++;
                }
            }
        }
        return geloescht;
    }
}
