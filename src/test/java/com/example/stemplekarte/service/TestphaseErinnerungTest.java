package com.example.stemplekarte.service;

import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.repository.ShopRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Erinnerung zur Testphase: 3 Tage vorher und wenn vorbei, je einmal, nur tagsueber. */
class TestphaseErinnerungTest {

    // 12:00 Uhr Berlin (Winterzeit = UTC+1)
    private static final Instant MITTAG = Instant.parse("2026-11-04T11:00:00Z");

    private ShopRepository repo;
    private EmailService mail;
    private TestphaseErinnerungService dienst;
    private Shop shop;

    @BeforeEach
    void aufbau() {
        repo = mock(ShopRepository.class);
        mail = mock(EmailService.class);
        dienst = new TestphaseErinnerungService(repo, mail, "https://app.example.de/, https://zwei.example.de");
        shop = Shop.create("laden@example.de", "hash", "Laden", 3);
        when(repo.findByTestBisNotNull()).thenReturn(List.of(shop));
        when(mail.sendTestphaseMail(any(), any(), anyBoolean(), anyString())).thenReturn(true);
    }

    @Test
    void frueh_nochKeineMail() {
        shop.setTestBis(MITTAG.plus(Duration.ofDays(5)));
        assertThat(dienst.lauf(MITTAG)).isZero();
        verify(mail, never()).sendTestphaseMail(any(), any(), anyBoolean(), anyString());
    }

    @Test
    void dreiTageVorher_einmalBald_dannEinmalVorbei() {
        Instant bis = MITTAG.plus(Duration.ofDays(3));
        shop.setTestBis(bis);
        assertThat(dienst.lauf(MITTAG)).isEqualTo(1);
        verify(mail).sendTestphaseMail(shop, bis, false, "https://app.example.de/dashboard");
        // Naechster Lauf: nicht nochmal.
        assertThat(dienst.lauf(MITTAG.plus(Duration.ofHours(1)))).isZero();

        Instant danach = bis.plus(Duration.ofHours(1));
        assertThat(dienst.lauf(danach)).isEqualTo(1);
        verify(mail).sendTestphaseMail(shop, bis, true, "https://app.example.de/dashboard");
        assertThat(dienst.lauf(danach.plus(Duration.ofDays(1)))).isZero();
        verify(mail, times(2)).sendTestphaseMail(any(), any(), anyBoolean(), anyString());
    }

    @Test
    void schonVorbei_nurDieVorbeiMail() {
        shop.setTestBis(MITTAG.minus(Duration.ofDays(1)));
        assertThat(dienst.lauf(MITTAG)).isEqualTo(1);
        verify(mail).sendTestphaseMail(eq(shop), any(), eq(true), anyString());
        verify(mail, never()).sendTestphaseMail(any(), any(), eq(false), anyString());
    }

    @Test
    void nachts_keineMail() {
        shop.setTestBis(MITTAG.plus(Duration.ofDays(1)));
        assertThat(dienst.lauf(Instant.parse("2026-11-04T22:00:00Z"))).isZero(); // 23 Uhr Berlin
        assertThat(dienst.lauf(Instant.parse("2026-11-04T06:30:00Z"))).isZero(); // 7:30 Uhr Berlin
    }

    @Test
    void mailNichtRaus_wirdSpaeterNochmalVersucht() {
        when(mail.sendTestphaseMail(any(), any(), anyBoolean(), anyString())).thenReturn(false);
        shop.setTestBis(MITTAG.plus(Duration.ofDays(2)));
        assertThat(dienst.lauf(MITTAG)).isZero();
        assertThat(shop.getTestMailStufe()).isZero();
    }

    @Test
    void verlaengern_erinnertWiederNeu() {
        shop.setTestBis(MITTAG.minus(Duration.ofHours(1)));
        dienst.lauf(MITTAG);
        assertThat(shop.getTestMailStufe()).isEqualTo(TestphaseErinnerungService.STUFE_VORBEI);
        shop.setTestBis(MITTAG.plus(Duration.ofDays(7)));
        assertThat(shop.getTestMailStufe()).isZero();
    }

    @Test
    void bezahlt_oderInaktiv_keineMail() {
        Shop bezahlt = Shop.create("b@example.de", "hash", "B", 3);
        when(repo.findByTestBisNotNull()).thenReturn(List.of(bezahlt));
        assertThat(dienst.lauf(MITTAG)).isZero();
        verify(mail, never()).sendTestphaseMail(any(), any(), anyBoolean(), anyString());
    }
}
