package com.example.stemplekarte.repository;

import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.model.StaffToken;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface StaffTokenRepository extends JpaRepository<StaffToken, String> {
    List<StaffToken> findByShop(Shop shop);
}