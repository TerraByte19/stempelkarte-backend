package com.example.stemplekarte.service;

import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.ScanLog;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.repository.CardRepository;
import com.example.stemplekarte.repository.CustomerCardRepository;
import com.example.stemplekarte.repository.ScanLogRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Baut die Shop-Gesamt-Statistik (fuer /api/shop/stats/summary UND das
 * Admin-Panel, das dieselben Zahlen fuer beliebige Laeden sehen will).
 */
@Service
public class StatsService {

    // Alle Laeden sind in Deutschland - Statistik-Zeitzone fest. (Der Server
    // laeuft auf Render in UTC; ZoneId.systemDefault() verschob "beste Stunde"
    // um 1-2 h und schob Mitternachts-Scans auf den falschen Tag.)
    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");

    // Tages-Verlauf (Balkendiagramme) zeigt nur die letzten 2 Wochen - 30 Tage
    // waren unuebersichtlich. Wochentag/Stunden-Auswertung ("staerkster Tag")
    // rechnet weiterhin mit einem groesseren Sample (30 Tage).
    private static final int HISTORY_DAYS = 14;
    private static final int SAMPLE_DAYS = 30;

    private final CardService cardService;
    private final CardRepository cardRepo;
    private final CustomerCardRepository customerCardRepo;
    private final ScanLogRepository scanLogRepo;

    public StatsService(CardService cardService, CardRepository cardRepo,
                        CustomerCardRepository customerCardRepo, ScanLogRepository scanLogRepo) {
        this.cardService = cardService;
        this.cardRepo = cardRepo;
        this.customerCardRepo = customerCardRepo;
        this.scanLogRepo = scanLogRepo;
    }

    public Map<String, Object> summary(Shop shop) {
        // ALLE Karten (auch deaktivierte) fuer die Lebenszeit-Summen - sonst
        // verschwinden Kunden/Stempel, sobald ein Laden eine Karte "loescht"
        // (= deaktiviert). Die perCard-Liste unten zeigt nur die aktiven.
        List<Card> alleKarten = cardRepo.findByShop(shop);

        // "vergeben" = alle je vergebenen Stempel. CustomerCard.stamps wird beim
        // Einloesen auf 0 gesetzt; die eingeloesten Stempel rechnen wir ueber
        // totalRewards*threshold zurueck. offeneStempel = aktueller Wallet-Stand
        // (kann sinken - das ist eine Verbindlichkeit, keine Aktivitaet).
        long stampsGranted = 0;
        long openStamps = 0;
        long einloesungen = 0;
        long cardRows = 0;              // customer_card-Zeilen (fuer Fuellgrad-Schnitt)

        int customersNearReward = 0;   // Karten >= 80% und noch nicht am Ziel
        int customersWithConsent = 0;  // customer_card-Zeilen mit Marketing-Einwilligung
        double fillSum = 0;            // Summe der Fuellgrade (pro customer_card)

        Instant now = Instant.now();
        Instant sampleWindowAgo = now.minus(SAMPLE_DAYS, java.time.temporal.ChronoUnit.DAYS);
        Instant sevenDaysAgo  = now.minus(7,  java.time.temporal.ChronoUnit.DAYS);
        Instant fourteenDaysAgo = now.minus(14, java.time.temporal.ChronoUnit.DAYS);

        // Tages-Fenster fuer die Verlaufs-Diagramme (Stempel + Neukunden).
        LocalDate von = now.minus(HISTORY_DAYS - 1L, java.time.temporal.ChronoUnit.DAYS).atZone(ZONE).toLocalDate();
        LocalDate bis = now.atZone(ZONE).toLocalDate();
        Map<String, int[]> byDay = new TreeMap<>();          // date -> [stamps, rewards]
        Map<String, Integer> newCustomersByDay = new TreeMap<>();
        for (LocalDate d = von; !d.isAfter(bis); d = d.plusDays(1)) {
            byDay.put(d.toString(), new int[2]);
            newCustomersByDay.put(d.toString(), 0);
        }

        List<Map<String, Object>> perCard = new ArrayList<>();
        int newThisWeek = 0, newLastWeek = 0;

        for (Card card : alleKarten) {
            List<CustomerCard> ccs = customerCardRepo.findByCard(card);
            int threshold = Math.max(1, card.getRewardThreshold());
            int stamps = ccs.stream().mapToInt(CustomerCard::getStamps).sum();
            int rewards = ccs.stream().mapToInt(CustomerCard::getTotalRewards).sum();

            openStamps    += stamps;
            einloesungen  += rewards;
            stampsGranted += (long) stamps + (long) rewards * threshold;
            cardRows      += ccs.size();

            for (CustomerCard cc : ccs) {
                if (cc.getStamps() >= threshold * 0.8 && cc.getStamps() < threshold) customersNearReward++;
                if (cc.isMarketingConsent()) customersWithConsent++;
                fillSum += Math.min(1.0, (double) cc.getStamps() / threshold);

                Instant created = cc.getCreatedAt();
                if (created != null) {
                    if (created.isAfter(sevenDaysAgo)) newThisWeek++;
                    else if (created.isAfter(fourteenDaysAgo)) newLastWeek++;

                    String day = created.atZone(ZONE).toLocalDate().toString();
                    Integer cur = newCustomersByDay.get(day);
                    if (cur != null) newCustomersByDay.put(day, cur + 1);
                }
            }

            if (card.isActive()) {
                Map<String, Object> cardMap = new HashMap<>();
                cardMap.put("cardId", card.getId());
                cardMap.put("cardName", card.getName());
                cardMap.put("customerCount", ccs.size());
                cardMap.put("totalStamps", stamps);
                cardMap.put("totalRewards", rewards);
                cardMap.put("rewardThreshold", card.getRewardThreshold());
                perCard.add(cardMap);
            }
        }

        // Personen statt customer_card-Zeilen (bei mehreren Karten pro Laden
        // sonst dieselbe Person mehrfach).
        long customerCount = customerCardRepo.countDistinctCustomersByShop(shop);
        long customersWithReward = customerCardRepo.countDistinctCustomersWithRewardByShop(shop);
        // "aktiv" ehrlich: mindestens ein Scan in 30 Tagen (nicht: Karte in den
        // 30 Tagen angelegt - das war der alte updatedAt-Weg).
        long activeCustomers30d = scanLogRepo.countDistinctCustomersSince(shop.getId(), sampleWindowAgo);

        double avgStampsPerCustomer = customerCount > 0
                ? Math.round((double) stampsGranted / customerCount * 10) / 10.0 : 0;
        int redemptionRate = customerCount > 0
                ? (int) Math.round((double) customersWithReward / customerCount * 100) : 0;
        int avgFillPercent = cardRows > 0
                ? (int) Math.round(fillSum / cardRows * 100) : 0;

        // Sample (30 Tage) aus dem ScanLog - fuer Wochentag/Stunden-Auswertung
        // und Monats-Summen. Bewusst groesser als das 2-Wochen-Verlaufsfenster,
        // damit "staerkster Tag" nicht auf zu wenig Daten steht.
        List<ScanLog> recent = scanLogRepo
                .findByShopIdAndScannedAtAfterOrderByScannedAtAsc(shop.getId(), sampleWindowAgo);

        int stampsThisWeek = 0, stampsThisMonth = 0;
        int rewardsThisWeek = 0, rewardsThisMonth = 0;
        int[] byWeekday = new int[8];   // Index 1-7
        int[] byHour = new int[24];

        for (ScanLog sl : recent) {
            stampsThisMonth += sl.getStampsAdded();
            rewardsThisMonth += sl.getRewardsEarned();
            if (sl.getScannedAt().isAfter(sevenDaysAgo)) {
                stampsThisWeek += sl.getStampsAdded();
                rewardsThisWeek += sl.getRewardsEarned();
            }
            var zdt = sl.getScannedAt().atZone(ZONE);
            byWeekday[zdt.getDayOfWeek().getValue()] += sl.getStampsAdded();
            byHour[zdt.getHour()] += sl.getStampsAdded();

            // Nur Tage innerhalb des 2-Wochen-Verlaufsfensters zaehlen - der Rest
            // vom 30-Tage-Sample ist nur fuer Wochentag/Stunde/Monat gedacht.
            String day = zdt.toLocalDate().toString();
            int[] v = byDay.get(day);
            if (v != null) {
                v[0] += sl.getStampsAdded();
                v[1] += sl.getRewardsEarned();
            }
        }

        String[] dayNames = {"", "Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag", "Samstag", "Sonntag"};
        String bestDay = null;
        int bestDayCount = 0;
        for (int i = 1; i <= 7; i++) if (byWeekday[i] > bestDayCount) { bestDayCount = byWeekday[i]; bestDay = dayNames[i]; }
        int bestHour = -1, bestHourCount = 0;
        for (int h = 0; h < 24; h++) if (byHour[h] > bestHourCount) { bestHourCount = byHour[h]; bestHour = h; }

        List<Map<String, Object>> history = new ArrayList<>();
        for (var e : byDay.entrySet()) {
            Map<String, Object> dayMap = new HashMap<>();
            dayMap.put("date", e.getKey());
            dayMap.put("stamps", e.getValue()[0]);
            dayMap.put("rewards", e.getValue()[1]);
            history.add(dayMap);
        }

        List<Map<String, Object>> newCustomerHistory = new ArrayList<>();
        int newCustomersInHistory = 0;
        for (var e : newCustomersByDay.entrySet()) {
            Map<String, Object> dayMap = new HashMap<>();
            dayMap.put("date", e.getKey());
            dayMap.put("count", e.getValue());
            newCustomerHistory.add(dayMap);
            newCustomersInHistory += e.getValue();
        }

        Map<String, Object> summary = new HashMap<>();
        summary.put("shopName", shop.getName());
        summary.put("totalCards", cardService.getByShop(shop).size());  // nur aktive
        summary.put("totalCustomers", customerCount);                   // Personen
        summary.put("totalStamps", stampsGranted);                      // je vergeben (Lebenszeit)
        summary.put("openStamps", openStamps);                          // aktueller Wallet-Stand
        summary.put("totalRewards", einloesungen);                      // Einloesungen
        summary.put("avgStampsPerCustomer", avgStampsPerCustomer);
        summary.put("redemptionRate", redemptionRate);
        summary.put("customersNearReward", customersNearReward);
        summary.put("avgFillPercent", avgFillPercent);
        summary.put("customersWithConsent", customersWithConsent);
        summary.put("activeCustomers30d", activeCustomers30d);
        summary.put("stampsThisWeek", stampsThisWeek);
        summary.put("stampsThisMonth", stampsThisMonth);
        summary.put("rewardsThisWeek", rewardsThisWeek);
        summary.put("rewardsThisMonth", rewardsThisMonth);
        summary.put("bestDay", bestDay);
        summary.put("bestHour", bestHour);
        summary.put("newCustomersThisWeek", newThisWeek);
        summary.put("newCustomersLastWeek", newLastWeek);
        summary.put("newCustomersInHistory", newCustomersInHistory); // Summe ueber die 2-Wochen-Kachel
        summary.put("perCard", perCard);
        summary.put("history", history);                 // 2 Wochen, luecklos
        summary.put("newCustomerHistory", newCustomerHistory); // 2 Wochen, luecklos

        // Stempel je Wochentag (Mo..So) und je Stunde (0..23) ueber die 30 Tage
        // - fuer "ruhigster Tag" und die Stunden-Uebersicht im Frontend.
        int[] byWeekdayOut = new int[7];
        for (int i = 1; i <= 7; i++) byWeekdayOut[i - 1] = byWeekday[i];
        summary.put("byWeekday", byWeekdayOut);
        summary.put("byHour", byHour);
        return summary;
    }

    // Tages-Detail: Stunden-Verteilung der Stempel fuer EINEN Tag. Wird
    // aufgerufen, wenn im Verlaufs-Diagramm ein Balken angetippt wird.
    public Map<String, Object> day(Shop shop, LocalDate date) {
        Instant from = date.atStartOfDay(ZONE).toInstant();
        Instant to = date.plusDays(1).atStartOfDay(ZONE).toInstant();

        List<ScanLog> logs = scanLogRepo
                .findByShopIdAndScannedAtBetweenOrderByScannedAtAsc(shop.getId(), from, to);

        int[] byHour = new int[24];
        int stamps = 0, rewards = 0;
        for (ScanLog sl : logs) {
            int h = sl.getScannedAt().atZone(ZONE).getHour();
            byHour[h] += sl.getStampsAdded();
            stamps += sl.getStampsAdded();
            rewards += sl.getRewardsEarned();
        }

        Map<String, Object> out = new HashMap<>();
        out.put("date", date.toString());
        out.put("byHour", byHour);
        out.put("stamps", stamps);
        out.put("rewards", rewards);
        return out;
    }
}
