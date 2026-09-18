package com.example.stemplekarte.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Gespeicherter Verlauf eines versendeten Newsletters. Ermöglicht dem Laden,
 * im Dashboard nachzulesen, was er zuletzt verschickt hat (Betreff, Text,
 * Bilder, Datum, Empfängerzahl).
 *
 * Der Eintrag wird beim Start des Versands angelegt (Status LAEUFT) und am
 * Ende mit dem echten Ergebnis abgeschlossen. Frueher wurden nur die
 * VERSUCHE gezaehlt - eine abgelehnte Adresse oder ein erreichtes
 * Tageslimit sah im Verlauf trotzdem wie ein erfolgreicher Versand aus.
 */
@Entity
@Table(name = "sent_newsletters")
public class SentNewsletter {

    @Id
    @Column(length = 64)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shop_id", nullable = false)
    private Shop shop;

    @Column(nullable = false, length = 200)
    private String subject;

    @Column(nullable = false, columnDefinition = "text")
    private String body;

    // Bild-URLs als eigene Tabelle (eine Zeile pro Bild)
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "sent_newsletter_images",
            joinColumns = @JoinColumn(name = "newsletter_id"))
    @Column(name = "image_url", length = 500)
    private List<String> imageUrls = new ArrayList<>();

    // Tatsaechlich beim Mailserver angenommene Mails (nicht: Versuche).
    @Column(name = "recipient_count", nullable = false)
    private int recipientCount;

    @Column(name = "failed_count", nullable = false,
            columnDefinition = "integer default 0")
    private int failedCount;

    // LAEUFT waehrend des Versands, FERTIG danach. Alte Eintraege haben
    // NULL - die gelten als FERTIG.
    @Column(name = "status", length = 16)
    private String status;

    // Erste paar Adressen, bei denen es geklemmt hat - damit der Laden
    // sieht, WO das Problem liegt, statt nur DASS eins da ist.
    @Column(name = "failed_sample", length = 500)
    private String failedSample;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt;

    protected SentNewsletter() {}

    /** Legt den Eintrag an, bevor die erste Mail rausgeht. */
    public static SentNewsletter starte(Shop shop, String subject, String body,
                                        List<String> imageUrls) {
        SentNewsletter n = new SentNewsletter();
        n.id = "NL-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
        n.shop = shop;
        n.subject = subject;
        n.body = body;
        n.imageUrls = (imageUrls != null) ? new ArrayList<>(imageUrls) : new ArrayList<>();
        n.recipientCount = 0;
        n.failedCount = 0;
        n.status = LAEUFT;
        n.sentAt = Instant.now();
        return n;
    }

    public static final String LAEUFT = "RUNNING";
    public static final String FERTIG = "DONE";
    public static final String UNTERBROCHEN = "INTERRUPTED";

    /**
     * Der Server wurde mitten im Versand neu gestartet (Deploy). Wie viele
     * Mails vorher rausgingen, weiss niemand mehr - das ehrlich anzeigen
     * statt den Eintrag fuer immer auf "wird versendet" stehen zu lassen.
     */
    public void alsUnterbrochenMarkieren() {
        this.status = UNTERBROCHEN;
    }

    /** Traegt das Versandergebnis ein und schliesst den Eintrag ab. */
    public void beende(int gesendet, List<String> fehlgeschlagen) {
        this.recipientCount = gesendet;
        this.failedCount = (fehlgeschlagen != null) ? fehlgeschlagen.size() : 0;
        this.failedSample = probe(fehlgeschlagen);
        this.status = FERTIG;
    }

    private static String probe(List<String> adressen) {
        if (adressen == null || adressen.isEmpty()) return null;
        String s = String.join(", ", adressen.subList(0, Math.min(5, adressen.size())));
        return (s.length() > 500) ? s.substring(0, 497) + "..." : s;
    }

    public String getId() { return id; }
    public Shop getShop() { return shop; }
    public String getSubject() { return subject; }
    public String getBody() { return body; }
    public List<String> getImageUrls() { return imageUrls; }
    public int getRecipientCount() { return recipientCount; }
    public int getFailedCount() { return failedCount; }
    public String getStatus() { return (status != null) ? status : FERTIG; }
    public String getFailedSample() { return failedSample; }
    public Instant getSentAt() { return sentAt; }
}