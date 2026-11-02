package com.example.stemplekarte.service;

import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.Customer;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.Kampagne;
import com.example.stemplekarte.model.KampagneEmpfaenger;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.repository.CustomerCardRepository;
import com.example.stemplekarte.repository.KampagneEmpfaengerRepository;
import com.example.stemplekarte.repository.KampagneRepository;
import com.example.stemplekarte.repository.ShopRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Bewertungs-Erinnerung: ab 10 Uhr, einmal pro Laden und Tag, nur mit Link
 * und eingeschaltet. Nach dem Start tragen ALLE Karten der Person bei
 * diesem Laden den Merker - sonst klingelt es auf der zweiten Karte noch mal.
 */
class BewertungsErinnerungTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
    private static final String URL = "https://g.page/r/abc/review";

    private Shop shop;
    private ShopRepository shopRepo;
    private KampagneRepository kampagneRepo;
    private KampagneEmpfaengerRepository empfaengerRepo;
    private CustomerCardRepository ccRepo;
    private KampagnenService kampagnen;
    private BewertungsErinnerungService dienst;
    private final Instant um11 = LocalDate.of(2026, 11, 3).atTime(11, 0).atZone(ZONE).toInstant();

    @BeforeEach
    void setUp() {
        shop = mock(Shop.class);
        when(shop.getId()).thenReturn("SHOP-1");
        when(shop.isActive()).thenReturn(true);
        when(shop.getBewertungErinnerungAb()).thenReturn(3);
        when(shop.getGoogleReviewUrl()).thenReturn(URL);
        shopRepo = mock(ShopRepository.class);
        when(shopRepo.findAll()).thenReturn(List.of(shop));
        kampagneRepo = mock(KampagneRepository.class);
        empfaengerRepo = mock(KampagneEmpfaengerRepository.class);
        ccRepo = mock(CustomerCardRepository.class);
        kampagnen = mock(KampagnenService.class);
        TransactionTemplate tx = mock(TransactionTemplate.class);
        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Consumer<TransactionStatus> c = inv.getArgument(0);
            c.accept(null);
            return null;
        }).when(tx).executeWithoutResult(any());
        dienst = new BewertungsErinnerungService(shopRepo, kampagneRepo, empfaengerRepo, ccRepo, kampagnen, tx);
    }

    private Kampagne kampagne() {
        return Kampagne.starte(shop, BewertungsErinnerungService.text(shop), Zielgruppe.BEWERTUNG, 1, um11);
    }

    @Test
    void vorZehnUhr_nichts() {
        Instant um9 = LocalDate.of(2026, 11, 3).atTime(9, 59).atZone(ZONE).toInstant();
        assertThat(dienst.lauf(um9)).isZero();
        verify(kampagnen, never()).starte(any(), anyString(), anyString());
    }

    @Test
    void ausOderOhneLink_nichts() {
        when(shop.getBewertungErinnerungAb()).thenReturn(0);
        assertThat(dienst.lauf(um11)).isZero();
        when(shop.getBewertungErinnerungAb()).thenReturn(3);
        when(shop.getGoogleReviewUrl()).thenReturn(null);
        assertThat(dienst.lauf(um11)).isZero();
        verify(kampagnen, never()).starte(any(), anyString(), anyString());
    }

    @Test
    void startet_einmalProTag_undMarkiertAlleKartenDerPerson() {
        Kampagne k = kampagne();
        when(kampagnen.starte(eq(shop), anyString(), eq("BEWERTUNG"))).thenReturn(k);

        Customer kunde = mock(Customer.class);
        Card stempel = Card.create(shop, "Kaffee", "x", 10, "Gratis Kaffee");
        Card punkte = Card.create(shop, "Punkte", "x", 10, "x");
        Shop fremd = mock(Shop.class);
        when(fremd.getId()).thenReturn("SHOP-2");
        Card fremdeKarte = Card.create(fremd, "Anderswo", "x", 10, "x");
        CustomerCard empfaenger = CustomerCard.create(kunde, stempel);
        CustomerCard zweite = CustomerCard.create(kunde, punkte);
        CustomerCard woanders = CustomerCard.create(kunde, fremdeKarte);

        KampagneEmpfaenger zeile = mock(KampagneEmpfaenger.class);
        when(zeile.getCustomerCardId()).thenReturn(empfaenger.getId());
        when(empfaengerRepo.findByKampagneId(k.getId())).thenReturn(List.of(zeile));
        when(ccRepo.findAllById(List.of(empfaenger.getId()))).thenReturn(List.of(empfaenger));
        when(ccRepo.findByCustomer(kunde)).thenReturn(List.of(empfaenger, zweite, woanders));

        assertThat(dienst.lauf(um11)).isEqualTo(1);
        assertThat(empfaenger.getBewertungErinnertAm()).isEqualTo(um11);
        assertThat(zweite.getBewertungErinnertAm()).isEqualTo(um11);
        assertThat(woanders.getBewertungErinnertAm()).isNull();
        verify(ccRepo).save(empfaenger);
        verify(ccRepo).save(zweite);
        verify(ccRepo, never()).save(woanders);

        // Zweiter Lauf am selben Tag: nichts. Naechster Tag wieder.
        assertThat(dienst.lauf(um11.plusSeconds(900))).isZero();
        assertThat(dienst.lauf(um11.plusSeconds(86_400))).isEqualTo(1);
    }

    @Test
    void heuteSchonInDerDatenbank_keinZweiter() {
        when(kampagneRepo.existsByShopAndGruppeAndErstelltAmGreaterThanEqual(eq(shop), eq("BEWERTUNG"), any()))
                .thenReturn(true);
        assertThat(dienst.lauf(um11)).isZero();
        verify(kampagnen, never()).starte(any(), anyString(), anyString());
    }

    @Test
    void niemandFaellig_morgenWieder() {
        when(kampagnen.starte(eq(shop), anyString(), eq("BEWERTUNG")))
                .thenThrow(new IllegalArgumentException("In dieser Gruppe ist gerade niemand"));
        assertThat(dienst.lauf(um11)).isZero();
        assertThat(dienst.lauf(um11.plusSeconds(86_400))).isZero();
        verify(kampagnen, org.mockito.Mockito.times(2)).starte(eq(shop), anyString(), eq("BEWERTUNG"));
    }

    @Test
    void text_langKurzOderGarNicht() {
        assertThat(BewertungsErinnerungService.text(shop))
                .isEqualTo("Danke für deinen Besuch! Magst du uns bei Google bewerten? " + URL);
        when(shop.getGoogleReviewUrl()).thenReturn("https://x.de/" + "a".repeat(110));
        assertThat(BewertungsErinnerungService.text(shop)).startsWith("Magst du uns bei Google bewerten? https://x.de/");
        assertThat(BewertungsErinnerungService.text(shop).length()).isLessThanOrEqualTo(Kampagne.TEXT_MAX);
        when(shop.getGoogleReviewUrl()).thenReturn("https://x.de/" + "a".repeat(200));
        assertThat(BewertungsErinnerungService.text(shop)).isNull();
    }

    // ── Einstellung im ShopService ──────────────────────────────────────

    private ShopService shopService(Shop laden) {
        ShopRepository repo = mock(ShopRepository.class);
        when(repo.findById("SHOP-1")).thenReturn(Optional.of(laden));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        return new ShopService(repo, null, null, null);
    }

    @Test
    void einstellung_brauchtLink_bereichUndLinkWegSchaltetAus() {
        Shop laden = Shop.create("a@b.de", "hash", "Café Mokka", 3);
        ShopService dienst = shopService(laden);
        assertThatThrownBy(() -> dienst.updateBewertungErinnerung("SHOP-1", 3))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Google-Link");
        dienst.updateGoogleReviewUrl("SHOP-1", URL);
        assertThatThrownBy(() -> dienst.updateBewertungErinnerung("SHOP-1", 21))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> dienst.updateBewertungErinnerung("SHOP-1", -1))
                .isInstanceOf(IllegalArgumentException.class);
        dienst.updateBewertungErinnerung("SHOP-1", 3);
        assertThat(laden.getBewertungErinnerungAb()).isEqualTo(3);
        // Aus geht immer.
        dienst.updateBewertungErinnerung("SHOP-1", 0);
        assertThat(laden.getBewertungErinnerungAb()).isZero();
        // Link entfernen schaltet die Erinnerung mit aus.
        dienst.updateBewertungErinnerung("SHOP-1", 5);
        dienst.updateGoogleReviewUrl("SHOP-1", "");
        assertThat(laden.getBewertungErinnerungAb()).isZero();
    }
}
