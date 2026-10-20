package com.example.stemplekarte.repository;

import com.example.stemplekarte.model.Kontaktanfrage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface KontaktanfrageRepository extends JpaRepository<Kontaktanfrage, String> {

    List<Kontaktanfrage> findTop200ByOrderByEingegangenAmDesc();
}
