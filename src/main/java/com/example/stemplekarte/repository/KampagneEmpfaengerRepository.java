package com.example.stemplekarte.repository;

import com.example.stemplekarte.model.KampagneEmpfaenger;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface KampagneEmpfaengerRepository extends JpaRepository<KampagneEmpfaenger, String> {

    // Eine Seite offener Zeilen fuer den Laeufer.
    List<KampagneEmpfaenger> findByKampagneIdAndErledigtFalse(String kampagneId, Pageable seite);

    long countByKampagneIdAndErledigtFalse(String kampagneId);

    // Alle Empfaenger einer Kampagne, fuer das Ergebnis "wer kam danach".
    List<KampagneEmpfaenger> findByKampagneId(String kampagneId);

    // Beim Loeschen eines Ladens (AdminController#deleteShop).
    void deleteByKampagneId(String kampagneId);
}
