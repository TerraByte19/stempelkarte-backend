package com.example.stemplekarte.service;

import com.example.stemplekarte.model.AdminZugriff;
import com.example.stemplekarte.model.FilialZugang;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.model.ShopOrt;
import com.example.stemplekarte.model.StaffToken;
import com.example.stemplekarte.model.Tarif;
import com.example.stemplekarte.repository.AdminZugriffRepository;
import com.example.stemplekarte.repository.ShopRepository;
import com.example.stemplekarte.repository.StaffTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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

/** Admin greift in eine Kette ein: Ueberblick stimmt, jeder Eingriff steht im Protokoll, nie ein Passwort. */
class AdminKettenServiceTest {

    private Shop kette;
    private ShopOrt nord;
    private ShopOrt sued;
    private ShopRepository shops;
    private OrteService orte;
    private FilialZugangService zugaenge;
    private StaffTokenRepository tokens;
    private AdminZugriffRepository protokoll;
    private AdminKettenService dienst;

    @BeforeEach
    void setUp() {
        kette = Shop.create("z@kette.de", "hash", "BackBaron", 3);
        kette.setTarif(Tarif.KETTE);
        kette.setMailsAus(true);
        nord = ShopOrt.create(kette, "Nord", 52.6, 13.4);
        sued = ShopOrt.create(kette, "Süd", 52.4, 13.4);
        sued.setAktiv(false);
        sued.setAdresse("Südweg 1, Berlin");

        shops = mock(ShopRepository.class);
        when(shops.findById(kette.getId())).thenReturn(Optional.of(kette));
        orte = mock(OrteService.class);
        when(orte.liste(kette)).thenReturn(List.of(nord, sued));
        zugaenge = mock(FilialZugangService.class);
        tokens = mock(StaffTokenRepository.class);
        protokoll = mock(AdminZugriffRepository.class);
        dienst = new AdminKettenService(shops, orte, zugaenge, tokens, protokoll);
    }

    private AdminZugriff letzterEintrag() {
        ArgumentCaptor<AdminZugriff> z = ArgumentCaptor.forClass(AdminZugriff.class);
        verify(protokoll).save(z.capture());
        return z.getValue();
    }

    @Test
    void uebersicht_zaehltFilialenLoginsUndGeraeteJeFiliale() {
        FilialZugang loginNord = FilialZugang.create(kette, "backbaron-nord", "HASH", nord.getId());
        FilialZugang loginHaupt = FilialZugang.create(kette, "backbaron-haupt", "HASH", null);
        loginHaupt.sperre(true);
        when(zugaenge.liste(kette)).thenReturn(List.of(loginNord, loginHaupt));
        StaffToken g1 = StaffToken.create(kette, "Kasse Nord 1");
        g1.ort(nord.getId());
        StaffToken g2 = StaffToken.create(kette, "Kasse Nord 2");
        g2.ort(nord.getId());
        StaffToken g3 = StaffToken.create(kette, "Zentrale");
        when(tokens.findByShop(kette)).thenReturn(List.of(g1, g2, g3));

        AdminKettenService.Uebersicht u = dienst.uebersicht(kette.getId());

        assertThat(u.kette()).isTrue();
        assertThat(u.mailsAus()).isTrue();
        assertThat(u.tarif()).isEqualTo("KETTE");
        assertThat(u.geraeteJeFiliale()).isEqualTo(Shop.GERAETE_JE_FILIALE_STANDARD);
        assertThat(u.filialen()).isEqualTo(3);        // Hauptstandort + 2
        assertThat(u.filialenAktiv()).isEqualTo(2);   // Sued ist aus
        assertThat(u.logins()).isEqualTo(2);
        assertThat(u.liste()).extracting(AdminKettenService.Filiale::id)
                .containsExactly("HAUPT", nord.getId(), sued.getId());

        AdminKettenService.Filiale n = u.liste().get(1);
        assertThat(n.geraete()).isEqualTo(2);
        assertThat(n.logins()).extracting(AdminKettenService.Login::benutzername).containsExactly("backbaron-nord");
        AdminKettenService.Filiale h = u.liste().get(0);
        assertThat(h.haupt()).isTrue();
        assertThat(h.geraete()).isEqualTo(1);
        assertThat(h.logins().get(0).gesperrt()).isTrue();
        AdminKettenService.Filiale s = u.liste().get(2);
        assertThat(s.aktiv()).isFalse();
        assertThat(s.adresse()).isEqualTo("Südweg 1, Berlin");
        // Nur lesen: kein Protokoll.
        verify(protokoll, never()).save(any());
    }

    @Test
    void loginAnlegenUndPasswort_stehenImProtokoll_ohnePasswort() {
        FilialZugang z = FilialZugang.create(kette, "backbaron-nord", "HASH", nord.getId());
        when(zugaenge.anlegen(kette, "backbaron-nord", "streng-geheim-1", nord.getId())).thenReturn(z);
        dienst.loginAnlegen(kette.getId(), "backbaron-nord", "streng-geheim-1", nord.getId(), "1.2.3.4");

        AdminZugriff e = letzterEintrag();
        assertThat(e.getArt()).isEqualTo(AdminZugriff.KETTE);
        assertThat(e.getShopId()).isEqualTo(kette.getId());
        assertThat(e.getIp()).isEqualTo("1.2.3.4");
        assertThat(e.getDetail()).contains("backbaron-nord").doesNotContain("streng-geheim-1");
    }

    @Test
    void passwortNeu_stehtImProtokoll_ohnePasswort() {
        FilialZugang z = FilialZugang.create(kette, "backbaron-nord", "HASH", nord.getId());
        when(zugaenge.passwortSetzen(kette, z.getId(), "ganz-neu-123")).thenReturn(z);
        dienst.loginPasswort(kette.getId(), z.getId(), "ganz-neu-123", null);
        assertThat(letzterEintrag().getDetail()).contains("backbaron-nord").doesNotContain("ganz-neu-123");
    }

    @Test
    void sperreUndFilialeSchalten_stehenImProtokoll() {
        FilialZugang z = FilialZugang.create(kette, "backbaron-nord", "HASH", nord.getId());
        when(zugaenge.sperren(kette, z.getId(), true)).thenReturn(z);
        dienst.loginSperre(kette.getId(), z.getId(), true, null);
        assertThat(letzterEintrag().getDetail()).contains("gesperrt").contains("backbaron-nord");
    }

    @Test
    void filialeSchalten_reichtAnDenOrteServiceUndProtokolliert() {
        dienst.filialeAktiv(kette.getId(), sued.getId(), true, null);
        verify(orte).aendern(kette.getId(), sued.getId(), null, null, null, true);
        assertThat(letzterEintrag().getDetail()).contains("eingeschaltet");
    }

    @Test
    void scheitertDerEingriff_gibtEsKeinenProtokollEintrag() {
        when(zugaenge.anlegen(any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("Unterkonten gibt es nur im Tarif Kette."));
        assertThatThrownBy(() -> dienst.loginAnlegen(kette.getId(), "x-y-z", "geheim123", null, null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(protokoll, never()).save(any());
    }

    @Test
    void geraeteJeFiliale_bereichUndUnbekannterLaden() {
        dienst.geraeteJeFiliale(kette.getId(), 8, null);
        assertThat(kette.getMaxTokensJeFiliale()).isEqualTo(8);
        verify(shops).save(kette);

        assertThatThrownBy(() -> dienst.geraeteJeFiliale(kette.getId(), 0, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> dienst.geraeteJeFiliale(kette.getId(), 51, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(kette.getMaxTokensJeFiliale()).isEqualTo(8);
        assertThatThrownBy(() -> dienst.uebersicht("SHOP-GIBT-ES-NICHT"))
                .isInstanceOf(NoSuchElementException.class);
    }
}
