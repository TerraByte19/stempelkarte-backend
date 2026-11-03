package com.example.stemplekarte.controller;

import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.model.StaffToken;
import com.example.stemplekarte.security.StaffTokenFilter;
import com.example.stemplekarte.service.CustomerService;
import com.example.stemplekarte.service.GeoPruefung;
import com.example.stemplekarte.service.PointsService;
import com.example.stemplekarte.wallet.WalletNotifier;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Rolle AUSHILFE: stempeln und Einkaeufe buchen ja, aber keine Karte
 * zuruecksetzen und keine Punkte einloesen, korrigieren oder zuruecknehmen.
 * Der Fehler muss 403 mit Klartext sein, damit der Scanner ihn anzeigt.
 */
class ZugangRollenTest {

    private Authentication aushilfe() {
        Shop shop = mock(Shop.class);
        when(shop.getId()).thenReturn("SHOP-1");
        StaffToken staff = StaffToken.create(shop, "Samstag", "AUSHILFE");
        return new UsernamePasswordAuthenticationToken(
                new StaffTokenFilter.StaffPrincipal(staff), null, List.of());
    }

    private static void ist403(Runnable r) {
        assertThatThrownBy(r::run)
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> {
                    var rse = (ResponseStatusException) e;
                    org.assertj.core.api.Assertions.assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    org.assertj.core.api.Assertions.assertThat(rse.getReason()).contains("nur stempeln");
                });
    }

    @Test
    void aushilfe_darfKeineKarteZuruecksetzen() {
        CustomerService service = mock(CustomerService.class);
        ScanController controller = new ScanController(service,
                mock(WalletNotifier.class), mock(PointsService.class), mock(GeoPruefung.class),
                mock(com.example.stemplekarte.service.PaketService.class));
        ist403(() -> controller.reset(new ScanController.ResetRequest("{}"), aushilfe()));
        verify(service, never()).resetCard(anyString(), any());
    }

    @Test
    void aushilfe_darfKeinePunkteEinloesenKorrigierenZuruecknehmen() {
        PointsService service = mock(PointsService.class);
        PointsController controller = new PointsController(service, mock(WalletNotifier.class), mock(GeoPruefung.class));
        ist403(() -> controller.redeem(new PointsController.RedeemRequest("{}", "RW-1", 0.0, 0.0), aushilfe()));
        ist403(() -> controller.correct(new PointsController.CorrectRequest("{}", 100L, 100L, 0.0, 0.0), aushilfe()));
        ist403(() -> controller.undo(new PointsController.UndoRequest("{}", "PB-1", 0.0, 0.0), aushilfe()));
        verify(service, never()).redeem(anyString(), any(), anyString(), anyString(), any());
        verify(service, never()).undo(anyString(), any(), anyString(), anyString());
    }

    @Test
    void aushilfe_darfEinkaeufeBuchen() {
        PointsService service = mock(PointsService.class);
        PointsController controller = new PointsController(service, mock(WalletNotifier.class), mock(GeoPruefung.class));
        // Standort-Pflicht und Mock-Antworten koennen hier alles Moegliche
        // werfen - entscheidend ist nur, dass NICHT die Rollen-Sperre zuschlaegt.
        Throwable t = org.assertj.core.api.Assertions.catchThrowable(
                () -> controller.earn(new PointsController.EarnRequest("{}", 1000, 0.0, 0.0), aushilfe()));
        if (t instanceof ResponseStatusException rse) {
            org.assertj.core.api.Assertions.assertThat(rse.getReason()).doesNotContain("nur stempeln");
        }
    }
}
