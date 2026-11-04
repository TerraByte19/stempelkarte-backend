package com.example.stemplekarte.service;

import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.model.Tarif;
import com.example.stemplekarte.repository.ShopOrtRepository;
import com.example.stemplekarte.repository.ShopRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Tarife: ohne Eintrag Pro, Basic ohne Pro-Funktionen, Kundengrenze nur als Hinweis. */
class TarifTest {

    private Shop laden() {
        return Shop.create("a@b.de", "hash", "Laden", 3);
    }

    @Test
    void ohneEintrag_istPro_bestandslaedenVerlierenNichts() {
        Shop s = laden();
        assertThat(s.getTarif()).isEqualTo(Tarif.PRO);
        for (Tarif.Funktion f : Tarif.Funktion.values()) {
            assertThat(TarifPruefung.darf(s, f)).as(f.name()).isTrue();
        }
    }

    @Test
    void basic_sperrtAllePro_funktionen_proUndKetteNicht() {
        for (Tarif.Funktion f : Tarif.Funktion.values()) {
            assertThat(Tarif.BASIC.erlaubt(f)).as(f.name()).isFalse();
            assertThat(Tarif.PRO.erlaubt(f)).as(f.name()).isTrue();
            assertThat(Tarif.KETTE.erlaubt(f)).as(f.name()).isTrue();
        }
        Shop s = laden();
        s.setTarif(Tarif.BASIC);
        assertThatThrownBy(() -> TarifPruefung.pruefe(s, Tarif.Funktion.KAMPAGNEN))
                .isInstanceOf(TarifPruefung.Gesperrt.class)
                .hasMessageContaining("Pro");
    }

    @Test
    void sperre_ist402_nicht403_sonstLoggtDasFrontendAus() {
        var antwort = new com.example.stemplekarte.controller.GlobalExceptionHandler()
                .handleTarif(new TarifPruefung.Gesperrt(Tarif.Funktion.NEWSLETTER));
        assertThat(antwort.getStatusCode().value()).isEqualTo(402);
        assertThat(antwort.getBody()).containsEntry("code", "TARIF").containsEntry("funktion", "NEWSLETTER");
    }

    @Test
    void testphaseVorbei_sperrtAlles_auchPro_verlaengernHebtAuf() {
        Shop s = laden();
        java.time.Instant jetzt = java.time.Instant.now();
        s.setTestBis(jetzt.plusSeconds(3600));
        assertThat(TarifPruefung.testVorbei(s, jetzt)).isFalse();
        TarifPruefung.pruefeTestphase(s);
        TarifPruefung.pruefe(s, Tarif.Funktion.KAMPAGNEN);

        s.setTestBis(jetzt.minusSeconds(1));
        assertThat(TarifPruefung.testVorbei(s, jetzt)).isTrue();
        assertThatThrownBy(() -> TarifPruefung.pruefeTestphase(s)).isInstanceOf(TarifPruefung.TestVorbei.class);
        assertThatThrownBy(() -> TarifPruefung.pruefe(s, Tarif.Funktion.KAMPAGNEN))
                .isInstanceOf(TarifPruefung.TestVorbei.class);
        assertThat(TarifPruefung.darf(s, Tarif.Funktion.AUTO_NACHRICHTEN)).isFalse();
        TarifPruefung.Stand stand = TarifPruefung.stand(s, 3);
        assertThat(stand.testVorbei()).isTrue();
        assertThat(stand.funktionen()).containsEntry("kampagnen", true);   // Tarif selbst unveraendert

        var antwort = new com.example.stemplekarte.controller.GlobalExceptionHandler()
                .handleTestVorbei(new TarifPruefung.TestVorbei());
        assertThat(antwort.getStatusCode().value()).isEqualTo(402);
        assertThat(antwort.getBody()).containsEntry("code", "TEST_VORBEI");

        s.setTestBis(null);   // bezahlt
        assertThat(TarifPruefung.testVorbei(s, jetzt)).isFalse();
        TarifPruefung.pruefe(s, Tarif.Funktion.KAMPAGNEN);
    }

    @Test
    void von_nimmtKleinschreibung_unbekanntKnallt() {
        assertThat(Tarif.von(" basic ")).isEqualTo(Tarif.BASIC);
        assertThat(Tarif.von("Kette")).isEqualTo(Tarif.KETTE);
        assertThatThrownBy(() -> Tarif.von("gold")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Tarif.von(" ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void kundengrenze_nurBasic_ab100Hinweis() {
        Shop s = laden();
        s.setTarif(Tarif.BASIC);
        TarifPruefung.Stand unter = TarifPruefung.stand(s, 99);
        assertThat(unter.kundenGrenze()).isEqualTo(100);
        assertThat(unter.grenzeErreicht()).isFalse();
        assertThat(unter.funktionen()).containsEntry("kampagnen", false).containsEntry("filialen", false);
        assertThat(TarifPruefung.stand(s, 100).grenzeErreicht()).isTrue();

        s.setTarif(Tarif.PRO);
        TarifPruefung.Stand pro = TarifPruefung.stand(s, 5000);
        assertThat(pro.tarif()).isEqualTo("PRO");
        assertThat(pro.kundenGrenze()).isNull();
        assertThat(pro.grenzeErreicht()).isFalse();
        assertThat(pro.funktionen()).containsEntry("api", true);
    }

    @Test
    void schnittstelle_basicAus_auchWennFreigeschaltet() {
        Shop s = laden();
        s.setApiFreigeschaltet(true);
        assertThat(s.isApiAktiv()).isTrue();
        s.setTarif(Tarif.BASIC);
        assertThat(s.isApiAktiv()).isFalse();
        assertThat(s.isApiFreigeschaltet()).isTrue();   // Freischaltung bleibt fuer den Weg zurueck
    }

    @Test
    void filialen_basicKeineWeiterenOrte_einstellungBleibt() {
        Shop s = laden();
        s.setMaxOrte(3);
        s.setTarif(Tarif.BASIC);
        assertThat(OrteService.erlaubt(s)).isZero();
        assertThat(s.getMaxOrte()).isEqualTo(3);

        ShopRepository shopRepo = mock(ShopRepository.class);
        when(shopRepo.findById(s.getId())).thenReturn(Optional.of(s));
        OrteService orte = new OrteService(mock(ShopOrtRepository.class), shopRepo);
        assertThatThrownBy(() -> orte.hinzufuegen(s.getId(), "Markt", 52.5, 13.4))
                .isInstanceOf(TarifPruefung.Gesperrt.class);

        s.setTarif(Tarif.PRO);
        assertThat(OrteService.erlaubt(s)).isEqualTo(3);
    }
}
