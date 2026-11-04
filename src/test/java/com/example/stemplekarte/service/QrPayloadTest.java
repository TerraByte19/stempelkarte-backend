package com.example.stemplekarte.service;

import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.Customer;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.repository.CustomerCardRepository;
import org.junit.jupiter.api.Test;

import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Kurzer QR "SK:<Kundenkarte>" und der alte lange bleiben beide lesbar. */
class QrPayloadTest {

    private final CustomerCardRepository repo = mock(CustomerCardRepository.class);

    private CustomerCard karte() {
        Customer kunde = mock(Customer.class);
        when(kunde.getId()).thenReturn("CUST-1");
        Card card = mock(Card.class);
        when(card.getId()).thenReturn("CARD-1");
        CustomerCard cc = mock(CustomerCard.class);
        when(cc.getId()).thenReturn("CC-1A2B3C4D");
        when(cc.getCustomer()).thenReturn(kunde);
        when(cc.getCard()).thenReturn(card);
        return cc;
    }

    @Test
    void kurz_istKleinUndFindetDieKarte() {
        CustomerCard cc = karte();
        String inhalt = QrPayload.fuer(cc);
        assertThat(inhalt).isEqualTo("SK:CC-1A2B3C4D");
        // Nur Grossbuchstaben, Ziffern und ":-": QR im Alphanumerik-Modus, kleinste Stufe.
        assertThat(inhalt).matches("[0-9A-Z:-]+");

        when(repo.findById("CC-1A2B3C4D")).thenReturn(Optional.of(cc));
        assertThat(QrPayload.parse(inhalt, repo)).isEqualTo(new QrPayload("CUST-1", "CARD-1"));
        // Handscanner mit Leerzeichen oder kleinem Praefix.
        assertThat(QrPayload.parse("  sk:CC-1A2B3C4D\n", repo)).isEqualTo(new QrPayload("CUST-1", "CARD-1"));
    }

    @Test
    void lang_wirdWeiterGelesen_ohneDatenbank() {
        assertThat(QrPayload.parse("{\"cid\":\"CUST-9\",\"cardId\":\"CARD-9\"}", repo))
                .isEqualTo(new QrPayload("CUST-9", "CARD-9"));
        verify(repo, never()).findById(anyString());
    }

    @Test
    void kurz_unbekanntOderKaputt() {
        when(repo.findById("CC-00000000")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> QrPayload.parse("SK:CC-00000000", repo))
                .isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> QrPayload.parse("SK:", repo))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> QrPayload.parse("SK:{\"x\":1}", repo))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> QrPayload.parse("hallo", repo))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repo, never()).findById("");
    }
}
