package com.example.stemplekarte.service;

import com.example.stemplekarte.model.*;
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

    // Harte Obergrenze als Defense-in-Depth, falls processScan jemals ohne
    // Controller-Validierung aufgerufen wird (schuetzt die Schleife vor DoS).
    private static final int MAX_STAMPS_PER_SCAN = 20;

    private final CustomerRepository customerRepo;
    private final CardRepository cardRepo;
    private final CustomerCardRepository customerCardRepo;
    private final ObjectMapper mapper = new ObjectMapper();

    public CustomerService(CustomerRepository customerRepo, CardRepository cardRepo,
                           CustomerCardRepository customerCardRepo) {
        this.customerRepo = customerRepo;
        this.cardRepo = cardRepo;
        this.customerCardRepo = customerCardRepo;
    }

    @Transactional
    public Customer getOrCreate(String name, String email) {
        return customerRepo.findByEmail(email.toLowerCase().trim())
                .orElseGet(() -> customerRepo.save(Customer.create(name, email)));
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

    @Transactional
    public ScanResult processScan(String qrPayload, Shop shop, int count) {
        // Defense-in-Depth: Eingabe hart begrenzen, unabhaengig vom Controller.
        if (count < 1) count = 1;
        if (count > MAX_STAMPS_PER_SCAN) count = MAX_STAMPS_PER_SCAN;

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

        for (int i = 0; i < count; i++) {
            if (cc.getStamps() >= threshold) {
                cc.redeemReward();
                result = new ScanResult.Redeemed(cc, card.getRewardText() + " eingeloest!");
            } else {
                cc.addStamp();
                result = cc.getStamps() == threshold
                        ? new ScanResult.Full(cc, "Karte voll! " + card.getRewardText())
                        : new ScanResult.Stamped(cc, "Stempel hinzugefuegt (%d/%d)"
                        .formatted(cc.getStamps(), threshold));
            }
        }

        customerCardRepo.save(cc);
        log.info("Scan fuer Kunde {} auf Karte {} ({}x): {}", customerId, cardId, count, result.message());
        return result;
    }
}