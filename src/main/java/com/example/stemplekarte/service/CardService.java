package com.example.stemplekarte.service;

import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.repository.CardRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;

@Service
public class CardService {

    private final CardRepository cardRepo;

    public CardService(CardRepository cardRepo) {
        this.cardRepo = cardRepo;
    }

    @Transactional
    public Card create(Shop shop, String name, String description,
                       int rewardThreshold, String rewardText) {
        if (rewardThreshold < 1 || rewardThreshold > 100) {
            throw new IllegalArgumentException("Stempel-Anzahl muss zwischen 1 und 100 liegen");
        }
        return cardRepo.save(Card.create(shop, name, description, rewardThreshold, rewardText));
    }

    @Transactional
    public Card save(Card card) {
        return cardRepo.save(card);
    }

    public List<Card> getByShop(Shop shop) {
        return cardRepo.findByShopAndActiveTrue(shop);
    }

    public Card getById(String id) {
        return cardRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Karte nicht gefunden: " + id));
    }

    public Card getByIdAndShop(String id, Shop shop) {
        Card card = getById(id);
        if (!card.getShop().getId().equals(shop.getId())) {
            throw new IllegalArgumentException("Karte gehoert nicht zu diesem Shop");
        }
        return card;
    }

    @Transactional
    public void deactivate(String cardId, Shop shop) {
        Card card = getByIdAndShop(cardId, shop);
        card.setActive(false);
        cardRepo.save(card);
    }
}