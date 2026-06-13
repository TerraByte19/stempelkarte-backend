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

    @Column(name = "recipient_count", nullable = false)
    private int recipientCount;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt;

    protected SentNewsletter() {}

    public static SentNewsletter create(Shop shop, String subject, String body,
                                        List<String> imageUrls, int recipientCount) {
        SentNewsletter n = new SentNewsletter();
        n.id = "NL-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
        n.shop = shop;
        n.subject = subject;
        n.body = body;
        n.imageUrls = (imageUrls != null) ? new ArrayList<>(imageUrls) : new ArrayList<>();
        n.recipientCount = recipientCount;
        n.sentAt = Instant.now();
        return n;
    }

    public String getId() { return id; }
    public Shop getShop() { return shop; }
    public String getSubject() { return subject; }
    public String getBody() { return body; }
    public List<String> getImageUrls() { return imageUrls; }
    public int getRecipientCount() { return recipientCount; }
    public Instant getSentAt() { return sentAt; }
}