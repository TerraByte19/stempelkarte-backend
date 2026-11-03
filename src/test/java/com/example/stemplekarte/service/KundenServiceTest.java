package com.example.stemplekarte.service;

import com.example.stemplekarte.model.AppleDeviceRegistration;
import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.Customer;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.PointsRounding;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.repository.AppleDeviceRepository;
import com.example.stemplekarte.repository.CustomerCardRepository;
import com.example.stemplekarte.repository.ScanLogRepository;
import com.example.stemplekarte.repository.ShopRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Stempel-Korrektur: Bereich 0..Schwelle, nur Stempelkarten, nicht bei
 * offenem Geschenk, nicht fuer fremde Laeden. Und: kein Besuch - der
 * letzte Besuch bleibt, wie er war.
 */
class KundenServiceTest {

    private Shop shop;
    private Card card;
    private CustomerCardRepository ccRepo;
    private AppleDeviceRepository deviceRepo;
    private ScanLogRepository scanLogRepo;
    private ShopRepository shopRepo;
    private KundenService dienst;

    @BeforeEach
    void setUp() {
        shop = mock(Shop.class);
        when(shop.getId()).thenReturn("SHOP-1");
        card = Card.create(shop, "Kaffee", "10 Stempel", 10, "Gratis Kaffee");
        ccRepo = mock(CustomerCardRepository.class);
        when(ccRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        deviceRepo = mock(AppleDeviceRepository.class);
        scanLogRepo = mock(ScanLogRepository.class);
        shopRepo = mock(ShopRepository.class);
        dienst = new KundenService(ccRepo, deviceRepo, scanLogRepo, shopRepo);
    }

    private CustomerCard kundenkarte(Card karte, int stempel) {
        return kundenkarte(karte, stempel, "CUST-1", "Adham", "adham@test.de");
    }

    private CustomerCard kundenkarte(Card karte, int stempel, String id, String name, String email) {
        Customer c = mock(Customer.class);
        when(c.getId()).thenReturn(id);
        when(c.getName()).thenReturn(name);
        when(c.getEmail()).thenReturn(email);
        when(c.isEmailConfirmed()).thenReturn(true);
        CustomerCard cc = CustomerCard.create(c, karte);
        for (int i = 0; i < stempel; i++) cc.addStamp();
        when(ccRepo.findById(cc.getId())).thenReturn(Optional.of(cc));
        return cc;
    }

    private Instant in(int tage) { return Instant.now().plus(Duration.ofDays(tage)); }

    // ── Kundengruppen ───────────────────────────────────────────────────

    @Test
    void gruppen_zaehltJedeGruppe_auchMitNull() {
        CustomerCard neu = kundenkarte(card, 1, "C1", "Anna", "anna@test.de");
        CustomerCard stamm = kundenkarte(card, 6, "C2", "Ben", "ben@test.de");
        when(ccRepo.findAlleKunden(shop)).thenReturn(List.of(neu, stamm));

        KundenService.Gruppen g = dienst.gruppen(shop, Instant.now());
        assertThat(g.zahlen()).containsEntry("NEU", 1L).containsEntry("STAMMGAST", 1L)
                .containsEntry("GELEGENTLICH", 0L).containsEntry("WIRD_RUHIG", 0L).containsEntry("VERLOREN", 0L);
        assertThat(g.regeln()).isEqualTo(Kundengruppe.Regeln.STANDARD);

        // 40 Tage spaeter sind beide "wird ruhig".
        assertThat(dienst.gruppen(shop, in(40)).zahlen()).containsEntry("WIRD_RUHIG", 2L).containsEntry("NEU", 0L);
    }

    @Test
    void suche_mitGruppe_filtertSuchtUndSchneidet() {
        CustomerCard a = kundenkarte(card, 6, "C1", "Anna", "anna@test.de");
        CustomerCard b = kundenkarte(card, 7, "C2", "Bernd", "bernd@test.de");
        CustomerCard c = kundenkarte(card, 1, "C3", "Annika", "annika@test.de");
        when(ccRepo.findAlleKunden(shop)).thenReturn(List.of(a, b, c));

        KundenService.Seite stamm = dienst.suche(shop, "", Kundengruppe.STAMMGAST, 0, 25, Instant.now());
        assertThat(stamm.totalItems()).isEqualTo(2);
        assertThat(stamm.items()).extracting(KundenService.Kunde::name).containsExactly("Anna", "Bernd");
        assertThat(stamm.items()).allMatch(k -> "STAMMGAST".equals(k.gruppe()));

        KundenService.Seite gesucht = dienst.suche(shop, " ANN ", Kundengruppe.STAMMGAST, 0, 25, Instant.now());
        assertThat(gesucht.items()).extracting(KundenService.Kunde::name).containsExactly("Anna");

        KundenService.Seite seite2 = dienst.suche(shop, "", Kundengruppe.STAMMGAST, 1, 1, Instant.now());
        assertThat(seite2.page()).isEqualTo(1);
        assertThat(seite2.totalPages()).isEqualTo(2);
        assertThat(seite2.items()).extracting(KundenService.Kunde::name).containsExactly("Bernd");

        KundenService.Seite leer = dienst.suche(shop, "", Kundengruppe.VERLOREN, 5, 25, Instant.now());
        assertThat(leer.items()).isEmpty();
        assertThat(leer.totalPages()).isZero();
        verify(ccRepo, never()).findKunden(any(), any(), any());
    }

    @Test
    void suche_ohneGruppe_alterWeg_zeileHatGruppe() {
        CustomerCard a = kundenkarte(card, 6, "C1", "Anna", "anna@test.de");
        when(ccRepo.findKunden(any(), any(), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(a)));
        KundenService.Seite s = dienst.suche(shop, "", null, 0, 25, Instant.now());
        assertThat(s.items()).hasSize(1);
        assertThat(s.items().get(0).gruppe()).isEqualTo("STAMMGAST");
        verify(ccRepo, never()).findAlleKunden(any());
    }

    @Test
    void punktekarte_besucheAusDemScanLog() {
        Card punkte = Card.createPoints(shop, "Punkte", "x", 100, PointsRounding.GENAU);
        CustomerCard p = kundenkarte(punkte, 0, "C9", "Pia", "pia@test.de");
        when(ccRepo.findAlleKunden(shop)).thenReturn(List.of(p));
        when(scanLogRepo.zaehleJeKundenkarte("SHOP-1"))
                .thenReturn(List.<Object[]>of(new Object[]{"C9", punkte.getId(), 5L}));
        assertThat(dienst.gruppen(shop, Instant.now()).zahlen()).containsEntry("STAMMGAST", 1L);

        when(scanLogRepo.countByCardIdAndCustomerId(punkte.getId(), "C9")).thenReturn(5L);
        assertThat(dienst.kunde(p).gruppe()).isEqualTo("STAMMGAST");
    }

    @Test
    void regeln_pruefen_undSpeichern() {
        Shop echt = Shop.create("a@b.de", "x", "Laden", 3);
        when(shopRepo.findById("SHOP-1")).thenReturn(Optional.of(echt));
        when(shopRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThatThrownBy(() -> dienst.updateRegeln("SHOP-1", new Kundengruppe.Regeln(5, 60, 30)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(shopRepo, never()).save(any());

        Shop gespeichert = dienst.updateRegeln("SHOP-1", new Kundengruppe.Regeln(3, 14, 45));
        assertThat(Kundengruppe.Regeln.von(gespeichert)).isEqualTo(new Kundengruppe.Regeln(3, 14, 45));
    }

    @Test
    void muster_kleinUndMitProzent() {
        assertThat(KundenService.muster(null)).isEqualTo("%");
        assertThat(KundenService.muster("  ")).isEqualTo("%");
        assertThat(KundenService.muster(" Adham ")).isEqualTo("%adham%");
    }

    @Test
    void korrektur_setztStand_laesstBesuchStehen() throws Exception {
        CustomerCard cc = kundenkarte(card, 3);
        Instant besuch = cc.getLastVisitAt();
        Thread.sleep(5);
        dienst.korrigiereStempel(shop, cc.getId(), 7);
        assertThat(cc.getStamps()).isEqualTo(7);
        assertThat(cc.getLastVisitAt()).isEqualTo(besuch);
        assertThat(cc.getUpdatedAt()).isAfter(besuch);
        verify(ccRepo).save(cc);
    }

    @Test
    void korrektur_gleicherStand_speichertNichts() {
        CustomerCard cc = kundenkarte(card, 3);
        dienst.korrigiereStempel(shop, cc.getId(), 3);
        verify(ccRepo, never()).save(any());
    }

    @Test
    void korrektur_bereich() {
        CustomerCard cc = kundenkarte(card, 3);
        assertThatThrownBy(() -> dienst.korrigiereStempel(shop, cc.getId(), -1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("0 und 10");
        assertThatThrownBy(() -> dienst.korrigiereStempel(shop, cc.getId(), 11))
                .isInstanceOf(IllegalArgumentException.class);
        dienst.korrigiereStempel(shop, cc.getId(), 10);
        assertThat(cc.getStamps()).isEqualTo(10);
        dienst.korrigiereStempel(shop, cc.getId(), 0);
        assertThat(cc.getStamps()).isZero();
    }

    @Test
    void korrektur_punktekarte_abgelehnt() {
        Card punkte = Card.createPoints(shop, "Punkte", "x", 100, PointsRounding.GENAU);
        CustomerCard cc = kundenkarte(punkte, 0);
        assertThatThrownBy(() -> dienst.korrigiereStempel(shop, cc.getId(), 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Scanner");
    }

    @Test
    void korrektur_gesperrt_abgelehnt() {
        CustomerCard cc = kundenkarte(card, 0);
        cc.willkommenVormerken("Erster Kaffee geht auf uns");
        assertThatThrownBy(() -> dienst.korrigiereStempel(shop, cc.getId(), 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("gesperrt");
    }

    @Test
    void korrektur_fremderLaden_wieNichtVorhanden() {
        Shop fremd = mock(Shop.class);
        when(fremd.getId()).thenReturn("SHOP-2");
        CustomerCard cc = kundenkarte(card, 3);
        assertThatThrownBy(() -> dienst.korrigiereStempel(fremd, cc.getId(), 5))
                .isInstanceOf(NoSuchElementException.class);
        assertThat(cc.getStamps()).isEqualTo(3);
    }

    @Test
    void zeile_fuersDashboard() {
        CustomerCard cc = kundenkarte(card, 4);
        when(deviceRepo.findBySerialNumber(cc.getId()))
                .thenReturn(List.of(mock(AppleDeviceRegistration.class)));
        KundenService.Kunde k = dienst.kunde(cc);
        assertThat(k.name()).isEqualTo("Adham");
        assertThat(k.email()).isEqualTo("adham@test.de");
        assertThat(k.cardName()).isEqualTo("Kaffee");
        assertThat(k.type()).isEqualTo("STAMP");
        assertThat(k.stamps()).isEqualTo(4);
        assertThat(k.rewardThreshold()).isEqualTo(10);
        assertThat(k.lastVisitAt()).isNotNull();
        assertThat(k.apple()).isTrue();
        assertThat(k.welcomePending()).isFalse();
        assertThat(k.emailConfirmed()).isTrue();
    }
}
