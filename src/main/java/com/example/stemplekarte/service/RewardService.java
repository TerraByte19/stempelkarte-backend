package com.example.stemplekarte.service;

import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.Reward;
import com.example.stemplekarte.repository.RewardRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;

@Service
public class RewardService {

    /** Mehr Zeilen macht die Rueckseite der Wallet-Karte unlesbar. */
    public static final int MAX_REWARDS = 20;

    private final RewardRepository rewardRepo;

    public RewardService(RewardRepository rewardRepo) {
        this.rewardRepo = rewardRepo;
    }

    @Transactional
    public Reward add(Card card, String name, long costPointsX100) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Praemie braucht einen Namen");
        }
        if (name.length() > 40) {
            throw new IllegalArgumentException("Name der Praemie: hoechstens 40 Zeichen");
        }
        if (costPointsX100 < 1) {
            throw new IllegalArgumentException("Praemie muss mindestens einen Punkt kosten");
        }
        if (rewardRepo.countByCardAndActiveTrue(card) >= MAX_REWARDS) {
            throw new IllegalArgumentException(
                    "Hoechstens " + MAX_REWARDS + " Praemien je Karte");
        }
        int naechsteReihe = list(card).size();
        return rewardRepo.save(Reward.create(card, name.trim(), costPointsX100, naechsteReihe));
    }

    public List<Reward> list(Card card) {
        return rewardRepo.findByCardAndActiveTrueOrderBySortOrderAscCostPointsX100Asc(card);
    }

    public Reward getByIdAndCard(String rewardId, Card card) {
        Reward r = rewardRepo.findById(rewardId)
                .orElseThrow(() -> new NoSuchElementException("Praemie nicht gefunden"));
        if (!r.getCard().getId().equals(card.getId())) {
            throw new IllegalArgumentException("Praemie gehoert nicht zu dieser Karte");
        }
        return r;
    }

    @Transactional
    public void deactivate(String rewardId, Card card) {
        Reward r = getByIdAndCard(rewardId, card);
        r.setActive(false);
        rewardRepo.save(r);
    }

    /**
     * Das Ziel fuer die Wallet-Karte: die billigste Praemie, die der Kunde
     * sich noch NICHT leisten kann.
     *
     * Kann er sich alles leisten, ist die teuerste das Ziel - sonst stuende
     * auf einer vollen Karte gar nichts. Bei leerem Katalog gibt es kein
     * Ziel; die Karte zeigt dann nur den Punktestand.
     *
     * Bewusst statisch und ohne Datenbank, damit die Regel in einem
     * Einheitentest festzunageln ist.
     */
    public static Reward naechstesZiel(List<Reward> katalog, long standX100) {
        if (katalog.isEmpty()) return null;
        return katalog.stream()
                .filter(r -> r.getCostPointsX100() > standX100)
                .min(Comparator.comparingLong(Reward::getCostPointsX100))
                .orElseGet(() -> katalog.stream()
                        .max(Comparator.comparingLong(Reward::getCostPointsX100))
                        .orElse(null));
    }
}
