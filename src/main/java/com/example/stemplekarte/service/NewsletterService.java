package com.example.stemplekarte.service;

import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.SentNewsletter;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.repository.CustomerCardRepository;
import com.example.stemplekarte.repository.SentNewsletterRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Versand eines Newsletters an alle Kunden eines Ladens.
 *
 * Laeuft als EIN Hintergrund-Job und sendet der Reihe nach. Vorher wurde pro
 * Empfaenger ein eigener Async-Aufruf gestartet: der Laden bekam sofort eine
 * Zahl zurueck, die nur die VERSUCHE zaehlte, und ob eine Mail wirklich
 * rausging, stand allenfalls im Server-Log. Jetzt wird das Ergebnis pro
 * Empfaenger ausgewertet und im Verlauf gespeichert.
 *
 * Der Reihe nach statt parallel, damit der Mailserver (Brevo) nicht mit
 * hunderten gleichzeitigen Verbindungen beschossen wird.
 */
@Service
public class NewsletterService {

    private static final Logger log = LoggerFactory.getLogger(NewsletterService.class);

    private final CustomerCardRepository customerCardRepo;
    private final SentNewsletterRepository verlaufRepo;
    private final EmailService emailService;

    @Value("${stempelkarte.base-url:http://localhost:8080}")
    private String baseUrl;

    public NewsletterService(CustomerCardRepository customerCardRepo,
                             SentNewsletterRepository verlaufRepo,
                             EmailService emailService) {
        this.customerCardRepo = customerCardRepo;
        this.verlaufRepo = verlaufRepo;
        this.emailService = emailService;
    }

    /**
     * Ein Empfaenger, losgeloest von der Datenbank. Der Versand laeuft in
     * einem anderen Thread ohne offene JPA-Session, deshalb werden die
     * noetigen Werte vorher herauskopiert statt Entities mitzuschleppen.
     */
    public record Empfaenger(String email, String customerId,
                             String customerCardId, String authToken) {}

    /**
     * Empfaenger des Newsletters: eine Zeile pro PERSON, nicht pro Karte.
     * Hat jemand zwei Karten desselben Ladens, ist das trotzdem ein Kunde
     * und eine Mail. Genommen wird die erste Karte mit Einwilligung - ueber
     * die laeuft der Abmelde-Link.
     */
    public List<CustomerCard> empfaenger(Shop shop) {
        Map<String, CustomerCard> proKunde = new LinkedHashMap<>();
        for (CustomerCard cc : customerCardRepo.findByCard_ShopAndMarketingConsentTrue(shop)) {
            proKunde.putIfAbsent(cc.getCustomer().getId(), cc);
        }
        return List.copyOf(proKunde.values());
    }

    /** Nur die, an die wirklich gesendet werden darf (Double-Opt-In). */
    public List<Empfaenger> bestaetigteEmpfaenger(Shop shop) {
        return empfaenger(shop).stream()
                .filter(cc -> cc.getCustomer().isEmailConfirmed())
                .map(cc -> new Empfaenger(
                        cc.getCustomer().getEmail(),
                        cc.getCustomer().getId(),
                        cc.getId(),
                        cc.getAuthToken()))
                .toList();
    }

    /**
     * Versendet im Hintergrund und traegt danach das Ergebnis in den
     * Verlaufs-Eintrag ein. Wirft nichts nach aussen - der Eintrag wird
     * auch bei einem Fehler mittendrin abgeschlossen, sonst haengt er
     * fuer immer auf "wird versendet".
     */
    @Async("newsletterExecutor")
    public void versendeImHintergrund(String newsletterId, Shop shop, List<Empfaenger> empfaenger,
                                      String subject, String body, List<String> imageUrls) {
        int gesendet = 0;
        List<String> fehlgeschlagen = new ArrayList<>();
        try {
            for (Empfaenger e : empfaenger) {
                boolean ok = emailService.sendNewsletterMail(
                        e.email(),
                        shop,                 // Branding (Logo + Hero-Bild im Header)
                        shop.getEmail(),      // Reply-To = der Laden
                        subject,
                        body,
                        imageUrls,
                        baseUrl + "/mail/unsubscribe?cc=" + e.customerCardId() + "&t=" + e.authToken(),
                        baseUrl + "/mail/delete-request?c=" + e.customerId());
                if (ok) gesendet++;
                else fehlgeschlagen.add(e.email());
            }
        } catch (RuntimeException ex) {
            log.error("Newsletter {} mittendrin abgebrochen: {}", newsletterId, ex.getMessage(), ex);
        } finally {
            abschliessen(newsletterId, gesendet, fehlgeschlagen);
        }
    }

    /**
     * Beim Hochfahren: Eintraege, die noch auf LAEUFT stehen, kann niemand
     * mehr zu Ende bringen - der Thread, der sie versendet hat, ist mit dem
     * alten Prozess gestorben (Deploy, Absturz). Ohne das haengt der Verlauf
     * fuer immer auf "wird versendet".
     *
     * Gilt so nur bei einer laufenden Instanz. Kaemen mehrere dazu, muesste
     * hier zusaetzlich die Instanz mitgeschrieben werden.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void haengendeVersaendeAufraeumen() {
        try {
            List<SentNewsletter> haengend = verlaufRepo.findByStatus(SentNewsletter.LAEUFT);
            for (SentNewsletter n : haengend) {
                n.alsUnterbrochenMarkieren();
                verlaufRepo.save(n);
            }
            if (!haengend.isEmpty()) {
                log.warn("{} Newsletter-Versand(e) wurden durch einen Neustart unterbrochen", haengend.size());
            }
        } catch (RuntimeException ex) {
            log.error("Aufraeumen haengender Newsletter fehlgeschlagen: {}", ex.getMessage());
        }
    }

    private void abschliessen(String newsletterId, int gesendet, List<String> fehlgeschlagen) {
        try {
            verlaufRepo.findById(newsletterId).ifPresent(n -> {
                n.beende(gesendet, fehlgeschlagen);
                verlaufRepo.save(n);
            });
            log.info("Newsletter {} fertig: {} gesendet, {} fehlgeschlagen",
                    newsletterId, gesendet, fehlgeschlagen.size());
        } catch (RuntimeException ex) {
            log.error("Ergebnis von Newsletter {} nicht gespeichert: {}", newsletterId, ex.getMessage());
        }
    }
}
