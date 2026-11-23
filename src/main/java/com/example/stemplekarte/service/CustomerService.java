package com.example.stemplekarte.service;

import com.example.stemplekarte.model.*;
import com.example.stemplekarte.repository.AppleDeviceRepository;
import com.example.stemplekarte.repository.CardRepository;
import com.example.stemplekarte.repository.CustomerCardRepository;
import com.example.stemplekarte.repository.CustomerRepository;
import com.example.stemplekarte.repository.ScanLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

@Service
public class CustomerService {

    private static final Logger log = LoggerFactory.getLogger(CustomerService.class);

    private final CustomerRepository customerRepo;
    private final CardRepository cardRepo;
    private final CustomerCardRepository customerCardRepo;
    private final AppleDeviceRepository appleDeviceRepo;
    private final EmailService emailService;
    private final ScanLogRepository scanLogRepo;

    // Optional, damit die Unit-Tests den Dienst weiter ohne Spring bauen
    // koennen: ohne Webhook-Dienst wird schlicht nichts gemeldet.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private WebhookService webhooks;

    void webhooks(WebhookService webhooks) { this.webhooks = webhooks; }

    private void webhook(Shop shop, String typ, java.util.Map<String, Object> daten) {
        if (webhooks != null) webhooks.melde(shop, typ, daten);
    }

    public CustomerService(CustomerRepository customerRepo, CardRepository cardRepo,
                           CustomerCardRepository customerCardRepo,
                           AppleDeviceRepository appleDeviceRepo,
                           EmailService emailService,
                           ScanLogRepository scanLogRepo) {
        this.customerRepo = customerRepo;
        this.cardRepo = cardRepo;
        this.customerCardRepo = customerCardRepo;
        this.appleDeviceRepo = appleDeviceRepo;
        this.emailService = emailService;
        this.scanLogRepo = scanLogRepo;
    }

    @Transactional
    public Customer getOrCreate(String name, String email) {
        return customerRepo.findByEmail(email.toLowerCase().trim())
                .orElseGet(() -> customerRepo.save(Customer.create(name, email)));
    }

    /**
     * Anmeldung über die Kunden-Landing-Page (/karte-neu/{cardId}).
     * - Erstellt Kunde + CustomerCard (oder findet bestehende).
     * - Speichert die Werbe-Einwilligung pro Karte/Laden (Checkbox).
     * - Versendet bei Bedarf die Bestätigungs-Mail (Double-Opt-In).
     */
    @Transactional
    public CustomerCard registerForCard(String name, String email, String cardId,
                                        boolean marketingConsent) {
        return registerForCard(name, email, cardId, marketingConsent, null, null);
    }

    /** birthday und phone: optionale Anmeldefelder (Shop.signup*), geprueft im Controller. */
    @Transactional
    public CustomerCard registerForCard(String name, String email, String cardId,
                                        boolean marketingConsent,
                                        java.time.LocalDate birthday, String phone) {
        return registerForCard(name, email, cardId, marketingConsent, birthday, phone, null);
    }

    /** language: vom Kunden auf der Anmeldeseite gewaehlt (de, en, ar, tr) oder null = nichts aendern. */
    @Transactional
    public CustomerCard registerForCard(String name, String email, String cardId,
                                        boolean marketingConsent,
                                        java.time.LocalDate birthday, String phone,
                                        String language) {
        return anmelden(name, email, cardId, marketingConsent, birthday, phone, language, false);
    }

    /**
     * Anmeldung mit Google (GoogleAnmeldung): Google hat die Adresse schon
     * bestaetigt, also keine Bestaetigungs-Mail und kein Warten - die Karte
     * ist sofort da. Alles andere wie registerForCard.
     */
    @Transactional
    public CustomerCard registerForCardBestaetigt(String name, String email, String cardId,
                                                  boolean marketingConsent,
                                                  java.time.LocalDate birthday, String phone,
                                                  String language) {
        return anmelden(name, email, cardId, marketingConsent, birthday, phone, language, true);
    }

    /**
     * Karte ohne Anmeldung: der Laden hat eingeschaltet, dass der Kunde
     * nichts eintippt. Jeder Aufruf legt einen NEUEN Kunden an - es gibt
     * keine Adresse, an der man ihn wiedererkennen koennte. Dass derselbe
     * Kunde nicht bei jedem Scan eine neue Karte bekommt, regelt die
     * Anmeldeseite (sie merkt sich die Karte im Browser).
     *
     * Keine Mail, kein Willkommensgeschenk (liesse sich sonst beliebig oft
     * holen), keine Werbe-Einwilligung.
     */
    @Transactional
    public CustomerCard registerOhneMail(String cardId, String language) {
        Card card = cardRepo.findById(cardId)
                .orElseThrow(() -> new NoSuchElementException("Karte nicht gefunden: " + cardId));
        if (!card.getShop().isAnmeldungOhneMail()) {
            throw new IllegalArgumentException("Bei diesem Laden braucht die Karte eine Anmeldung.");
        }

        Customer customer = Customer.createOhneMail();
        String gewaehlt = com.example.stemplekarte.i18n.Texts.gewaehlt(language);
        if (gewaehlt != null) customer.setLanguage(gewaehlt);
        customer = customerRepo.save(customer);

        CustomerCard cc = customerCardRepo.save(CustomerCard.create(customer, card));
        webhook(card.getShop(), "customer.signup", WebhookService.daten(
                "customerId", customer.getId(), "cardId", card.getId(), "customerCardId", cc.getId(),
                "name", customer.getName(), "email", null,
                "marketingConsent", false));
        return cc;
    }

    /** Gibt es diese Kundenkarte (noch)? Legt nichts an. */
    @Transactional(readOnly = true)
    public boolean hatKarte(String customerId, String cardId) {
        if (customerId == null || cardId == null) return false;
        Customer customer = customerRepo.findById(customerId).orElse(null);
        Card card = cardRepo.findById(cardId).orElse(null);
        if (customer == null || card == null) return false;
        return customerCardRepo.findByCustomerAndCard(customer, card).isPresent();
    }

    /** Sprache des Kunden setzen (Sprachleiste auf der Kartenseite). Unbekannt = nichts. */
    @Transactional
    public void spracheSetzen(Customer customer, String language) {
        String l = com.example.stemplekarte.i18n.Texts.gewaehlt(language);
        if (customer == null || l == null || l.equals(customer.getLanguage())) return;
        customer.setLanguage(l);
        customerRepo.save(customer);
    }

    private CustomerCard anmelden(String name, String email, String cardId,
                                  boolean marketingConsent,
                                  java.time.LocalDate birthday, String phone,
                                  String language, boolean bestaetigt) {
        Card card = cardRepo.findById(cardId)
                .orElseThrow(() -> new NoSuchElementException("Karte nicht gefunden: " + cardId));

        Customer customer = getOrCreate(name, email);
        // Sprache nur, wenn der Kunde sie auf der Seite gewaehlt hat. Ohne
        // Wahl bleibt es, wie es war: neu = Deutsch, bestehend = seine Sprache.
        String gewaehlt = com.example.stemplekarte.i18n.Texts.gewaehlt(language);
        if (gewaehlt != null) customer.setLanguage(gewaehlt);

        // Jede Kartenanmeldung verlangt eine frische Mail-Bestätigung:
        // emailConfirmed zurücksetzen + neuen Token vergeben, damit der
        // Kunde die Karte erst nach Klick auf den Mail-Link bekommt.
        // (Solange unbestätigt, wird der Kunde auch nicht im Newsletter
        // angeschrieben — rechtlich sauber.)
        // Mit Google ist die Adresse schon bestaetigt: dann sofort frei.
        if (bestaetigt) customer.confirmEmail();
        else customer.requireNewConfirmation();
        customerRepo.save(customer);

        CustomerCard cc = customerCardRepo.findByCustomerAndCard(customer, card).orElse(null);
        boolean neu = cc == null;
        if (neu) {
            cc = customerCardRepo.save(neueKundenkarte(customer, card));
            webhook(card.getShop(), "customer.signup", WebhookService.daten(
                    "customerId", customer.getId(), "cardId", card.getId(), "customerCardId", cc.getId(),
                    "name", customer.getName(), "email", customer.getEmail(),
                    "marketingConsent", marketingConsent));
        }

        // Einwilligung speichern (nur wenn neu, nicht ungewollt zurücksetzen)
        if (marketingConsent && !cc.isMarketingConsent()) {
            cc.giveMarketingConsent();
            customerCardRepo.save(cc);
        }
        // Geburtstag/Nummer an der Karte dieses Ladens, nur leere Felder.
        if (birthday != null || phone != null) {
            cc.anmeldedatenErgaenzen(birthday, phone);
            customerCardRepo.save(cc);
        }

        // Bestätigungs-Mail IMMER verschicken (jede Anmeldung neu).
        // Wer sich nochmal anmeldet, solange das Geschenk offen ist, wird in
        // der Mail wieder daran erinnert. Nicht bei Google: nichts zu bestaetigen.
        if (!bestaetigt) {
            emailService.sendConfirmationMail(customer, card.getShop(), card.getId(),
                    cc.isWillkommenOffen() ? cc.getWillkommenText() : null);
        }

        return cc;
    }

    /**
     * Die EINZIGE Stelle, an der eine Karte gesperrt wird: nur hier, nur bei
     * einer neu angelegten Kundenkarte. Wer die Karte schon hat - auch eine
     * eingeloeste oder ueber getOrCreateCustomerCard entstandene -, bekommt
     * ueber eine zweite Anmeldung kein zweites Geschenk.
     */
    private static CustomerCard neueKundenkarte(Customer customer, Card card) {
        CustomerCard cc = CustomerCard.create(customer, card);
        // Kein Geschenk, solange der Laden Karten ohne Anmeldung ausgibt:
        // es liesse sich sonst mit jeder neuen Karte noch einmal holen.
        if (card.isWillkommenAktiv() && !card.getShop().isAnmeldungOhneMail()) {
            cc.willkommenVormerken(card.getWillkommenText());
        }
        return cc;
    }

    public Customer getById(String id) {
        return customerRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Kunde nicht gefunden: " + id));
    }

    public Optional<Customer> findById(String id) {
        return customerRepo.findById(id);
    }

    @Transactional
    public CustomerCard getOrCreateCustomerCard(String customerId, String cardId) {
        Customer customer = getById(customerId);
        Card card = cardRepo.findById(cardId)
                .orElseThrow(() -> new NoSuchElementException("Karte nicht gefunden: " + cardId));
        CustomerCard cc = customerCardRepo.findByCustomerAndCard(customer, card).orElse(null);
        if (cc != null) return cc;
        return customerCardRepo.save(CustomerCard.create(customer, card));
    }

    public CustomerCard getCustomerCardById(String customerCardId) {
        return customerCardRepo.findById(customerCardId)
                .orElseThrow(() -> new NoSuchElementException("CustomerCard nicht gefunden: " + customerCardId));
    }

    public CustomerCard getCustomerCard(String customerId, String cardId) {
        Customer customer = getById(customerId);
        Card card = cardRepo.findById(cardId)
                .orElseThrow(() -> new NoSuchElementException("Karte nicht gefunden: " + cardId));
        return customerCardRepo.findByCustomerAndCard(customer, card)
                .orElseThrow(() -> new NoSuchElementException("Kunde hat diese Karte nicht"));
    }

    public List<CustomerCard> getAllCardsOfCustomer(String customerId) {
        Customer customer = getById(customerId);
        return customerCardRepo.findByCustomer(customer);
    }

    // Optional wie der Webhook-Dienst: Tests bauen den Dienst ohne dieses Protokoll.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.example.stemplekarte.repository.KorrekturLogRepository korrekturLogRepo;

    /**
     * DSGVO: Recht auf Loeschung. Entfernt den Kunden vollstaendig aus dem System —
     * seine CustomerCards und die zugehoerigen Apple-Geraeteregistrierungen
     * (Push-Tokens). Hinweis: In der Google Wallet kann eine bereits gespeicherte
     * Karte separat per API auf EXPIRED gesetzt werden; das hier entfernt die
     * personenbezogenen Daten aus DEINEM System.
     */
    @Transactional
    public void deleteCustomerData(String customerId) {
        Customer customer = customerRepo.findById(customerId)
                .orElseThrow(() -> new NoSuchElementException("Kunde nicht gefunden: " + customerId));

        List<CustomerCard> cards = customerCardRepo.findByCustomer(customer);
        for (CustomerCard cc : cards) {
            // serialNumber der Apple-Registrierung == CustomerCard-ID
            appleDeviceRepo.deleteBySerialNumber(cc.getId());
            // Korrektur-Protokoll der Filialen haengt an der Kundenkarte.
            if (korrekturLogRepo != null) korrekturLogRepo.deleteByCustomerCardId(cc.getId());
        }
        customerCardRepo.deleteAll(cards);
        customerRepo.delete(customer);

        log.info("DSGVO-Loeschung: Kunde {} mit {} Karte(n) entfernt", customerId, cards.size());
    }

    @Transactional
    public ScanResult processScan(String qrPayload, Shop shop, int count) {
        return processScan(qrPayload, shop, count, null);
    }

    /** staffLabel: Name des Zugangs, der gestempelt hat - nur fuers Protokoll. */
    @Transactional
    public ScanResult processScan(String qrPayload, Shop shop, int count, String staffLabel) {
        return processScan(qrPayload, shop, count, staffLabel, null);
    }

    /** ortId: Filiale (ShopOrt.id), in der gescannt wurde - nur fuers Protokoll. Null = Hauptstandort. */
    @Transactional
    public ScanResult processScan(String qrPayload, Shop shop, int count, String staffLabel, String ortId) {
        return stempeln(QrPayload.parse(qrPayload, customerCardRepo), shop, count, staffLabel, ortId);
    }

    /**
     * Stempeln ohne QR-Umweg. Der NFC-Weg kennt Kunde und Karte direkt.
     */
    @Transactional
    public ScanResult processScan(QrPayload qr, Shop shop, int count) {
        return stempeln(qr, shop, count, null, null);
    }

    /** NFC-Karte einer Filiale: Name der NFC-Karte und Filiale fuers Protokoll. */
    @Transactional
    public ScanResult stempelnPerNfc(QrPayload qr, Shop shop, int count, String staffLabel, String ortId) {
        return stempeln(qr, shop, count, staffLabel, ortId);
    }

    @Transactional
    public ScanResult stempeln(QrPayload qr, Shop shop, int count, String staffLabel, String ortId) {
        String customerId = qr.customerId();
        String cardId = qr.cardId();

        Card card = cardRepo.findById(cardId)
                .orElseThrow(() -> new NoSuchElementException("Karte nicht gefunden"));

        if (!card.getShop().getId().equals(shop.getId())) {
            throw new IllegalArgumentException("Diese Karte gehoert nicht zu deinem Shop");
        }

        if (!card.isActive()) {
            throw new IllegalArgumentException("Diese Karte ist nicht mehr aktiv");
        }

        CustomerCard cc = getOrCreateCustomerCard(customerId, cardId);

        // Gesperrt: kein Stempel, bevor das Geschenk eingeloest ist. Hier und
        // nicht im Controller, damit JEDER Stempel-Weg haengen bleibt - auch
        // ein Scanner mit altem Stand, der die Willkommens-Maske nicht kennt.
        // IllegalArgumentException = 400 mit dieser Meldung im Scanner;
        // IllegalStateException kaeme als 503 an und saehe nach Ausfall aus.
        if (cc.isWillkommenOffen()) {
            log.info("Scan abgelehnt, Willkommensgeschenk offen: Kunde {} Karte {}", customerId, cardId);
            throw new IllegalArgumentException(
                    "Erst das Willkommensgeschenk einlösen - bitte Scanner neu laden.");
        }

        int threshold = card.getRewardThreshold();


        ScanResult result = null;
        int rewardsEarnedThisScan = 0;
        // Eingeloeste Praemien in diesem Scan - fuer die Statistik je Filiale.
        int eingeloestThisScan = 0;

        // Mitgliedskarte: ein Scan = ein Besuch, egal wie viele Stempel der
        // Scanner mitschickt. Keine Schwelle, keine Praemie.
        if (card.isMember()) {
            count = 1;
            cc.addStamp();
            result = new ScanResult.Visited(cc, "Besuch erfasst (%d)".formatted(cc.getStamps()), 0);
        }

        // 10er-Karte: Besuche vom Paket abbuchen, hoechstens so viele wie
        // uebrig. Leer = nichts buchen, kein Besuch, kein Protokoll - das
        // Personal sieht "Paket leer" und laedt auf (PaketService).
        if (card.isPackage()) {
            if (cc.getPaketRest() <= 0) {
                log.info("Scan auf leerer 10er-Karte: Kunde {} Karte {}", customerId, cardId);
                return new ScanResult.Empty(cc, PaketService.leerText(card), 0);
            }
            count = cc.paketAbbuchen(count, java.time.Instant.now());
            result = new ScanResult.Debited(cc, PaketService.abgebuchtText(card, cc, count), 0);
        }

        for (int i = 0; i < count && !card.isMember() && !card.isPackage(); i++) {
            if (cc.getStamps() >= threshold) {
                cc.redeemReward(threshold);
                eingeloestThisScan++;
                result = new ScanResult.Redeemed(cc, card.getRewardText() + " eingeloest!", rewardsEarnedThisScan);
            } else {
                cc.addStamp();
                if (cc.getStamps() == threshold) {
                    rewardsEarnedThisScan++;
                    result = new ScanResult.Full(cc, "Karte voll! " + card.getRewardText(), rewardsEarnedThisScan);
                } else {
                    result = new ScanResult.Stamped(cc, "Stempel hinzugefuegt (%d/%d)"
                            .formatted(cc.getStamps(), threshold), rewardsEarnedThisScan);
                }
            }
        }

        customerCardRepo.save(cc);


        // Webhook (Kasse, CRM): in derselben Transaktion wie der Stempel.
        String ereignis = switch (result) {
            case ScanResult.Stamped s -> "stamp.added";
            case ScanResult.Full f -> "card.full";
            case ScanResult.Redeemed r -> "reward.redeemed";
            case ScanResult.Visited v -> "visit.recorded";
            case ScanResult.Debited d -> "package.debited";
            case ScanResult.Empty e -> "package.empty";   // kommt hier nicht an (oben zurueckgegeben)
        };
        webhook(card.getShop(), ereignis, WebhookService.daten(
                "customerId", customerId, "cardId", cardId, "customerCardId", cc.getId(),
                "stamps", cc.getStamps(), "totalRewards", cc.getTotalRewards(),
                "rewardThreshold", threshold, "stampsAdded", count,
                "rewardText", (result instanceof ScanResult.Full || result instanceof ScanResult.Redeemed)
                        ? card.getRewardText() : null,
                "remaining", card.isPackage() ? cc.getPaketRest() : null,
                "staff", staffLabel, "locationId", ortId));

        // Scan protokollieren (für Verlaufs-Statistik). Schlägt das Logging
        // fehl, darf der Scan selbst NICHT scheitern — daher abgesichert.
        try {
            // Mitglieds- und 10er-Karte: der Besuch steht im Protokoll, aber ohne
            // Stempel - wie bei der Punktekarte, damit die Stempel-Statistik sauber bleibt.
            scanLogRepo.save(ScanLog.create(
                    shop.getId(), cardId, customerId, card.isMember() || card.isPackage() ? 0 : count,
                    rewardsEarnedThisScan, staffLabel, ortId).eingeloest(eingeloestThisScan));
        } catch (Exception e) {
            log.warn("ScanLog konnte nicht gespeichert werden: {}", e.getMessage());
        }

        log.info("Scan fuer Kunde {} auf Karte {} ({}x): {}", customerId, cardId, count, result.message());
        return result;
    }


    /**
     * Willkommensgeschenk an der Kasse einloesen: Karte frei, 0 Stempel.
     *
     * Dieselben Pruefungen wie beim Stempeln (Laden, aktiv). Eine Kundenkarte
     * wird hier NICHT angelegt - ohne Anmeldung gibt es kein Geschenk.
     *
     * Karte und Kunde werden zuerst geladen, dann die Kundenkarte gesperrt.
     * Die Reihenfolge ist Absicht: die Sperr-Abfrage laedt keine Beziehungen
     * mit (siehe Repository), und so zeigen sie auf die hier schon geladenen
     * Objekte. Der Controller liest sie nach dem Ende der Transaktion.
     */
    @Transactional
    public CustomerCard willkommenEinloesen(String qrPayload, Shop shop) {
        QrPayload qr = QrPayload.parse(qrPayload, customerCardRepo);

        Card card = cardRepo.findById(qr.cardId())
                .orElseThrow(() -> new NoSuchElementException("Karte nicht gefunden"));
        if (!card.getShop().getId().equals(shop.getId())) {
            throw new IllegalArgumentException("Diese Karte gehoert nicht zu deinem Shop");
        }
        if (!card.isActive()) {
            throw new IllegalArgumentException("Diese Karte ist nicht mehr aktiv");
        }

        customerRepo.findById(qr.customerId())
                .orElseThrow(() -> new NoSuchElementException("Dieser Kunde hat die Karte noch nicht"));
        CustomerCard cc = customerCardRepo.findZumEinloesen(qr.customerId(), card.getId())
                .orElseThrow(() -> new NoSuchElementException("Dieser Kunde hat die Karte noch nicht"));

        // Wirft bei "schon eingeloest" - das Personal soll es sehen.
        cc.willkommenEinloesen(java.time.Instant.now());
        customerCardRepo.save(cc);

        log.info("Willkommensgeschenk eingeloest: Kunde {} Karte {} laden {}",
                qr.customerId(), card.getId(), shop.getId());
        return cc;
    }

    /**
     * Setzt eine Kundenkarte komplett zurück (Stempel + Belohnungszähler auf 0).
     * Wird vom Reset-Button im Scanner genutzt. Parst denselben QR-Payload wie
     * beim Stempeln und prüft, dass die Karte zum Shop des Mitarbeiters gehört.
     */
    @Transactional
    public CustomerCard resetCard(String qrPayload, Shop shop) {
        QrPayload qr = QrPayload.parse(qrPayload, customerCardRepo);
        String customerId = qr.customerId();
        String cardId = qr.cardId();

        Card card = cardRepo.findById(cardId)
                .orElseThrow(() -> new NoSuchElementException("Karte nicht gefunden"));

        if (!card.getShop().getId().equals(shop.getId())) {
            throw new IllegalArgumentException("Diese Karte gehoert nicht zu deinem Shop");
        }

        CustomerCard cc = getOrCreateCustomerCard(customerId, cardId);
        cc.resetAll();
        customerCardRepo.save(cc);

        log.info("Karte zurueckgesetzt: Kunde {} auf Karte {}", customerId, cardId);
        return cc;
    }
}