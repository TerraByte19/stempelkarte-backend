package com.example.stemplekarte.service;

import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.model.Stimme;
import com.example.stemplekarte.repository.StimmeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Stimmen: Laden reicht ein (Einverstaendnis Pflicht, Text bereinigt), Admin
 * gibt frei oder lehnt ab, Website sieht nur Freigegebenes ohne Internes.
 * Eine Aenderung setzt die Freigabe zurueck.
 */
class StimmenServiceTest {

    private static final Instant JETZT = Instant.parse("2026-11-07T20:00:00Z");

    private Shop shop;
    private StimmeRepository repo;
    private StimmenService dienst;

    @BeforeEach
    void aufbau() {
        shop = Shop.create("laden@localhost.test", "hash", "Café Nord", 3);
        repo = mock(StimmeRepository.class);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(repo.findByShop(shop)).thenReturn(Optional.empty());
        dienst = new StimmenService(repo);
    }

    private StimmenService.Eingabe eingabe(String zitat) {
        return new StimmenService.Eingabe(zitat, "Mo, Inhaber", "Berlin-Wedding", true);
    }

    @Test
    void nochNichts_leererStand() {
        StimmenService.Stand s = dienst.meine(shop);
        assertThat(s.status()).isNull();
        assertThat(s.zitat()).isNull();
    }

    @Test
    void einreichen_speichertOffen_mitEinverstaendnis() {
        StimmenService.Stand s = dienst.einreichen(shop,
                eingabe("  „Seit der Karte kommen die Stammgäste  öfter.“ "), JETZT);

        assertThat(s.status()).isEqualTo("OFFEN");
        assertThat(s.zitat()).isEqualTo("Seit der Karte kommen die Stammgäste öfter.");   // Anfuehrungszeichen und Doppel-Leerzeichen weg
        assertThat(s.person()).isEqualTo("Mo, Inhaber");
        assertThat(s.ort()).isEqualTo("Berlin-Wedding");
        assertThat(s.eingereichtAm()).isEqualTo(JETZT.toString());
        assertThat(s.entschiedenAm()).isNull();

        ArgumentCaptor<Stimme> gespeichert = ArgumentCaptor.forClass(Stimme.class);
        verify(repo).save(gespeichert.capture());
        Stimme st = gespeichert.getValue();
        assertThat(st.getId()).startsWith("STM-");
        assertThat(st.getShop()).isSameAs(shop);
        assertThat(st.getEinverstandenAm()).isEqualTo(JETZT);
        assertThat(st.getStatus()).isEqualTo(Stimme.Status.OFFEN);
    }

    @Test
    void ohneEinverstaendnis_keinSpeichern() {
        assertThatThrownBy(() -> dienst.einreichen(shop,
                new StimmenService.Eingabe("Ein schönes Zitat über die Karte.", null, "Berlin", false), JETZT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("erscheinen darf");
        assertThatThrownBy(() -> dienst.einreichen(shop, null, JETZT)).isInstanceOf(IllegalArgumentException.class);
        verify(repo, never()).save(any());
    }

    @Test
    void zuKurz_zuLang_ohneOrt_abgelehnt() {
        assertThatThrownBy(() -> dienst.einreichen(shop, eingabe("Top!"), JETZT))
                .hasMessageContaining("kurzen Satz");
        assertThatThrownBy(() -> dienst.einreichen(shop, eingabe("\"\""), JETZT))
                .hasMessageContaining("kurzen Satz");
        assertThatThrownBy(() -> dienst.einreichen(shop, eingabe("x".repeat(321)), JETZT))
                .hasMessageContaining("320");
        assertThatThrownBy(() -> dienst.einreichen(shop,
                new StimmenService.Eingabe("Ein schönes Zitat über die Karte.", null, " ", true), JETZT))
                .hasMessageContaining("Ort");
        assertThatThrownBy(() -> dienst.einreichen(shop,
                new StimmenService.Eingabe("Ein schönes Zitat über die Karte.", "p".repeat(101), "Berlin", true), JETZT))
                .hasMessageContaining("100");
        verify(repo, never()).save(any());
    }

    @Test
    void personOptional_steuerzeichenRaus() {
        StimmenService.Stand s = dienst.einreichen(shop,
                new StimmenService.Eingabe("Unsere Gäste\u0007 lieben die Karte im Handy.", "   ", "Berlin", true), JETZT);
        assertThat(s.zitat()).isEqualTo("Unsere Gäste lieben die Karte im Handy.");
        assertThat(s.person()).isNull();
    }

    @Test
    void aenderung_setztFreigabeZurueck() {
        Stimme alt = Stimme.create(shop, "Alter Text, der freigegeben war.", null, "Berlin", JETZT.minus(Duration.ofDays(3)));
        alt.freigeben(JETZT.minus(Duration.ofDays(2)));
        when(repo.findByShop(shop)).thenReturn(Optional.of(alt));

        StimmenService.Stand s = dienst.einreichen(shop, eingabe("Neuer Text, der noch geprüft werden muss."), JETZT);

        assertThat(s.status()).isEqualTo("OFFEN");
        assertThat(s.entschiedenAm()).isNull();
        assertThat(alt.getZitat()).isEqualTo("Neuer Text, der noch geprüft werden muss.");
        assertThat(alt.getEinverstandenAm()).isEqualTo(JETZT);
        assertThat(alt.getCreatedAt()).isEqualTo(JETZT.minus(Duration.ofDays(3)));   // dieselbe Zeile, kein Duplikat
        verify(repo).save(alt);
    }

    @Test
    void zurueckziehen_loeschtNurWennVorhanden() {
        dienst.zurueckziehen(shop);
        verify(repo, never()).delete(any());

        Stimme s = Stimme.create(shop, "Ein Zitat, das wieder weg soll.", null, "Berlin", JETZT);
        when(repo.findByShop(shop)).thenReturn(Optional.of(s));
        dienst.zurueckziehen(shop);
        verify(repo).delete(s);
    }

    @Test
    void admin_freigebenUndAblehnen() {
        Stimme s = Stimme.create(shop, "Ein Zitat, das der Admin prüft.", "Mo", "Berlin", JETZT);
        when(repo.findById(s.getId())).thenReturn(Optional.of(s));

        StimmenService.AdminZeile frei = dienst.freigeben(s.getId(), JETZT.plusSeconds(60));
        assertThat(frei.status()).isEqualTo("FREIGEGEBEN");
        assertThat(frei.entschiedenAm()).isEqualTo(JETZT.plusSeconds(60).toString());
        assertThat(frei.laden()).isEqualTo("Café Nord");
        assertThat(frei.email()).isEqualTo("laden@localhost.test");
        assertThat(frei.einverstandenAm()).isEqualTo(JETZT.toString());

        StimmenService.AdminZeile ab = dienst.ablehnen(s.getId(), JETZT.plusSeconds(120));
        assertThat(ab.status()).isEqualTo("ABGELEHNT");
        assertThat(s.getStatus()).isEqualTo(Stimme.Status.ABGELEHNT);

        assertThatThrownBy(() -> dienst.freigeben("STM-GIBTESNICHT", JETZT)).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void oeffentlich_nurFreigegebenes_ohneInternes() {
        Stimme s = Stimme.create(shop, "Die Karte im Handy kommt super an.", "Mo, Inhaber", "Berlin-Wedding", JETZT);
        s.freigeben(JETZT);
        when(repo.findTop6ByStatusOrderByEntschiedenAmDesc(Stimme.Status.FREIGEGEBEN)).thenReturn(List.of(s));

        List<StimmenService.Oeffentlich> out = dienst.oeffentlich();

        assertThat(out).containsExactly(
                new StimmenService.Oeffentlich("Die Karte im Handy kommt super an.", "Café Nord", "Berlin-Wedding", "Mo, Inhaber"));
        // Nur der Status FREIGEGEBEN wird abgefragt - OFFEN und ABGELEHNT kommen nie auf die Seite.
        verify(repo, never()).findTop6ByStatusOrderByEntschiedenAmDesc(Stimme.Status.OFFEN);
        verify(repo, never()).findTop6ByStatusOrderByEntschiedenAmDesc(Stimme.Status.ABGELEHNT);
    }

    @Test
    void helfer_sauberUndAnfuehrungszeichen() {
        assertThat(StimmenService.sauber("  a \t b\n c ", 100)).isEqualTo("a b c");
        assertThat(StimmenService.sauber("   ", 100)).isNull();
        assertThat(StimmenService.sauber("abcdef", 3)).isEqualTo("abc");
        assertThat(StimmenService.ohneAnfuehrungszeichen("»Hallo«")).isEqualTo("Hallo");
        assertThat(StimmenService.ohneAnfuehrungszeichen("'Hallo'")).isEqualTo("Hallo");
        assertThat(StimmenService.ohneAnfuehrungszeichen("Er sagt „Hallo“ oft")).isEqualTo("Er sagt „Hallo“ oft");
        assertThat(StimmenService.ohneAnfuehrungszeichen("\"\"")).isNull();
    }
}
