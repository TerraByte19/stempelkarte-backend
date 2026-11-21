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
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Karte ohne Anmeldung: der Laden schaltet ein, dass der Kunde nichts
 * eintippt.
 *
 * Die Spalte email ist in der Datenbank Pflicht und eindeutig. So ein Kunde
 * bekommt deshalb eine erfundene Adresse - und die groesste Gefahr ist, dass
 * irgendwo doch eine Mail an sie geht oder sie in der Kundenliste auftaucht.
 * Dazu: ohne Schalter darf der Weg nicht offen sein, und es gibt kein
 * Willkommensgeschenk (liesse sich mit jeder neuen Karte noch einmal holen).
 */
class AnmeldungOhneMailTest {

    private final CustomerRepository customerRepo = mock(CustomerRepository.class);
    private final CustomerCardRepository ccRepo = mock(CustomerCardRepository.class);
    private final EmailService mail = mock(EmailService.class);

    private CustomerService dienst(Card card) {
        CardRepository cardRepo = mock(CardRepository.class);
        when(cardRepo.findById(anyString())).thenReturn(Optional.of(card));
        when(ccRepo.save(any(CustomerCard.class))).thenAnswer(i -> i.getArgument(0));
        when(ccRepo.findByCustomerAndCard(any(), any())).thenReturn(Optional.empty());
        when(customerRepo.save(any(Customer.class))).thenAnswer(i -> i.getArgument(0));
        when(customerRepo.findByEmail(anyString())).thenReturn(Optional.empty());
        return new CustomerService(customerRepo, cardRepo, ccRepo, mock(AppleDeviceRepository.class),
                mail, mock(ScanLogRepository.class));
    }

    private Card karte(boolean ohneMail) {
        Shop shop = mock(Shop.class);
        when(shop.getId()).thenReturn("SHOP-1");
        when(shop.isAnmeldungOhneMail()).thenReturn(ohneMail);
        return Card.create(shop, "Kaffee", "x", 10, "Gratis Kaffee");
    }

    @Test
    void kundeOhneMail_hatPlatzhalterAdresseDieNieAngezeigtWird() {
        Customer a = Customer.createOhneMail();
        Customer b = Customer.createOhneMail();

        assertThat(a.isOhneMail()).isTrue();
        assertThat(a.getEmailAnzeige()).isNull();
        assertThat(a.getEmail()).endsWith(Customer.OHNE_MAIL_ENDUNG);
        // Spalte ist eindeutig: zwei Kunden duerfen nie dieselbe Adresse tragen.
        assertThat(a.getEmail()).isNotEqualTo(b.getEmail());
        assertThat(a.getId()).isNotEqualTo(b.getId());
        // Kein erfundener Name ("Kunde 12345" war dem User zu viel): leer.
        assertThat(a.getName()).isEmpty();
        assertThat(a.hatName()).isFalse();
        assertThat(a.isEmailConfirmed()).isFalse();
        assertThat(a.getConfirmToken()).isNull();
    }

    @Test
    void normalerKunde_bleibtWieBisher() {
        Customer c = Customer.create("Ayşe", "Ayse@Example.com");

        assertThat(c.isOhneMail()).isFalse();
        assertThat(c.getEmailAnzeige()).isEqualTo("ayse@example.com");
    }

    @Test
    void mitSchalter_gibtEsSofortEineKarteOhneMail() {
        CustomerCard cc = dienst(karte(true)).registerOhneMail("CARD-1", "tr");

        assertThat(cc.getCustomer().isOhneMail()).isTrue();
        assertThat(cc.getCustomer().getLanguage()).isEqualTo("tr");
        assertThat(cc.getStamps()).isZero();
        assertThat(cc.isMarketingConsent()).isFalse();
        verify(customerRepo).save(cc.getCustomer());
        verifyNoInteractions(mail);
    }

    @Test
    void jederAufrufLegtEinenEigenenKundenAn() {
        CustomerService dienst = dienst(karte(true));

        CustomerCard eins = dienst.registerOhneMail("CARD-1", null);
        CustomerCard zwei = dienst.registerOhneMail("CARD-1", null);

        assertThat(eins.getCustomer().getId()).isNotEqualTo(zwei.getCustomer().getId());
    }

    @Test
    void ohneSchalter_istDerWegZu() {
        CustomerService dienst = dienst(karte(false));

        assertThatThrownBy(() -> dienst.registerOhneMail("CARD-1", null))
                .isInstanceOf(IllegalArgumentException.class);

        verify(customerRepo, never()).save(any());
        verify(ccRepo, never()).save(any());
    }

    @Test
    void keinWillkommensgeschenkSolangeDerSchalterAnIst() {
        Card mitSchalter = karte(true);
        mitSchalter.updateWillkommen(true, "Erster Kaffee gratis");
        Card ohneSchalter = karte(false);
        ohneSchalter.updateWillkommen(true, "Erster Kaffee gratis");

        // Ohne Anmeldung: nie gesperrt.
        assertThat(dienst(mitSchalter).registerOhneMail("CARD-1", null).isWillkommenOffen()).isFalse();
        // Auch der E-Mail-Weg gibt bei diesem Laden kein Geschenk mehr aus.
        assertThat(dienst(mitSchalter).registerForCard("A", "a@example.com", "CARD-1", false).isWillkommenOffen()).isFalse();
        // Laden ohne Schalter: Geschenk wie bisher.
        assertThat(dienst(ohneSchalter).registerForCard("A", "a@example.com", "CARD-1", false).isWillkommenOffen()).isTrue();
    }

    @Test
    void platzhalterNameIstKeinVorname() {
        CustomerCard cc = dienst(karte(true)).registerOhneMail("CARD-1", null);

        assertThat(Platzhalter.fuelle("Danke, {name}! Noch {offen} Stempel.", cc, null))
                .isEqualTo("Danke! Noch 10 Stempel.");
    }

    @Test
    void hatKarte_legtNichtsAn() {
        CustomerService dienst = dienst(karte(true));
        when(customerRepo.findById("CUST-FEHLT")).thenReturn(Optional.empty());

        assertThat(dienst.hatKarte("CUST-FEHLT", "CARD-1")).isFalse();
        assertThat(dienst.hatKarte(null, "CARD-1")).isFalse();

        Customer da = Customer.createOhneMail();
        when(customerRepo.findById(da.getId())).thenReturn(Optional.of(da));
        // Kunde da, aber keine Karte bei diesem Laden.
        assertThat(dienst.hatKarte(da.getId(), "CARD-1")).isFalse();
        when(ccRepo.findByCustomerAndCard(any(), any()))
                .thenReturn(Optional.of(mock(CustomerCard.class)));
        assertThat(dienst.hatKarte(da.getId(), "CARD-1")).isTrue();

        verify(ccRepo, never()).save(any());
    }
}
