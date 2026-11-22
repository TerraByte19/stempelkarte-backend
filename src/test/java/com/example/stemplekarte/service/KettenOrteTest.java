package com.example.stemplekarte.service;

import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.model.ShopOrt;
import com.example.stemplekarte.model.StaffToken;
import com.example.stemplekarte.model.Tarif;
import com.example.stemplekarte.repository.ScanLogRepository;
import com.example.stemplekarte.repository.ShopOrtRepository;
import com.example.stemplekarte.repository.ShopRepository;
import com.example.stemplekarte.repository.StaffTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tarif Kette: keine Zehner-Grenze, Filialen ein- und ausschalten, Liste
 * auf einmal anlegen. Fuer Basic/Pro bleibt alles, wie es war.
 */
class KettenOrteTest {

    private Shop kette;
    private Shop pro;
    private ShopRepository shopRepo;
    private ShopOrtRepository ortRepo;
    private OrteService dienst;

    @BeforeEach
    void setUp() {
        kette = Shop.create("z@kette.de", "hash", "BackBaron", 3);
        kette.setTarif(Tarif.KETTE);
        pro = Shop.create("p@laden.de", "hash", "Einzelladen", 3);
        pro.setMaxOrte(2);
        shopRepo = mock(ShopRepository.class);
        when(shopRepo.findById(kette.getId())).thenReturn(Optional.of(kette));
        when(shopRepo.findById(pro.getId())).thenReturn(Optional.of(pro));
        when(shopRepo.save(any())).thenAnswer(a -> a.getArgument(0));
        ortRepo = mock(ShopOrtRepository.class);
        dienst = new OrteService(ortRepo, shopRepo);
    }

    private List<ShopOrt> orte(Shop shop, int anzahl) {
        List<ShopOrt> l = new ArrayList<>();
        for (int i = 0; i < anzahl; i++) l.add(ShopOrt.create(shop, "Filiale " + i, 52.0 + i * 0.01, 13.0));
        return l;
    }

    // ── Grenze ──────────────────────────────────────────────────────────

    @Test
    void kette_hatKeineZehnerGrenze_auchOhneAdminZahl() {
        assertThat(kette.getMaxOrte()).isZero();
        assertThat(OrteService.erlaubt(kette)).isEqualTo(OrteService.KETTE_MAX);
        when(ortRepo.countByShop(kette)).thenReturn(32L);
        dienst.hinzufuegen(kette.getId(), "Filiale 33", 52.52, 13.405);
        verify(ortRepo).save(any(ShopOrt.class));
    }

    @Test
    void pro_bleibtBeiDerAdminZahl() {
        assertThat(OrteService.erlaubt(pro)).isEqualTo(2);
        pro.setMaxOrte(50);
        assertThat(OrteService.erlaubt(pro)).isEqualTo(OrteService.MAX_WEITERE);
    }

    // ── Welche Orte gelten ──────────────────────────────────────────────

    @Test
    void geltende_kette_nurEingeschaltete_ohneObergrenze() {
        List<ShopOrt> alle = orte(kette, 33);
        alle.get(0).setAktiv(false);
        alle.get(20).setAktiv(false);
        List<ShopOrt> g = OrteService.geltende(kette, alle);
        assertThat(g).hasSize(31).doesNotContain(alle.get(0), alle.get(20));
    }

    @Test
    void geltende_pro_dieErstenErlaubten_schalterZaehltNicht() {
        List<ShopOrt> alle = orte(pro, 4);
        alle.get(0).setAktiv(false);   // hat fuer Pro keine Wirkung
        assertThat(OrteService.geltende(pro, alle)).containsExactly(alle.get(0), alle.get(1));
        pro.setMaxOrte(0);
        assertThat(OrteService.geltende(pro, alle)).isEmpty();
    }

    // ── Aendern ─────────────────────────────────────────────────────────

    @Test
    void aendern_nameLageUndSchalter() {
        ShopOrt ort = ShopOrt.create(kette, "Nord", 52.5, 13.4);
        when(ortRepo.findByIdAndShop(ort.getId(), kette)).thenReturn(Optional.of(ort));

        dienst.aendern(kette.getId(), ort.getId(), "  Nord 2 ", null, null, null);
        assertThat(ort.getLabel()).isEqualTo("Nord 2");
        assertThat(ort.getLatitude()).isEqualTo(52.5);
        assertThat(ort.isAktiv()).isTrue();

        dienst.aendern(kette.getId(), ort.getId(), null, 48.1, 11.5, false);
        assertThat(ort.getLabel()).isEqualTo("Nord 2");
        assertThat(ort.getLatitude()).isEqualTo(48.1);
        assertThat(ort.getLongitude()).isEqualTo(11.5);
        assertThat(ort.isAktiv()).isFalse();
    }

    @Test
    void aendern_prueftEingaben() {
        ShopOrt ort = ShopOrt.create(kette, "Nord", 52.5, 13.4);
        when(ortRepo.findByIdAndShop(ort.getId(), kette)).thenReturn(Optional.of(ort));
        assertThatThrownBy(() -> dienst.aendern(kette.getId(), ort.getId(), " ", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        // Lage nur mit beiden Werten
        assertThatThrownBy(() -> dienst.aendern(kette.getId(), ort.getId(), null, 48.1, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> dienst.aendern(kette.getId(), "ORT-FREMD", "x", null, null, null))
                .isInstanceOf(NoSuchElementException.class);
        assertThat(ort.getLabel()).isEqualTo("Nord");
    }

    @Test
    void aendern_schalterNurImTarifKette() {
        ShopOrt ort = ShopOrt.create(pro, "Markt", 52.5, 13.4);
        when(ortRepo.findByIdAndShop(ort.getId(), pro)).thenReturn(Optional.of(ort));
        assertThatThrownBy(() -> dienst.aendern(pro.getId(), ort.getId(), null, null, null, false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Kette");
        // Umbenennen geht fuer jeden
        dienst.aendern(pro.getId(), ort.getId(), "Wochenmarkt", null, null, null);
        assertThat(ort.getLabel()).isEqualTo("Wochenmarkt");
    }

    // ── Sammel-Anlage ───────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void sammel_legtAlleAusgeschaltetAn() {
        dienst.sammel(kette.getId(), List.of(
                new OrteService.Neu("Mitte", "Hauptstr. 5, Berlin", 52.52, 13.405),
                new OrteService.Neu("Nord", null, 52.60, 13.40)));
        ArgumentCaptor<List<ShopOrt>> liste = ArgumentCaptor.forClass(List.class);
        verify(ortRepo).saveAll(liste.capture());
        assertThat(liste.getValue()).hasSize(2);
        assertThat(liste.getValue()).allMatch(o -> !o.isAktiv());
        assertThat(liste.getValue().get(0).getLabel()).isEqualTo("Mitte");
        assertThat(liste.getValue().get(0).getAdresse()).isEqualTo("Hauptstr. 5, Berlin");
        assertThat(liste.getValue().get(1).getAdresse()).isNull();
    }

    @Test
    void sammel_eineFalscheZeile_nichtsWirdGespeichert() {
        assertThatThrownBy(() -> dienst.sammel(kette.getId(), List.of(
                new OrteService.Neu("Mitte", null, 52.52, 13.405),
                new OrteService.Neu("Kaputt", null, 91.0, 13.4))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageStartingWith("Zeile 2");
        verify(ortRepo, never()).saveAll(anyList());
    }

    @Test
    void sammel_nurKette_nichtLeer_mitNotbremse() {
        assertThatThrownBy(() -> dienst.sammel(pro.getId(),
                List.of(new OrteService.Neu("Mitte", null, 52.52, 13.405))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Kette");
        assertThatThrownBy(() -> dienst.sammel(kette.getId(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        when(ortRepo.countByShop(kette)).thenReturn((long) OrteService.KETTE_MAX);
        assertThatThrownBy(() -> dienst.sammel(kette.getId(),
                List.of(new OrteService.Neu("Mitte", null, 52.52, 13.405))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("500");
        verify(ortRepo, never()).saveAll(anyList());
    }

    // ── Hauptstandort ───────────────────────────────────────────────────

    @Test
    void hauptstandort_istStandardmaessigFiliale_schalterNurKette() {
        assertThat(kette.isHauptIstFiliale()).isTrue();
        dienst.hauptIstFiliale(kette.getId(), false);
        assertThat(kette.isHauptIstFiliale()).isFalse();
        assertThatThrownBy(() -> dienst.hauptIstFiliale(pro.getId(), false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(pro.isHauptIstFiliale()).isTrue();
    }

    // ── Ausgeschaltete Filiale: kein Scan, keine Zuordnung ──────────────

    @Test
    void filialeAus_sperrtNurGeraeteDerAusgeschaltetenFilialeEinerKette() {
        FilialenService filialen = new FilialenService(ortRepo,
                mock(ScanLogRepository.class), mock(StaffTokenRepository.class));
        ShopOrt nord = ShopOrt.create(kette, "Nord", 52.5, 13.4);
        when(ortRepo.findById(nord.getId())).thenReturn(Optional.of(nord));
        StaffToken geraet = StaffToken.create(kette, "Kasse Nord");
        geraet.ort(nord.getId());
        StaffToken haupt = StaffToken.create(kette, "Kasse Zentrale");

        assertThat(filialen.filialeAus(geraet, kette)).isFalse();
        nord.setAktiv(false);
        assertThat(filialen.filialeAus(geraet, kette)).isTrue();
        assertThat(filialen.filialeAus(haupt, kette)).isFalse();

        // Derselbe Schalter an einem Laden ohne Tarif Kette sperrt nie.
        ShopOrt markt = ShopOrt.create(pro, "Markt", 52.5, 13.4);
        markt.setAktiv(false);
        when(ortRepo.findById(markt.getId())).thenReturn(Optional.of(markt));
        StaffToken proGeraet = StaffToken.create(pro, "Kasse");
        proGeraet.ort(markt.getId());
        assertThat(filialen.filialeAus(proGeraet, pro)).isFalse();
    }

    @Test
    void ortFuer_ordnetKeinerAusgeschaltetenFilialeZu() {
        FilialenService filialen = new FilialenService(ortRepo,
                mock(ScanLogRepository.class), mock(StaffTokenRepository.class));
        ShopOrt nord = ShopOrt.create(kette, "Nord", 52.5, 13.4);
        when(ortRepo.findByShopOrderByCreatedAtAsc(kette)).thenReturn(List.of(nord));
        StaffToken ohneOrt = StaffToken.create(kette, "Springer");

        assertThat(filialen.ortFuer(ohneOrt, kette, 52.5, 13.4)).isEqualTo(nord.getId());
        nord.setAktiv(false);
        assertThat(filialen.ortFuer(ohneOrt, kette, 52.5, 13.4)).isNull();
    }
}
