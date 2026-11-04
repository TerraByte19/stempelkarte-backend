package com.example.stemplekarte.service;

import com.example.stemplekarte.model.AppleDeviceRegistration;
import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.Customer;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.Kampagne;
import com.example.stemplekarte.model.KampagneEmpfaenger;
import com.example.stemplekarte.model.ScanLog;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.repository.AppleDeviceRepository;
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
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Kampagne: wer beim Start in die Liste kommt, wer wann den Text bekommt,
 * und dass ein iPhone mit altem Pass erst still aufgefrischt wird. Dazu die
 * Tuer: Textpruefung, Zielgruppe, eine Nachricht pro Karte und Tag.
 */
class KampagnenServiceTest {

    private final Instant jetzt = Instant.now();

    private Shop shop;
    private Card card;
    private KampagneRepository kampagneRepo;
    private KampagneEmpfaengerRepository empfaengerRepo;
    private CustomerCardRepository ccRepo;
    private ShopRepository shopRepo;
    private AppleDeviceRepository deviceRepo;
    private ApnsPushService apns;
    private GoogleWalletService google;
    private KampagnenService dienst;

    @BeforeEach
    void setUp() {
        shop = mock(Shop.class);
        when(shop.getId()).thenReturn("SHOP-1");
        when(shop.getName()).thenReturn("Café Mokka");
        card = Card.create(shop, "Kaffee", "10 Stempel", 10, "Gratis Kaffee");
        kampagneRepo = mock(KampagneRepository.class);
        when(kampagneRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        empfaengerRepo = mock(KampagneEmpfaengerRepository.class);
        ccRepo = mock(CustomerCardRepository.class);
        shopRepo = mock(ShopRepository.class);
        when(shopRepo.findById("SHOP-1")).thenReturn(Optional.of(shop));
        deviceRepo = mock(AppleDeviceRepository.class);
        apns = mock(ApnsPushService.class);
        google = mock(GoogleWalletService.class);
        TransactionTemplate tx = mock(TransactionTemplate.class);
        when(tx.execute(any())).thenAnswer(inv ->
                ((TransactionCallback<?>) inv.getArgument(0)).doInTransaction(null));
        scanLogRepo = mock(ScanLogRepository.class);
        dienst = new KampagnenService(kampagneRepo, empfaengerRepo, ccRepo, scanLogRepo, shopRepo, deviceRepo,
                mock(RewardService.class), apns, google, tx);
    }

    private ScanLogRepository scanLogRepo;

    private ScanLog scanVon(String kundeId, Instant wann) {
        ScanLog sl = mock(ScanLog.class);
        when(sl.getCustomerId()).thenReturn(kundeId);
        when(sl.getScannedAt()).thenReturn(wann);
        return sl;
    }

    // ── Ergebnis ─────────────────────────────────────────────────────────

    @Test
    void wiedergekommen_zaehltPersonenUnterDenEmpfaengern() {
        Kampagne k = kampagne();
        when(empfaengerRepo.findByKampagneId(k.getId())).thenReturn(List.of(
                KampagneEmpfaenger.neu(k.getId(), "CC-A"), KampagneEmpfaenger.neu(k.getId(), "CC-B")));
        when(ccRepo.kundenIdsVon(any())).thenReturn(List.<Object[]>of(
                new Object[]{"CC-A", "CUST-A"}, new Object[]{"CC-B", "CUST-B"}));
        // A kam zweimal, C ist kein Empfaenger, B kam nicht.
        List<ScanLog> scans = List.of(
                scanVon("CUST-A", jetzt.plusSeconds(100)),
                scanVon("CUST-A", jetzt.plusSeconds(200)),
                scanVon("CUST-C", jetzt.plusSeconds(300)));
        when(scanLogRepo.findByShopIdAndScannedAtBetweenOrderByScannedAtAsc(eq("SHOP-1"), any(), any()))
                .thenReturn(scans);
        assertThat(dienst.wiedergekommen(k, "SHOP-1", jetzt.plus(Duration.ofDays(1))).mitNachricht()).isEqualTo(1);
    }

    @Test
    void wiedergekommen_trenntKontrollgruppe() {
        Kampagne k = kampagne();
        when(empfaengerRepo.findByKampagneId(k.getId())).thenReturn(List.of(
                KampagneEmpfaenger.neu(k.getId(), "CC-A"),
                KampagneEmpfaenger.kontrolle(k.getId(), "CC-B")));
        when(ccRepo.kundenIdsVon(any())).thenReturn(List.<Object[]>of(
                new Object[]{"CC-A", "CUST-A"}, new Object[]{"CC-B", "CUST-B"}));
        List<ScanLog> scans = List.of(scanVon("CUST-A", jetzt.plusSeconds(100)), scanVon("CUST-B", jetzt.plusSeconds(200)));
        when(scanLogRepo.findByShopIdAndScannedAtBetweenOrderByScannedAtAsc(eq("SHOP-1"), any(), any()))
                .thenReturn(scans);
        KampagnenService.Ergebnis e = dienst.wiedergekommen(k, "SHOP-1", jetzt.plus(Duration.ofDays(1)));
        assertThat(e.mitNachricht()).isEqualTo(1);
        assertThat(e.kontrolle()).isEqualTo(1);
    }

    @Test
    void aufteilen_einZehntelAbZehn() {
        List<CustomerCard> zwanzig = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) zwanzig.add(kundenkarte());
        var a = KampagnenService.aufteilen(zwanzig, true, new java.util.Random(1));
        assertThat(a.kontrolle()).hasSize(2);
        assertThat(a.nachricht()).hasSize(18);
        assertThat(a.nachricht()).doesNotContainAnyElementsOf(a.kontrolle());
        // Unter zehn: keine Kontrollgruppe, auch wenn gewuenscht.
        var klein = KampagnenService.aufteilen(zwanzig.subList(0, 9), true, new java.util.Random(1));
        assertThat(klein.kontrolle()).isEmpty();
        assertThat(klein.nachricht()).hasSize(9);
        // Nicht gewuenscht: alle bekommen die Nachricht.
        assertThat(KampagnenService.aufteilen(zwanzig, false, new java.util.Random(1)).kontrolle()).isEmpty();
    }

    @Test
    void start_mitKontrollgruppe_schreibtBeideGruppen() {
        CustomerCard[] karten = new CustomerCard[10];
        for (int i = 0; i < 10; i++) karten[i] = kundenkarte();
        basis(karten);
        Kampagne k = dienst.starte(shop, "Heute 20%", "alle", null, true);
        assertThat(k.getEmpfaenger()).isEqualTo(9);
        assertThat(k.getKontrolleEmpfaenger()).isEqualTo(1);
        ArgumentCaptor<List<KampagneEmpfaenger>> rows = ArgumentCaptor.captor();
        verify(empfaengerRepo).saveAll(rows.capture());
        assertThat(rows.getValue()).hasSize(10);
        assertThat(rows.getValue().stream().filter(KampagneEmpfaenger::istKontrolle).count()).isEqualTo(1);
        // Kontrollzeile ist von Anfang an erledigt - der Laeufer fasst sie nie an.
        assertThat(rows.getValue().stream().filter(KampagneEmpfaenger::istKontrolle).allMatch(KampagneEmpfaenger::isErledigt)).isTrue();
    }

    @Test
    void geplantMitKontrollgruppe_teiltBeimAktivieren() {
        CustomerCard[] karten = new CustomerCard[10];
        for (int i = 0; i < 10; i++) karten[i] = kundenkarte();
        basis(karten);
        Kampagne k = dienst.starte(shop, "x", "alle", jetzt.plus(Duration.ofHours(1)), true);
        assertThat(k.getKontrolleEmpfaenger()).isEqualTo(-1);   // Merker
        dienst.aktiviere(k, jetzt.plus(Duration.ofHours(1)));
        assertThat(k.laeuft()).isTrue();
        assertThat(k.getEmpfaenger()).isEqualTo(9);
        assertThat(k.getKontrolleEmpfaenger()).isEqualTo(1);
    }

    @Test
    void wiedergekommen_ohneEmpfaenger_null() {
        Kampagne k = kampagne();
        when(empfaengerRepo.findByKampagneId(k.getId())).thenReturn(List.of());
        assertThat(dienst.wiedergekommen(k, "SHOP-1", jetzt).mitNachricht()).isZero();
        verify(scanLogRepo, never()).findByShopIdAndScannedAtBetweenOrderByScannedAtAsc(anyString(), any(), any());
    }

    @Test
    void wiedergekommen_fensterEndetNach7Tagen() {
        Kampagne k = kampagne();
        when(empfaengerRepo.findByKampagneId(k.getId())).thenReturn(List.of(KampagneEmpfaenger.neu(k.getId(), "CC-A")));
        when(ccRepo.kundenIdsVon(any())).thenReturn(List.<Object[]>of(new Object[]{"CC-A", "CUST-A"}));
        when(scanLogRepo.findByShopIdAndScannedAtBetweenOrderByScannedAtAsc(anyString(), any(), any())).thenReturn(List.of());
        dienst.wiedergekommen(k, "SHOP-1", jetzt.plus(Duration.ofDays(30)));
        // Gefragt wird nur bis Start + 7 Tage, nicht bis heute.
        verify(scanLogRepo).findByShopIdAndScannedAtBetweenOrderByScannedAtAsc(
                "SHOP-1", k.startAm(), k.startAm().plus(Duration.ofDays(7)));
    }

    @Test
    void lauf_schreibtErgebnisNachAblaufFest() {
        Kampagne k = kampagne();
        k.beenden(jetzt.minus(Duration.ofDays(8)));
        when(kampagneRepo.findByStatusAndErgebnisAmIsNullAndFertigAmBefore(eq(Kampagne.FERTIG), any()))
                .thenReturn(List.of(k));
        when(empfaengerRepo.findByKampagneId(k.getId())).thenReturn(List.of());
        dienst.lauf();
        assertThat(k.ergebnisFest()).isTrue();
        assertThat(k.getWiedergekommen()).isZero();
        verify(kampagneRepo).save(k);
    }

    private int kunden = 0;

    private Customer kunde() {
        Customer c = mock(Customer.class);
        when(c.getId()).thenReturn("CUST-" + (++kunden));
        return c;
    }

    private CustomerCard kundenkarte() {
        return kundenkarte(kunde(), card);
    }

    private CustomerCard kundenkarte(Customer kunde, Card karte) {
        CustomerCard cc = CustomerCard.create(kunde, karte);
        cc.addStamp();
        when(ccRepo.findById(cc.getId())).thenReturn(Optional.of(cc));
        return cc;
    }

    private void basis(CustomerCard... karten) {
        when(ccRepo.findKampagnenBasis(shop)).thenReturn(List.of(karten));
    }

    private Kampagne kampagne() {
        return Kampagne.starte(shop, "Heute 20% auf Kuchen", Zielgruppe.ALLE, 1, jetzt);
    }

    private List<KampagneEmpfaenger> zeilen(Kampagne k, CustomerCard... karten) {
        List<KampagneEmpfaenger> rows = java.util.Arrays.stream(karten)
                .map(cc -> KampagneEmpfaenger.neu(k.getId(), cc.getId())).toList();
        when(empfaengerRepo.findByKampagneIdAndErledigtFalse(eq(k.getId()), any()))
                .thenAnswer(inv -> rows.stream().filter(r -> !r.isErledigt()).toList());
        when(empfaengerRepo.countByKampagneIdAndErledigtFalse(k.getId()))
                .thenAnswer(inv -> rows.stream().filter(r -> !r.isErledigt()).count());
        return rows;
    }

    private void mitApple(CustomerCard cc) {
        when(deviceRepo.findBySerialNumber(cc.getId()))
                .thenReturn(List.of(mock(AppleDeviceRegistration.class)));
    }

    // ── Tuer ─────────────────────────────────────────────────────────────

    @Test
    void sauber_steuerzeichenWerdenLeerzeichen() {
        assertThat(KampagnenService.sauber("  Heute\n20%\tauf  Kuchen ")).isEqualTo("Heute 20% auf Kuchen");
        assertThat(KampagnenService.sauber(null)).isEmpty();
    }

    @Test
    void leererText_abgelehnt() {
        assertThatThrownBy(() -> dienst.starte(shop, " \n ", null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(kampagneRepo, never()).save(any());
    }

    @Test
    void zuLangerText_abgelehnt() {
        assertThatThrownBy(() -> dienst.starte(shop, "x".repeat(161), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("160");
    }

    @Test
    void unbekannteGruppe_abgelehnt() {
        assertThatThrownBy(() -> dienst.starte(shop, "Hallo", "sternzeichen"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Zielgruppe");
        verify(kampagneRepo, never()).save(any());
    }

    @Test
    void niemandInDerGruppe_abgelehnt() {
        basis(kundenkarte());
        assertThatThrownBy(() -> dienst.starte(shop, "Hallo", "inaktiv_90"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("niemand");
        verify(kampagneRepo, never()).save(any());
    }

    @Test
    void start_schaltetFeldEin_undSchreibtEmpfaengerFest() {
        CustomerCard a = kundenkarte();
        CustomerCard b = kundenkarte();
        // b hatte heute schon eine Kampagne - wird uebersprungen.
        b.kampagneErhalten("vorhin", jetzt);
        basis(a, b);

        Kampagne k = dienst.starte(shop, "Heute 20% auf Kuchen", "alle");

        verify(shop).nachrichtenEinschalten(any());
        verify(shopRepo).save(shop);
        assertThat(k.getText()).isEqualTo("Heute 20% auf Kuchen");
        assertThat(k.getGruppe()).isEqualTo("ALLE");
        assertThat(k.getEmpfaenger()).isEqualTo(1);
        assertThat(k.laeuft()).isTrue();

        ArgumentCaptor<List<KampagneEmpfaenger>> rows = ArgumentCaptor.captor();
        verify(empfaengerRepo).saveAll(rows.capture());
        assertThat(rows.getValue()).extracting(KampagneEmpfaenger::getCustomerCardId)
                .containsExactly(a.getId());
    }

    // ── Planen ───────────────────────────────────────────────────────────

    @Test
    void geplant_wartetOhneEmpfaengerzeilen() {
        basis(kundenkarte());
        Instant spaeter = jetzt.plus(Duration.ofHours(3));
        Kampagne k = dienst.starte(shop, "Happy Hour ab 16 Uhr", "alle", spaeter);
        assertThat(k.istGeplant()).isTrue();
        assertThat(k.getStatus()).isEqualTo(Kampagne.GEPLANT);
        assertThat(k.getGeplantAm()).isEqualTo(spaeter);
        assertThat(k.getEmpfaenger()).isEqualTo(1);
        verify(empfaengerRepo, never()).saveAll(any());
        // Das Feld kommt trotzdem ab sofort in jeden Pass.
        verify(shop).nachrichtenEinschalten(any());
    }

    @Test
    void geplant_vergangenheitUndZuWeitWeg_abgelehnt() {
        basis(kundenkarte());
        assertThatThrownBy(() -> dienst.starte(shop, "x", "alle", jetzt.minusSeconds(10)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Vergangenheit");
        assertThatThrownBy(() -> dienst.starte(shop, "x", "alle", jetzt.plus(Duration.ofDays(91))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("90");
        verify(kampagneRepo, never()).save(any());
    }

    @Test
    void geplant_niemandJetzt_trotzdemErlaubt() {
        basis();
        Kampagne k = dienst.starte(shop, "x", "inaktiv_90", jetzt.plus(Duration.ofDays(1)));
        assertThat(k.istGeplant()).isTrue();
        assertThat(k.getEmpfaenger()).isZero();
    }

    @Test
    void faellig_bestimmtEmpfaengerJetzt_undLaeuft() {
        CustomerCard cc = kundenkarte();
        basis(cc);
        Kampagne k = Kampagne.starte(shop, "Happy Hour", Zielgruppe.ALLE, 0, jetzt.minusSeconds(600), jetzt.minusSeconds(1));
        dienst.aktiviere(k, jetzt);
        assertThat(k.laeuft()).isTrue();
        assertThat(k.getEmpfaenger()).isEqualTo(1);
        ArgumentCaptor<List<KampagneEmpfaenger>> rows = ArgumentCaptor.captor();
        verify(empfaengerRepo).saveAll(rows.capture());
        assertThat(rows.getValue()).extracting(KampagneEmpfaenger::getCustomerCardId).containsExactly(cc.getId());
    }

    @Test
    void faellig_aberInzwischenBasic_nichtGesendet() {
        when(shop.getTarif()).thenReturn(com.example.stemplekarte.model.Tarif.BASIC);
        basis(kundenkarte());
        Kampagne k = Kampagne.starte(shop, "Happy Hour", Zielgruppe.ALLE, 1, jetzt.minusSeconds(600), jetzt.minusSeconds(1));
        dienst.aktiviere(k, jetzt);
        assertThat(k.getStatus()).isEqualTo(Kampagne.FERTIG);
        assertThat(k.getEmpfaenger()).isZero();
        verify(empfaengerRepo, never()).saveAll(any());
    }

    @Test
    void faellig_niemandDa_gleichFertig() {
        basis();
        Kampagne k = Kampagne.starte(shop, "x", Zielgruppe.ALLE, 3, jetzt.minusSeconds(600), jetzt.minusSeconds(1));
        dienst.aktiviere(k, jetzt);
        assertThat(k.getStatus()).isEqualTo(Kampagne.FERTIG);
        assertThat(k.getEmpfaenger()).isZero();
        verify(empfaengerRepo, never()).saveAll(any());
    }

    @Test
    void abbrechen_nurGeplante() {
        Kampagne geplant = Kampagne.starte(shop, "x", Zielgruppe.ALLE, 1, jetzt, jetzt.plusSeconds(3600));
        when(kampagneRepo.findByIdAndShop(geplant.getId(), shop)).thenReturn(Optional.of(geplant));
        dienst.abbrechen(shop, geplant.getId());
        verify(kampagneRepo).delete(geplant);

        Kampagne laeuft = kampagne();
        when(kampagneRepo.findByIdAndShop(laeuft.getId(), shop)).thenReturn(Optional.of(laeuft));
        assertThatThrownBy(() -> dienst.abbrechen(shop, laeuft.getId()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> dienst.abbrechen(shop, "KP-FREMD"))
                .isInstanceOf(java.util.NoSuchElementException.class);
    }

    // ── Mehrere Karten in einem Laden ────────────────────────────────────

    @Test
    void zweiKartenEinerPerson_nurDieZuletztBenutzte() throws Exception {
        Customer person = kunde();
        Card punkte = Card.createPoints(shop, "Punkte", "x", 100,
                com.example.stemplekarte.model.PointsRounding.GENAU);
        CustomerCard alt = kundenkarte(person, card);
        Thread.sleep(5);
        CustomerCard neu = kundenkarte(person, punkte);
        basis(alt, neu);

        List<CustomerCard> empf = dienst.empfaenger(shop, Zielgruppe.ALLE, jetzt.plusSeconds(1));
        assertThat(empf).extracting(CustomerCard::getId).containsExactly(neu.getId());

        var alle = dienst.zaehle(shop).stream()
                .filter(z -> z.gruppe() == Zielgruppe.ALLE).findFirst().orElseThrow();
        assertThat(alle.empfaenger()).isEqualTo(1);
    }

    @Test
    void heuteAufDerAnderenKarteBenachrichtigt_personWirdUebersprungen() {
        Customer person = kunde();
        Card punkte = Card.createPoints(shop, "Punkte", "x", 100,
                com.example.stemplekarte.model.PointsRounding.GENAU);
        CustomerCard a = kundenkarte(person, card);
        CustomerCard b = kundenkarte(person, punkte);
        a.kampagneErhalten("vorhin", jetzt);
        basis(a, b);

        assertThat(dienst.empfaenger(shop, Zielgruppe.ALLE, jetzt.plusSeconds(1))).isEmpty();
        var alle = dienst.zaehle(shop).stream()
                .filter(z -> z.gruppe() == Zielgruppe.ALLE).findFirst().orElseThrow();
        assertThat(alle.empfaenger()).isZero();
        assertThat(alle.heuteSchon()).isEqualTo(1);
    }

    @Test
    void zaehle_jeGruppeMitHeuteSchon() {
        CustomerCard a = kundenkarte();
        CustomerCard b = kundenkarte();
        b.kampagneErhalten("vorhin", jetzt);
        basis(a, b);
        var alle = dienst.zaehle(shop).stream()
                .filter(z -> z.gruppe() == Zielgruppe.ALLE).findFirst().orElseThrow();
        assertThat(alle.empfaenger()).isEqualTo(1);
        assertThat(alle.heuteSchon()).isEqualTo(1);
        // Nur die waehlbaren Gruppen; GEBURTSTAG_HEUTE gehoert dem Automaten.
        assertThat(dienst.zaehle(shop)).hasSize(Zielgruppe.waehlbare().size());
        assertThat(dienst.zaehle(shop)).extracting(KampagnenService.GruppenZahl::gruppe)
                .doesNotContain(Zielgruppe.GEBURTSTAG_HEUTE);
    }

    @Test
    void heuteSchon_gesternZaehltNicht() {
        CustomerCard cc = kundenkarte();
        Instant tag = KampagnenService.tagesbeginn(jetzt);
        cc.kampagneErhalten("gestern", tag.minusSeconds(1));
        assertThat(KampagnenService.heuteSchon(cc, tag)).isFalse();
        cc.kampagneErhalten("heute", tag.plusSeconds(1));
        assertThat(KampagnenService.heuteSchon(cc, tag)).isTrue();
    }

    @Test
    void tagesbeginn_berlinerZeit() {
        Instant um0130Berlin = Instant.parse("2026-07-10T23:30:00Z"); // 01:30 MESZ am 11.7.
        assertThat(KampagnenService.tagesbeginn(um0130Berlin))
                .isEqualTo(Instant.parse("2026-07-10T22:00:00Z"));
        assertThat(KampagnenService.tagesbeginn(um0130Berlin).atZone(ZoneId.of("Europe/Berlin")).getDayOfMonth())
                .isEqualTo(11);
    }

    // ── Versand ──────────────────────────────────────────────────────────

    @Test
    void platzhalter_jeKarteGefuellt_vorlageBleibt() {
        CustomerCard cc = kundenkarte();   // 1 von 10, Praemie "Gratis Kaffee"
        Kampagne k = Kampagne.starte(shop, "Noch {offen} Stempel bis: {praemie}!", Zielgruppe.ALLE, 1, jetzt);
        zeilen(k, cc);

        dienst.verarbeite(k, jetzt.plusSeconds(30));

        assertThat(cc.getKampagneText()).isEqualTo("Noch 9 Stempel bis: Gratis Kaffee!");
        verify(google).sendeNachricht(cc.getId(), "Café Mokka", "Noch 9 Stempel bis: Gratis Kaffee!", "kampagne");
        assertThat(k.getText()).isEqualTo("Noch {offen} Stempel bis: {praemie}!");
    }

    @Test
    void nurGoogle_bekommtTextSofort() {
        CustomerCard cc = kundenkarte();
        Kampagne k = kampagne();
        List<KampagneEmpfaenger> rows = zeilen(k, cc);

        dienst.verarbeite(k, jetzt.plusSeconds(30));

        assertThat(cc.getKampagneText()).isEqualTo("Heute 20% auf Kuchen");
        assertThat(cc.nachrichtFrisch()).isTrue();
        assertThat(rows.get(0).isErledigt()).isTrue();
        verify(google).sendeNachricht(cc.getId(), "Café Mokka", "Heute 20% auf Kuchen", "kampagne");
        verify(apns, never()).notifyUpdate(anyString());
        assertThat(k.getGesendet()).isEqualTo(1);
        assertThat(k.laeuft()).isTrue();
    }

    @Test
    void apple_alterPass_erstStill_dannText() {
        CustomerCard cc = kundenkarte();
        // Feld seit "gleich": der Pass der Karte ist aelter.
        when(shop.nachrichtenFeldSeit()).thenReturn(jetzt.plusSeconds(1));
        mitApple(cc);
        Kampagne k = kampagne();
        List<KampagneEmpfaenger> rows = zeilen(k, cc);

        dienst.verarbeite(k, jetzt.plusSeconds(30));
        verify(apns).notifyUpdate(cc.getId());
        verify(google, never()).sendeNachricht(anyString(), anyString(), anyString(), anyString());
        assertThat(cc.getKampagneText()).isNull();
        assertThat(rows.get(0).isErledigt()).isFalse();
        assertThat(rows.get(0).istAufgefrischt()).isTrue();
        assertThat(k.getGesendet()).isZero();

        // Naechster Lauf: updatedAt liegt jetzt nach dem Einschalten.
        dienst.verarbeite(k, jetzt.plusSeconds(60));
        verify(google).sendeNachricht(eq(cc.getId()), anyString(), anyString(), eq("kampagne"));
        assertThat(cc.getKampagneText()).isEqualTo("Heute 20% auf Kuchen");
        assertThat(rows.get(0).isErledigt()).isTrue();
        assertThat(k.getGesendet()).isEqualTo(1);
    }

    @Test
    void apple_passMitFeld_bekommtTextSofort() {
        when(shop.nachrichtenFeldSeit()).thenReturn(jetzt.minus(Duration.ofDays(30)));
        CustomerCard cc = kundenkarte();
        mitApple(cc);
        Kampagne k = kampagne();
        zeilen(k, cc);
        dienst.verarbeite(k, jetzt.plusSeconds(30));
        verify(apns).notifyUpdate(cc.getId());
        verify(google).sendeNachricht(eq(cc.getId()), anyString(), anyString(), eq("kampagne"));
        assertThat(cc.getKampagneText()).isEqualTo("Heute 20% auf Kuchen");
    }

    @Test
    void nichtsMehrOffen_kampagneFertig() {
        Kampagne k = kampagne();
        zeilen(k);
        dienst.verarbeite(k, jetzt.plusSeconds(30));
        assertThat(k.laeuft()).isFalse();
        assertThat(k.getStatus()).isEqualTo(Kampagne.FERTIG);
        assertThat(k.getFertigAm()).isEqualTo(jetzt.plusSeconds(30));
        verify(kampagneRepo).save(k);
    }

    @Test
    void geloeschteKarte_wirdAbgehakt_kampagneLaeuftWeiter() {
        Kampagne k = kampagne();
        KampagneEmpfaenger weg = KampagneEmpfaenger.neu(k.getId(), "CC-WEG");
        CustomerCard da = kundenkarte();
        KampagneEmpfaenger bleibt = KampagneEmpfaenger.neu(k.getId(), da.getId());
        when(ccRepo.findById("CC-WEG")).thenReturn(Optional.empty());
        // Erste Seite nur die geloeschte Karte, danach ist noch eine offen.
        when(empfaengerRepo.findByKampagneIdAndErledigtFalse(eq(k.getId()), any()))
                .thenReturn(List.of(weg));
        when(empfaengerRepo.countByKampagneIdAndErledigtFalse(k.getId())).thenReturn(1L);

        dienst.verarbeite(k, jetzt.plusSeconds(30));

        assertThat(weg.isErledigt()).isTrue();
        assertThat(bleibt.isErledigt()).isFalse();
        assertThat(k.laeuft()).isTrue();
        verify(google, never()).sendeNachricht(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void pushFehler_brichtNichtAb() {
        CustomerCard a = kundenkarte();
        CustomerCard b = kundenkarte();
        mitApple(a);
        when(shop.nachrichtenFeldSeit()).thenReturn(jetzt.minus(Duration.ofDays(30)));
        doThrow(new RuntimeException("APNs weg")).when(apns).notifyUpdate(a.getId());
        Kampagne k = kampagne();
        zeilen(k, a, b);
        dienst.verarbeite(k, jetzt.plusSeconds(30));
        verify(google).sendeNachricht(eq(b.getId()), anyString(), anyString(), eq("kampagne"));
        assertThat(k.getGesendet()).isEqualTo(2);
    }
}
