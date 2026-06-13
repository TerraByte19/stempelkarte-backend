package com.example.stemplekarte.repository;

import com.example.stemplekarte.model.SentNewsletter;
import com.example.stemplekarte.model.Shop;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SentNewsletterRepository extends JpaRepository<SentNewsletter, String> {

    // Newsletter eines Shops, neueste zuerst, seitenweise (für den Verlauf).
    Page<SentNewsletter> findByShopOrderBySentAtDesc(Shop shop, Pageable pageable);
}