package com.example.stemplekarte.service;

import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.ScanLog;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.repository.CardRepository;
import com.example.stemplekarte.repository.CustomerCardRepository;
import com.example.stemplekarte.repository.CustomerRepository;
import com.example.stemplekarte.model.Customer;
import com.example.stemplekarte.model.BookingKind;
import com.example.stemplekarte.model.PointsBooking;
import com.example.stemplekarte.repository.PointsBookingRepository;
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
    private final CustomerRepository customerRepo;
    private final ScanLogRepository scanLogRepo;
    private final PointsBookingRepository pointsBookingRepo;

    public StatsService(CardService cardService, CardRepository cardRepo,
                        CustomerCardRepository customerCardRepo, CustomerRepository customerRepo,
                        ScanLogRepository scanLogRepo, PointsBookingRepository pointsBookingRepo) {
        this.pointsBookingRepo = pointsBookingRepo;
        this.cardService = cardService;
        this.cardRepo = cardRepo;
        this.customerCardRepo = customerCardRepo;
        this.customerRepo = customerRepo;
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
        // Was die Kunden auf Punktekarten noch einloesen duerfen. Die
        // betriebswirtschaftlich wichtigste Zahl und bisher unsichtbar:
        // sie sagt dem Laden, welche Verpflichtung er angesammelt hat.
        long punkteAusstehendGesamt = 0;

        int customersNearReward = 0;   // Karten >= 80% und noch nicht am Ziel
        int customersWithConsent = 0;  // customer_card-Zeilen mit Marketing-Einwilligung
        double fillSum = 0;            // Summe der Fuellgrade (pro customer_card)

        Instant now = Instant.now();
        Instant sampleWindowAgo = now.minus(SAMPLE_DAYS, java.time.temporal.ChronoUnit.DAYS);
        Instant sevenDaysAgo  = now.minus(7,  java.time.temporal.ChronoUnit.DAYS);
        Instant fourteenDaysAgo = now.minus(14, java.time.temporal.ChronoUnit.DAYS);
        // Vormonat (Tag 31-60) nur fuer den Vergleich "diesen Monat vs. letzten".
        Instant prevMonthAgo = now.minus(2L * SAMPLE_DAYS, java.time.temporal.ChronoUnit.DAYS);

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
            int rewards = ccs.stream().mapToInt(CustomerCard::getTotalRewards).sum();

            // Neukunden und Einwilligungen gelten fuer BEIDE Kartentypen.
            for (CustomerCard cc : ccs) {
                if (cc.isMarketingConsent()) customersWithConsent++;

                Instant created = cc.getCreatedAt();
                if (created != null) {
                    if (created.isAfter(sevenDaysAgo)) newThisWeek++;
                    else if (created.isAfter(fourteenDaysAgo)) newLastWeek++;

                    String day = created.atZone(ZONE).toLocalDate().toString();
                    Integer cur = newCustomersByDay.get(day);
                    if (cur != null) newCustomersByDay.put(day, cur + 1);
                }
            }

            einloesungen += rewards;

            if (card.isMember() || card.isPackage()) {
                // Mitglieds- und 10er-Karte: keine Stempel, keine Praemien - nur
                // Besuche; die 10er-Karte dazu Pakete und offenes Guthaben.
                if (card.isActive()) {
                    Map<String, Object> cardMap = new HashMap<>();
                    cardMap.put("cardId", card.getId());
                    cardMap.put("cardName", card.getName());
                    cardMap.put("type", card.isMember() ? "MEMBER" : "PACKAGE");
                    cardMap.put("customerCount", ccs.size());
                    cardMap.put("totalStamps", 0);
                    cardMap.put("totalRewards", 0);
                    cardMap.put("rewardThreshold", card.isPackage() ? card.getRewardThreshold() : 0);
                    cardMap.put("besuche", ccs.stream().mapToInt(CustomerCard::getStamps).sum());
                    if (card.isPackage()) {
                        cardMap.put("paketEinheit", card.getPaketEinheit());
                        cardMap.put("paketeGekauft", ccs.stream().mapToInt(CustomerCard::getPaketeGekauft).sum());
                        cardMap.put("restGesamt", ccs.stream().mapToInt(CustomerCard::getPaketRest).sum());
                        cardMap.put("leer", ccs.stream()
                                .filter(cc -> cc.getPaketeGekauft() >= 1 && cc.getPaketRest() <= 0).count());
                    }
                    perCard.add(cardMap);
                }
                continue;
            }

            if (card.isPoints()) {
                // Stempel-Kennzahlen gelten hier nicht. Frueher lief jede Karte
                // durch dieselbe Schleife, und weil eine Punktekarte
                // reward_threshold = 1 traegt, erhoehte dort jede eingeloeste
                // Praemie die "vergebenen Stempel" um eins.
                punkteAusstehendGesamt += punkteKarte(card, ccs, perCard);
                continue;
            }

            int threshold = Math.max(1, card.getRewardThreshold());
            int stamps = ccs.stream().mapToInt(CustomerCard::getStamps).sum();

            openStamps    += stamps;
            stampsGranted += (long) stamps + (long) rewards * threshold;
            cardRows      += ccs.size();

            for (CustomerCard cc : ccs) {
                if (cc.getStamps() >= threshold * 0.8 && cc.getStamps() < threshold) customersNearReward++;
                fillSum += Math.min(1.0, (double) cc.getStamps() / threshold);
            }

            if (card.isActive()) {
                Map<String, Object> cardMap = new HashMap<>();
                cardMap.put("cardId", card.getId());
                cardMap.put("cardName", card.getName());
                cardMap.put("type", "STAMP");
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
        // Geholt werden 60 Tage: die zweite Haelfte dient nur dem Vergleich
        // zum Vormonat und bleibt aus Wochentag/Stunde/Verlauf draussen.
        List<ScanLog> recent = scanLogRepo
                .findByShopIdAndScannedAtAfterOrderByScannedAtAsc(shop.getId(), prevMonthAgo);

        int stampsThisWeek = 0, stampsThisMonth = 0;
        int rewardsThisWeek = 0, rewardsThisMonth = 0;
        // Vorzeitraeume: Tag 8-14 und Tag 31-60. Daraus macht das Dashboard
        // "+12 % gegenueber letzter Woche".
        int stampsLastWeek = 0, stampsPrevMonth = 0;
        int rewardsLastWeek = 0, rewardsPrevMonth = 0;
        // Praemien je Stempelkarte in 30 Tagen, fuer die Rangliste ueber alle Karten.
        Map<String, Integer> rewardsByCard = new HashMap<>();
        int[] byWeekday = new int[8];   // Index 1-7
        int[] byHour = new int[24];

        for (ScanLog sl : recent) {
            if (!sl.getScannedAt().isAfter(sampleWindowAgo)) {
                stampsPrevMonth += sl.getStampsAdded();
                rewardsPrevMonth += sl.getRewardsEarned();
                continue;
            }
            stampsThisMonth += sl.getStampsAdded();
            rewardsThisMonth += sl.getRewardsEarned();
            if (sl.getRewardsEarned() > 0 && sl.getCardId() != null) {
                rewardsByCard.merge(sl.getCardId(), sl.getRewardsEarned(), Integer::sum);
            }
            if (sl.getScannedAt().isAfter(sevenDaysAgo)) {
                stampsThisWeek += sl.getStampsAdded();
                rewardsThisWeek += sl.getRewardsEarned();
            } else if (sl.getScannedAt().isAfter(fourteenDaysAgo)) {
                stampsLastWeek += sl.getStampsAdded();
                rewardsLastWeek += sl.getRewardsEarned();
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

        // 30-Tage-Fenster wie beim ScanLog-Sample, damit beide Auswertungen
        // denselben Zeitraum meinen.
        List<PointsBooking> buchungen = pointsBookingRepo
                .findByShopIdAndCreatedAtAfterOrderByCreatedAtAsc(shop.getId(), sampleWindowAgo);

        long punkteUmsatzCents = buchungen.stream()
                .filter(b -> b.getKind() == BookingKind.EARN && b.getAmountCents() != null)
                .mapToLong(PointsBooking::getAmountCents).sum();

        long punkteVergeben = buchungen.stream()
                .filter(b -> b.getKind() == BookingKind.EARN)
                .mapToLong(PointsBooking::getDeltaPointsX100).sum();

        // Nur nicht zurueckgenommene Einloesungen: eine Praemie, die
        // rueckgaengig gemacht wurde, hat der Kunde nie bekommen und gehoert
        // nicht in die Rangliste.
        Map<String, Long> topRewards = buchungen.stream()
                .filter(b -> b.getKind() == BookingKind.REDEEM && !b.istZurueckgenommen())
                .filter(b -> b.getRewardName() != null)
                .collect(java.util.stream.Collectors.groupingBy(
                        PointsBooking::getRewardName, java.util.stream.Collectors.counting()));

        // Rangliste ueber BEIDE Kartentypen, 30 Tage: Stempelkarten aus dem
        // ScanLog (eine Praemie je Karte), Punktekarten aus den Buchungen.
        // Bisher gab es sie nur fuer Punktekarten - ein Laden mit zwei
        // Stempelkarten sah nicht, welche besser laeuft.
        List<Map<String, Object>> topRewardsAll = new ArrayList<>();
        for (Card card : alleKarten) {
            if (card.isPoints()) continue;
            int n = rewardsByCard.getOrDefault(card.getId(), 0);
            if (n > 0) topRewardsAll.add(praemienZeile(card.getRewardText(), card.getName(), n));
        }
        topRewards.forEach((name, n) -> topRewardsAll.add(praemienZeile(name, null, n)));
        topRewardsAll.sort((a, b) -> Long.compare((long) b.get("count"), (long) a.get("count")));
        List<Map<String, Object>> topRewardsTop = topRewardsAll.size() > 5
                ? new ArrayList<>(topRewardsAll.subList(0, 5)) : topRewardsAll;

        Map<String, Object> summary = new HashMap<>();
        summary.put("shopName", shop.getName());
        summary.put("topRewardsAll", topRewardsTop);
        // Einstellung des Ladens, nicht des Betrachters - das Admin-Panel zeigt
        // nur diesen Wert an (kein eigener Schalter dort).
        summary.put("excludeSunday", Boolean.TRUE.equals(shop.getExcludeSundayFromStats()));
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
        summary.put("stampsLastWeek", stampsLastWeek);      // Tag 8-14
        summary.put("rewardsLastWeek", rewardsLastWeek);
        summary.put("stampsPrevMonth", stampsPrevMonth);    // Tag 31-60
        summary.put("rewardsPrevMonth", rewardsPrevMonth);
        summary.put("bestDay", bestDay);
        summary.put("bestHour", bestHour);
        summary.put("newCustomersThisWeek", newThisWeek);
        summary.put("newCustomersLastWeek", newLastWeek);
        summary.put("newCustomersInHistory", newCustomersInHistory); // Summe ueber die 2-Wochen-Kachel
        summary.put("pointsRevenueCents", punkteUmsatzCents);
        summary.put("pointsGrantedX100", punkteVergeben);
        summary.put("pointsOutstandingX100", punkteAusstehendGesamt);
        summary.put("topRewards", topRewards);
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

    /**
     * Kennzahlen einer Punktekarte. Bewusst getrennt von den Stempel-Summen:
     * Stempel und Punkte sind verschiedene Einheiten und gehoeren nicht in
     * dieselbe Saeule.
     *
     * Gibt den ausstehenden Bestand zurueck, damit die Schleife ihn
     * aufsummieren kann - auch von deaktivierten Karten: eingeloest werden
     * duerfen die Punkte trotzdem noch, die Verpflichtung verschwindet nicht
     * mit dem Ausblenden der Karte.
     */
    private long punkteKarte(Card card, List<CustomerCard> ccs,
                             List<Map<String, Object>> perCard) {
        long ausstehend = ausstehendePunkte(
                ccs.stream().map(CustomerCard::getPointsX100).toList());
        int einloesungen = ccs.stream().mapToInt(CustomerCard::getTotalRewards).sum();

        if (card.isActive()) {
            Map<String, Object> m = new HashMap<>();
            m.put("cardId", card.getId());
            m.put("cardName", card.getName());
            m.put("type", "POINTS");
            m.put("customerCount", ccs.size());
            m.put("pointsOutstandingX100", ausstehend);
            m.put("totalRewards", einloesungen);
            perCard.add(m);
        }
        return ausstehend;
    }

    private static Map<String, Object> praemienZeile(String name, String karte, long count) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", name != null ? name : "");
        m.put("card", karte);
        m.put("count", count);
        return m;
    }

    /**
     * Scans einer Karte heute und in den letzten 7 Tagen - fuer die
     * Kartenkacheln im Dashboard. "Scans" statt "Stempel": ein Scan mit
     * count 3 ist EIN Besuch am Tresen, und darum geht es hier (wird der
     * QR-Code benutzt?).
     */
    public Map<String, Object> scanZaehler(Card card) {
        Instant now = Instant.now();
        Instant tagesbeginn = now.atZone(ZONE).toLocalDate().atStartOfDay(ZONE).toInstant();
        Map<String, Object> m = new HashMap<>();
        m.put("scansToday", scanLogRepo.countByCardIdAndScannedAtAfter(card.getId(), tagesbeginn));
        m.put("scans7d", scanLogRepo.countByCardIdAndScannedAtAfter(card.getId(),
                now.minus(7, java.time.temporal.ChronoUnit.DAYS)));
        return m;
    }

    /**
     * Letzte Aktivitaeten fuers Dashboard: die juengsten Scans mit Namen und
     * die neuesten Kundenkarten. ScanLog traegt nur IDs (Loeschen darf nicht
     * am Protokoll scheitern), die Namen kommen hier dazu. Ein Kunde, der
     * sich inzwischen geloescht hat, bleibt als leerer Name stehen.
     */
    public Map<String, Object> aktivitaet(Shop shop) {
        List<ScanLog> scans = scanLogRepo.findTop15ByShopIdOrderByScannedAtDesc(shop.getId());

        Map<String, String> kundenNamen = new HashMap<>();
        customerRepo.findAllById(scans.stream().map(ScanLog::getCustomerId)
                        .filter(id -> id != null).distinct().toList())
                .forEach(c -> kundenNamen.put(c.getId(), c.getName()));
        Map<String, String> kartenNamen = new HashMap<>();
        cardRepo.findAllById(scans.stream().map(ScanLog::getCardId).distinct().toList())
                .forEach(c -> kartenNamen.put(c.getId(), c.getName()));

        List<Map<String, Object>> letzte = new ArrayList<>();
        for (ScanLog sl : scans) {
            Map<String, Object> m = new HashMap<>();
            m.put("zeit", sl.getScannedAt().toString());
            m.put("kunde", kundenNamen.getOrDefault(sl.getCustomerId(), ""));
            m.put("karte", kartenNamen.getOrDefault(sl.getCardId(), ""));
            m.put("stempel", sl.getStampsAdded());
            m.put("praemien", sl.getRewardsEarned());
            m.put("von", sl.getStaffLabel() != null ? sl.getStaffLabel() : "");
            letzte.add(m);
        }

        List<Map<String, Object>> neue = new ArrayList<>();
        for (CustomerCard cc : customerCardRepo.findTop5ByCard_ShopOrderByCreatedAtDesc(shop)) {
            Customer k = cc.getCustomer();
            Map<String, Object> m = new HashMap<>();
            m.put("name", k != null ? k.getName() : "");
            m.put("karte", cc.getCard().getName());
            m.put("seit", cc.getCreatedAt() != null ? cc.getCreatedAt().toString() : null);
            m.put("gesperrt", cc.isWillkommenOffen());
            neue.add(m);
        }

        Map<String, Object> out = new HashMap<>();
        out.put("scans", letzte);
        out.put("neueKunden", neue);
        return out;
    }

    /** Was die Kunden noch einloesen duerfen. Bewusst statisch und ohne
     *  Datenbank, damit die Regel in einem Einheitentest steht. */
    static long ausstehendePunkte(List<Long> staende) {
        return staende.stream().mapToLong(Long::longValue).sum();
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
