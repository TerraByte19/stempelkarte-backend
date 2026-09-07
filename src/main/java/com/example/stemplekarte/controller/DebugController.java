package com.example.stemplekarte.controller;

import com.example.stemplekarte.config.AppProperties;
import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.Customer;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.AppleDeviceRegistration;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.repository.AppleDeviceRepository;
import com.example.stemplekarte.repository.CardRepository;
import com.example.stemplekarte.repository.CustomerCardRepository;
import com.example.stemplekarte.repository.CustomerRepository;
import com.example.stemplekarte.repository.ScanLogRepository;
import com.example.stemplekarte.repository.ShopRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.env.Environment;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Support-/Debug-Auswertungen. Liegt unter /api/admin -> nur Plattform-Admin
 * (Security-Regel /api/admin/** = ROLE_ADMIN). Nur lesend.
 *
 * Hintergrund: Beschwerde "Stempel kommt beim Kunden nicht an, im System aber
 * schon" entsteht, wenn ein Mensch mehrere customer_cards auf DERSELBEN
 * Laden-Karte hat (mehrfach mit anders geschriebener E-Mail angemeldet).
 * Personal stempelt die eine Karte, der Kunde schaut auf die andere.
 */
@Tag(name = "Debug", description = "Support-Auswertungen (nur Admin, nur lesend)")
@RestController
@RequestMapping("/api/admin/debug")
public class DebugController {

    private final ShopRepository shopRepo;
    private final CardRepository cardRepo;
    private final CustomerCardRepository customerCardRepo;
    private final CustomerRepository customerRepo;
    private final ScanLogRepository scanLogRepo;
    private final AppleDeviceRepository deviceRepo;
    private final AppProperties props;
    private final Environment env;

    public DebugController(ShopRepository shopRepo, CardRepository cardRepo,
                          CustomerCardRepository customerCardRepo,
                          CustomerRepository customerRepo, ScanLogRepository scanLogRepo,
                          AppleDeviceRepository deviceRepo, AppProperties props, Environment env) {
        this.shopRepo = shopRepo;
        this.cardRepo = cardRepo;
        this.customerCardRepo = customerCardRepo;
        this.customerRepo = customerRepo;
        this.scanLogRepo = scanLogRepo;
        this.deviceRepo = deviceRepo;
        this.props = props;
        this.env = env;
    }

    public record CardRow(
            String customerCardId, String customerId,
            String customerName, String email, String normEmail,
            boolean emailConfirmed, int stamps, int totalRewards,
            boolean marketingConsent, Instant createdAt, Instant updatedAt) {}

    public record CardBlock(String cardId, String cardName, int rewardThreshold,
                            boolean active, List<CardRow> customerCards) {}

    public record DuplicateGroup(String person, String cardId, String cardName,
                                 int totalCards, List<CardRow> cards) {}

    public record ScanRow(Instant scannedAt, String cardId, String customerId,
                          int stampsAdded, int rewardsEarned) {}

    public record ShopReport(String shopId, String shopName, String shopEmail,
                             boolean active, String language,
                             int cardCount, int customerCardCount,
                             List<CardBlock> cards,
                             List<DuplicateGroup> likelyDuplicates,
                             List<ScanRow> recentScans,
                             String error) {}

    @Operation(summary = "Laden(en) je Namens-Teil auswerten: customer_cards, Dubletten, Scans")
    @GetMapping("/dupes")
    @Transactional(readOnly = true)
    public Map<String, Object> dupes(
            @RequestParam(name = "shop") String shopQuery,
            @RequestParam(name = "scanLimit", defaultValue = "150") int scanLimit) {

        String needle = shopQuery.toLowerCase().trim();

        List<Shop> shops = shopRepo.findAll().stream()
                .filter(s -> s.getName() != null && s.getName().toLowerCase().contains(needle))
                .sorted(Comparator.comparing(Shop::getName))
                .toList();

        List<ShopReport> reports = new ArrayList<>();
        for (Shop shop : shops) {
            try {
                reports.add(buildReport(shop, scanLimit));
            } catch (Exception e) {
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                reports.add(new ShopReport(shop.getId(), shop.getName(), shop.getEmail(),
                        shop.isActive(), safeLang(shop), 0, 0,
                        List.of(), List.of(), List.of(),
                        sw.toString().lines().limit(12).collect(Collectors.joining(" | "))));
            }
        }

        Map<String, Object> out = new TreeMap<>();
        out.put("query", shopQuery);
        out.put("matchedShops", reports.size());
        out.put("reports", reports);
        return out;
    }

    private ShopReport buildReport(Shop shop, int scanLimit) {
        List<Card> cards = cardRepo.findByShop(shop);
        cards.sort(Comparator.comparing(Card::getName, Comparator.nullsLast(String::compareTo)));

        // 1. alle customer_cards je Karte einsammeln (nur IDs vom Kunden - safe, kein Lazy-Load)
        Map<String, List<CustomerCard>> ccByCard = new LinkedHashMap<>();
        Set<String> customerIds = new java.util.HashSet<>();
        for (Card card : cards) {
            List<CustomerCard> ccs = customerCardRepo.findByCard(card);
            ccByCard.put(card.getId(), ccs);
            for (CustomerCard cc : ccs) customerIds.add(cc.getCustomer().getId());
        }

        // 2. Kunden in EINEM Rutsch laden (voll initialisiert, kein Lazy)
        Map<String, Customer> custById = customerRepo.findAllById(customerIds).stream()
                .collect(Collectors.toMap(Customer::getId, c -> c));

        List<CardBlock> cardBlocks = new ArrayList<>();
        int ccTotal = 0;
        Map<String, List<CardRow>> byPersonAndCard = new LinkedHashMap<>();

        for (Card card : cards) {
            List<CustomerCard> ccs = new ArrayList<>(ccByCard.getOrDefault(card.getId(), List.of()));
            ccs.sort(Comparator.comparing(CustomerCard::getCreatedAt,
                    Comparator.nullsLast(Comparator.naturalOrder())));
            ccTotal += ccs.size();

            List<CardRow> rows = new ArrayList<>();
            for (CustomerCard cc : ccs) {
                Customer cust = custById.get(cc.getCustomer().getId());
                String email = cust != null ? cust.getEmail() : null;
                String name = cust != null ? cust.getName() : "(unbekannt)";
                boolean confirmed = cust != null && cust.isEmailConfirmed();
                String norm = normEmail(email);
                CardRow row = new CardRow(
                        cc.getId(), cc.getCustomer().getId(), name, email, norm,
                        confirmed, cc.getStamps(), cc.getTotalRewards(),
                        cc.isMarketingConsent(), cc.getCreatedAt(), cc.getUpdatedAt());
                rows.add(row);
                byPersonAndCard
                        .computeIfAbsent(norm + " @@ " + card.getId(), k -> new ArrayList<>())
                        .add(row);
            }
            cardBlocks.add(new CardBlock(card.getId(), card.getName(),
                    card.getRewardThreshold(), card.isActive(), rows));
        }

        List<DuplicateGroup> dups = new ArrayList<>();
        for (var e : byPersonAndCard.entrySet()) {
            if (e.getValue().size() < 2) continue;
            String[] parts = e.getKey().split(" @@ ", 2);
            String cardId = parts.length > 1 ? parts[1] : "?";
            String cardName = cards.stream().filter(c -> c.getId().equals(cardId))
                    .map(Card::getName).findFirst().orElse("?");
            dups.add(new DuplicateGroup(parts[0], cardId, cardName, e.getValue().size(), e.getValue()));
        }

        List<ScanRow> scans = scanLogRepo.findByShopIdOrderByScannedAtDesc(shop.getId()).stream()
                .limit(Math.max(0, scanLimit))
                .map(sl -> new ScanRow(sl.getScannedAt(), sl.getCardId(),
                        sl.getCustomerId(), sl.getStampsAdded(), sl.getRewardsEarned()))
                .toList();

        return new ShopReport(shop.getId(), shop.getName(), shop.getEmail(), shop.isActive(),
                safeLang(shop), cards.size(), ccTotal, cardBlocks, dups, scans, null);
    }

    private static String safeLang(Shop s) {
        try { return s.getLanguageOrDefault(); } catch (Exception e) { return null; }
    }

    // ── Apple-Wallet-Diagnose ────────────────────────────────────────────
    // Beschwerde "Stempel im System, aber nicht auf dem iPhone" - hier
    // sichtbar: stimmt die webServiceURL im Pass (falsches Profil -> localhost),
    // laeuft APNs ueberhaupt, und haben frische Karten eine Geraete-
    // Registrierung (ohne die kann iOS die Karte NIE nachladen).

    // Bei jedem Wallet-Fix-Push hochzaehlen -> zeigt zweifelsfrei, ob der
    // aktuelle Stand auf Render live ist.
    private static final String WALLET_FIX_VERSION = "v3 (Push-bei-Register + 5 Fixes)";

    public record WalletConfig(
            String codeVersion,
            String activeProfiles, String baseUrl, String computedWebServiceUrl,
            boolean webServiceUrlHttps, String passTypeIdentifier,
            boolean apnsEnabled, boolean apnsSandbox,
            boolean apnsKeyIdSet, boolean apnsTeamIdSet, boolean apnsAuthKeyFileExists,
            boolean signingCertB64Exists, boolean wwdrCertExists) {}

    public record DeviceRow(String deviceIdMasked, String pushTokenMasked, Instant registeredAt) {}

    public record WalletCardRow(
            String customerCardId, String customerName, int stamps, int totalRewards,
            Instant createdAt, Instant updatedAt,
            int deviceCount, List<DeviceRow> devices) {}

    public record WalletShopReport(String shopId, String shopName, boolean active,
                                   int cardsChecked, int cardsWithoutDevice, int cardsWithDevice,
                                   List<WalletCardRow> cards, String error) {}

    @Operation(summary = "Apple-Wallet-Diagnose: webServiceURL, APNs-Konfig, Geraete-Registrierungen je Karte")
    @GetMapping("/wallet")
    @Transactional(readOnly = true)
    public Map<String, Object> wallet(
            @RequestParam(name = "shop") String shopQuery,
            @RequestParam(name = "limit", defaultValue = "40") int limit) {
        try {
            return walletInner(shopQuery, limit);
        } catch (Exception e) {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            Map<String, Object> err = new TreeMap<>();
            err.put("FATAL", sw.toString().lines().limit(25).collect(Collectors.joining("\n")));
            return err;
        }
    }

    private Map<String, Object> walletInner(String shopQuery, int limit) {
        String base = props.baseUrl();
        String wsUrl = (base != null ? base : "") + "/wallet/";
        WalletConfig cfg = new WalletConfig(
                WALLET_FIX_VERSION,
                String.join(",", env.getActiveProfiles()),
                base, wsUrl, wsUrl.startsWith("https://"),
                props.apple() != null ? props.apple().passTypeIdentifier() : null,
                props.apns() != null && props.apns().enabled(),
                props.apns() != null && props.apns().useSandbox(),
                props.apns() != null && notBlank(props.apns().keyId()),
                props.apns() != null && notBlank(props.apns().teamId()),
                props.apns() != null && fileExists(props.apns().authKeyPath()),
                fileExists("/etc/secrets/pass-certificate.b64"),
                fileExists("/etc/secrets/apple-wwdr.pem")
        );

        String needle = shopQuery.toLowerCase().trim();
        List<Shop> shops = shopRepo.findAll().stream()
                .filter(s -> s.getName() != null && s.getName().toLowerCase().contains(needle))
                .sorted(Comparator.comparing(Shop::getName))
                .toList();

        List<WalletShopReport> reports = new ArrayList<>();
        for (Shop shop : shops) {
            try {
                List<Card> cards = cardRepo.findByShop(shop);
                List<CustomerCard> ccs = new ArrayList<>();
                for (Card c : cards) ccs.addAll(customerCardRepo.findByCard(c));
                ccs.sort(Comparator.comparing(CustomerCard::getCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())));
                if (ccs.size() > limit) ccs = ccs.subList(0, limit);

                Set<String> customerIds = ccs.stream()
                        .map(cc -> cc.getCustomer().getId()).collect(Collectors.toSet());
                Map<String, Customer> custById = customerRepo.findAllById(customerIds).stream()
                        .collect(Collectors.toMap(Customer::getId, x -> x));

                List<WalletCardRow> rows = new ArrayList<>();
                int without = 0, with = 0;
                for (CustomerCard cc : ccs) {
                    List<AppleDeviceRegistration> regs = deviceRepo.findBySerialNumber(cc.getId());
                    if (regs.isEmpty()) without++; else with++;
                    List<DeviceRow> drs = regs.stream().map(r -> new DeviceRow(
                            mask(r.getDeviceLibraryIdentifier()), mask(r.getPushToken()),
                            r.getRegisteredAt())).toList();
                    Customer cust = custById.get(cc.getCustomer().getId());
                    rows.add(new WalletCardRow(cc.getId(),
                            cust != null ? cust.getName() : "(unbekannt)",
                            cc.getStamps(), cc.getTotalRewards(),
                            cc.getCreatedAt(), cc.getUpdatedAt(), regs.size(), drs));
                }
                reports.add(new WalletShopReport(shop.getId(), shop.getName(), shop.isActive(),
                        rows.size(), without, with, rows, null));
            } catch (Exception e) {
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                reports.add(new WalletShopReport(shop.getId(), shop.getName(), shop.isActive(),
                        0, 0, 0, List.of(),
                        sw.toString().lines().limit(12).collect(Collectors.joining(" | "))));
            }
        }

        Map<String, Object> out = new TreeMap<>();
        out.put("config", cfg);
        out.put("query", shopQuery);
        out.put("matchedShops", reports.size());
        out.put("reports", reports);
        return out;
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }

    private static boolean fileExists(String path) {
        try { return path != null && java.nio.file.Files.exists(java.nio.file.Path.of(path)); }
        catch (Exception e) { return false; }
    }

    private static String mask(String s) {
        if (s == null) return null;
        if (s.length() <= 10) return s.substring(0, Math.min(4, s.length())) + "...";
        return s.substring(0, 6) + "..." + s.substring(s.length() - 4);
    }

    /**
     * Normalisiert eine E-Mail: lowercase, trim; bei gmail/googlemail zusaetzlich
     * Punkte im lokalen Teil entfernen und alles ab '+' abschneiden.
     */
    static String normEmail(String email) {
        if (email == null) return "";
        String e = email.toLowerCase().trim();
        int at = e.indexOf('@');
        if (at <= 0) return e;
        String local = e.substring(0, at);
        String domain = e.substring(at + 1);
        int plus = local.indexOf('+');
        if (plus >= 0) local = local.substring(0, plus);
        if (domain.equals("gmail.com") || domain.equals("googlemail.com")) {
            local = local.replace(".", "");
            domain = "gmail.com";
        }
        return local + "@" + domain;
    }
}
