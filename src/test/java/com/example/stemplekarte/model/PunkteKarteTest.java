package com.example.stemplekarte.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Nagelt fest, dass bestehende Karten Stempelkarten bleiben.
 *
 * Die Spalte type kommt per ddl-auto dazu und ist bei jeder vorhandenen
 * Zeile zunaechst NULL. Wuerde getType() das durchreichen, liefen alle
 * Karten, die gerade in echten Laeden liegen, in einen NullPointer oder
 * schlimmer: in den Punkte-Zweig.
 *
 * Der Shop ist ein Mock und kein null: Card.create liest die Farb-Defaults
 * vom Laden und wuerde sonst schon beim Anlegen fliegen.
 */
class PunkteKarteTest {

    private Shop laden() {
        return mock(Shop.class);
    }

    @Test
    void neueStempelkarte_istStempelkarte() {
        Card c = Card.create(laden(), "Kaffee", "10 Stempel", 10, "Gratis Kaffee");
        assertThat(c.getType()).isEqualTo(CardType.STAMP);
        assertThat(c.isPoints()).isFalse();
    }

    @Test
    void neuePunktekarte_traegtKursUndRundung() {
        Card c = Card.createPoints(laden(), "Bistro", "Punkte sammeln",
                100, PointsRounding.GENAU);
        assertThat(c.getType()).isEqualTo(CardType.POINTS);
        assertThat(c.isPoints()).isTrue();
        assertThat(c.getPointsPerEuroX100()).isEqualTo(100);
        assertThat(c.getPointsRounding()).isEqualTo(PointsRounding.GENAU);
    }

    @Test
    void punktekarte_haeltDieNotNullSpaltenBesetzt() {
        // reward_threshold und reward_text sind NOT NULL und bleiben es -
        // eine bestehende Spalte nachtraeglich nullable zu machen schafft
        // ddl-auto nicht zuverlaessig. Bei Punktekarten stehen sie auf
        // unauffaelligen Werten und werden nirgends angezeigt.
        Card c = Card.createPoints(laden(), "Bistro", "Punkte sammeln",
                100, PointsRounding.GENAU);
        assertThat(c.getRewardThreshold()).isEqualTo(1);
        assertThat(c.getRewardText()).isEmpty();
    }

    @Test
    void kursUndRundungSpaeterAenderbar() {
        Card c = Card.createPoints(laden(), "Bistro", "Punkte sammeln",
                100, PointsRounding.GENAU);
        c.updatePointsSettings(500, PointsRounding.ABRUNDEN);
        assertThat(c.getPointsPerEuroX100()).isEqualTo(500);
        assertThat(c.getPointsRounding()).isEqualTo(PointsRounding.ABRUNDEN);
    }

    @Test
    void nullWerteBeimAendernLassenAltesStehen() {
        // Gleiches Muster wie updateDesign/updateColors: was nicht
        // mitgeschickt wird, bleibt wie es war.
        Card c = Card.createPoints(laden(), "Bistro", "Punkte sammeln",
                100, PointsRounding.GENAU);
        c.updatePointsSettings(null, null);
        assertThat(c.getPointsPerEuroX100()).isEqualTo(100);
        assertThat(c.getPointsRounding()).isEqualTo(PointsRounding.GENAU);
    }
}
