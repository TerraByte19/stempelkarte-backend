package com.example.stemplekarte.service;

import com.example.stemplekarte.model.AdminZugriff;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.repository.AdminZugriffRepository;
import com.example.stemplekarte.repository.ShopRepository;
import com.example.stemplekarte.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
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
 * Admin oeffnet einen Laden und aendert dessen Login-Adresse: jeder Zugriff
 * steht im Protokoll, das Passwort bleibt unberuehrt, und eine Adresse kann
 * nie doppelt oder kaputt gespeichert werden.
 */
class AdminZugangServiceTest {

    private static final String SECRET = "test-secret-mindestens-32-zeichen-lang-0123456789";

    private Shop shop;
    private ShopRepository shops;
    private AdminZugriffRepository protokoll;
    private JwtService jwt;
    private AdminZugangService dienst;

    @BeforeEach
    void aufbau() {
        shop = Shop.create("alt@laden.de", "HASH", "Café Mond", 3);
        shops = mock(ShopRepository.class);
        when(shops.findById(shop.getId())).thenReturn(Optional.of(shop));
        protokoll = mock(AdminZugriffRepository.class);
        jwt = new JwtService(SECRET);
        dienst = new AdminZugangService(shops, protokoll, jwt);
    }

    private AdminZugriff eintrag() {
        ArgumentCaptor<AdminZugriff> c = ArgumentCaptor.forClass(AdminZugriff.class);
        verify(protokoll).save(c.capture());
        return c.getValue();
    }

    // ── Laden oeffnen ────────────────────────────────────────────────────

    @Test
    void oeffnen_gibtLadenTokenFuerEineStundeUndProtokolliert() {
        Instant vorher = Instant.now();
        AdminZugangService.Zugang z = dienst.oeffnen(shop.getId(), "1.2.3.4");

        assertThat(z.shopId()).isEqualTo(shop.getId());
        assertThat(z.name()).isEqualTo("Café Mond");
        assertThat(jwt.isValid(z.token())).isTrue();
        assertThat(jwt.extractShopId(z.token())).isEqualTo(shop.getId());
        assertThat(jwt.isSupport(z.token())).isTrue();
        assertThat(Duration.between(vorher, z.bis())).isBetween(Duration.ofMinutes(59), Duration.ofMinutes(61));

        AdminZugriff e = eintrag();
        assertThat(e.getArt()).isEqualTo(AdminZugriff.ZUGANG);
        assertThat(e.getShopId()).isEqualTo(shop.getId());
        assertThat(e.getShopName()).isEqualTo("Café Mond");
        assertThat(e.getIp()).isEqualTo("1.2.3.4");
        assertThat(e.getZeitpunkt()).isNotNull();
    }

    @Test
    void oeffnen_tokenIstKeinAdminToken() {
        String token = dienst.oeffnen(shop.getId(), null).token();
        assertThat(jwt.isAdmin(token)).isFalse();
        assertThat(jwt.extractRole(token)).isEqualTo("SHOP");
    }

    @Test
    void oeffnen_laesstPasswortUndEmailInRuhe() {
        dienst.oeffnen(shop.getId(), null);
        assertThat(shop.getPasswordHash()).isEqualTo("HASH");
        assertThat(shop.getEmail()).isEqualTo("alt@laden.de");
        verify(shops, never()).save(any());
    }

    @Test
    void oeffnen_gesperrterLadenBekommtKeinenTokenUndKeinenEintrag() {
        shop.setActive(false);
        assertThatThrownBy(() -> dienst.oeffnen(shop.getId(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("gesperrt");
        verify(protokoll, never()).save(any());
    }

    @Test
    void oeffnen_unbekannterLaden() {
        when(shops.findById("SHOP-X")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> dienst.oeffnen("SHOP-X", null)).isInstanceOf(NoSuchElementException.class);
        verify(protokoll, never()).save(any());
    }

    @Test
    void oeffnen_ohneProtokollKeinZugang() {
        when(protokoll.save(any())).thenThrow(new RuntimeException("DB weg"));
        assertThatThrownBy(() -> dienst.oeffnen(shop.getId(), null)).hasMessage("DB weg");
    }

    @Test
    void normalerLadenTokenIstKeinSupportToken() {
        assertThat(jwt.isSupport(jwt.generateToken(shop.getId(), shop.getEmail()))).isFalse();
        assertThat(jwt.isSupport(jwt.generateAdminToken())).isFalse();
        assertThat(jwt.isSupport("kaputt")).isFalse();
    }

    // ── E-Mail aendern ───────────────────────────────────────────────────

    @Test
    void email_wirdKleinUndOhneRandGespeichertWieDerLoginSucht() {
        when(shops.existsByEmail("neu@laden.de")).thenReturn(false);
        Shop s = dienst.emailAendern(shop.getId(), "  Neu@Laden.DE ", "1.2.3.4");

        assertThat(s.getEmail()).isEqualTo("neu@laden.de");
        verify(shops).save(shop);
        assertThat(shop.getPasswordHash()).isEqualTo("HASH");

        AdminZugriff e = eintrag();
        assertThat(e.getArt()).isEqualTo(AdminZugriff.EMAIL);
        assertThat(e.getDetail()).isEqualTo("alt@laden.de -> neu@laden.de");
    }

    @Test
    void email_einesAnderenLadensWirdAbgelehnt() {
        when(shops.existsByEmail("vergeben@laden.de")).thenReturn(true);
        assertThatThrownBy(() -> dienst.emailAendern(shop.getId(), "Vergeben@laden.de", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("anderen Laden");
        nichtsGeaendert();
    }

    @Test
    void email_dieselbeAdresseIstKeineAenderung() {
        assertThatThrownBy(() -> dienst.emailAendern(shop.getId(), " ALT@laden.de", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schon die Adresse");
        nichtsGeaendert();
    }

    @Test
    void email_kaputteAdressenWerdenAbgelehnt() {
        String[] kaputte = {null, "", "   ", "ohne-at.de", "a@b", "a b@c.de", "a@@b.de", "@b.de", "a@.",
                "x".repeat(200) + "@b.de"};
        for (String kaputt : kaputte) {
            assertThatThrownBy(() -> dienst.emailAendern(shop.getId(), kaputt, null))
                    .as("Adresse: %s", kaputt)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("gültige");
        }
        nichtsGeaendert();
    }

    @Test
    void email_unbekannterLaden() {
        when(shops.findById("SHOP-X")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> dienst.emailAendern("SHOP-X", "a@b.de", null))
                .isInstanceOf(NoSuchElementException.class);
    }

    private void nichtsGeaendert() {
        assertThat(shop.getEmail()).isEqualTo("alt@laden.de");
        verify(shops, never()).save(any());
        verify(protokoll, never()).save(any());
    }
}
