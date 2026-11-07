package com.example.stemplekarte.controller;

import com.example.stemplekarte.controller.TerminController.TerminRequest;
import com.example.stemplekarte.model.Terminanfrage;
import com.example.stemplekarte.repository.TerminanfrageRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Vorfuehrung buchen: Anfrage landet nur im Admin, Honigtopf und Drossel
 * halten Bots draussen, Pflichtfelder und Zeitfenster werden geprueft.
 */
class TerminanfrageTest {

    // 2026-11-07 22:00 Berlin
    private static final Instant JETZT = Instant.parse("2026-11-07T21:00:00Z");

    private final TerminanfrageRepository repo = mock(TerminanfrageRepository.class);
    private final TerminController controller = new TerminController(repo);

    private static TerminRequest gueltig() {
        return new TerminRequest(" Adham ", "Café Nord", "", "+49 170 1234567", "im_laden",
                "2026-11-12", "10:30", "Müllerstr. 1, 13353 Berlin", "Bitte nach 10 Uhr", "de", "");
    }

    @Test
    void gueltigeAnfrage_wirdGespeichert() {
        var res = controller.anfragen(gueltig(), "1.2.3.4, 10.0.0.1", JETZT);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        ArgumentCaptor<Terminanfrage> c = ArgumentCaptor.forClass(Terminanfrage.class);
        verify(repo).save(c.capture());
        Terminanfrage t = c.getValue();
        assertThat(t.getId()).startsWith("TER-");
        assertThat(t.getName()).isEqualTo("Adham");
        assertThat(t.getLaden()).isEqualTo("Café Nord");
        assertThat(t.getEmail()).isNull();                       // leer = null
        assertThat(t.getTelefon()).isEqualTo("+49 170 1234567");
        assertThat(t.getArt()).isEqualTo(Terminanfrage.Art.IM_LADEN);
        assertThat(t.getWunschAm()).isEqualTo(LocalDate.of(2026, 11, 12));
        assertThat(t.getWunschZeit()).isEqualTo("10:30");
        assertThat(t.getAdresse()).isEqualTo("Müllerstr. 1, 13353 Berlin");
        assertThat(t.getStatus()).isEqualTo(Terminanfrage.Status.OFFEN);
        assertThat(t.getEingegangenAm()).isEqualTo(JETZT);
    }

    @Test
    void honigtopf_200_aberNichtGespeichert() {
        TerminRequest bot = new TerminRequest("Bot", "Bot", "bot@x.de", null, "VIDEO",
                "2026-11-12", "10:00", null, null, "de", "http://spam");
        assertThat(controller.anfragen(bot, "9.9.9.9", JETZT).getStatusCode().value()).isEqualTo(200);
        verify(repo, never()).save(any());
    }

    @Test
    void pflichtfelder_undZeitfenster() {
        TerminRequest g = gueltig();
        assertThatThrownBy(() -> TerminController.pruefen(mit(g, r -> new TerminRequest(" ", r.shop(), r.email(), r.phone(), r.art(), r.date(), r.time(), r.address(), r.message(), r.language(), r.website())), JETZT))
                .hasMessageContaining("Namen");
        assertThatThrownBy(() -> TerminController.pruefen(mit(g, r -> new TerminRequest(r.name(), r.shop(), "", "", r.art(), r.date(), r.time(), r.address(), r.message(), r.language(), r.website())), JETZT))
                .hasMessageContaining("Telefon oder E-Mail");
        assertThatThrownBy(() -> TerminController.pruefen(mit(g, r -> new TerminRequest(r.name(), r.shop(), "keine-mail", "", r.art(), r.date(), r.time(), r.address(), r.message(), r.language(), r.website())), JETZT))
                .hasMessageContaining("E-Mail-Adresse");
        assertThatThrownBy(() -> TerminController.pruefen(mit(g, r -> new TerminRequest(r.name(), r.shop(), r.email(), r.phone(), "BRIEF", r.date(), r.time(), r.address(), r.message(), r.language(), r.website())), JETZT))
                .hasMessageContaining("im Laden, Telefon oder Video");
        // heute (Berlin 7.11.) ist zu frueh, morgen geht, in 91 Tagen nicht mehr
        assertThatThrownBy(() -> TerminController.pruefen(mit(g, r -> new TerminRequest(r.name(), r.shop(), r.email(), r.phone(), r.art(), "2026-11-07", r.time(), r.address(), r.message(), r.language(), r.website())), JETZT))
                .hasMessageContaining("Zukunft");
        assertThat(TerminController.pruefen(mit(g, r -> new TerminRequest(r.name(), r.shop(), r.email(), r.phone(), r.art(), "2026-11-08", r.time(), r.address(), r.message(), r.language(), r.website())), JETZT).getWunschAm())
                .isEqualTo(LocalDate.of(2026, 11, 8));
        assertThatThrownBy(() -> TerminController.pruefen(mit(g, r -> new TerminRequest(r.name(), r.shop(), r.email(), r.phone(), r.art(), "2027-02-06", r.time(), r.address(), r.message(), r.language(), r.website())), JETZT))
                .hasMessageContaining("90");
        assertThatThrownBy(() -> TerminController.pruefen(mit(g, r -> new TerminRequest(r.name(), r.shop(), r.email(), r.phone(), r.art(), "morgen", r.time(), r.address(), r.message(), r.language(), r.website())), JETZT))
                .hasMessageContaining("Wunschtag");
        assertThatThrownBy(() -> TerminController.pruefen(mit(g, r -> new TerminRequest(r.name(), r.shop(), r.email(), r.phone(), r.art(), r.date(), "25:00", r.address(), r.message(), r.language(), r.website())), JETZT))
                .hasMessageContaining("Uhrzeit");
        // im Laden ohne Adresse geht nicht, Telefon ohne Adresse schon
        assertThatThrownBy(() -> TerminController.pruefen(mit(g, r -> new TerminRequest(r.name(), r.shop(), r.email(), r.phone(), "IM_LADEN", r.date(), r.time(), "", r.message(), r.language(), r.website())), JETZT))
                .hasMessageContaining("Adresse");
        Terminanfrage tel = TerminController.pruefen(mit(g, r -> new TerminRequest(r.name(), r.shop(), "a@b.de", "", "telefon", r.date(), r.time(), "", "", "xx-YY", r.website())), JETZT);
        assertThat(tel.getArt()).isEqualTo(Terminanfrage.Art.TELEFON);
        assertThat(tel.getAdresse()).isNull();
        assertThat(tel.getSprache()).isEqualTo("de");   // Unsinn in language -> de
        verify(repo, never()).save(any());
    }

    @Test
    void drossel_dreiJeAdresse() {
        for (int i = 0; i < 3; i++) controller.anfragen(gueltig(), "5.5.5.5", JETZT.plusSeconds(i));
        assertThatThrownBy(() -> controller.anfragen(gueltig(), "5.5.5.5", JETZT.plusSeconds(10)))
                .isInstanceOf(IllegalArgumentException.class);
        // andere Adresse geht weiter
        controller.anfragen(gueltig(), "6.6.6.6", JETZT.plusSeconds(11));
        verify(repo, times(4)).save(any());
    }

    @Test
    void admin_listeStatusLoeschen() {
        Terminanfrage t = Terminanfrage.create("Mo", "Café Nord", "mo@x.de", null, Terminanfrage.Art.VIDEO,
                LocalDate.of(2026, 11, 12), "14:00", null, null, "tr", JETZT);
        when(repo.findTop200ByOrderByEingegangenAmDesc()).thenReturn(List.of(t));
        when(repo.findById(t.getId())).thenReturn(Optional.of(t));
        when(repo.existsById(t.getId())).thenReturn(true);

        var zeilen = controller.alle();
        assertThat(zeilen).hasSize(1);
        assertThat(zeilen.get(0)).containsEntry("laden", "Café Nord").containsEntry("art", "VIDEO")
                .containsEntry("wunschAm", "2026-11-12").containsEntry("wunschZeit", "14:00")
                .containsEntry("status", "OFFEN").containsEntry("sprache", "tr");

        assertThat(controller.status(t.getId(), new TerminController.StatusRequest("bestaetigt")).getBody())
                .containsEntry("status", "BESTAETIGT");
        assertThat(t.getStatus()).isEqualTo(Terminanfrage.Status.BESTAETIGT);
        assertThatThrownBy(() -> controller.status(t.getId(), new TerminController.StatusRequest("egal")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(controller.status("TER-NIX", new TerminController.StatusRequest("ERLEDIGT")).getStatusCode().value()).isEqualTo(404);

        assertThat(controller.loeschen(t.getId()).getStatusCode().value()).isEqualTo(204);
        verify(repo).deleteById(t.getId());
    }

    private interface Aenderung { TerminRequest an(TerminRequest r); }
    private static TerminRequest mit(TerminRequest r, Aenderung a) { return a.an(r); }
}
