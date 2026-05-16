package com.example.stemplekarte.repository;

import com.example.stemplekarte.model.Shop;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface ShopRepository extends JpaRepository<Shop, String> {
    Optional<Shop> findByEmail(String email);
    boolean existsByEmail(String email);
}