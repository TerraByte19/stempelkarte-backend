package com.example.stemplekarte.repository;

import com.example.stemplekarte.model.AdminZugriff;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AdminZugriffRepository extends JpaRepository<AdminZugriff, String> {

    List<AdminZugriff> findTop200ByOrderByZeitpunktDesc();
}
