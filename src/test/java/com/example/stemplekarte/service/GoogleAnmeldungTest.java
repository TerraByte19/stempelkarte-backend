package com.example.stemplekarte.service;

import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.Customer;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.repository.AppleDeviceRepository;
import com.example.stemplekarte.repository.CardRepository;
import com.example.stemplekarte.repository.CustomerCardRepository;
import com.example.stemplekarte.repository.CustomerRepository;
import com.example.stemplekarte.repository.ScanLogRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Anmeldung mit Google: Token-Pruefung ohne Netz, und die Karte kommt ohne Bestaetigungs-Mail. */
class GoogleAnmeldungTest {

    private static final String CLIENT = "123-abc.apps.googleusercontent.com";

    /** Antwortet statt Google mit festem JSON (null = abgelehnt). */
    private static GoogleAnmeldung mitAntwort(String clientId, String json) {
        return new GoogleAnmeldung(clientId) {
            @Override
            protected JsonNode tokeninfo(String idToken) {
                try {
                    return json == null ? null : new ObjectMapper().readTree(json);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        };
    }

    private static String info(String aud, String verified) {
        return "{\"iss\":\"https://accounts.google.com\",\"aud\":\"" + aud + "\",\"email\":\"ayse@example.com\","
                + "\"email_verified\":\"" + verified + "\",\"name\":\"Ayşe Yılmaz\"}";
    }

    @Test
    void ohneClientId_inaktiv() {
        GoogleAnmeldung g = new GoogleAnmeldung("  ");
        assertThat(g.aktiv()).isFalse();
        assertThatThrownBy(() -> g.pruefe("x")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void gueltigesToken_liefertProfil() {
        GoogleAnmeldung g = mitAntwort(CLIENT, info(CLIENT, "true"));
        assertThat(g.aktiv()).isTrue();
        GoogleAnmeldung.Profil p = g.pruefe("token");
        assertThat(p.email()).isEqualTo("ayse@example.com");
        assertThat(p.name()).isEqualTo("Ayşe Yılmaz");
    }

    @Test
    void fremdeClientId_nichtBestaetigt_abgelehnt_abgelehnt() {
        assertThatThrownBy(() -> mitAntwort(CLIENT, info("andere-app", "true")).pruefe("token"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> mitAntwort(CLIENT, info(CLIENT, "false")).pruefe("token"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("nicht bestätigt");
        assertThatThrownBy(() -> mitAntwort(CLIENT, null).pruefe("token"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> mitAntwort(CLIENT, info(CLIENT, "true")).pruefe(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nameFehlt_dannAusDerAdresse() {
        GoogleAnmeldung g = mitAntwort(CLIENT,
                "{\"iss\":\"accounts.google.com\",\"aud\":\"" + CLIENT + "\",\"email\":\"mo@example.com\",\"email_verified\":\"true\"}");
        assertThat(g.pruefe("token").name()).isEqualTo("mo");
    }

    @Test
    void anmeldungBestaetigt_keineMail_sofortFrei() {
        CustomerRepository customerRepo = mock(CustomerRepository.class);
        CardRepository cardRepo = mock(CardRepository.class);
        CustomerCardRepository ccRepo = mock(CustomerCardRepository.class);
        EmailService mail = mock(EmailService.class);
        Shop shop = mock(Shop.class);
        when(shop.getId()).thenReturn("SHOP-1");
        Card card = Card.create(shop, "Kaffee", "x", 10, "Gratis Kaffee");
        when(cardRepo.findById(anyString())).thenReturn(Optional.of(card));
        when(customerRepo.findByEmail(anyString())).thenReturn(Optional.empty());
        when(customerRepo.save(any(Customer.class))).thenAnswer(i -> i.getArgument(0));
        when(ccRepo.save(any(CustomerCard.class))).thenAnswer(i -> i.getArgument(0));
        when(ccRepo.findByCustomerAndCard(any(), any())).thenReturn(Optional.empty());
        CustomerService service = new CustomerService(customerRepo, cardRepo, ccRepo,
                mock(AppleDeviceRepository.class), mail, mock(ScanLogRepository.class), mock(EmpfehlungService.class));

        CustomerCard cc = service.registerForCardBestaetigt("Ayşe", "ayse@example.com", card.getId(), true, null, null, null);

        assertThat(cc.getCustomer().isEmailConfirmed()).isTrue();
        assertThat(cc.getCustomer().getConfirmToken()).isNull();
        assertThat(cc.isMarketingConsent()).isTrue();
        verify(mail, never()).sendConfirmationMail(any(), any(), anyString(), any());

        // Der normale Weg schickt weiter die Mail und wartet auf den Klick.
        CustomerCard normal = service.registerForCard("Ayşe", "ayse@example.com", card.getId(), false, null, null, null);
        assertThat(normal.getCustomer().isEmailConfirmed()).isFalse();
        verify(mail).sendConfirmationMail(any(), any(), anyString(), any());
    }
}
