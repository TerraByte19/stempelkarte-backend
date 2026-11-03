package com.example.stemplekarte.service;

import com.example.stemplekarte.config.AppProperties;
import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.Customer;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.PointsRounding;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.repository.CustomerCardRepository;
import com.example.stemplekarte.wallet.GoogleWalletService;
import com.example.stemplekarte.wallet.WalletNotifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Kunde wirbt Kunde: Code und Link, Verknuepfen nur fuer dieselbe Karte
 * und nicht sich selbst, Bonus einmal beim ersten Besuch fuer beide,
 * gedeckelt bei der Schwelle.
 */
class EmpfehlungServiceTest {

    private Shop shop;
    private Card card;
    private CustomerCardRepository ccRepo;
    private WalletNotifier notifier;
    private EmpfehlungService dienst;
    private final Instant jetzt = Instant.now();

    @BeforeEach
    void setUp() {
        shop = mock(Shop.class);
        when(shop.getId()).thenReturn("SHOP-1");
        card = Card.create(shop, "Kaffee", "10 Stempel", 10, "Gratis Kaffee");
        card.updateEmpfehlung(true, 2);
        ccRepo = mock(CustomerCardRepository.class);
        when(ccRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        AppProperties props = mock(AppProperties.class);
        when(props.baseUrl()).thenReturn("https://stampit.test");
        notifier = mock(WalletNotifier.class);
        dienst = new EmpfehlungService(ccRepo, props, notifier);
    }

    private CustomerCard karte(Card c, String kundeId, int stempel) {
        Customer k = mock(Customer.class);
        when(k.getId()).thenReturn(kundeId);
        when(k.getName()).thenReturn("Kunde " + kundeId);
        CustomerCard cc = CustomerCard.create(k, c);
        for (int i = 0; i < stempel; i++) cc.addStamp();
        when(ccRepo.findById(cc.getId())).thenReturn(Optional.of(cc));
        return cc;
    }

    private CustomerCard werber(int stempel) {
        CustomerCard w = karte(card, "CUST-W", stempel);
        w.empfehlungsCodeSetzen("ABC234");
        when(ccRepo.findByEmpfehlungsCode("ABC234")).thenReturn(Optional.of(w));
        return w;
    }

    // ── Code und Link ───────────────────────────────────────────────────

    @Test
    void code_sechsZeichen_ohneVerwechselbare_undLink() {
        String code = dienst.neuerCode();
        assertThat(code).hasSize(6).matches("[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]+");
        CustomerCard cc = karte(card, "CUST-1", 0);
        assertThat(cc.getEmpfehlungsCode()).isNull();
        dienst.codeSicherstellen(cc);
        assertThat(cc.getEmpfehlungsCode()).hasSize(6);
        verify(ccRepo).save(cc);
        // Zweites Mal: nichts.
        dienst.codeSicherstellen(cc);
        verify(ccRepo).save(cc);
        assertThat(dienst.link(cc)).isEqualTo("https://stampit.test/karte-neu/" + card.getId() + "?ref=" + cc.getEmpfehlungsCode());
    }

    @Test
    void code_belegt_wirdNeuGewuerfelt() {
        when(ccRepo.existsByEmpfehlungsCode(anyString())).thenReturn(true, false);
        assertThat(dienst.neuerCode()).hasSize(6);
        verify(ccRepo, org.mockito.Mockito.times(2)).existsByEmpfehlungsCode(anyString());
    }

    @Test
    void codeSauber_grossUndOhneLeerzeichen() {
        assertThat(EmpfehlungService.codeSauber(" abc-234 ")).isEqualTo("ABC234");
        assertThat(EmpfehlungService.codeSauber("  ")).isNull();
        assertThat(EmpfehlungService.codeSauber(null)).isNull();
    }

    // ── Verknuepfen ─────────────────────────────────────────────────────

    @Test
    void verknuepfen_gleicheKarte_nichtSelbst_nurEinmal() {
        werber(3);
        CustomerCard neu = karte(card, "CUST-N", 0);
        assertThat(dienst.verknuepfen(neu, " abc234 ", jetzt)).isTrue();
        assertThat(neu.getGeworbenVon()).isEqualTo(werberId());
        assertThat(neu.getGeworbenAm()).isEqualTo(jetzt);
        // Schon geworben: bleibt.
        assertThat(dienst.verknuepfen(neu, "ABC234", jetzt)).isFalse();

        // Sich selbst werben: nein.
        CustomerCard selbst = karte(card, "CUST-W", 0);
        assertThat(dienst.verknuepfen(selbst, "ABC234", jetzt)).isFalse();

        // Unbekannter Code, leerer Code: nein, keine Ausnahme.
        assertThat(dienst.verknuepfen(karte(card, "CUST-X", 0), "ZZZZZZ", jetzt)).isFalse();
        assertThat(dienst.verknuepfen(karte(card, "CUST-X", 0), null, jetzt)).isFalse();
    }

    private String werberId() {
        return ccRepo.findByEmpfehlungsCode("ABC234").orElseThrow().getId();
    }

    @Test
    void verknuepfen_andereKarte_oderAus_nein() {
        werber(3);
        Card andere = Card.create(shop, "Tee", "x", 8, "Gratis Tee");
        andere.updateEmpfehlung(true, 1);
        assertThat(dienst.verknuepfen(karte(andere, "CUST-N", 0), "ABC234", jetzt)).isFalse();

        card.updateEmpfehlung(false, 2);
        assertThat(dienst.verknuepfen(karte(card, "CUST-N", 0), "ABC234", jetzt)).isFalse();
    }

    // ── Bonus ───────────────────────────────────────────────────────────

    @Test
    void belohnen_beideBekommenBonus_einmal_pushAnWerber() {
        CustomerCard w = werber(3);
        CustomerCard neu = karte(card, "CUST-N", 1);
        neu.geworbenVon(w.getId(), jetzt);

        EmpfehlungService.Bonus b = dienst.belohnen(neu, jetzt);
        assertThat(b).isEqualTo(new EmpfehlungService.Bonus(2, false));
        assertThat(neu.getStamps()).isEqualTo(3);
        assertThat(w.getStamps()).isEqualTo(5);
        assertThat(neu.getEmpfehlungBonusAm()).isEqualTo(jetzt);
        verify(ccRepo).save(w);
        verify(notifier).nachStempelAenderung(w, GoogleWalletService.Meldung.STEMPEL);

        // Zweites Mal: nichts mehr.
        assertThat(dienst.belohnen(neu, jetzt)).isNull();
        assertThat(w.getStamps()).isEqualTo(5);
    }

    @Test
    void belohnen_gedeckeltBeiSchwelle_keinBesuchBeimWerber() {
        CustomerCard w = werber(9);
        Instant besuch = w.getLastVisitAt();
        CustomerCard neu = karte(card, "CUST-N", 1);
        neu.geworbenVon(w.getId(), jetzt);
        dienst.belohnen(neu, jetzt);
        assertThat(w.getStamps()).isEqualTo(10);
        assertThat(w.getLastVisitAt()).isEqualTo(besuch);
    }

    @Test
    void belohnen_nichtGeworben_ausOderWerberWeg_nurMerker() {
        CustomerCard neu = karte(card, "CUST-N", 1);
        assertThat(dienst.belohnen(neu, jetzt)).isNull();
        assertThat(neu.getEmpfehlungBonusAm()).isNull();

        neu.geworbenVon("CC-WEG", jetzt);
        when(ccRepo.findById("CC-WEG")).thenReturn(Optional.empty());
        assertThat(dienst.belohnen(neu, jetzt)).isNull();
        assertThat(neu.getEmpfehlungBonusAm()).isEqualTo(jetzt);
        assertThat(neu.getStamps()).isEqualTo(1);
        verify(notifier, never()).nachStempelAenderung(any(), any());
    }

    @Test
    void belohnen_punktekarte_bonusInPunkten() {
        Card punkte = Card.createPoints(shop, "Punkte", "x", 100, PointsRounding.GENAU);
        punkte.updateEmpfehlung(true, 3);
        CustomerCard w = karte(punkte, "CUST-W", 0);
        CustomerCard neu = karte(punkte, "CUST-N", 0);
        neu.addPoints(250);
        neu.geworbenVon(w.getId(), jetzt);
        EmpfehlungService.Bonus b = dienst.belohnen(neu, jetzt);
        assertThat(b).isEqualTo(new EmpfehlungService.Bonus(3, true));
        assertThat(neu.getPointsX100()).isEqualTo(550);
        assertThat(w.getPointsX100()).isEqualTo(300);
        assertThat(w.getLastVisitAt()).isNull();
        verify(notifier).nachStempelAenderung(w, GoogleWalletService.Meldung.KEINE);
    }

    // ── Einstellung und Stand ───────────────────────────────────────────

    @Test
    void einstellen_bereich() {
        assertThatThrownBy(() -> dienst.einstellen(card, true, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> dienst.einstellen(card, true, 11)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> dienst.einstellen(card, true, 10))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Stempel bis zur Prämie");
        dienst.einstellen(card, true, 3);
        assertThat(card.isEmpfehlungAktiv()).isTrue();
        assertThat(card.getEmpfehlungBonus()).isEqualTo(3);
    }

    @Test
    void stand_zahlenUndTopWerber() {
        CustomerCard w = werber(3);
        when(ccRepo.countByCardAndGeworbenVonIsNotNull(card)).thenReturn(4L);
        when(ccRepo.countByCardAndEmpfehlungBonusAmIsNotNull(card)).thenReturn(2L);
        when(ccRepo.topWerber(any(), any())).thenReturn(List.<Object[]>of(
                new Object[]{w.getId(), 3L}, new Object[]{"CC-WEG", 1L}));
        EmpfehlungService.Stand s = dienst.stand(card);
        assertThat(s.aktiv()).isTrue();
        assertThat(s.bonus()).isEqualTo(2);
        assertThat(s.geworben()).isEqualTo(4);
        assertThat(s.belohnt()).isEqualTo(2);
        assertThat(s.top()).containsExactly(
                new EmpfehlungService.Werber("Kunde CUST-W", 3), new EmpfehlungService.Werber("Gelöschter Kunde", 1));
    }
}
