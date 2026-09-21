package com.example.stemplekarte.repository;

import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.Reward;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RewardRepository extends JpaRepository<Reward, String> {

    // Nach der vom Laden gesetzten Reihenfolge, bei Gleichstand nach Preis.
    // Der Preis als zweites Kriterium haelt die Pass-Rueckseite lesbar, auch
    // wenn ein Laden die Sortierung nie angefasst hat (alle auf 0).
    List<Reward> findByCardAndActiveTrueOrderBySortOrderAscCostPointsX100Asc(Card card);

    long countByCardAndActiveTrue(Card card);
}
