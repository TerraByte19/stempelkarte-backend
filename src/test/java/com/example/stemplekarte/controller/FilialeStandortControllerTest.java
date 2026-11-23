package com.example.stemplekarte.controller;

import com.example.stemplekarte.model.FilialZugang;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.model.ShopOrt;
import com.example.stemplekarte.model.Tarif;
import com.example.stemplekarte.repository.ShopOrtRepository;
import com.example.stemplekarte.security.JwtAuthFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Das Unterkonto setzt nur den Standort der EIGENEN Filiale. */
class FilialeStandortControllerTest {

    private ShopOrtRepository orte;
    private FilialeStandortController controller;
    private Shop kette;
    private ShopOrt nord;

    @BeforeEach
    void aufbau() {
        orte = mock(ShopOrtRepository.class);
        controller = new FilialeStandortController(orte);
        kette = Shop.create("z@kette.de", "HASH", "BackBaron", 3);
        kette.setTarif(Tarif.KETTE);
        nord = ShopOrt.create(kette, "Nord", 52.5, 13.4);
        when(orte.findByIdAndShop(nord.getId(), kette)).thenReturn(Optional.of(nord));
    }

    private Authentication login(String ortId) {
        Authentication auth = mock(Authentication.class);
        when(auth.getPrincipal()).thenReturn(new JwtAuthFilter.FilialPrincipal(
                FilialZugang.create(kette, "backbaron-x", "HASH", ortId), kette));
        return auth;
    }

    @Test
    void setztDenStandortDerEigenenFiliale() {
        var antwort = controller.setzen(
                new FilialeStandortController.StandortRequest(51.514, 7.465), login(nord.getId()));
        assertThat(nord.getLatitude()).isEqualTo(51.514);
        assertThat(nord.getLongitude()).isEqualTo(7.465);
        assertThat(antwort).containsEntry("label", "Nord").containsEntry("latitude", 51.514);
        verify(orte).save(nord);
    }

    @Test
    void ungueltigeKoordinaten_aendernNichts() {
        Authentication auth = login(nord.getId());
        for (var req : new FilialeStandortController.StandortRequest[]{
                new FilialeStandortController.StandortRequest(null, 7.4),
                new FilialeStandortController.StandortRequest(91.0, 7.4),
                new FilialeStandortController.StandortRequest(Double.NaN, 7.4),
                new FilialeStandortController.StandortRequest(51.5, Double.POSITIVE_INFINITY),
                new FilialeStandortController.StandortRequest(51.5, 181.0),
                new FilialeStandortController.StandortRequest(0.0, 0.0)}) {
            assertThatThrownBy(() -> controller.setzen(req, auth)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(nord.getLatitude()).isEqualTo(52.5);
        verify(orte, never()).save(any());
    }

    @Test
    void hauptstandort_bleibtBeimHauptkonto() {
        assertThatThrownBy(() -> controller.setzen(
                new FilialeStandortController.StandortRequest(51.5, 7.4), login(null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Hauptkonto");
        assertThatThrownBy(() -> controller.stand(login(null))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fremdeOderGeloeschteFiliale_gibtEsNicht() {
        when(orte.findByIdAndShop("ORT-FREMD", kette)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> controller.setzen(
                new FilialeStandortController.StandortRequest(51.5, 7.4), login("ORT-FREMD")))
                .isInstanceOf(NoSuchElementException.class);
    }
}
