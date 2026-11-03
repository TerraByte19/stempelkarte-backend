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

    // Eigene Automation: wann jede Karte zuletzt von ihr eine Nachricht
    // bekam - [customerCardId, Instant]. Daraus macht der Automat "schon
    // dran gewesen" (einmal je Karte, je Pause, je Jahr).
    @org.springframework.data.jpa.repository.Query(
            "select e.customerCardId, max(k.erstelltAm) from KampagneEmpfaenger e, Kampagne k "
            + "where e.kampagneId = k.id and k.automationId = :automationId group by e.customerCardId")
    List<Object[]> letzterVersandJeKarte(@org.springframework.data.repository.query.Param("automationId") String automationId);
}
