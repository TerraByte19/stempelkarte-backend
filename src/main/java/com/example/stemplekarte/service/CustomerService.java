package com.example.stemplekarte.service;

import com.example.stemplekarte.model.*;
import com.example.stemplekarte.repository.AppleDeviceRepository;
import com.example.stemplekarte.repository.CardRepository;
import com.example.stemplekarte.repository.CustomerCardRepository;
import com.example.stemplekarte.repository.CustomerRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

@Service
public class CustomerService {

    private static final Logger log = LoggerFactory.getLogger(CustomerService.class);

    private final CustomerRepository customerRepo;
    private final CardRepository cardRepo;
    private final CustomerCardRepository customerCardRepo;
    private final AppleDeviceRepository appleDeviceRepo;
    private final EmailService emailService;
    private final ObjectMapper mapper = new ObjectMapper();

    public CustomerService(CustomerRepository customerRepo, CardRepository cardRepo,
                           CustomerCardRepository customerCardRepo,
                           AppleDeviceRepository appleDeviceRepo,
                           EmailService emailService) {
        this.customerRepo = customerRepo;
        this.cardRepo = cardRepo;
        this.customerCardRepo = customerCardRepo;
        this.appleDeviceRepo = appleDeviceRepo;
        this.emailService = emailService;
    }

    @Transactional
    public Customer getOrCreate(String name, String email) {
        return customerRepo.findByEmail(email.toLowerCase().trim())
                .orElseGet(() -> customerRepo.save(Customer.create(name, email)));
    }

    /**
     * Anmeldung über die Kunden-Landing-Page (/karte-neu/{cardId}).
     * - Erstellt Kunde + CustomerCard (oder findet bestehende).
     * - Speichert die Werbe-Einwilligung pro Karte/Laden (Checkbox).
     * - Versendet bei Bedarf die Bestätigungs-Mail (Double-Opt-In).
     */
    @Transactional
    public CustomerCard registerForCard(String name, String email, String cardId,
                                        boolean marketingConsent) {
        Card card = cardRepo.findById(cardId)
                .orElseThrow(() -> new NoSuchElementException("Karte nicht gefunden: " + cardId));

        Customer customer = getOrCreate(name, email);

        // Jede Kartenanmeldung verlangt eine frische Mail-Bestätigung:
        // emailConfirmed zurücksetzen + neuen Token vergeben, damit der
        // Kunde die Karte erst nach Klick auf den Mail-Link bekommt.
        // (Solange unbestätigt, wird der Kunde auch nicht im Newsletter
        // angeschrieben — rechtlich sauber.)
        customer.requireNewConfirmation();
        customerRepo.save(customer);

        CustomerCard cc = customerCardRepo.findByCustomerAndCard(customer, card)
                .orElseGet(() -> customerCardRepo.save(CustomerCard.create(customer, card)));

        // Einwilligung speichern (nur wenn neu, nicht ungewollt zurücksetzen)
        if (marketingConsent && !cc.isMarketingConsent()) {
            cc.giveMarketingConsent();
            customerCardRepo.save(cc);
        }

        // Bestätigungs-Mail IMMER verschicken (jede Anmeldung neu).
        emailService.sendConfirmationMail(customer, card.getShop(), card.getId());

        return cc;
    }

    public Customer getById(String id) {
        return customerRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Kunde nicht gefunden: " + id));
    }

    public Optional<Customer> findById(String id) {
        return customerRepo.findById(id);
    }

    @Transactional
    public CustomerCard getOrCreateCustomerCard(String customerId, String cardId) {
        Customer customer = getById(customerId);
        Card card = cardRepo.findById(cardId)
                .orElseThrow(() -> new NoSuchElementException("Karte nicht gefunden: " + cardId));
        return customerCardRepo.findByCustomerAndCard(customer, card)
                .orElseGet(() -> customerCardRepo.save(CustomerCard.create(customer, card)));
    }

    public CustomerCard getCustomerCardById(String customerCardId) {
        return customerCardRepo.findById(customerCardId)
                .orElseThrow(() -> new NoSuchElementException("CustomerCard nicht gefunden: " + customerCardId));
    }

    public CustomerCard getCustomerCard(String customerId, String cardId) {
        Customer customer = getById(customerId);
        Card card = cardRepo.findById(cardId)
                .orElseThrow(() -> new NoSuchElementException("Karte nicht gefunden: " + cardId));
        return customerCardRepo.findByCustomerAndCard(customer, card)
                .orElseThrow(() -> new NoSuchElementException("Kunde hat diese Karte nicht"));
    }

    public List<CustomerCard> getAllCardsOfCustomer(String customerId) {
        Customer customer = getById(customerId);
        return customerCardRepo.findByCustomer(customer);
    }

    /**
     * DSGVO: Recht auf Loeschung. Entfernt den Kunden vollstaendig aus dem System —
     * seine CustomerCards und die zugehoerigen Apple-Geraeteregistrierungen
     * (Push-Tokens). Hinweis: In der Google Wallet kann eine bereits gespeicherte
     * Karte separat per API auf EXPIRED gesetzt werden; das hier entfernt die
     * personenbezogenen Daten aus DEINEM System.
     */
    @Transactional
    public void deleteCustomerData(String customerId) {
        Customer customer = customerRepo.findById(customerId)
                .orElseThrow(() -> new NoSuchElementException("Kunde nicht gefunden: " + customerId));

        List<CustomerCard> cards = customerCardRepo.findByCustomer(customer);
        for (CustomerCard cc : cards) {
            // serialNumber der Apple-Registrierung == CustomerCard-ID
            appleDeviceRepo.deleteBySerialNumber(cc.getId());
        }
        customerCardRepo.deleteAll(cards);
        customerRepo.delete(customer);

        log.info("DSGVO-Loeschung: Kunde {} mit {} Karte(n) entfernt", customerId, cards.size());
    }

    @Transactional
    public ScanResult processScan(String qrPayload, Shop shop, int count) {
        String customerId;
        String cardId;
        try {
            JsonNode node = mapper.readTree(qrPayload);
            customerId = node.path("cid").asText();
            cardId = node.path("cardId").asText();
            if (customerId.isBlank() || cardId.isBlank()) {
                throw new IllegalArgumentException("QR enthaelt keine Kunden- oder Karten-ID");
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Ungueltiger QR-Code: " + e.getMessage());
        }

        Card card = cardRepo.findById(cardId)
                .orElseThrow(() -> new NoSuchElementException("Karte nicht gefunden"));

        if (!card.getShop().getId().equals(shop.getId())) {
            throw new IllegalArgumentException("Diese Karte gehoert nicht zu deinem Shop");
        }

        if (!card.isActive()) {
            throw new IllegalArgumentException("Diese Karte ist nicht mehr aktiv");
        }

        CustomerCard cc = getOrCreateCustomerCard(customerId, cardId);
        int threshold = card.getRewardThreshold();
        ScanResult result = null;
        int rewardsEarnedThisScan = 0;

        for (int i = 0; i < count; i++) {
            if (cc.getStamps() >= threshold) {
                cc.redeemReward();
                result = new ScanResult.Redeemed(cc, card.getRewardText() + " eingeloest!", rewardsEarnedThisScan);
            } else {
                cc.addStamp();
                if (cc.getStamps() == threshold) {
                    rewardsEarnedThisScan++;
                    result = new ScanResult.Full(cc, "Karte voll! " + card.getRewardText(), rewardsEarnedThisScan);
                } else {
                    result = new ScanResult.Stamped(cc, "Stempel hinzugefuegt (%d/%d)"
                            .formatted(cc.getStamps(), threshold), rewardsEarnedThisScan);
                }
            }
        }

        customerCardRepo.save(cc);
        log.info("Scan fuer Kunde {} auf Karte {} ({}x): {}", customerId, cardId, count, result.message());
        return result;
    }
}