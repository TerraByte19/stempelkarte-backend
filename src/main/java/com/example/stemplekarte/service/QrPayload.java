package com.example.stemplekarte.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Der Inhalt des Kunden-QR-Codes: Kunde und Karte, sonst nichts.
 *
 * Lag zweimal wortgleich in CustomerService (processScan, resetCard). Der
 * Punkte-Dienst braucht dasselbe, deshalb hier an einer Stelle. Die
 * Fehlermeldungen sind absichtlich wortgleich zu vorher - sie erscheinen
 * dem Personal im Scanner, und eine Aenderung waere eine Verhaltensaenderung
 * durch die Hintertuer.
 */
public record QrPayload(String customerId, String cardId) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
