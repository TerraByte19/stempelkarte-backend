package com.example.stemplekarte.service;

import com.example.stemplekarte.model.Automation;
import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.Customer;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.Kampagne;
import com.example.stemplekarte.model.KampagneEmpfaenger;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.repository.AppleDeviceRepository;
import com.example.stemplekarte.repository.AutomationRepository;
import com.example.stemplekarte.repository.CardRepository;
import com.example.stemplekarte.repository.CustomerCardRepository;
import com.example.stemplekarte.repository.KampagneEmpfaengerRepository;
import com.example.stemplekarte.repository.KampagneRepository;
import com.example.stemplekarte.repository.ScanLogRepository;
import com.example.stemplekarte.repository.ShopRepository;
import com.example.stemplekarte.wallet.ApnsPushService;
import com.example.stemplekarte.wallet.GoogleWalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Baukasten: welche Karte wann dran ist (je Ausloeser einmal je Karte,
 * Pause, Runde, Jahr, Paket), eine Karte je Person, Tageslimit, Lauf ab
 * 10 Uhr einmal am Tag, und die Pruefungen beim Anlegen.
 */
class AutomationTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
    private final Instant um11 = LocalDate.of(2026, 11, 3).atTime(11, 0).atZone(ZONE).toInstant();

    private Shop shop;
    private Card card;
    private Customer anna;
    private AutomationRepository automationRepo;
    private ShopRepository shopRepo;
    private CardRepository cardRepo;
    private KampagneRepository kampagneRepo;
    private KampagneEmpfaengerRepository empfaengerRepo;
    private CustomerCardRepository ccRepo;
    private KampagnenService kampagnen;
    private AutomationService dienst;

    @BeforeEach
    void aufbau() {
        shop = mock(Shop.class);
        when(shop.getId()).thenReturn("SHOP-1");
        when(shop.isActive()).thenReturn(true);
        card = Card.create(shop, "Kaffee", "x", 10, "Gratis Kaffee");
        anna = mock(Customer.class);
        when(anna.getId()).thenReturn("CUST-A");
        automationRepo = mock(AutomationRepository.class);
        when(automationRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        shopRepo = mock(ShopRepository.class);
        when(shopRepo.findById("SHOP-1")).thenReturn(Optional.of(shop));
        cardRepo = mock(CardRepository.class);
        when(cardRepo.findById(card.getId())).thenReturn(Optional.of(card));
        kampagneRepo = mock(KampagneRepository.class);
        empfaengerRepo = mock(KampagneEmpfaengerRepository.class);
        ccRepo = mock(CustomerCardRepository.class);
        kampagnen = mock(KampagnenService.class);
        dienst = new AutomationService(automationRepo, shopRepo, cardRepo, kampagneRepo, empfaengerRepo, ccRepo, kampagnen);
    }

    private Automation auto(AutomationAusloeser a, int wert) {
        return Automation.neu(shop, "Test", a.name(), wert, null, null, "Hallo {name}!", true, um11);
    }

    private CustomerCard karte(Customer kunde, int stamps, Instant besuch) {
        CustomerCard cc = CustomerCard.create(kunde, card);
        for (int i = 0; i < stamps; i++) cc.addStamp();
        if (besuch != null) cc.passAuffrischen(besuch);   // lastVisitAt bleibt vom addStamp
        return cc;
    }

    /** Karte mit festem letzten Besuch: ueber kampagneErhalten laesst sich lastVisitAt nicht setzen, also stempeln und dann zurueckdatieren. */
    private CustomerCard karteMitBesuch(Customer kunde, int stamps, Instant besuch) {
        String id = "CC-" + kunde.getId() + "-" + stamps + "-" + System.nanoTime();
        CustomerCard cc = mock(CustomerCard.class);
        when(cc.getId()).thenReturn(id);
        when(cc.getCard()).thenReturn(card);
        when(cc.getCustomer()).thenReturn(kunde);
        when(cc.getStamps()).thenReturn(stamps);
        when(cc.letzterBesuch()).thenReturn(besuch);
        when(cc.getLastVisitAt()).thenReturn(besuch);
        when(cc.getCreatedAt()).thenReturn(um11.minus(Duration.ofDays(30)));
        return cc;
    }

    private final Kundengruppe.Regeln regeln = Kundengruppe.Regeln.STANDARD;

    // ── Regeln ──────────────────────────────────────────────────────────

    @Test
    void nachAnmeldung_imFenster_einmal() {
        Automation a = auto(AutomationAusloeser.NACH_ANMELDUNG, 3);
        CustomerCard cc = mock(CustomerCard.class);
        when(cc.getCard()).thenReturn(card);
        when(cc.getCustomer()).thenReturn(anna);
        when(cc.getCreatedAt()).thenReturn(um11.minus(Duration.ofDays(3)).minusSeconds(60));
        assertThat(AutomationService.passt(a, cc, um11, null, regeln)).isTrue();
        // Schon gehabt.
        assertThat(AutomationService.passt(a, cc, um11, um11.minus(Duration.ofHours(1)), regeln)).isFalse();
        // Zu frisch, und zu lange her (Fenster 3 Tage).
        when(cc.getCreatedAt()).thenReturn(um11.minus(Duration.ofDays(2)));
        assertThat(AutomationService.passt(a, cc, um11, null, regeln)).isFalse();
        when(cc.getCreatedAt()).thenReturn(um11.minus(Duration.ofDays(7)));
        assertThat(AutomationService.passt(a, cc, um11, null, regeln)).isFalse();
    }

    @Test
    void keinBesuchSeit_einmalJePause() {
        Automation a = auto(AutomationAusloeser.KEIN_BESUCH_SEIT, 30);
        Instant besuch = um11.minus(Duration.ofDays(31));
        CustomerCard cc = karteMitBesuch(anna, 3, besuch);
        assertThat(AutomationService.passt(a, cc, um11, null, regeln)).isTrue();
        // Nach dem Versand erst wieder, wenn ein neuer Besuch dazwischen lag.
        assertThat(AutomationService.passt(a, cc, um11, besuch.plus(Duration.ofDays(1)), regeln)).isFalse();
        assertThat(AutomationService.passt(a, cc, um11, besuch.minus(Duration.ofDays(10)), regeln)).isTrue();
        // Erst 29 Tage: nicht.
        assertThat(AutomationService.passt(a, karteMitBesuch(anna, 3, um11.minus(Duration.ofDays(29))), um11, null, regeln)).isFalse();
    }

    @Test
    void besuchNr_stempelStand_tagDanach() {
        Instant gestern = um11.minus(Duration.ofDays(1));
        CustomerCard cc = karteMitBesuch(anna, 5, gestern);
        Automation besuch5 = auto(AutomationAusloeser.BESUCH_NR, 5);
        assertThat(AutomationService.passt(besuch5, cc, um11, null, regeln)).isTrue();
        assertThat(AutomationService.passt(besuch5, cc, um11, gestern.plusSeconds(5), regeln)).isFalse();   // einmal je Karte
        assertThat(AutomationService.passt(besuch5, karteMitBesuch(anna, 4, gestern), um11, null, regeln)).isFalse();
        // Direkt nach dem Stempel: noch nicht (20 Stunden), nach 4 Tagen: nicht mehr.
        assertThat(AutomationService.passt(besuch5, karteMitBesuch(anna, 5, um11.minus(Duration.ofHours(2))), um11, null, regeln)).isFalse();
        assertThat(AutomationService.passt(besuch5, karteMitBesuch(anna, 5, um11.minus(Duration.ofDays(4))), um11, null, regeln)).isFalse();

        Automation stand8 = auto(AutomationAusloeser.STEMPEL_STAND, 8);
        CustomerCard acht = karteMitBesuch(anna, 8, gestern);
        assertThat(AutomationService.passt(stand8, acht, um11, null, regeln)).isTrue();
        // Einmal je Runde: nach dem Versand erst wieder nach einem neuen Besuch.
        assertThat(AutomationService.passt(stand8, acht, um11, gestern.plusSeconds(5), regeln)).isFalse();
        assertThat(AutomationService.passt(stand8, acht, um11, gestern.minus(Duration.ofDays(20)), regeln)).isTrue();
        assertThat(AutomationService.passt(stand8, cc, um11, null, regeln)).isFalse();
    }

    @Test
    void geburtstag_inTagen_einmalImJahr() {
        Automation a = auto(AutomationAusloeser.GEBURTSTAG_IN_TAGEN, 7);
        CustomerCard cc = karteMitBesuch(anna, 1, um11.minus(Duration.ofDays(5)));
        when(cc.getBirthday()).thenReturn(LocalDate.of(1990, 11, 10));
        assertThat(AutomationService.passt(a, cc, um11, null, regeln)).isTrue();
        assertThat(AutomationService.passt(a, cc, um11, um11.minus(Duration.ofDays(200)), regeln)).isFalse();
        assertThat(AutomationService.passt(a, cc, um11, um11.minus(Duration.ofDays(360)), regeln)).isTrue();
        when(cc.getBirthday()).thenReturn(LocalDate.of(1990, 11, 11));
        assertThat(AutomationService.passt(a, cc, um11, null, regeln)).isFalse();
        // 29. Februar in einem Nicht-Schaltjahr: am 28.
        assertThat(AutomationService.geburtstagInTagen(LocalDate.of(2000, 2, 29), LocalDate.of(2027, 2, 21), 7)).isTrue();
        assertThat(AutomationService.geburtstagInTagen(LocalDate.of(2000, 2, 29), LocalDate.of(2028, 2, 22), 7)).isTrue();
    }

    @Test
    void paketRest_einmalJePaket() {
        Card paket = Card.createPackage(shop, "Yoga", "x", 10, "Kurse");
        CustomerCard cc = CustomerCard.create(anna, paket);
        Instant vorgestern = um11.minus(Duration.ofDays(2));
        cc.paketAufladen(10, vorgestern.minus(Duration.ofDays(1)));
        cc.paketAbbuchen(8, vorgestern);
        Automation a = auto(AutomationAusloeser.PAKET_REST, 2);
        assertThat(AutomationService.passt(a, cc, um11, null, regeln)).isTrue();
        assertThat(AutomationService.passt(a, cc, um11, vorgestern.plusSeconds(1), regeln)).isFalse();
        // Neues Paket: wieder dran, sobald der Stand wieder 2 ist.
        cc.paketAufladen(10, um11.minus(Duration.ofDays(1)).minusSeconds(1));
        cc.paketAbbuchen(10, um11.minus(Duration.ofDays(1)));
        assertThat(cc.getPaketRest()).isEqualTo(2);
        assertThat(AutomationService.passt(a, cc, um11, vorgestern.plusSeconds(1), regeln)).isTrue();
        // Stempelkarte: nie.
        assertThat(AutomationService.passt(a, karteMitBesuch(anna, 2, vorgestern), um11, null, regeln)).isFalse();
    }

    @Test
    void filter_karteUndGruppe() {
        Instant besuch = um11.minus(Duration.ofDays(40));
        CustomerCard ruhig = karteMitBesuch(anna, 2, besuch);
        Automation a = Automation.neu(shop, "Ruhige", AutomationAusloeser.KEIN_BESUCH_SEIT.name(), 30,
                null, "WIRD_RUHIG", "Komm wieder!", true, um11);
        assertThat(AutomationService.passt(a, ruhig, um11, null, regeln)).isTrue();
        a.aendern(null, null, null, null, "STAMMGAST", null, null, um11);
        assertThat(AutomationService.passt(a, ruhig, um11, null, regeln)).isFalse();
        a.aendern(null, null, null, "CARD-ANDERE", "", null, null, um11);
        assertThat(AutomationService.passt(a, ruhig, um11, null, regeln)).isFalse();
        a.aendern(null, null, null, card.getId(), null, null, null, um11);
        assertThat(AutomationService.passt(a, ruhig, um11, null, regeln)).isTrue();
    }

    @Test
    void faellige_eineKarteJePerson_undTageslimit() {
        Instant besuch = um11.minus(Duration.ofDays(40));
        CustomerCard a1 = karteMitBesuch(anna, 2, besuch);
        CustomerCard a2 = karteMitBesuch(anna, 3, besuch.plus(Duration.ofDays(1)));
        Customer ben = mock(Customer.class);
        when(ben.getId()).thenReturn("CUST-B");
        CustomerCard b = karteMitBesuch(ben, 1, besuch);
        when(b.getKampagneAm()).thenReturn(um11.minus(Duration.ofHours(1)));   // heute schon eine Nachricht
        Automation a = auto(AutomationAusloeser.KEIN_BESUCH_SEIT, 30);

        List<CustomerCard> ohneLimit = dienst.faellige(a, List.of(a1, a2, b), um11, Map.of(), regeln, false);
        assertThat(ohneLimit).containsExactly(a2, b);
        List<CustomerCard> mitLimit = dienst.faellige(a, List.of(a1, a2, b), um11, Map.of(), regeln, true);
        assertThat(mitLimit).containsExactly(a2);
    }

    // ── Laeufer ─────────────────────────────────────────────────────────

    @Test
    void lauf_ab10Uhr_einmalAmTag_startetKampagne() {
        Automation a = auto(AutomationAusloeser.KEIN_BESUCH_SEIT, 30);
        when(automationRepo.findByAktivTrue()).thenReturn(List.of(a));
        CustomerCard cc = karteMitBesuch(anna, 2, um11.minus(Duration.ofDays(40)));
        when(ccRepo.findKampagnenBasis(shop)).thenReturn(List.of(cc));
        Kampagne k = Kampagne.starte(shop, "Hallo", Zielgruppe.AUTOMATION, 1, um11);
        when(kampagnen.starteFuer(eq(shop), anyString(), any(), eq(a.getId()))).thenReturn(k);

        Instant um9 = LocalDate.of(2026, 11, 3).atTime(9, 30).atZone(ZONE).toInstant();
        assertThat(dienst.lauf(um9)).isZero();
        verify(kampagnen, never()).starteFuer(any(), anyString(), any(), anyString());

        assertThat(dienst.lauf(um11)).isEqualTo(1);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CustomerCard>> empf = ArgumentCaptor.forClass(List.class);
        verify(kampagnen).starteFuer(eq(shop), eq("Hallo {name}!"), empf.capture(), eq(a.getId()));
        assertThat(empf.getValue()).containsExactly(cc);
        assertThat(a.getZuletztAm()).isEqualTo(um11);
        assertThat(a.getGesendet()).isEqualTo(1);

        // Zweiter Lauf am selben Tag: nichts.
        assertThat(dienst.lauf(um11.plus(Duration.ofHours(2)))).isZero();
        // Naechster Tag, aber laut Datenbank lief sie schon (Neustart): nichts.
        when(kampagneRepo.existsByAutomationIdAndErstelltAmGreaterThanEqual(eq(a.getId()), any())).thenReturn(true);
        assertThat(dienst.lauf(um11.plus(Duration.ofDays(1)))).isZero();
    }

    @Test
    void basic_anlegenGesperrt_pausierenGeht_laeuferUeberspringt() {
        when(shop.getTarif()).thenReturn(com.example.stemplekarte.model.Tarif.BASIC);
        assertThatThrownBy(() -> dienst.anlegen("SHOP-1", new AutomationService.Anfrage("x", "NACH_ANMELDUNG", 1, null, null, "t", null)))
                .isInstanceOf(TarifPruefung.Gesperrt.class);

        Automation a = auto(AutomationAusloeser.KEIN_BESUCH_SEIT, 30);
        when(automationRepo.findByIdAndShop(a.getId(), shop)).thenReturn(Optional.of(a));
        assertThatThrownBy(() -> dienst.aendern("SHOP-1", a.getId(), new AutomationService.Anfrage("y", null, null, null, null, null, null)))
                .isInstanceOf(TarifPruefung.Gesperrt.class);
        assertThat(dienst.aendern("SHOP-1", a.getId(), new AutomationService.Anfrage(null, null, null, null, null, null, false)).aktiv()).isFalse();

        Automation laufend = auto(AutomationAusloeser.KEIN_BESUCH_SEIT, 30);
        when(automationRepo.findByAktivTrue()).thenReturn(List.of(laufend));
        CustomerCard faellig = karteMitBesuch(anna, 2, um11.minus(Duration.ofDays(40)));
        when(ccRepo.findKampagnenBasis(shop)).thenReturn(List.of(faellig));
        assertThat(dienst.lauf(um11)).isZero();
        verify(kampagnen, never()).starteFuer(any(), anyString(), any(), anyString());
    }

    @Test
    void lauf_niemandFaellig_keineKampagne_pausiertNicht() {
        Automation a = auto(AutomationAusloeser.STEMPEL_STAND, 9);
        when(automationRepo.findByAktivTrue()).thenReturn(List.of(a));
        CustomerCard zwei = karteMitBesuch(anna, 2, um11.minus(Duration.ofDays(1)));
        when(ccRepo.findKampagnenBasis(shop)).thenReturn(List.of(zwei));
        assertThat(dienst.lauf(um11)).isZero();
        verify(kampagnen, never()).starteFuer(any(), anyString(), any(), anyString());
        assertThat(a.isAktiv()).isTrue();
    }

    // ── Verwaltung ──────────────────────────────────────────────────────

    @Test
    void anlegen_prueftAlles_undBegrenzt() {
        AutomationService.Eintrag e = dienst.anlegen("SHOP-1", new AutomationService.Anfrage(
                "  Komm  zurück ", "kein_besuch_seit", 21, card.getId(), "wird_ruhig", " Wir vermissen dich, {name}! ", null));
        assertThat(e.name()).isEqualTo("Komm zurück");
        assertThat(e.ausloeser()).isEqualTo("KEIN_BESUCH_SEIT");
        assertThat(e.ausloeserTitel()).isEqualTo("Tage ohne Besuch");
        assertThat(e.wert()).isEqualTo(21);
        assertThat(e.cardId()).isEqualTo(card.getId());
        assertThat(e.gruppe()).isEqualTo("WIRD_RUHIG");
        assertThat(e.text()).isEqualTo("Wir vermissen dich, {name}!");
        assertThat(e.aktiv()).isTrue();

        assertThatThrownBy(() -> dienst.anlegen("SHOP-1", new AutomationService.Anfrage("x", "quatsch", 1, null, null, "t", null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Auslöser");
        assertThatThrownBy(() -> dienst.anlegen("SHOP-1", new AutomationService.Anfrage("x", "STEMPEL_STAND", 0, null, null, "t", null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("zwischen 1 und 100");
        assertThatThrownBy(() -> dienst.anlegen("SHOP-1", new AutomationService.Anfrage("x", "NACH_ANMELDUNG", 1, "CARD-FREMD", null, "t", null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Karte");
        assertThatThrownBy(() -> dienst.anlegen("SHOP-1", new AutomationService.Anfrage("x", "NACH_ANMELDUNG", 1, null, "VIP", "t", null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Kundengruppe");
        assertThatThrownBy(() -> dienst.anlegen("SHOP-1", new AutomationService.Anfrage("x", "NACH_ANMELDUNG", 1, null, null, " ", null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Text");
        assertThatThrownBy(() -> dienst.anlegen("SHOP-1", new AutomationService.Anfrage(" ", "NACH_ANMELDUNG", 1, null, null, "t", null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Name");

        when(automationRepo.countByShop(shop)).thenReturn((long) AutomationService.MAX_JE_LADEN);
        assertThatThrownBy(() -> dienst.anlegen("SHOP-1", new AutomationService.Anfrage("x", "NACH_ANMELDUNG", 1, null, null, "t", null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Höchstens");
    }

    @Test
    void aendern_nurGesetzteFelder_pausieren_loeschen() {
        Automation a = auto(AutomationAusloeser.BESUCH_NR, 5);
        when(automationRepo.findByIdAndShop(a.getId(), shop)).thenReturn(Optional.of(a));

        AutomationService.Eintrag e = dienst.aendern("SHOP-1", a.getId(),
                new AutomationService.Anfrage(null, null, null, null, null, null, false));
        assertThat(e.aktiv()).isFalse();
        assertThat(e.name()).isEqualTo("Test");
        assertThat(e.wert()).isEqualTo(5);

        // Neuer Ausloeser prueft den alten Wert gegen seinen Bereich.
        assertThatThrownBy(() -> dienst.aendern("SHOP-1", a.getId(),
                new AutomationService.Anfrage(null, "GEBURTSTAG_IN_TAGEN", 90, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        e = dienst.aendern("SHOP-1", a.getId(), new AutomationService.Anfrage(null, "GEBURTSTAG_IN_TAGEN", 7, "", "", null, true));
        assertThat(e.ausloeser()).isEqualTo("GEBURTSTAG_IN_TAGEN");
        assertThat(e.wert()).isEqualTo(7);
        assertThat(e.cardId()).isNull();
        assertThat(e.aktiv()).isTrue();

        assertThatThrownBy(() -> dienst.aendern("SHOP-1", "AU-FREMD", new AutomationService.Anfrage(null, null, null, null, null, null, true)))
                .isInstanceOf(java.util.NoSuchElementException.class);
        dienst.loeschen("SHOP-1", a.getId());
        verify(automationRepo).delete(a);
    }

    @Test
    void liste_zaehltWerJetztDranWaere() {
        Automation a = auto(AutomationAusloeser.KEIN_BESUCH_SEIT, 30);
        when(automationRepo.findByShopOrderByErstelltAmAsc(shop)).thenReturn(List.of(a));
        CustomerCard dran = karteMitBesuch(anna, 2, um11.minus(Duration.ofDays(40)));
        Customer ben = mock(Customer.class);
        when(ben.getId()).thenReturn("CUST-B");
        CustomerCard nicht = karteMitBesuch(ben, 2, um11.minus(Duration.ofDays(3)));
        when(ccRepo.findKampagnenBasis(shop)).thenReturn(List.of(dran, nicht));
        List<AutomationService.Eintrag> l = dienst.liste(shop, um11);
        assertThat(l).hasSize(1);
        assertThat(l.get(0).trifftJetzt()).isEqualTo(1);
        // Schon dran gewesen (Empfaengerzeile einer frueheren Kampagne dieser Automation).
        String dranId = dran.getId();
        List<Object[]> versand = List.<Object[]>of(new Object[]{dranId, um11.minus(Duration.ofDays(2))});
        when(empfaengerRepo.letzterVersandJeKarte(a.getId())).thenReturn(versand);
        assertThat(dienst.liste(shop, um11).get(0).trifftJetzt()).isZero();
    }

    // ── KampagnenService.starteFuer ─────────────────────────────────────

    @Test
    void starteFuer_schreibtEmpfaengerUndAutomation() {
        when(kampagneRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        TransactionTemplate tx = mock(TransactionTemplate.class);
        KampagnenService echt = new KampagnenService(kampagneRepo, empfaengerRepo, ccRepo, mock(ScanLogRepository.class),
                shopRepo, mock(AppleDeviceRepository.class), mock(RewardService.class),
                mock(ApnsPushService.class), mock(GoogleWalletService.class), tx);
        CustomerCard cc = karte(anna, 1, null);

        Kampagne k = echt.starteFuer(shop, "  Hallo {name}!  ", List.of(cc), "AU-1");
        assertThat(k.getGruppe()).isEqualTo("AUTOMATION");
        assertThat(k.getAutomationId()).isEqualTo("AU-1");
        assertThat(k.getEmpfaenger()).isEqualTo(1);
        assertThat(k.getKontrolleEmpfaenger()).isZero();
        assertThat(k.laeuft()).isTrue();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<KampagneEmpfaenger>> zeilen = ArgumentCaptor.forClass(List.class);
        verify(empfaengerRepo).saveAll(zeilen.capture());
        assertThat(zeilen.getValue()).extracting(KampagneEmpfaenger::getCustomerCardId).containsExactly(cc.getId());
        verify(shop).nachrichtenEinschalten(any());

        assertThatThrownBy(() -> echt.starteFuer(shop, "Hallo", List.of(), "AU-1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> echt.starteFuer(shop, " ", List.of(cc), "AU-1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(Zielgruppe.AUTOMATION.waehlbar()).isFalse();
        assertThat(Zielgruppe.AUTOMATION.passt(cc, um11, c -> List.of())).isFalse();
    }
}
