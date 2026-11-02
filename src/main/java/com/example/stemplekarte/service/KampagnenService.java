package com.example.stemplekarte.service;

import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.Kampagne;
import com.example.stemplekarte.model.KampagneEmpfaenger;
import com.example.stemplekarte.model.Reward;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.repository.AppleDeviceRepository;
import com.example.stemplekarte.repository.CustomerCardRepository;
import com.example.stemplekarte.repository.KampagneEmpfaengerRepository;
import com.example.stemplekarte.repository.KampagneRepository;
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
    private final ShopRepository shopRepo;
    private final AppleDeviceRepository deviceRepo;
    private final RewardService rewardService;
    private final ApnsPushService apnsPushService;
    private final GoogleWalletService googleWalletService;
    private final TransactionTemplate tx;

    public KampagnenService(KampagneRepository kampagneRepo, KampagneEmpfaengerRepository empfaengerRepo,
                            CustomerCardRepository customerCardRepo, ShopRepository shopRepo,
                            AppleDeviceRepository deviceRepo, RewardService rewardService,
                            ApnsPushService apnsPushService, GoogleWalletService googleWalletService,
                            TransactionTemplate tx) {
        this.kampagneRepo = kampagneRepo;
        this.empfaengerRepo = empfaengerRepo;
        this.customerCardRepo = customerCardRepo;
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
        for (Zielgruppe g : Zielgruppe.values()) {
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

    @Transactional
    public Kampagne starte(Shop shop, String roh, String gruppeKey) {
        String text = sauber(roh);
        if (text.isEmpty()) {
            throw new IllegalArgumentException("Der Text darf nicht leer sein");
        }
        if (text.length() > Kampagne.TEXT_MAX) {
            throw new IllegalArgumentException("Der Text darf höchstens " + Kampagne.TEXT_MAX + " Zeichen haben");
        }
        Zielgruppe gruppe = Zielgruppe.von(gruppeKey);
        Instant jetzt = Instant.now();
        // Den Laden frisch laden statt die Kopie aus dem JWT-Filter zu
        // speichern - die ueberschriebe sonst alle Spalten mit altem Stand.
        Shop laden = shopRepo.findById(shop.getId()).orElseThrow();
        // Ab jetzt traegt jeder neu gebaute Pass das Feld. Muss VOR dem
        // Kampagnen-Eintrag stehen, sonst gilt ein Pass von dazwischen als
        // "hat das Feld", obwohl er es nicht hat.
        laden.nachrichtenEinschalten(jetzt);
        shopRepo.save(laden);
        List<CustomerCard> empf = empfaenger(laden, gruppe, jetzt);
        if (empf.isEmpty()) {
            throw new IllegalArgumentException(
                    "In dieser Gruppe ist gerade niemand, der heute noch keine Nachricht bekommen hat.");
        }
        Kampagne k = kampagneRepo.save(Kampagne.starte(laden, text, gruppe, empf.size(), jetzt));
        empfaengerRepo.saveAll(empf.stream()
                .map(cc -> KampagneEmpfaenger.neu(k.getId(), cc.getId())).toList());
        log.info("[KAMPAGNE] {} gestartet, Laden {}, Gruppe {}, {} Empfaenger",
                k.getId(), laden.getId(), gruppe, empf.size());
        return k;
    }

    // ── Laeufer ──────────────────────────────────────────────────────────

    @Scheduled(fixedDelay = 30_000, initialDelay = 15_000)
    public void lauf() {
        List<Kampagne> laufend;
        try {
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

    private record Versand(String customerCardId, boolean apple, boolean nurAuffrischen) {}

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
            try { googleWalletService.sendeNachricht(v.customerCardId(), titel, k.getText(), "kampagne"); }
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
                versand.add(new Versand(cc.getId(), true, true));
            } else {
                cc.kampagneErhalten(k.getText(), jetzt);
                e.erledigen();
                versand.add(new Versand(cc.getId(), apple, false));
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
