package com.example.stemplekarte.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.repository.CustomerCardRepository;

import java.util.NoSuchElementException;
import java.util.regex.Pattern;

/**
 * Der Inhalt des Kunden-QR-Codes: Kunde und Karte, sonst nichts.
 *
 * Lag zweimal wortgleich in CustomerService (processScan, resetCard). Der
 * Punkte-Dienst braucht dasselbe, deshalb hier an einer Stelle. Die
 * Fehlermeldungen sind absichtlich wortgleich zu vorher - sie erscheinen
 * dem Personal im Scanner, und eine Aenderung waere eine Verhaltensaenderung
 * durch die Hintertuer.
 *
 * Zwei Formen:
 * - kurz (neu): "SK:" + Kundenkarten-ID, z. B. SK:CC-1A2B3C4D. Kleiner
 *   QR-Code mit groben Punkten, liest sich auch bei Sonne und Sprung im Glas.
 *   Verraet ausserdem die Kunden-ID nicht mehr.
 * - lang (alt): {"cid":"...","cardId":"..."}. Liegt auf allen bisher
 *   ausgegebenen Karten und wird dauerhaft weiter gelesen.
 */
public record QrPayload(String customerId, String cardId) {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    public static final String KURZ = "SK:";
    private static final Pattern KARTEN_ID = Pattern.compile("[A-Za-z0-9_-]{1,60}");

    /** Inhalt des QR-Codes auf der Wallet-Karte (kurze Form). */
    public static String fuer(CustomerCard cc) {
        return KURZ + cc.getId();
    }

    /** Liest beide Formen; die kurze braucht die Datenbank. */
    public static QrPayload parse(String qrPayload, CustomerCardRepository repo) {
        String roh = qrPayload == null ? "" : qrPayload.trim();
        if (!roh.regionMatches(true, 0, KURZ, 0, KURZ.length())) return parse(roh);
        String id = roh.substring(KURZ.length()).trim();
        if (!KARTEN_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Ungueltiger QR-Code");
        }
        CustomerCard cc = repo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Karte nicht gefunden"));
        if (cc.getCustomer() == null || cc.getCard() == null) {
            throw new NoSuchElementException("Karte nicht gefunden");
        }
        return new QrPayload(cc.getCustomer().getId(), cc.getCard().getId());
    }


    public static QrPayload parse(String qrPayload) {
        String customerId;
        String cardId;
        try {
            JsonNode node = MAPPER.readTree(qrPayload);
            customerId = node.path("cid").asText();
            cardId = node.path("cardId").asText();
        } catch (Exception e) {
            throw new IllegalArgumentException("Ungueltiger QR-Code: " + e.getMessage());
        }
        if (customerId.isBlank() || cardId.isBlank()) {
            throw new IllegalArgumentException("QR enthaelt keine Kunden- oder Karten-ID");
        }
        return new QrPayload(customerId, cardId);
    }
}
