package com.example.stemplekarte.controller;

import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.model.StaffToken;
import com.example.stemplekarte.security.StaffTokenFilter;
import com.example.stemplekarte.service.PointsService;
import com.example.stemplekarte.wallet.WalletNotifier;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Zwei Dinge, die im Laden wehtun, wenn sie fehlen.
 *
 * Erstens: ein Aufruf ohne gueltiges Staff-Token muss 401 geben, nicht 500.
 * Beim Sperren-Knopf im Admin-Panel hat genau das eine Sitzung gekostet -
 * eine fehlende Annotation liess einen sauberen Fehlerfall als generischen
 * 500 ankommen, und im Panel war nicht zu unterscheiden, ob der Server
 * kaputt ist oder die Anmeldung abgelaufen.
 *
 * Zweitens: das Staff-Token darf die Antwort nicht verlassen. Sein Wert ist
 * zugleich Primaerschluessel und Zugangsberechtigung; in der Buchungsliste,
 * die der Scanner anzeigt, hat er nichts zu suchen. Gebucht wird mit dem
 * Geraetenamen.
 */
class PunkteControllerTest {

    private Authentication authMit(Shop shop, String label) {
        StaffToken staff = mock(StaffToken.class);
        when(staff.getShop()).thenReturn(shop);
        when(staff.getLabel()).thenReturn(label);
        return new UsernamePasswordAuthenticationToken(
                new StaffTokenFilter.StaffPrincipal(staff), null, List.of());
    }

    private PointsService.PointsResult ergebnis() {
        var buchung = new PointsService.BookingView(
                "PB-1", "EARN", 14_500, "145", 14_500L, null, "Kasse 1", Instant.now());
        var ziel = new PointsService.RewardView(
                "RW-1", "Kuchen", 25_000, "250", false, 10_500);
        return new PointsService.PointsResult(
                "CC-1", "Adham", "CARD-1", "Bistro",
                14_500, "145", buchung, ziel, 10_500, "105",
                true, List.of(ziel));
    }

    @Test
    void ohneStaffToken_gibt401() {
        PointsController controller = new PointsController(
                mock(PointsService.class), mock(WalletNotifier.class));

        assertThatThrownBy(() -> controller.earn(
                new PointsController.EarnRequest("{}", 1000), null))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void buchtMitDemGeraetenamen_nichtMitDemToken() {
        PointsService service = mock(PointsService.class);
        Shop shop = mock(Shop.class);
        when(shop.getId()).thenReturn("SHOP-1");
        when(service.earn(anyString(), any(Shop.class), anyLong(), anyString()))
                .thenReturn(ergebnis());

        PointsController controller = new PointsController(service, mock(WalletNotifier.class));

        var antwort = controller.earn(
                new PointsController.EarnRequest("{}", 14_500),
                authMit(shop, "Kasse 1"));

        verify(service).earn(anyString(), any(Shop.class), anyLong(), eq("Kasse 1"));
        assertThat(antwort.letzteBuchung().staffLabel()).isEqualTo("Kasse 1");
        assertThat(antwort.pointsText()).isEqualTo("145");
        assertThat(antwort.zielName()).isEqualTo("Kuchen");
    }

    @Test
    void meldetDieAenderungAnDieWalletWege() {
        PointsService service = mock(PointsService.class);
        WalletNotifier notifier = mock(WalletNotifier.class);
        Shop shop = mock(Shop.class);
        when(service.earn(anyString(), any(Shop.class), anyLong(), anyString()))
                .thenReturn(ergebnis());

        new PointsController(service, notifier).earn(
                new PointsController.EarnRequest("{}", 14_500),
                authMit(shop, "Kasse 1"));

        // Nur die ID wandert nach draussen, keine Entity - der Controller
        // sitzt ausserhalb der Transaktion.
        verify(notifier).nachPunkteAenderung("CC-1", 14_500, "Kuchen", 10_500);
    }
}
