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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Sprache des Kunden: ein neuer Kunde bekommt die Sprache des Ladens, ein
 * bestehender Kunde (auch einer ohne Eintrag aus der Zeit davor) wird durch
 * eine zweite Anmeldung woanders NICHT umgestellt.
 */
class KundenSpracheTest {

    private CustomerService dienst(CustomerRepository customerRepo, Card card) {
        CardRepository cardRepo = mock(CardRepository.class);
        CustomerCardRepository ccRepo = mock(CustomerCardRepository.class);
        when(cardRepo.findById(anyString())).thenReturn(Optional.of(card));
        when(ccRepo.save(any(CustomerCard.class))).thenAnswer(i -> i.getArgument(0));
        when(ccRepo.findByCustomerAndCard(any(), any())).thenReturn(Optional.empty());
        when(customerRepo.save(any(Customer.class))).thenAnswer(i -> i.getArgument(0));
        return new CustomerService(customerRepo, cardRepo, ccRepo, mock(AppleDeviceRepository.class),
                mock(EmailService.class), mock(ScanLogRepository.class), mock(EmpfehlungService.class));
    }

    private Card karteVon(String sprache) {
        Shop shop = mock(Shop.class);
        when(shop.getId()).thenReturn("SHOP-" + sprache);
        when(shop.getLanguageOrDefault()).thenReturn(sprache);
        return Card.create(shop, "K", "x", 10, "R");
    }

    @Test
    void neuerKunde_bekommtDieSpracheDesLadens() {
        CustomerRepository repo = mock(CustomerRepository.class);
        when(repo.findByEmail(anyString())).thenReturn(Optional.empty());
        CustomerCard cc = dienst(repo, karteVon("tr")).registerForCard("Ayşe", "ayse@example.com", "CARD-1", false, null, null, null);
        assertThat(cc.getCustomer().getLanguage()).isEqualTo("tr");
    }

    @Test
    void bestandskundeOhneSprache_bleibtOhne_alsoJeLadenDessenSprache() {
        Customer alt = Customer.create("Alex", "alex@example.com");   // Altbestand: language null
        CustomerRepository repo = mock(CustomerRepository.class);
        when(repo.findByEmail(anyString())).thenReturn(Optional.of(alt));
        dienst(repo, karteVon("tr")).registerForCard("Alex", "alex@example.com", "CARD-1", false, null, null, null);
        assertThat(alt.getLanguage()).isNull();
    }

    @Test
    void kundeMitSprache_behaeltSie() {
        Customer alt = Customer.create("Alex", "alex@example.com");
        alt.setLanguage("de");
        CustomerRepository repo = mock(CustomerRepository.class);
        when(repo.findByEmail(anyString())).thenReturn(Optional.of(alt));
        dienst(repo, karteVon("ar")).registerForCard("Alex", "alex@example.com", "CARD-1", false, null, null, null);
        assertThat(alt.getLanguage()).isEqualTo("de");
    }
}
