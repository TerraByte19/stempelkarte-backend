package com.example.stemplekarte.controller;

import com.example.stemplekarte.model.FilialZugang;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.model.Tarif;
import com.example.stemplekarte.security.JwtAuthFilter;
import com.example.stemplekarte.security.LoginAttemptService;
import com.example.stemplekarte.service.FilialZugangService;
import com.example.stemplekarte.service.FilialenService;
import com.example.stemplekarte.service.ShopService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class FilialLoginTest {

    private ShopService shopService;
    private FilialZugangService filialService;
    private LoginAttemptService versuche;
    private AuthController controller;
    private Shop kette;

    @BeforeEach
    void aufbau() {
        shopService = mock(ShopService.class);
        filialService = mock(FilialZugangService.class);
        versuche = mock(LoginAttemptService.class);
        controller = new AuthController(shopService, versuche, filialService);
        kette = Shop.create("z@kette.de", "HASH", "BackBaron", 3);
        kette.setTarif(Tarif.KETTE);
    }

    @Test
    void mitEmail_laeuftDerLadenLoginWieBisher() {
        when(shopService.login("z@kette.de", "pw")).thenReturn("LADEN-TOKEN");
        when(shopService.getByEmail("z@kette.de")).thenReturn(kette);

        AuthController.AuthResponse r = controller.login(
                new AuthController.LoginRequest("z@kette.de", "pw"), new MockHttpServletRequest());

        assertThat(r.token()).isEqualTo("LADEN-TOKEN");
        assertThat(r.shopId()).isEqualTo(kette.getId());
        assertThat(r.rolle()).isEqualTo("SHOP");
        assertThat(r.ortId()).isNull();
        verifyNoInteractions(filialService);
    }

    @Test
    void ohneAt_istEsEinUnterkonto() {
        FilialZugang z = FilialZugang.create(kette, "backbaron-mitte", "HASH", null);
        when(filialService.login("backbaron-mitte", "pw"))
                .thenReturn(new FilialZugangService.Anmeldung("FILIAL-TOKEN", z, FilialenService.HAUPT_LABEL));

        AuthController.AuthResponse r = controller.login(
                new AuthController.LoginRequest("backbaron-mitte", "pw"), new MockHttpServletRequest());

        assertThat(r.token()).isEqualTo("FILIAL-TOKEN");
        assertThat(r.shopId()).isEqualTo(kette.getId());
        assertThat(r.name()).isEqualTo("BackBaron");
        assertThat(r.rolle()).isEqualTo("FILIALE");
        assertThat(r.ortId()).isEqualTo("HAUPT");
        assertThat(r.ortLabel()).isEqualTo(FilialenService.HAUPT_LABEL);
        verify(versuche).loginSucceeded(any());
        verify(shopService, never()).login(anyString(), anyString());
    }

    @Test
    void fehlversuchAmUnterkonto_wirdGezaehlt() {
        when(filialService.login("backbaron-mitte", "falsch"))
                .thenThrow(new IllegalArgumentException(FilialZugangService.LOGIN_FALSCH));
        assertThatThrownBy(() -> controller.login(
                new AuthController.LoginRequest("backbaron-mitte", "falsch"), new MockHttpServletRequest()))
                .isInstanceOf(IllegalArgumentException.class);
        verify(versuche).loginFailed(any());
    }

    @Test
    void ich_zeigtKetteUndFiliale() {
        FilialZugang z = FilialZugang.create(kette, "backbaron-mitte", "HASH", null);
        when(filialService.ortLabel(z)).thenReturn(FilialenService.HAUPT_LABEL);
        var auth = new UsernamePasswordAuthenticationToken(
                new JwtAuthFilter.FilialPrincipal(z, kette), null, List.of());

        Map<String, Object> ich = new FilialeController(filialService, null).ich(auth);

        assertThat(ich).containsEntry("kette", "BackBaron")
                .containsEntry("benutzername", "backbaron-mitte")
                .containsEntry("ortId", "HAUPT")
                .containsEntry("ortLabel", FilialenService.HAUPT_LABEL);
    }
}
