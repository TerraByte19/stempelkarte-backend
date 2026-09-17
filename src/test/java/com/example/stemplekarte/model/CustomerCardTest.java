package com.example.stemplekarte.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests fuer das Einloesen. Wichtig ist der Fall, dass ein Laden die
 * Stempelzahl nachtraeglich senkt (z.B. 10 -> 5): dann hat ein Kunde mehr
 * Stempel als noetig, und der Rest darf beim Einloesen nicht verfallen.
 */
class CustomerCardTest {

    private CustomerCard karteMitStempeln(int stamps) {
        CustomerCard cc = CustomerCard.create(null, null);
        for (int i = 0; i < stamps; i++) cc.addStamp();
        return cc;
    }

    @Test
    void volleKarte_gehtAufNull() {
        CustomerCard cc = karteMitStempeln(10);
        cc.redeemReward(10);
        assertThat(cc.getStamps()).isEqualTo(0);
        assertThat(cc.getTotalRewards()).isEqualTo(1);
    }

    @Test
    void gesenkteSchwelle_restBleibtStehen() {
        // 10 Stempel gesammelt, Laden senkt die Schwelle auf 5
        CustomerCard cc = karteMitStempeln(10);
        cc.redeemReward(5);
        // erste Belohnung raus, 5 Stempel zaehlen fuer die naechste
        assertThat(cc.getStamps()).isEqualTo(5);
        assertThat(cc.getTotalRewards()).isEqualTo(1);

        cc.redeemReward(5);
        assertThat(cc.getStamps()).isEqualTo(0);
        assertThat(cc.getTotalRewards()).isEqualTo(2);
    }

    @Test
    void gehtNieUnterNull() {
        CustomerCard cc = karteMitStempeln(3);
        cc.redeemReward(10);
        assertThat(cc.getStamps()).isEqualTo(0);
    }
}
