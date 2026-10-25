package com.example.stemplekarte.service;

import com.example.stemplekarte.model.AppleDeviceRegistration;
import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.Customer;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.repository.AppleDeviceRepository;
import com.example.stemplekarte.repository.CustomerCardRepository;
import com.example.stemplekarte.repository.ShopRepository;
import com.example.stemplekarte.wallet.ApnsPushService;
import com.example.stemplekarte.wallet.GoogleWalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Rueckhol-Nachricht: wer wann was bekommt - und dass es pro Pause bei einer bleibt. */
class RueckholTest {

    private final Instant jetzt = Instant.now();
    // Liegt in der Zukunft: jede frische Karte gilt damit als "lange nicht da".
    private final Instant grenze = jetzt.plus(Duration.ofDays(1));
    // Zeitpunkt der Laeufe: 30 Tage nach dem letzten Besuch.
    private final Instant spaeter = jetzt.plus(Duration.ofDays(30));

    private Shop shop;
    private Card card;
    private CustomerCardRepository ccRepo;
    private AppleDeviceRepository deviceRepo;
    private ApnsPushService apns;
    private GoogleWalletService google;
    private RueckholService dienst;

    @BeforeEach
    void setUp() {
        shop = mock(Shop.class);
        when(shop.getId()).thenReturn("SHOP-1");
        when(shop.getName()).thenReturn("Café Mokka");
        when(shop.isActive()).thenReturn(true);
        when(shop.isRueckholAktiv()).thenReturn(true);
        card = Card.create(shop, "Kaffee", "10 Stempel", 10, "Gratis Kaffee");

        ccRepo = mock(CustomerCardRepository.class);
        deviceRepo = mock(AppleDeviceRepository.class);
        apns = mock(ApnsPushService.class);
        google = mock(GoogleWalletService.class);
        TransactionTemplate tx = mock(TransactionTemplate.class);
        when(tx.execute(any())).thenAnswer(inv ->
                ((TransactionCallback<?>) inv.getArgument(0)).doInTransaction(null));

        dienst = new RueckholService(mock(ShopRepository.class), ccRepo, deviceRepo,
                mock(RewardService.class), apns, google, tx);
    }

    private CustomerCard kundenkarte(int stempel) {
        CustomerCard cc = CustomerCard.create(mock(Customer.class), card);
        for (int i = 0; i < stempel; i++) cc.addStamp();
        return cc;
    }

    private void kandidaten(CustomerCard... karten) {
        when(ccRepo.findRueckholKandidaten(eq(shop), any(), any())).thenReturn(List.of(karten));
    }

    private void mitApple(CustomerCard cc) {
        when(deviceRepo.findBySerialNumber(cc.getId()))
                .thenReturn(List.of(mock(AppleDeviceRegistration.class)));
    }

    // ── Regel auf der Karte ──────────────────────────────────────────────

    @Test
    void langeNichtDa_istFaellig() {
        assertThat(kundenkarte(3).rueckholFaellig(grenze)).isTrue();
    }

    @Test
    void kuerzlichDa_istNichtFaellig() {
        CustomerCard cc = kundenkarte(3);
        assertThat(cc.rueckholFaellig(jetzt.minus(Duration.ofDays(14)))).isFalse();
    }

    @Test
    void proPause_nurEineNachricht() {
        CustomerCard cc = kundenkarte(3);
        cc.rueckholGesendet("Wir vermissen dich!", spaeter);
        assertThat(cc.rueckholFaellig(spaeter.plus(Duration.ofDays(30)))).isFalse();
        assertThat(cc.rueckholSichtbar()).isTrue();
    }

    @Test
    void nachDemNaechstenBesuch_wiederFaelligUndNachrichtWeg() throws Exception {
        CustomerCard cc = kundenkarte(3);
        cc.rueckholGesendet("Wir vermissen dich!", Instant.now());
        Thread.sleep(5);
        cc.addStamp();
        assertThat(cc.rueckholSichtbar()).isFalse();
        assertThat(cc.rueckholFaellig(grenze)).isTrue();
    }

    @Test
    void verschickenZaehltNichtAlsBesuch() {
        CustomerCard cc = kundenkarte(3);
        Instant besuch = cc.letzterBesuch();
        cc.rueckholGesendet("x", spaeter);
        assertThat(cc.letzterBesuch()).isEqualTo(besuch);
        assertThat(cc.getUpdatedAt()).isEqualTo(spaeter);
    }

    // ── Text ─────────────────────────────────────────────────────────────

    @Test
    void standardText_nenntFehlendeStempel() {
        assertThat(RueckholService.text(shop, kundenkarte(7), mock(RewardService.class)))
                .isEqualTo("Wir vermissen dich! Noch 3 Stempel bis: Gratis Kaffee");
    }

    @Test
    void standardText_volleKarte() {
        assertThat(RueckholService.text(shop, kundenkarte(10), mock(RewardService.class)))
                .isEqualTo("Wir vermissen dich! Gratis Kaffee wartet auf dich.");
    }

    @Test
    void eigenerText_mitPlatzhaltern() {
        when(shop.getWinbackText()).thenReturn("Lange nicht gesehen! Nur noch {stempel} bis {belohnung}.");
        assertThat(RueckholService.text(shop, kundenkarte(8), mock(RewardService.class)))
                .isEqualTo("Lange nicht gesehen! Nur noch 2 bis Gratis Kaffee.");
    }

    @Test
    void tage_nurAusDerAuswahl() {
        when(shop.getWinbackDays()).thenReturn(21);
        assertThat(RueckholService.tageFuer(shop)).isEqualTo(21);
        when(shop.getWinbackDays()).thenReturn(5);
        assertThat(RueckholService.tageFuer(shop)).isEqualTo(14);
        when(shop.getWinbackDays()).thenReturn(null);
        assertThat(RueckholService.tageFuer(shop)).isEqualTo(14);
    }

    // ── Versand ──────────────────────────────────────────────────────────

    @Test
    void nurGoogle_bekommtNachrichtSofort() {
        CustomerCard cc = kundenkarte(7);
        kandidaten(cc);

        dienst.verschicke(shop, spaeter);

        verify(google).sendeNachricht(eq(cc.getId()), eq("Café Mokka"), anyString());
        verify(apns, never()).notifyUpdate(anyString());
        assertThat(cc.rueckholSichtbar()).isTrue();
    }

    @Test
    void apple_alterPass_wirdErstStillAufgefrischt() {
        CustomerCard cc = kundenkarte(7);
        // Funktion erst nach der letzten Aenderung der Karte eingeschaltet.
        when(shop.getWinbackEnabledAt()).thenReturn(Instant.now().plusSeconds(1));
        mitApple(cc);
        kandidaten(cc);

        dienst.verschicke(shop, spaeter);

        verify(apns).notifyUpdate(cc.getId());
        verify(google, never()).sendeNachricht(anyString(), anyString(), anyString());
        assertThat(cc.rueckholSichtbar()).isFalse();
        // Naechster Lauf: Pass traegt das Feld, jetzt kommt die Nachricht.
        dienst.verschicke(shop, spaeter.plus(Duration.ofHours(1)));
        verify(google).sendeNachricht(eq(cc.getId()), anyString(), anyString());
        assertThat(cc.rueckholSichtbar()).isTrue();
    }

    @Test
    void apple_passMitFeld_bekommtNachricht() {
        when(shop.getWinbackEnabledAt()).thenReturn(jetzt.minus(Duration.ofDays(30)));
        CustomerCard cc = kundenkarte(7);
        mitApple(cc);
        kandidaten(cc);

        dienst.verschicke(shop, spaeter);

        verify(apns).notifyUpdate(cc.getId());
        verify(google).sendeNachricht(eq(cc.getId()), anyString(), anyString());
        assertThat(cc.rueckholSichtbar()).isTrue();
    }

    @Test
    void schonBenachrichtigt_wirdUebersprungen() {
        CustomerCard cc = kundenkarte(7);
        cc.rueckholGesendet("alt", Instant.now().plusSeconds(1));
        kandidaten(cc);

        dienst.verschicke(shop, spaeter);

        verify(google, never()).sendeNachricht(anyString(), anyString(), anyString());
        verify(apns, never()).notifyUpdate(anyString());
    }
}
