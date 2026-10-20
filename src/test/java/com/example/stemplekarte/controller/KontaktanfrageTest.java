package com.example.stemplekarte.controller;

import com.example.stemplekarte.controller.PublicContactController.ContactRequest;
import com.example.stemplekarte.model.Kontaktanfrage;
import com.example.stemplekarte.repository.KontaktanfrageRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Anfragen aus dem Kontaktformular landen nur im Admin-Panel, es geht
 * keine Mail raus.
 */
class KontaktanfrageTest {

    private final KontaktanfrageRepository repo = mock(KontaktanfrageRepository.class);
    private final PublicContactController controller = new PublicContactController(repo);

    @Test
    void gueltigeAnfrageWirdGespeichert() {
        var res = controller.contact(
                new ContactRequest(" Adham ", "Cafe Mitte", "a@b.de", "Hallo", ""), "1.2.3.4");

        assertEquals(200, res.getStatusCode().value());
        ArgumentCaptor<Kontaktanfrage> gespeichert = ArgumentCaptor.forClass(Kontaktanfrage.class);
        verify(repo).save(gespeichert.capture());
        assertEquals("Adham", gespeichert.getValue().getName());
        assertEquals("Cafe Mitte", gespeichert.getValue().getLaden());
        assertFalse(gespeichert.getValue().isErledigt());
    }

    @Test
    void honigtopfWirdNichtGespeichert() {
        var res = controller.contact(
                new ContactRequest("Bot", "Bot", "bot@x.de", "spam", "http://spam"), "1.2.3.5");

        assertEquals(200, res.getStatusCode().value());
        verify(repo, never()).save(any());
    }

    @Test
    void leereFelderWerdenNichtGespeichert() {
        var res = controller.contact(new ContactRequest("", "", "", "", ""), "1.2.3.6");

        assertEquals(400, res.getStatusCode().value());
        verify(repo, never()).save(any());
    }
}
