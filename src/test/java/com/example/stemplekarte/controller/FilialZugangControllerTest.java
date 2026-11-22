package com.example.stemplekarte.controller;

import com.example.stemplekarte.model.FilialZugang;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.model.Tarif;
import com.example.stemplekarte.security.JwtAuthFilter;
import com.example.stemplekarte.service.FilialZugangService;
import com.example.stemplekarte.service.FilialenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class FilialZugangControllerTest {

    private FilialZugangService service;
    private FilialZugangController controller;
    private Shop kette;
    private Authentication auth;
    private FilialZugang zugang;

    @BeforeEach
    void aufbau() {
        service = mock(FilialZugangService.class);
        controller = new FilialZugangController(service);
        kette = Shop.create("z@kette.de", "HASH", "BackBaron", 3);
        kette.setTarif(Tarif.KETTE);
        auth = new UsernamePasswordAuthenticationToken(new JwtAuthFilter.ShopPrincipal(kette), null, List.of());
        zugang = FilialZugang.create(kette, "backbaron-mitte", "GEHEIMER-HASH", null);
        when(service.ortLabel(zugang)).thenReturn(FilialenService.HAUPT_LABEL);
    }

    @Test
    void liste_zeigtKeinPasswort() {
        when(service.liste(kette)).thenReturn(List.of(zugang));
        List<FilialZugangController.Eintrag> liste = controller.liste(auth);
        assertThat(liste).hasSize(1);
        FilialZugangController.Eintrag e = liste.get(0);
        assertThat(e.id()).isEqualTo(zugang.getId());
        assertThat(e.benutzername()).isEqualTo("backbaron-mitte");
        assertThat(e.ortId()).isEqualTo("HAUPT");
        assertThat(e.ortLabel()).isEqualTo(FilialenService.HAUPT_LABEL);
        assertThat(e.gesperrt()).isFalse();
        assertThat(e.toString()).doesNotContain("GEHEIMER-HASH");
    }

    @Test
    void anlegen_reichtAnDenServiceDesEigenenLadens() {
        when(service.anlegen(kette, "backbaron-mitte", "geheim123", "HAUPT")).thenReturn(zugang);
        FilialZugangController.Eintrag e = controller.anlegen(auth,
                new FilialZugangController.NeuRequest("backbaron-mitte", "geheim123", "HAUPT"));
        assertThat(e.benutzername()).isEqualTo("backbaron-mitte");
    }

    @Test
    void passwortSperreLoeschen_nurImEigenenLaden() {
        when(service.passwortSetzen(kette, zugang.getId(), "ganzneu123")).thenReturn(zugang);
        when(service.sperren(kette, zugang.getId(), true)).thenReturn(zugang);

        controller.passwort(auth, zugang.getId(), new FilialZugangController.PasswortRequest("ganzneu123"));
        controller.sperre(auth, zugang.getId(), new FilialZugangController.SperreRequest(true));
        controller.loeschen(auth, zugang.getId());

        verify(service).passwortSetzen(kette, zugang.getId(), "ganzneu123");
        verify(service).sperren(kette, zugang.getId(), true);
        verify(service).loeschen(kette, zugang.getId());
    }
}
