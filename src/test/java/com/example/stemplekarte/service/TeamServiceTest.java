package com.example.stemplekarte.service;

import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.model.StaffToken;
import com.example.stemplekarte.repository.ScanLogRepository;
import com.example.stemplekarte.repository.StaffTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
 * Team-Verwaltung: Name und Rolle sauber, nur eigene Zugaenge, der letzte
 * bleibt. Und die Rolle selbst: ohne Eintrag ist niemand Aushilfe.
 */
class TeamServiceTest {

    private final Instant jetzt = Instant.parse("2026-11-03T12:00:00Z");
    private Shop shop;
    private Shop fremd;
    private StaffTokenRepository tokens;
    private ScanLogRepository scans;
    private ShopService shopService;
    private TeamService dienst;

    @BeforeEach
    void aufbau() {
        shop = Shop.create("a@b.de", "hash", "Café Mokka", 3);
        fremd = Shop.create("x@y.de", "hash", "Anderer", 3);
        tokens = mock(StaffTokenRepository.class);
        when(tokens.save(any())).thenAnswer(inv -> inv.getArgument(0));
        scans = mock(ScanLogRepository.class);
        shopService = mock(ShopService.class);
        dienst = new TeamService(tokens, scans, shopService);
    }

    private StaffToken zugang(Shop laden, String label, String rolle) {
        StaffToken t = StaffToken.create(laden, label, rolle);
        when(tokens.findById(t.getToken())).thenReturn(Optional.of(t));
        return t;
    }

    @Test
    void rolle_leerIstMitarbeiter_unbekanntKnallt() {
        assertThat(StaffToken.rolleSauber(null)).isEqualTo("MITARBEITER");
        assertThat(StaffToken.rolleSauber(" aushilfe ")).isEqualTo("AUSHILFE");
        assertThatThrownBy(() -> StaffToken.rolleSauber("chef")).isInstanceOf(IllegalArgumentException.class);
        StaffToken alt = StaffToken.create(shop, "Kasse 1");
        assertThat(alt.istAushilfe()).isFalse();
        assertThat(alt.darfEinloesen()).isTrue();
        StaffToken aushilfe = StaffToken.create(shop, "Samstag", "AUSHILFE");
        assertThat(aushilfe.istAushilfe()).isTrue();
        assertThat(aushilfe.darfEinloesen()).isFalse();
    }

    @Test
    void name_getrimmtUndBegrenzt() {
        assertThat(TeamService.labelSauber("  Kasse  1 \n")).isEqualTo("Kasse 1");
        assertThatThrownBy(() -> TeamService.labelSauber("  ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TeamService.labelSauber("x".repeat(41))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anlegen_gehtUeberDenShopService() {
        dienst.anlegen(shop, " Theke ", "aushilfe");
        verify(shopService).createStaffToken(shop.getId(), "Theke", "AUSHILFE");
    }

    @Test
    void liste_mitStempelzaehlerUndZuletzt() {
        StaffToken a = StaffToken.create(shop, "Kasse 1");
        a.benutzt(jetzt.minusSeconds(60));
        StaffToken b = StaffToken.create(shop, "Samstag", "AUSHILFE");
        when(tokens.findByShop(shop)).thenReturn(List.of(b, a));
        when(scans.countByShopIdAndStaffLabelAndScannedAtAfter(eq(shop.getId()), eq("Kasse 1"), any())).thenReturn(12L);

        List<TeamService.Zugang> liste = dienst.liste(shop, jetzt);
        assertThat(liste).hasSize(2);
        // Beide im selben Augenblick angelegt - deshalb nach Name suchen, nicht nach Position.
        TeamService.Zugang kasse = liste.stream().filter(z -> z.label().equals("Kasse 1")).findFirst().orElseThrow();
        TeamService.Zugang samstag = liste.stream().filter(z -> z.label().equals("Samstag")).findFirst().orElseThrow();
        assertThat(kasse.stempel7d()).isEqualTo(12);
        assertThat(kasse.zuletzt()).isEqualTo(jetzt.minusSeconds(60).toString());
        assertThat(kasse.rolle()).isEqualTo("MITARBEITER");
        assertThat(kasse.token()).isEqualTo(a.getToken());
        assertThat(samstag.rolle()).isEqualTo("AUSHILFE");
        assertThat(samstag.zuletzt()).isNull();
        assertThat(samstag.stempel7d()).isZero();
    }

    @Test
    void aendern_nurEigene_nullLaesstFeldInRuhe() {
        StaffToken t = zugang(shop, "Kasse 1", null);
        dienst.aendern(shop, t.getToken(), null, "AUSHILFE");
        assertThat(t.getLabel()).isEqualTo("Kasse 1");
        assertThat(t.getRolle()).isEqualTo("AUSHILFE");
        dienst.aendern(shop, t.getToken(), "Theke", null);
        assertThat(t.getLabel()).isEqualTo("Theke");
        assertThat(t.getRolle()).isEqualTo("AUSHILFE");

        StaffToken fremder = zugang(fremd, "Fremd", null);
        assertThatThrownBy(() -> dienst.aendern(shop, fremder.getToken(), "Hack", null))
                .isInstanceOf(NoSuchElementException.class);
        assertThat(fremder.getLabel()).isEqualTo("Fremd");
        assertThatThrownBy(() -> dienst.aendern(shop, "gibt-es-nicht", "x", null))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void entfernen_derLetzteBleibt() {
        StaffToken a = zugang(shop, "Kasse 1", null);
        StaffToken b = zugang(shop, "Kasse 2", null);
        when(tokens.findByShop(shop)).thenReturn(List.of(a, b));
        dienst.entfernen(shop, b.getToken());
        verify(tokens).delete(b);

        when(tokens.findByShop(shop)).thenReturn(List.of(a));
        assertThatThrownBy(() -> dienst.entfernen(shop, a.getToken()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("letzte");
        verify(tokens, never()).delete(a);

        StaffToken fremder = zugang(fremd, "Fremd", null);
        assertThatThrownBy(() -> dienst.entfernen(shop, fremder.getToken()))
                .isInstanceOf(NoSuchElementException.class);
        verify(tokens, never()).delete(fremder);
    }
}
