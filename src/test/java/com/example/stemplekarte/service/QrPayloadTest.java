package com.example.stemplekarte.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Das Parsen des QR-Inhalts lag zweimal wortgleich in CustomerService und
 * wird vom Punkte-Dienst ein drittes Mal gebraucht. Dieser Test haelt fest,
 * dass die gemeinsame Fassung sich genau wie die alten Kopien verhaelt -
 * besonders bei den Fehlerfaellen, denn deren Meldung sieht das Personal
 * im Scanner.
 */
class QrPayloadTest {

    @Test
    void liestKundeUndKarte() {
        QrPayload p = QrPayload.parse("{\"cid\":\"CUST-1\",\"cardId\":\"CARD-9\"}");
        assertThat(p.customerId()).isEqualTo("CUST-1");
        assertThat(p.cardId()).isEqualTo("CARD-9");
    }

    @Test
    void zusaetzlicheFelderStoerenNicht() {
        // Aeltere Karten hatten einen Zeitstempel im QR. Die liegen noch in
        // echten Wallets und muessen weiter lesbar sein.
        QrPayload p = QrPayload.parse("{\"cid\":\"CUST-1\",\"cardId\":\"CARD-9\",\"ts\":123}");
        assertThat(p.cardId()).isEqualTo("CARD-9");
    }

    @Test
    void keinJson_meldetUngueltigenQr() {
        assertThatThrownBy(() -> QrPayload.parse("kein json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Ungueltiger QR-Code");
    }

    @Test
    void fehlendeKartenId_meldetFehlendeId() {
        assertThatThrownBy(() -> QrPayload.parse("{\"cid\":\"CUST-1\"}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Karten-ID");
    }
}
