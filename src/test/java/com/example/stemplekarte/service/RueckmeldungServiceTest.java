package com.example.stemplekarte.service;

import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.Customer;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.Rueckmeldung;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.repository.CustomerCardRepository;
import com.example.stemplekarte.repository.RueckmeldungRepository;
import com.example.stemplekarte.repository.ShopRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Rueckmeldung abgeben und lesen. Die Tueren beim Abgeben sind der Kern:
 * ohne Karte nichts, ohne Einschalten nichts, einmal am Tag.
 */
class RueckmeldungServiceTest {

    private final Instant jetzt = Instant.parse("2026-11-02T12:00:00Z");
    private RueckmeldungRepository repo;
    private CustomerCardRepository ccRepo;
    private ShopRepository shopRepo;
    private RueckmeldungService dienst;
    private Shop shop;
    private Card card;
    private CustomerCard cc;

    @BeforeEach
    void aufbau() {
        repo = mock(RueckmeldungRepository.class);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ccRepo = mock(CustomerCardRepository.class);
        shopRepo = mock(ShopRepository.class);
        dienst = new RueckmeldungService(repo, ccRepo, shopRepo);
        shop = Shop.create("a@b.de", "hash", "Café Mokka", 3);
        shop.setRueckmeldungAktiv(true);
        card = Card.create(shop, "Kaffeekarte", "x", 10, "Gratis Kaffee");
        cc = CustomerCard.create(Customer.create("Adham", "adham@test.de"), card);
        when(ccRepo.findByCustomer_IdAndCard_Id("CUST-1", "CARD-1")).thenReturn(Optional.of(cc));
    }

    @Test
    void abgeben_speichertMitKarteUndGetrimmtemText() {
        Rueckmeldung r = dienst.abgeben("CUST-1", "CARD-1", 4, "  Super Kaffee  ", jetzt);
        assertThat(r.getSterne()).isEqualTo(4);
        assertThat(r.getText()).isEqualTo("Super Kaffee");
        assertThat(r.getCustomerCardId()).isEqualTo(cc.getId());
        assertThat(r.getShop()).isSameAs(shop);
        assertThat(r.isGelesen()).isFalse();
        assertThat(r.getEingegangenAm()).isEqualTo(jetzt);
    }

    @Test
    void abgeben_leererTextWirdNull() {
        assertThat(dienst.abgeben("CUST-1", "CARD-1", 5, "   ", jetzt).getText()).isNull();
        assertThat(dienst.abgeben("CUST-1", "CARD-1", 5, null, jetzt).getText()).isNull();
    }

    @Test
    void abgeben_sterneNurEinsBisFuenf_textBegrenzt() {
        assertThatThrownBy(() -> dienst.abgeben("CUST-1", "CARD-1", 0, null, jetzt))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> dienst.abgeben("CUST-1", "CARD-1", 6, null, jetzt))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> dienst.abgeben("CUST-1", "CARD-1", 3, "x".repeat(1001), jetzt))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("1000");
        verify(repo, never()).save(any());
    }

    @Test
    void abgeben_unbekannteKarte_legtNichtsAn() {
        assertThatThrownBy(() -> dienst.abgeben("CUST-X", "CARD-1", 3, null, jetzt))
                .isInstanceOf(NoSuchElementException.class);
        verify(repo, never()).save(any());
    }

    @Test
    void abgeben_ladenHatNichtEingeschaltet() {
        shop.setRueckmeldungAktiv(false);
        assertThatThrownBy(() -> dienst.abgeben("CUST-1", "CARD-1", 3, null, jetzt))
                .isInstanceOf(RueckmeldungService.Aus.class);
        verify(repo, never()).save(any());
    }

    @Test
    void abgeben_einmalProKarteUndTag() {
        ArgumentCaptor<Instant> seit = ArgumentCaptor.forClass(Instant.class);
        when(repo.existsByCustomerCardIdAndEingegangenAmAfter(eq(cc.getId()), seit.capture())).thenReturn(true);
        assertThatThrownBy(() -> dienst.abgeben("CUST-1", "CARD-1", 3, null, jetzt))
                .isInstanceOf(RueckmeldungService.HeuteSchon.class);
        assertThat(seit.getValue()).isEqualTo(jetzt.minus(Duration.ofHours(24)));
        verify(repo, never()).save(any());
    }

    @Test
    void stand_schnittAnzahlUndNamen() {
        Rueckmeldung a = Rueckmeldung.neu(shop, cc.getId(), 5, "Top", jetzt.minus(Duration.ofDays(1)));
        Rueckmeldung b = Rueckmeldung.neu(shop, "CC-WEG", 2, null, jetzt.minus(Duration.ofDays(2)));
        b.gelesen();
        when(repo.findTop50ByShopOrderByEingegangenAmDesc(shop)).thenReturn(List.of(a, b));
        when(repo.findByShopAndEingegangenAmAfter(eq(shop), any())).thenReturn(List.of(a, b));
        when(repo.countByShopAndGelesenFalse(shop)).thenReturn(1L);
        when(ccRepo.findAllById(any())).thenReturn(List.of(cc));

        RueckmeldungService.Stand s = dienst.stand(shop, jetzt);
        assertThat(s.aktiv()).isTrue();
        assertThat(s.ungelesen()).isEqualTo(1);
        assertThat(s.schnitt()).isEqualTo(3.5);
        assertThat(s.anzahl30()).isEqualTo(2);
        assertThat(s.items()).hasSize(2);
        assertThat(s.items().get(0).kunde()).isEqualTo("Adham");
        assertThat(s.items().get(0).karte()).isEqualTo("Kaffeekarte");
        assertThat(s.items().get(0).text()).isEqualTo("Top");
        // Karte geloescht: Zeile bleibt, Name leer, Text nie null.
        assertThat(s.items().get(1).kunde()).isEmpty();
        assertThat(s.items().get(1).text()).isEmpty();
        assertThat(s.items().get(1).gelesen()).isTrue();
    }

    @Test
    void stand_ohneRueckmeldungen_schnittNull() {
        when(repo.findTop50ByShopOrderByEingegangenAmDesc(shop)).thenReturn(List.of());
        when(repo.findByShopAndEingegangenAmAfter(eq(shop), any())).thenReturn(List.of());
        when(ccRepo.findAllById(any())).thenReturn(List.of());
        RueckmeldungService.Stand s = dienst.stand(shop, jetzt);
        assertThat(s.schnitt()).isEqualTo(0.0);
        assertThat(s.anzahl30()).isZero();
        assertThat(s.items()).isEmpty();
    }

    @Test
    void gelesen_nurEigeneRueckmeldung() {
        Rueckmeldung r = Rueckmeldung.neu(shop, cc.getId(), 5, null, jetzt);
        when(repo.findByIdAndShop(r.getId(), shop)).thenReturn(Optional.of(r));
        dienst.gelesen(shop, r.getId());
        assertThat(r.isGelesen()).isTrue();
        assertThatThrownBy(() -> dienst.gelesen(shop, "RM-fremd"))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void schalten_schreibtAufFrischenShop() {
        Shop frisch = Shop.create("a@b.de", "hash", "Café Mokka", 3);
        when(shopRepo.findById(shop.getId())).thenReturn(Optional.of(frisch));
        when(shopRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        assertThat(dienst.schalten(shop, true)).isTrue();
        assertThat(frisch.isRueckmeldungAktiv()).isTrue();
        assertThat(dienst.schalten(shop, false)).isFalse();
        assertThat(frisch.isRueckmeldungAktiv()).isFalse();
    }
}
