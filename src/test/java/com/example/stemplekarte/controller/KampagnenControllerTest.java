package com.example.stemplekarte.controller;

import com.example.stemplekarte.model.Kampagne;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.repository.KampagneRepository;
import com.example.stemplekarte.security.JwtAuthFilter;
import com.example.stemplekarte.service.KampagnenService;
import com.example.stemplekarte.service.Zielgruppe;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** GET/POST /api/shop/kampagnen: was das Dashboard bekommt. */
class KampagnenControllerTest {

    private KampagnenService service;
    private KampagneRepository repo;
    private KampagnenController controller;
    private Shop shop;
    private Authentication auth;

    @BeforeEach
    void aufbau() {
        service = mock(KampagnenService.class);
        repo = mock(KampagneRepository.class);
        controller = new KampagnenController(service, repo);
        shop = mock(Shop.class);
        auth = new UsernamePasswordAuthenticationToken(
                new JwtAuthFilter.ShopPrincipal(shop), null, List.of());
    }

    @Test
    void stand_gruppenUndVerlauf() {
        Kampagne k = Kampagne.starte(shop, "Heute 20%", Zielgruppe.INAKTIV_30, 12,
                Instant.parse("2026-11-02T10:00:00Z"));
        k.gesendet(12);
        k.beenden(Instant.parse("2026-11-02T10:05:00Z"));
        when(service.zaehle(shop)).thenReturn(List.of(
                new KampagnenService.GruppenZahl(Zielgruppe.ALLE, 40, 12),
                new KampagnenService.GruppenZahl(Zielgruppe.INAKTIV_30, 0, 12)));
        when(repo.findTop20ByShopOrderByErstelltAmDesc(shop)).thenReturn(List.of(k));
        // Fertig, Fenster offen: Zwischenstand live vom Service.
        when(shop.getId()).thenReturn("SHOP-1");
        k.kontrollgruppe(2);
        when(service.wiedergekommen(eq(k), eq("SHOP-1"), any()))
                .thenReturn(new KampagnenService.Ergebnis(4, 1));

        var stand = controller.stand(auth);
        assertThat(stand.items().get(0).wiedergekommen()).isEqualTo(4);
        assertThat(stand.items().get(0).ergebnisFest()).isFalse();
        assertThat(stand.items().get(0).kontrolleEmpfaenger()).isEqualTo(2);
        assertThat(stand.items().get(0).kontrolleWiedergekommen()).isEqualTo(1);
        assertThat(stand.textMax()).isEqualTo(160);
        assertThat(stand.gruppen()).hasSize(2);
        assertThat(stand.gruppen().get(0).gruppe()).isEqualTo("ALLE");
        assertThat(stand.gruppen().get(0).empfaenger()).isEqualTo(40);
        assertThat(stand.gruppen().get(0).heuteSchon()).isEqualTo(12);
        var item = stand.items().get(0);
        assertThat(item.text()).isEqualTo("Heute 20%");
        assertThat(item.gruppe()).isEqualTo("INAKTIV_30");
        assertThat(item.status()).isEqualTo("DONE");
        assertThat(item.gesendet()).isEqualTo(12);
        assertThat(item.erstelltAm()).isEqualTo("2026-11-02T10:00:00Z");
        assertThat(item.fertigAm()).isEqualTo("2026-11-02T10:05:00Z");
    }

    @Test
    void starten_gibtDenNeuenEintrag() {
        Kampagne k = Kampagne.starte(shop, "Hallo", Zielgruppe.NEU, 3, Instant.now());
        when(service.starte(shop, "Hallo", "neu", null, false)).thenReturn(k);
        var item = controller.starte(new KampagnenController.KampagneRequest("Hallo", "neu", "", null), auth);
        assertThat(item.id()).startsWith("KP-");
        assertThat(item.gruppe()).isEqualTo("NEU");
        assertThat(item.status()).isEqualTo("RUNNING");
        assertThat(item.geplantAm()).isNull();
        assertThat(item.fertigAm()).isNull();
    }

    @Test
    void planen_parstDenZeitpunkt() {
        Instant wann = Instant.parse("2026-12-24T15:00:00Z");
        Kampagne k = Kampagne.starte(shop, "Frohe Weihnachten", Zielgruppe.ALLE, 3, Instant.now(), wann);
        when(service.starte(shop, "Frohe Weihnachten", "alle", wann, true)).thenReturn(k);
        var item = controller.starte(
                new KampagnenController.KampagneRequest("Frohe Weihnachten", "alle", "2026-12-24T15:00:00Z", true), auth);
        assertThat(item.status()).isEqualTo("SCHEDULED");
        assertThat(item.geplantAm()).isEqualTo("2026-12-24T15:00:00Z");
        assertThatThrownBy(() -> controller.starte(
                new KampagnenController.KampagneRequest("x", "alle", "morgen", null), auth))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
