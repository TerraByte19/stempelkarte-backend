package com.example.stemplekarte.service;

import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.Kampagne;
import com.example.stemplekarte.model.KampagneEmpfaenger;
import com.example.stemplekarte.model.Reward;
import com.example.stemplekarte.model.ScanLog;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.repository.AppleDeviceRepository;
import com.example.stemplekarte.repository.CustomerCardRepository;
import com.example.stemplekarte.repository.KampagneEmpfaengerRepository;
import com.example.stemplekarte.repository.KampagneRepository;
import com.example.stemplekarte.repository.ScanLogRepository;
import com.example.stemplekarte.repository.ShopRepository;
import com.example.stemplekarte.wallet.ApnsPushService;
import com.example.stemplekarte.wallet.GoogleWalletService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Push-Kampagne: ein Text vom Laden an eine Zielgruppe seiner Kunden, als
 * Mitteilung auf iPhone und Android.
 *
 * Derselbe Weg wie die Rueckhol-Nachricht (RueckholService): Apple ueber das
 * Rueckseitenfeld "nachricht" mit changeMessage, Google ueber eine eigene
 * Nachricht mit TEXT_AND_NOTIFY. Auch dieselbe Falle: iOS meldet nur
 * Aenderungen an einem Feld, das der Pass auf dem Handy schon hat. Aeltere
 * Paesse werden deshalb erst still aufgefrischt und bekommen den Text im
 * naechsten Lauf.
 *
 * Beim Start wird die Empfaengerliste festgeschrieben (KampagneEmpfaenger),
 * der Laeufer arbeitet sie alle 30 s in Seiten von 25 Karten ab. Jede
 * geaenderte Karte holt sich iOS neu, und ein Pass-Bau kostet auf der
 * kleinen Instanz 1-3 s - ein Laden mit 300 Kunden ist in etwa sechs
 * Minuten durch, ohne die Instanz auf einen Schlag zu fluten.
 *
 * Jede Karte bekommt hoechstens eine Kampagne am Tag: Apple laesst rund
 * drei Pushes pro Karte und Tag durch, der Rest muss fuer Stempel bleiben.
 * Der Laden darf trotzdem mehrere Kampagnen an verschiedene Gruppen
 * schicken - wer heute schon eine hatte, wird uebersprungen.
 */
@Service
public class KampagnenService {

    private static final Logger log = LoggerFactory.getLogger(KampagnenService.class);
    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");

    static final int SEITE = 25;

    private final KampagneRepository kampagneRepo;
    private final KampagneEmpfaengerRepository empfaengerRepo;
    private final CustomerCardRepository customerCardRepo;
    private final ScanLogRepository scanLogRepo;
    private final ShopRepository shopRepo;
    private final AppleDeviceRepository deviceRepo;
    private final RewardService rewardService;
    private final ApnsPushService apnsPushService;
    private final GoogleWalletService googleWalletService;
    private final TransactionTemplate tx;

    public KampagnenService(KampagneRepository kampagneRepo, KampagneEmpfaengerRepository empfaengerRepo,
                            CustomerCardRepository customerCardRepo, ScanLogRepository scanLogRepo,
                            ShopRepository shopRepo, AppleDeviceRepository deviceRepo,
                            RewardService rewardService, ApnsPushService apnsPushService,
                            GoogleWalletService googleWalletService, TransactionTemplate tx) {
        this.kampagneRepo = kampagneRepo;
        this.empfaengerRepo = empfaengerRepo;
        this.customerCardRepo = customerCardRepo;
        this.scanLogRepo = scanLogRepo;
        this.shopRepo = shopRepo;
        this.deviceRepo = deviceRepo;
        this.rewardService = rewardService;
        this.apnsPushService = apnsPushService;
        this.googleWalletService = googleWalletService;
        this.tx = tx;
    }

    // ── Zielgruppen ──────────────────────────────────────────────────────

    /** empfaenger = trifft die Gruppe und hatte heute noch keine; heuteSchon = wird uebersprungen. */
    public record GruppenZahl(Zielgruppe gruppe, long empfaenger, long heuteSchon) {}

    /**
     * Alle Gruppen in einem Durchlauf ueber die Grundmenge. Gezaehlt werden
     * PERSONEN, nicht Karten - wie bei den Empfaengern.
     */
    public List<GruppenZahl> zaehle(Shop shop) {
        Instant jetzt = Instant.now();
        Function<Card, List<Reward>> katalog = katalogCache();
        List<CustomerCard> basis = customerCardRepo.findKampagnenBasis(shop);
        Set<String> heute = heuteBenachrichtigt(basis, tagesbeginn(jetzt));
        List<GruppenZahl> out = new ArrayList<>();
        for (Zielgruppe g : Zielgruppe.waehlbare()) {
            Set<String> passend = new HashSet<>(), uebersprungen = new HashSet<>();
            for (CustomerCard cc : basis) {
                if (!g.passt(cc, jetzt, katalog)) continue;
                String kunde = cc.getCustomer().getId();
                if (heute.contains(kunde)) uebersprungen.add(kunde); else passend.add(kunde);
            }
            out.add(new GruppenZahl(g, passend.size(), uebersprungen.size()));
        }
        return out;
    }

    /**
     * Eine Karte pro PERSON, nicht pro Karte: hat jemand bei diesem Laden
     * Stempel- und Punktekarte, bekommt er die Nachricht einmal - auf der
     * Karte, die er zuletzt benutzt hat (und die die Gruppe trifft). Sonst
     * klingelt dasselbe Handy zweimal, und beide Paesse verbrauchen einen Push.
     */
    List<CustomerCard> empfaenger(Shop shop, Zielgruppe gruppe, Instant jetzt) {
        Function<Card, List<Reward>> katalog = katalogCache();
        List<CustomerCard> basis = customerCardRepo.findKampagnenBasis(shop);
        Set<String> heute = heuteBenachrichtigt(basis, tagesbeginn(jetzt));
        Map<String, CustomerCard> proKunde = new LinkedHashMap<>();
        for (CustomerCard cc : basis) {
            if (!gruppe.passt(cc, jetzt, katalog)) continue;
            String kunde = cc.getCustomer().getId();
            if (heute.contains(kunde)) continue;
            proKunde.merge(kunde, cc, KampagnenService::zuletztBenutzt);
        }
        return List.copyOf(proKunde.values());
    }

    /**
     * Personen, die heute auf IRGENDEINER Karte dieses Ladens schon eine
     * Kampagne bekamen. Das Tageslimit gilt dem Handy, nicht der Karte.
     */
    static Set<String> heuteBenachrichtigt(List<CustomerCard> basis, Instant tagesbeginn) {
        Set<String> kunden = new HashSet<>();
        for (CustomerCard cc : basis) {
            if (heuteSchon(cc, tagesbeginn)) kunden.add(cc.getCustomer().getId());
        }
        return kunden;
    }

    static boolean heuteSchon(CustomerCard cc, Instant tagesbeginn) {
        return cc.getKampagneAm() != null && !cc.getKampagneAm().isBefore(tagesbeginn);
    }

    static CustomerCard zuletztBenutzt(CustomerCard a, CustomerCard b) {
        Instant ba = a.letzterBesuch(), bb = b.letzterBesuch();
        if (ba == null) return b;
        if (bb == null) return a;
        return bb.isAfter(ba) ? b : a;
    }

    static Instant tagesbeginn(Instant jetzt) {
        return jetzt.atZone(ZONE).toLocalDate().atStartOfDay(ZONE).toInstant();
    }

    // Der Katalog einer Punktekarte wird pro Durchlauf nur einmal geladen,
    // nicht pro Kunde.
    private Function<Card, List<Reward>> katalogCache() {
        Map<String, List<Reward>> cache = new HashMap<>();
        return card -> cache.computeIfAbsent(card.getId(), id -> rewardService.list(card));
    }

    // ── Start ────────────────────────────────────────────────────────────

    /**
     * Steuerzeichen raus: der Text steht im Wallet-Pass und in der
     * Android-Mitteilung, ein Zeilenumbruch zerlegt mindestens eins davon.
     */
    static String sauber(String text) {
        if (text == null) return "";
        return text.replaceAll("\\p{Cntrl}", " ").replaceAll(" {2,}", " ").trim();
    }

    public static final int PLANUNG_MAX_TAGE = 90;
    /** Unter so vielen Empfaengern sagt eine Kontrollgruppe nichts aus. */
    public static final int KONTROLLE_MIN = 10;

    public Kampagne starte(Shop shop, String roh, String gruppeKey) {
        return starte(shop, roh, gruppeKey, null, false);
    }

    public Kampagne starte(Shop shop, String roh, String gruppeKey, Instant geplantAm) {
        return starte(shop, roh, gruppeKey, geplantAm, false);
    }

    // ── Kontrollgruppe ───────────────────────────────────────────────────

    record Aufteilung(List<CustomerCard> nachricht, List<CustomerCard> kontrolle) {}

    /**
     * Ein Zehntel zufaellig in die Kontrollgruppe, mindestens eine Karte -
     * aber erst ab KONTROLLE_MIN Empfaengern, sonst misst man Rauschen.
     * Zufall von aussen, damit der Test ihn festhalten kann.
     */
    static Aufteilung aufteilen(List<CustomerCard> empf, boolean kontrollgruppe, java.util.Random zufall) {
        if (!kontrollgruppe || empf.size() < KONTROLLE_MIN) return new Aufteilung(empf, List.of());
        List<CustomerCard> gemischt = new ArrayList<>(empf);
        java.util.Collections.shuffle(gemischt, zufall);
        int n = Math.max(1, gemischt.size() / 10);
        return new Aufteilung(List.copyOf(gemischt.subList(n, gemischt.size())),
                List.copyOf(gemischt.subList(0, n)));
    }

    /** Zeilen fuer beide Gruppen schreiben, Zahlen am Eintrag setzen. */
    private void empfaengerFestschreiben(Kampagne k, List<CustomerCard> empf, boolean kontrollgruppe) {
        Aufteilung a = aufteilen(empf, kontrollgruppe, new java.util.Random());
        List<KampagneEmpfaenger> zeilen = new ArrayList<>();
        for (CustomerCard cc : a.nachricht()) zeilen.add(KampagneEmpfaenger.neu(k.getId(), cc.getId()));
        for (CustomerCard cc : a.kontrolle()) zeilen.add(KampagneEmpfaenger.kontrolle(k.getId(), cc.getId()));
        empfaengerRepo.saveAll(zeilen);
        k.aktivieren(a.nachricht().size());
        k.kontrollgruppe(a.kontrolle().size());
    }

    /**
     * geplantAm == null: sofort, Empfaenger werden jetzt festgeschrieben.
     * Sonst wartet die Kampagne als GEPLANT; der Laeufer bestimmt die
     * Empfaenger erst zum Zeitpunkt (aktiviere). Die Zahl im Eintrag ist bis
     * dahin eine Schaetzung von jetzt. kontrollgruppe: ein Zehntel bekommt
     * nichts und dient dem Vergleich.
     */
    @Transactional
    public Kampagne starte(Shop shop, String roh, String gruppeKey, Instant geplantAm, boolean kontrollgruppe) {
        String text = sauber(roh);
        if (text.isEmpty()) {
            throw new IllegalArgumentException("Der Text darf nicht leer sein");
        }
        if (text.length() > Kampagne.TEXT_MAX) {
            throw new IllegalArgumentException("Der Text darf höchstens " + Kampagne.TEXT_MAX + " Zeichen haben");
        }
        Zielgruppe gruppe = Zielgruppe.von(gruppeKey);
        Instant jetzt = Instant.now();
        if (geplantAm != null) {
            if (geplantAm.isBefore(jetzt.plusSeconds(60))) {
                throw new IllegalArgumentException("Der Zeitpunkt liegt in der Vergangenheit");
            }
            if (geplantAm.isAfter(jetzt.plus(java.time.Duration.ofDays(PLANUNG_MAX_TAGE)))) {
                throw new IllegalArgumentException("Höchstens " + PLANUNG_MAX_TAGE + " Tage im Voraus");
            }
        }
        // Den Laden frisch laden statt die Kopie aus dem JWT-Filter zu
        // speichern - die ueberschriebe sonst alle Spalten mit altem Stand.
        Shop laden = shopRepo.findById(shop.getId()).orElseThrow();
        // Ab jetzt traegt jeder neu gebaute Pass das Feld. Muss VOR dem
        // Kampagnen-Eintrag stehen, sonst gilt ein Pass von dazwischen als
        // "hat das Feld", obwohl er es nicht hat. Bei geplanten Kampagnen
        // gewinnt das sogar Zeit: jeder Stempel bis dahin bringt das Feld
        // auf ein weiteres iPhone.
        laden.nachrichtenEinschalten(jetzt);
        shopRepo.save(laden);
        List<CustomerCard> empf = empfaenger(laden, gruppe, jetzt);
        if (geplantAm != null) {
            Kampagne k = Kampagne.starte(laden, text, gruppe, empf.size(), jetzt, geplantAm);
            // Merker, dass beim Aktivieren aufgeteilt werden soll: -1 = gewuenscht.
            if (kontrollgruppe) k.kontrollgruppe(-1);
            k = kampagneRepo.save(k);
            log.info("[KAMPAGNE] {} geplant fuer {}, Laden {}, Gruppe {}, ~{} Empfaenger",
                    k.getId(), geplantAm, laden.getId(), gruppe, empf.size());
            return k;
        }
        if (empf.isEmpty()) {
            throw new IllegalArgumentException(
                    "In dieser Gruppe ist gerade niemand, der heute noch keine Nachricht bekommen hat.");
        }
        Kampagne k = kampagneRepo.save(Kampagne.starte(laden, text, gruppe, empf.size(), jetzt));
        empfaengerFestschreiben(k, empf, kontrollgruppe);
        kampagneRepo.save(k);
        log.info("[KAMPAGNE] {} gestartet, Laden {}, Gruppe {}, {} Empfaenger, {} Kontrolle",
                k.getId(), laden.getId(), gruppe, k.getEmpfaenger(), k.getKontrolleEmpfaenger());
        return k;
    }

    /**
     * Kampagne mit fertiger Empfaengerliste (eigene Automation,
     * AutomationService). Gruppe AUTOMATION, keine Kontrollgruppe; die
     * Karte je Person und das Tageslimit hat der Aufrufer schon beachtet.
     */
    @Transactional
    public Kampagne starteFuer(Shop shop, String roh, List<CustomerCard> empf, String automationId) {
        String text = sauber(roh);
        if (text.isEmpty()) {
            throw new IllegalArgumentException("Der Text darf nicht leer sein");
        }
        if (text.length() > Kampagne.TEXT_MAX) {
            throw new IllegalArgumentException("Der Text darf höchstens " + Kampagne.TEXT_MAX + " Zeichen haben");
        }
        if (empf == null || empf.isEmpty()) {
            throw new IllegalArgumentException("Niemand fällig");
        }
        Instant jetzt = Instant.now();
        Shop laden = shopRepo.findById(shop.getId()).orElseThrow();
        laden.nachrichtenEinschalten(jetzt);
        shopRepo.save(laden);
        Kampagne k = Kampagne.starte(laden, text, Zielgruppe.AUTOMATION, empf.size(), jetzt);
        k.vonAutomation(automationId);
        k = kampagneRepo.save(k);
        empfaengerFestschreiben(k, empf, false);
        kampagneRepo.save(k);
        log.info("[KAMPAGNE] {} gestartet (Automation {}), Laden {}, {} Empfaenger",
                k.getId(), automationId, laden.getId(), k.getEmpfaenger());
        return k;
    }

    /** Geplante Kampagne verwerfen. Laufende oder fertige lassen sich nicht zuruecknehmen. */
    @Transactional
    public void abbrechen(Shop shop, String kampagneId) {
        Kampagne k = kampagneRepo.findByIdAndShop(kampagneId, shop)
                .orElseThrow(() -> new java.util.NoSuchElementException("Kampagne nicht gefunden"));
        if (!k.istGeplant()) {
            throw new IllegalArgumentException("Nur geplante Kampagnen lassen sich abbrechen");
        }
        kampagneRepo.delete(k);
        log.info("[KAMPAGNE] {} abgebrochen, Laden {}", kampagneId, shop.getId());
    }

    /**
     * Geplante Kampagne ist faellig: Empfaenger jetzt bestimmen und
     * festschreiben. Niemand da -> gleich fertig mit 0, nicht ewig geplant.
     */
    void aktiviere(Kampagne k, Instant jetzt) {
        List<CustomerCard> empf = tx.execute(status -> {
            List<CustomerCard> e = empfaenger(k.getShop(), Zielgruppe.von(k.getGruppe()), jetzt);
            // -1 war der Merker "Kontrollgruppe gewuenscht" beim Planen.
            boolean kontrollgruppe = k.getKontrolleEmpfaenger() < 0;
            if (e.isEmpty()) {
                k.aktivieren(0);
                k.kontrollgruppe(0);
                k.beenden(jetzt);
            } else {
                empfaengerFestschreiben(k, e, kontrollgruppe);
            }
            kampagneRepo.save(k);
            return e;
        });
        log.info("[KAMPAGNE] {} faellig, {} Empfaenger", k.getId(), empf != null ? empf.size() : 0);
    }

    // ── Ergebnis ─────────────────────────────────────────────────────────

    public static final int ERGEBNIS_TAGE = 7;

    /**
     * Wie viele Empfaenger seit dem Versand mindestens einmal gescannt haben,
     * innerhalb von ERGEBNIS_TAGE. Personen, nicht Scans: wer dreimal kam,
     * zaehlt einmal. Vor Ablauf des Fensters ist das ein Zwischenstand.
     *
     * shopId kommt extra, weil die Kampagnen aus dem Verlauf den Laden nur
     * als Lazy-Proxy tragen.
     */
    /** mitNachricht = Benachrichtigte, die kamen; kontrolle = Kontrollgruppe, die kam. */
    public record Ergebnis(int mitNachricht, int kontrolle) {}

    public Ergebnis wiedergekommen(Kampagne k, String shopId, Instant jetzt) {
        List<KampagneEmpfaenger> zeilen = empfaengerRepo.findByKampagneId(k.getId());
        if (zeilen.isEmpty() || k.startAm() == null) return new Ergebnis(0, 0);
        // Karten-ID -> Personen-ID, dann je Gruppe die Personen.
        Map<String, String> kundeVonKarte = new HashMap<>();
        for (Object[] z : customerCardRepo.kundenIdsVon(
                zeilen.stream().map(KampagneEmpfaenger::getCustomerCardId).toList())) {
            if (z[0] != null && z[1] != null) kundeVonKarte.put(z[0].toString(), z[1].toString());
        }
        Set<String> benachrichtigt = new HashSet<>(), kontrolle = new HashSet<>();
        for (KampagneEmpfaenger e : zeilen) {
            String kunde = kundeVonKarte.get(e.getCustomerCardId());
            if (kunde == null) continue;
            (e.istKontrolle() ? kontrolle : benachrichtigt).add(kunde);
        }
        Instant von = k.startAm();
        Instant bis = von.plus(java.time.Duration.ofDays(ERGEBNIS_TAGE));
        if (jetzt.isBefore(bis)) bis = jetzt;
        Set<String> daMit = new HashSet<>(), daOhne = new HashSet<>();
        for (ScanLog sl : scanLogRepo.findByShopIdAndScannedAtBetweenOrderByScannedAtAsc(shopId, von, bis)) {
            String kunde = sl.getCustomerId();
            if (kunde == null) continue;
            if (benachrichtigt.contains(kunde)) daMit.add(kunde);
            if (kontrolle.contains(kunde)) daOhne.add(kunde);
        }
        return new Ergebnis(daMit.size(), daOhne.size());
    }

    // ── Laeufer ──────────────────────────────────────────────────────────

    @Scheduled(fixedDelay = 30_000, initialDelay = 15_000)
    public void lauf() {
        List<Kampagne> laufend;
        try {
            Instant jetzt = Instant.now();
            for (Kampagne k : kampagneRepo.findByStatusAndGeplantAmLessThanEqual(Kampagne.GEPLANT, jetzt)) {
                try {
                    aktiviere(k, jetzt);
                } catch (Exception e) {
                    log.warn("[KAMPAGNE] {} nicht aktiviert: {}", k.getId(), e.getMessage());
                }
            }
            // Fenster um: Ergebnis festschreiben, damit das Dashboard es nicht
            // bei jedem Aufruf neu zaehlt.
            Instant grenze = jetzt.minus(java.time.Duration.ofDays(ERGEBNIS_TAGE));
            for (Kampagne k : kampagneRepo.findByStatusAndErgebnisAmIsNullAndFertigAmBefore(Kampagne.FERTIG, grenze)) {
                try {
                    Ergebnis e = wiedergekommen(k, k.getShop().getId(), jetzt);
                    k.ergebnis(e.mitNachricht(), e.kontrolle(), jetzt);
                    kampagneRepo.save(k);
                } catch (Exception e) {
                    log.warn("[KAMPAGNE] {} Ergebnis nicht festgeschrieben: {}", k.getId(), e.getMessage());
                }
            }
            laufend = kampagneRepo.findByStatus(Kampagne.LAEUFT);
        } catch (Exception e) {
            log.warn("[KAMPAGNE] Lauf abgebrochen: {}", e.getMessage());
            return;
        }
        for (Kampagne k : laufend) {
            try {
                verarbeite(k, Instant.now());
            } catch (Exception e) {
                // Eine kaputte Kampagne haelt die anderen nicht auf.
                log.warn("[KAMPAGNE] {} fehlgeschlagen: {}", k.getId(), e.getMessage());
            }
        }
    }

    /** text = je Karte gefuellte Vorlage (Platzhalter), null beim stillen Auffrischen. */
    private record Versand(String customerCardId, boolean apple, boolean nurAuffrischen, String text) {}

    /** Eine Seite: festschreiben, committen, dann pushen. Nichts mehr offen = fertig. */
    void verarbeite(Kampagne k, Instant jetzt) {
        List<Versand> versand = tx.execute(status -> markiere(k, jetzt));
        if (versand == null) return;
        if (versand.isEmpty()) {
            // Leer kann auch heissen: die Seite bestand nur aus geloeschten
            // Karten. Fertig ist die Kampagne erst, wenn keine Zeile mehr offen ist.
            if (empfaengerRepo.countByKampagneIdAndErledigtFalse(k.getId()) > 0) return;
            k.beenden(jetzt);
            kampagneRepo.save(k);
            log.info("[KAMPAGNE] {} fertig: {} von {} Karten mit Text", k.getId(), k.getGesendet(), k.getEmpfaenger());
            return;
        }
        String titel = k.getShop().getName();
        int mitText = 0;
        for (Versand v : versand) {
            // Ein fehlgeschlagener Push bricht nichts ab - gespeichert ist schon alles.
            if (v.apple()) {
                try { apnsPushService.notifyUpdate(v.customerCardId()); }
                catch (Exception e) { log.warn("[KAMPAGNE] APNs fehlgeschlagen {}: {}", v.customerCardId(), e.getMessage()); }
            }
            if (v.nurAuffrischen()) continue;
            mitText++;
            try { googleWalletService.sendeNachricht(v.customerCardId(), titel, v.text(), "kampagne"); }
            catch (Exception e) { log.warn("[KAMPAGNE] Google fehlgeschlagen {}: {}", v.customerCardId(), e.getMessage()); }
        }
        k.gesendet(mitText);
        kampagneRepo.save(k);
        log.info("[KAMPAGNE] {}: {} Karten mit Text, {} still aufgefrischt",
                k.getId(), mitText, versand.size() - mitText);
    }

    private List<Versand> markiere(Kampagne k, Instant jetzt) {
        Shop shop = k.getShop();
        List<Versand> versand = new ArrayList<>();
        // Platzhalter ({name}, {offen}, {praemie}) je Karte fuellen; der
        // Katalog einer Punktekarte wird dabei nur einmal geladen.
        Function<Card, List<Reward>> katalog = katalogCache();
        List<KampagneEmpfaenger> seite = empfaengerRepo.findByKampagneIdAndErledigtFalse(
                k.getId(), PageRequest.of(0, SEITE));
        for (KampagneEmpfaenger e : seite) {
            CustomerCard cc = customerCardRepo.findById(e.getCustomerCardId()).orElse(null);
            if (cc == null) {
                // Karte seit dem Start geloescht - abhaken, sonst bleibt die
                // Kampagne fuer immer auf "laeuft".
                e.erledigen();
                empfaengerRepo.save(e);
                continue;
            }
            boolean apple = hatApple(cc.getId());
            if (apple && !feldAufDemHandy(shop, cc) && !e.istAufgefrischt()) {
                // Alter Pass ohne Nachrichtenfeld: erst still nachreichen. Die
                // Zeile bleibt offen und bekommt den Text im naechsten Lauf.
                cc.passAuffrischen(jetzt);
                e.aufgefrischt(jetzt);
                versand.add(new Versand(cc.getId(), true, true, null));
            } else {
                String text = Platzhalter.fuelle(k.getText(), cc, katalog);
                cc.kampagneErhalten(text, jetzt);
                e.erledigen();
                versand.add(new Versand(cc.getId(), apple, false, text));
            }
            customerCardRepo.save(cc);
            empfaengerRepo.save(e);
        }
        return versand;
    }

    /** Wie RueckholService#feldAufDemHandy: Pass nach dem Einschalten gebaut? */
    static boolean feldAufDemHandy(Shop shop, CustomerCard cc) {
        Instant seit = shop.nachrichtenFeldSeit();
        return seit != null && cc.getUpdatedAt() != null && cc.getUpdatedAt().isAfter(seit);
    }

    private boolean hatApple(String customerCardId) {
        return !deviceRepo.findBySerialNumber(customerCardId).isEmpty();
    }
}
