package com.example.stemplekarte.controller;

import com.example.stemplekarte.controller.PublicContactController.ContactRequest;
import com.example.stemplekarte.model.Kontaktanfrage;
import com.example.stemplekarte.repository.KontaktanfrageRepository;
import com.example.stemplekarte.service.EmailService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Anfragen aus dem Kontaktformular landen im Admin-Panel. Die Mail an den
 * Betreiber ist nur der Hinweis - geht sie verloren, steht die Anfrage
 * trotzdem in der Datenbank.
 */
class KontaktanfrageTest {

    private final EmailService mail = mock(EmailService.class);
    private final KontaktanfrageRepository repo = mock(KontaktanfrageRepository.class);
    private final PublicContactController controller = new PublicContactController(mail, repo);

    @Test
    void gueltigeAnfrageWirdGespeichertUndGemeldet() {
        var res = controller.contact(
                new ContactRequest(" Adham ", "Cafe Mitte", "a@b.de", "Hallo", ""), "1.2.3.4");

        assertEquals(200, res.getStatusCode().value());
        ArgumentCaptor<Kontaktanfrage> gespeichert = ArgumentCaptor.forClass(Kontaktanfrage.class);
        verify(repo).save(gespeichert.capture());
        assertEquals("Adham", gespeichert.getValue().getName());
        assertEquals("Cafe Mitte", gespeichert.getValue().getLaden());
        assertFalse(gespeichert.getValue().isErledigt());
        verify(mail).sendContactRequestMail(" Adham ", "Cafe Mitte", "a@b.de", "Hallo");
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
