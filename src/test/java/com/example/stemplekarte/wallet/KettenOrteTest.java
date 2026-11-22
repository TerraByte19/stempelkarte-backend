package com.example.stemplekarte.wallet;

import com.example.stemplekarte.config.AppProperties;
import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.Customer;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.model.ShopOrt;
import com.example.stemplekarte.model.Tarif;
import com.example.stemplekarte.repository.ScanLogRepository;
import com.example.stemplekarte.repository.ShopOrtRepository;
import com.example.stemplekarte.service.RewardService;
import de.brendamour.jpasskit.PKLocation;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Kette mit mehr als zehn Filialen: der Pass lernt aus den Besuchen des
 * Kunden. Vor dem ersten Stempel keine Orte, danach die besuchten plus die
 * naechsten zur meistbesuchten Filiale.
 */
class KettenOrteTest {

    private static final Instant T0 = Instant.parse("2026-11-01T10:00:00Z");

    /** 33 Filialen auf einer Linie nach Norden, je rund 1,1 km auseinander. */
    private static List<KettenOrte.Filiale> linie(int anzahl) {
        List<KettenOrte.Filiale> l = new ArrayList<>();
        for (int i = 0; i < anzahl; i++) l.add(new KettenOrte.Filiale("F" + i, 52.0 + i * 0.01, 13.0));
        return l;
    }

    private static List<String> ids(List<KettenOrte.Filiale> l) {
        return l.stream().map(KettenOrte.Filiale::id).toList();
    }

    // ── Reine Auswahl ───────────────────────────────────────────────────

    @Test
    void ohneBesuch_keineOrte() {
        assertThat(KettenOrte.waehle(linie(33), List.of(), 10)).isEmpty();
    }

    @Test
    void ersterStempel_dieseFilialePlusDieNeunNaechsten() {
        List<KettenOrte.Filiale> w = KettenOrte.waehle(linie(33),
                List.of(new KettenOrte.Besuch("F20", 1, T0)), 10);
        assertThat(w).hasSize(10);
        assertThat(w.get(0).id()).isEqualTo("F20");
        // Nachbarn auf beiden Seiten, keine weit entfernte Filiale.
        assertThat(ids(w)).containsExactlyInAnyOrder(
                "F20", "F19", "F21", "F18", "F22", "F17", "F23", "F16", "F24", "F15");
    }

    @Test
    void besuchteFilialenBleiben_meistbesuchteVorn_restRichtetSichNachIhr() {
        List<KettenOrte.Filiale> w = KettenOrte.waehle(linie(33), List.of(
                new KettenOrte.Besuch("F2", 1, T0),
                new KettenOrte.Besuch("F30", 5, T0),
                new KettenOrte.Besuch("F10", 5, T0.plusSeconds(60))), 10);
        // Gleichstand F30/F10: der juengere Besuch gewinnt.
        assertThat(ids(w).subList(0, 3)).containsExactly("F10", "F30", "F2");
        // Auffuellen rund um F10, nicht rund um F30.
        assertThat(ids(w).subList(3, 10)).containsExactlyInAnyOrder("F9", "F11", "F8", "F12", "F7", "F13", "F6");
    }

    @Test
    void mehrAlsZehnBesuchte_dieZehnMeistbesuchten() {
        List<KettenOrte.Besuch> besuche = new ArrayList<>();
        for (int i = 0; i < 12; i++) besuche.add(new KettenOrte.Besuch("F" + i, i + 1, T0));
        List<KettenOrte.Filiale> w = KettenOrte.waehle(linie(33), besuche, 10);
        assertThat(ids(w)).containsExactly("F11", "F10", "F9", "F8", "F7", "F6", "F5", "F4", "F3", "F2");
    }

    @Test
    void besucheInFilialenDieNichtMehrZaehlen_werdenUebergangen() {
        // F5 ist ausgeschaltet (nicht waehlbar), F99 geloescht.
        List<KettenOrte.Filiale> waehlbar = new ArrayList<>(linie(8));
        waehlbar.removeIf(f -> f.id().equals("F5"));
        List<KettenOrte.Filiale> w = KettenOrte.waehle(waehlbar, List.of(
                new KettenOrte.Besuch("F5", 9, T0),
                new KettenOrte.Besuch("F99", 4, T0),
                new KettenOrte.Besuch("F1", 1, T0)), 10);
        assertThat(w).hasSize(7);
        assertThat(w.get(0).id()).isEqualTo("F1");
        assertThat(ids(w)).doesNotContain("F5", "F99");

        // Nur Besuche in Filialen, die nicht mehr zaehlen: nichts.
        assertThat(KettenOrte.waehle(waehlbar, List.of(new KettenOrte.Besuch("F5", 9, T0)), 10)).isEmpty();
    }

    @Test
    void wenigerFilialenAlsPlaetze_alleEinmal() {
        List<KettenOrte.Filiale> w = KettenOrte.waehle(linie(3),
                List.of(new KettenOrte.Besuch("F1", 2, T0)), 10);
        assertThat(ids(w)).containsExactly("F1", "F0", "F2");
    }

    // ── Im Pass ─────────────────────────────────────────────────────────

    private Shop kette(boolean hauptIstFiliale) {
        Shop shop = mock(Shop.class);
        when(shop.getId()).thenReturn("SHOP-K");
        when(shop.getName()).thenReturn("BackBaron");
        when(shop.getTarif()).thenReturn(Tarif.KETTE);
        when(shop.isLockScreenActive()).thenReturn(true);
        when(shop.getLatitude()).thenReturn(51.0);
        when(shop.getLongitude()).thenReturn(13.0);
        when(shop.isHauptIstFiliale()).thenReturn(hauptIstFiliale);
        return shop;
    }

    private CustomerCard karte(Card card) {
        Customer c = mock(Customer.class);
        when(c.getId()).thenReturn("CUST-1");
        CustomerCard cc = CustomerCard.create(c, card);
        cc.addStamp();
        return cc;
    }

    private List<ShopOrt> filialen(Shop shop, int anzahl) {
        List<ShopOrt> l = new ArrayList<>();
        for (int i = 0; i < anzahl; i++) l.add(ShopOrt.create(shop, "Filiale " + i, 52.0 + i * 0.01, 13.0));
        return l;
    }

    @Test
    void pass_vorDemErstenStempel_keineOrte() {
        Shop shop = kette(true);
        Card card = Card.create(shop, "Brotkarte", "x", 10, "1 Brot gratis");
        ShopOrtRepository orte = mock(ShopOrtRepository.class);
        when(orte.findByShopOrderByCreatedAtAsc(shop)).thenReturn(filialen(shop, 33));
        ScanLogRepository scans = mock(ScanLogRepository.class);
        when(scans.besucheJeFiliale(any(), any(), any())).thenReturn(List.of());

        var dienst = new ApplePassService(mock(AppProperties.class), mock(PassTemplateGenerator.class),
                mock(RewardService.class), orte);
        dienst.setScanLogRepo(scans);
        assertThat(dienst.sperrbildschirmOrte(shop, card, karte(card))).isEmpty();
    }

    @Test
    void pass_nachStempelInFiliale_zehnOrteRundUmDieseFiliale_ohneAusgeschaltete() {
        Shop shop = kette(false);   // Zentrale ist keine Filiale
        Card card = Card.create(shop, "Brotkarte", "x", 10, "1 Brot gratis");
        List<ShopOrt> alle = filialen(shop, 33);
        alle.get(21).setAktiv(false);
        ShopOrtRepository orte = mock(ShopOrtRepository.class);
        when(orte.findByShopOrderByCreatedAtAsc(shop)).thenReturn(alle);
        ScanLogRepository scans = mock(ScanLogRepository.class);
        CustomerCard cc = karte(card);
        List<Object[]> besuche = new ArrayList<>();
        besuche.add(new Object[]{alle.get(20).getId(), 3L, T0});
        besuche.add(new Object[]{null, 1L, T0});   // Stempel ohne Filiale (z. B. NFC)
        when(scans.besucheJeFiliale("SHOP-K", "CUST-1", card.getId())).thenReturn(besuche);

        var dienst = new ApplePassService(mock(AppProperties.class), mock(PassTemplateGenerator.class),
                mock(RewardService.class), orte);
        dienst.setScanLogRepo(scans);
        List<PKLocation> im = dienst.sperrbildschirmOrte(shop, card, cc);

        assertThat(im).hasSize(10);
        assertThat(im.get(0).getLatitude()).isEqualTo(alle.get(20).getLatitude());
        // Die ausgeschaltete Nachbarfiliale und die Zentrale fehlen.
        assertThat(im).extracting(PKLocation::getLatitude)
                .doesNotContain(alle.get(21).getLatitude(), 51.0);
        assertThat(im).extracting(PKLocation::getRelevantText).containsOnly("Noch 9 Stempel bis: 1 Brot gratis");
    }

    @Test
    void pass_stempelAmHauptstandort_derAlsFilialeZaehlt() {
        Shop shop = kette(true);
        Card card = Card.create(shop, "Brotkarte", "x", 10, "1 Brot gratis");
        ShopOrtRepository orte = mock(ShopOrtRepository.class);
        when(orte.findByShopOrderByCreatedAtAsc(shop)).thenReturn(filialen(shop, 3));
        ScanLogRepository scans = mock(ScanLogRepository.class);
        List<Object[]> besuche = new ArrayList<>();
        besuche.add(new Object[]{null, 2L, T0});
        when(scans.besucheJeFiliale(any(), any(), any())).thenReturn(besuche);

        var dienst = new ApplePassService(mock(AppProperties.class), mock(PassTemplateGenerator.class),
                mock(RewardService.class), orte);
        dienst.setScanLogRepo(scans);
        List<PKLocation> im = dienst.sperrbildschirmOrte(shop, card, karte(card));
        assertThat(im).hasSize(4);
        assertThat(im.get(0).getLatitude()).isEqualTo(51.0);
    }
}
