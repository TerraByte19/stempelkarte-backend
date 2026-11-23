package com.example.stemplekarte.service;

import com.example.stemplekarte.model.FilialZugang;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.model.ShopOrt;
import com.example.stemplekarte.model.StaffToken;
import com.example.stemplekarte.model.Tarif;
import com.example.stemplekarte.repository.FilialZugangRepository;
import com.example.stemplekarte.repository.ShopOrtRepository;
import com.example.stemplekarte.repository.StaffTokenRepository;
import com.example.stemplekarte.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class FilialZugangServiceTest {

    private static final String SECRET = "test-secret-mindestens-32-zeichen-lang-0123456789";

    private FilialZugangRepository zugaenge;
    private ShopOrtRepository orte;
    private StaffTokenRepository staffTokens;
    private PasswordEncoder encoder;
    private JwtService jwt;
    private FilialZugangService service;
    private Shop kette;
    private ShopOrt mitte;

    @BeforeEach
    void aufbau() {
        zugaenge = mock(FilialZugangRepository.class);
        orte = mock(ShopOrtRepository.class);
        staffTokens = mock(StaffTokenRepository.class);
        encoder = new BCryptPasswordEncoder(4);
        jwt = new JwtService(SECRET);
        service = new FilialZugangService(zugaenge, orte, staffTokens, encoder, jwt);
        kette = Shop.create("z@kette.de", "HASH", "BackBaron", 3);
        kette.setTarif(Tarif.KETTE);
        when(zugaenge.save(any())).thenAnswer(a -> a.getArgument(0));
        // Ein Login haengt immer an einer Filiale - am Hauptstandort gibt es keins.
        mitte = ShopOrt.create(kette, "Mitte", 52.5, 13.4);
        when(orte.findById(mitte.getId())).thenReturn(Optional.of(mitte));
        when(orte.findByIdAndShop(mitte.getId(), kette)).thenReturn(Optional.of(mitte));
    }

    /** ortId null = die Standard-Filiale "Mitte". */
    private FilialZugang vorhanden(String name, String passwort, String ortId) {
        return vorhandenAn(name, passwort, ortId != null ? ortId : mitte.getId());
    }

    private FilialZugang vorhandenAn(String name, String passwort, String ortId) {
        FilialZugang z = FilialZugang.create(kette, name, encoder.encode(passwort), ortId);
        when(zugaenge.findWithShopByBenutzername(name)).thenReturn(Optional.of(z));
        when(zugaenge.findByIdAndShop(z.getId(), kette)).thenReturn(Optional.of(z));
        return z;
    }

    @Test
    void anlegen_speichertGehashtesPasswort() {
        FilialZugang z = service.anlegen(kette, "BackBaron-Mitte", "geheim123", mitte.getId());
        assertThat(z.getBenutzername()).isEqualTo("backbaron-mitte");
        assertThat(encoder.matches("geheim123", z.getPasswortHash())).isTrue();
        assertThat(z.getOrtId()).isEqualTo(mitte.getId());
    }

    @Test
    void anlegen_amHauptstandortGibtEsKeinLogin() {
        for (String haupt : new String[]{null, "", "HAUPT"}) {
            assertThatThrownBy(() -> service.anlegen(kette, "backbaron-haupt", "geheim123", haupt))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage(FilialZugangService.KEIN_HAUPT_LOGIN);
        }
        verify(zugaenge, never()).save(any());
    }

    @Test
    void anlegen_nurImTarifKette() {
        Shop pro = Shop.create("p@laden.de", "HASH", "Einzelladen", 3);
        assertThatThrownBy(() -> service.anlegen(pro, "laden-eins", "geheim123", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Tarif Kette");
    }

    @Test
    void anlegen_fremdeFilialeGibtEsNicht() {
        when(orte.findByIdAndShop("ORT-FREMD", kette)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.anlegen(kette, "backbaron-x", "geheim123", "ORT-FREMD"))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void anlegen_kurzesPasswortUndDoppelterName() {
        assertThatThrownBy(() -> service.anlegen(kette, "backbaron-mitte", "kurz", mitte.getId()))
                .isInstanceOf(IllegalArgumentException.class);
        when(zugaenge.existsByBenutzername("backbaron-mitte")).thenReturn(true);
        assertThatThrownBy(() -> service.anlegen(kette, "backbaron-mitte", "geheim123", mitte.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("vergeben");
    }

    @Test
    void login_gibtFilialTokenMitVersion() {
        FilialZugang z = vorhanden("backbaron-mitte", "geheim123", null);
        FilialZugangService.Anmeldung a = service.login(" BackBaron-Mitte ", "geheim123");
        assertThat(jwt.extractRole(a.token())).isEqualTo("FILIALE");
        assertThat(jwt.extractShopId(a.token())).isEqualTo(z.getId());
        assertThat(jwt.extractVersion(a.token())).isEqualTo(0);
        assertThat(a.ortLabel()).isEqualTo("Mitte");
        assertThat(z.getLastLoginAt()).isNotNull();
    }

    @Test
    void login_falschesPasswortUndUnbekannterName_gleicherText() {
        vorhanden("backbaron-mitte", "geheim123", null);
        when(zugaenge.findWithShopByBenutzername("gibt-es-nicht")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.login("backbaron-mitte", "falsch"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(FilialZugangService.LOGIN_FALSCH);
        assertThatThrownBy(() -> service.login("gibt-es-nicht", "geheim123"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(FilialZugangService.LOGIN_FALSCH);
        assertThatThrownBy(() -> service.login("a@b.de", "geheim123"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(FilialZugangService.LOGIN_FALSCH);
    }

    @Test
    void login_gesperrtLadenAusTarifWeg() {
        FilialZugang z = vorhanden("backbaron-mitte", "geheim123", null);
        z.sperre(true);
        assertThatThrownBy(() -> service.login("backbaron-mitte", "geheim123"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("gesperrt");
        z.sperre(false);
        kette.setTarif(Tarif.PRO);
        assertThatThrownBy(() -> service.login("backbaron-mitte", "geheim123"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("gesperrt");
        kette.setTarif(Tarif.KETTE);
        kette.setActive(false);
        assertThatThrownBy(() -> service.login("backbaron-mitte", "geheim123"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("deaktiviert");
    }

    @Test
    void login_ausgeschalteteFilialeKommtNichtRein() {
        ShopOrt nord = ShopOrt.create(kette, "Nord", 52.5, 13.4);
        nord.setAktiv(false);
        when(orte.findById(nord.getId())).thenReturn(Optional.of(nord));
        vorhanden("backbaron-nord", "geheim123", nord.getId());
        assertThatThrownBy(() -> service.login("backbaron-nord", "geheim123"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(FilialZugangService.FILIALE_AUS);
        nord.setAktiv(true);
        assertThat(service.login("backbaron-nord", "geheim123").ortLabel()).isEqualTo("Nord");
    }

    @Test
    void altesLoginAmHauptstandort_kommtNichtMehrRein() {
        vorhandenAn("backbaron-haupt", "geheim123", null);
        assertThatThrownBy(() -> service.login("backbaron-haupt", "geheim123"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(FilialZugangService.FILIALE_AUS);
    }

    @Test
    void filialeWartetAufBestaetigung_loginKommtNichtRein() {
        vorhanden("backbaron-mitte", "geheim123", null);
        mitte.setBestaetigt(false);
        assertThatThrownBy(() -> service.login("backbaron-mitte", "geheim123"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(FilialZugangService.FILIALE_AUS);
        mitte.setBestaetigt(true);
        assertThat(service.login("backbaron-mitte", "geheim123").ortLabel()).isEqualTo("Mitte");
    }

    @Test
    void passwortSetzen_machtAlteTokensUngueltig() {
        FilialZugang z = vorhanden("backbaron-mitte", "geheim123", null);
        service.passwortSetzen(kette, z.getId(), "ganzneu123");
        assertThat(z.getTokenVersion()).isEqualTo(1);
        assertThat(encoder.matches("ganzneu123", z.getPasswortHash())).isTrue();
    }

    @Test
    void fremderZugang_istNichtDa() {
        when(zugaenge.findByIdAndShop("FZ-FREMD", kette)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.sperren(kette, "FZ-FREMD", true)).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> service.loeschen(kette, "FZ-FREMD")).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void sperren_gibtDenGeraetenDerFilialeNeueSchluessel() {
        ShopOrt nord = ShopOrt.create(kette, "Nord", 52.5, 13.4);
        StaffToken imNorden = StaffToken.create(kette, "Kasse Nord", StaffToken.ROLLE_AUSHILFE);
        imNorden.ort(nord.getId());
        StaffToken woanders = StaffToken.create(kette, "Kasse Sued");
        woanders.ort("ORT-SUED");
        StaffToken haupt = StaffToken.create(kette, "Zentrale");
        when(staffTokens.findByShop(kette)).thenReturn(List.of(imNorden, woanders, haupt));
        FilialZugang z = vorhanden("backbaron-nord", "geheim123", nord.getId());

        service.sperren(kette, z.getId(), true);

        verify(staffTokens).delete(imNorden);
        verify(staffTokens, never()).delete(woanders);
        verify(staffTokens, never()).delete(haupt);
        ArgumentCaptor<StaffToken> neu = ArgumentCaptor.forClass(StaffToken.class);
        verify(staffTokens).save(neu.capture());
        assertThat(neu.getValue().getToken()).isNotEqualTo(imNorden.getToken());
        assertThat(neu.getValue().getLabel()).isEqualTo("Kasse Nord");
        assertThat(neu.getValue().getRolle()).isEqualTo(StaffToken.ROLLE_AUSHILFE);
        assertThat(neu.getValue().getOrtId()).isEqualTo(nord.getId());

        // Nochmal sperren oder freigeben fasst die Geraete nicht noch einmal an.
        service.sperren(kette, z.getId(), true);
        service.sperren(kette, z.getId(), false);
        verify(staffTokens, times(1)).save(any());
    }

    @Test
    void sperren_amHauptstandortBleibenDieGeraeteDesHauptkontos() {
        FilialZugang z = vorhandenAn("backbaron-haupt", "geheim123", null);
        service.sperren(kette, z.getId(), true);
        assertThat(z.isGesperrt()).isTrue();
        verifyNoInteractions(staffTokens);
    }

    @Test
    void loeschen_entferntDenZugang() {
        FilialZugang z = vorhanden("backbaron-mitte", "geheim123", null);
        service.loeschen(kette, z.getId());
        verify(zugaenge).delete(z);
    }
}
