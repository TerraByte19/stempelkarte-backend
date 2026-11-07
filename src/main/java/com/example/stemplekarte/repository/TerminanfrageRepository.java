package com.example.stemplekarte.repository;

import com.example.stemplekarte.model.Terminanfrage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TerminanfrageRepository extends JpaRepository<Terminanfrage, String> {

    List<Terminanfrage> findTop200ByOrderByEingegangenAmDesc();
}
