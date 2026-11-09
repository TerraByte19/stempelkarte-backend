package com.example.stemplekarte.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Ordner im Admin-Panel: Rand weg, leer = ohne Ordner, hoechstens 40 Zeichen. */
class AdminOrdnerTest {

    @Test
    void setztUndBereinigt() {
        Shop s = new Shop();
        s.setAdminOrdner("  Kunde \t zahlend ");
        assertThat(s.getAdminOrdner()).isEqualTo("Kunde zahlend");
    }

    @Test
    void leerHeisstOhneOrdner() {
        Shop s = new Shop();
        s.setAdminOrdner("Intern");
        s.setAdminOrdner("   ");
        assertThat(s.getAdminOrdner()).isNull();
        s.setAdminOrdner(null);
        assertThat(s.getAdminOrdner()).isNull();
    }

    @Test
    void kuerztAufVierzigZeichen() {
        Shop s = new Shop();
        s.setAdminOrdner("x".repeat(60));
        assertThat(s.getAdminOrdner()).hasSize(40);
    }
}
