package com.example.stemplekarte.controller;

import com.example.stemplekarte.service.AdminKettenService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Admin: neue Filialen aller Ketten, die auf die Bestaetigung warten.
 * Bestaetigt oder abgelehnt wird je Laden (AdminKettenController). Zugang
 * wie alles unter /api/admin: nur mit dem Admin-Token.
 */
@RestController
@RequestMapping("/api/admin/kette/anfragen")
public class AdminKettenAnfragenController {

    private final AdminKettenService dienst;

    public AdminKettenAnfragenController(AdminKettenService dienst) {
        this.dienst = dienst;
    }

    @GetMapping
    public List<AdminKettenService.Anfrage> anfragen() {
        return dienst.anfragen();
    }
}
