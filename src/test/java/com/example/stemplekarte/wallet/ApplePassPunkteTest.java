package com.example.stemplekarte.wallet;

import com.example.stemplekarte.config.AppProperties;
import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.Customer;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.PointsRounding;
import com.example.stemplekarte.model.Reward;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.service.RewardService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Der wichtigste Test dieses Umbaus.
 *
 * Der Pass nutzte bisher NUR Kopf-, Haupt-, Neben- und Zusatzfeld -
 * Rueckseitenfelder gab es keine. Der Praemien-Katalog wird das erste.
 * Rutscht dieses Feld versehentlich auch in den Stempel-Pass, aendert sich
 * der Pass fuer Karten, die gerade in echten Laeden liegen. Ein unpassendes
 * Feld hat iOS schon einmal den aktualisierten Pass verwerfen lassen
 * (damals groupingIdentifier): Karte installiert, aktualisiert aber nie -
 * und gemerkt wird es erst, wenn ein Kunde sich beschwert, dass sein
 * Stempel nicht ankommt.
 *
 * Deshalb steht hier beides: was der Punkte-Pass koennen MUSS und was der
 * Stempel-Pass weiterhin NICHT haben darf.
 */
class ApplePassPunkteTest {

    private ApplePassService dienst(RewardService rewards) {
        // baueFelder signiert nichts, und loadSigningInfo gibt null zurueck,
        // wenn die Zertifikatsdateien fehlen - genau der Fall im Test.
        return new ApplePassService(mock(AppProperties.class),
                mock(PassTemplateGenerator.class), rewards);
    }

    private CustomerCard karte(Card card, int stamps, long pointsX100) {
        Customer customer = mock(Customer.class);
        when(customer.getName()).thenReturn("Adham");
        CustomerCard cc = mock(CustomerCard.class);
        when(cc.getCustomer()).thenReturn(customer);
        when(cc.getCard()).thenReturn(card);
        when(cc.getStamps()).thenReturn(stamps);
        when(cc.getPointsX100()).thenReturn(pointsX100);
        return cc;
    }

    private Card stempelkarte() {
        return Card.create(mock(Shop.class), "Kaffee", "10 Stempel", 10, "Gratis Kaffee");
    }

    private Card punktekarte() {
        // 5 Euro pro Punkt
        return Card.createPoints(mock(Shop.class), "Bistro", "Punkte", 20, PointsRounding.GENAU);
    }

    // ── Stempel-Pass: darf sich NICHT bewegen ────────────────────────────

    @Test
    void stempelPass_hatWeiterhinKeineRueckseitenfelder() {
        Card card = stempelkarte();
        var pass = dienst(mock(RewardService.class)).baueFelder(card, karte(card, 3, 0), false);
        assertThat(pass.getBackFields()).isNullOrEmpty();
    }

    @Test
    void stempelPass_behaeltSeineFelder() {
        Card card = stempelkarte();
        var pass = dienst(mock(RewardService.class)).baueFelder(card, karte(card, 3, 0), false);

        assertThat(pass.getHeaderFields()).hasSize(1);
        assertThat(pass.getHeaderFields().get(0).getLabel()).isEqualTo("STEMPEL");
        assertThat(pass.getHeaderFields().get(0).getValue()).isEqualTo("3/10");
        // Countdown im grossen Mittelfeld: noch 7
        assertThat(pass.getPrimaryFields()).hasSize(1);
        assertThat(pass.getPrimaryFields().get(0).getValue()).isEqualTo("7");
        assertThat(pass.getSecondaryFields().get(0).getValue()).isEqualTo("Gratis Kaffee");
        assertThat(pass.getAuxiliaryFields().get(0).getValue()).isEqualTo("Adham");
    }

    @Test
    void stempelPass_imRasterStilUnveraendert() {
        Card card = stempelkarte();
        var pass = dienst(mock(RewardService.class)).baueFelder(card, karte(card, 3, 0), true);

        // Im Raster gibt es kein Hauptfeld - das Raster zeichnet das Bild.
        assertThat(pass.getPrimaryFields()).isNullOrEmpty();
        assertThat(pass.getHeaderFields().get(0).getValue()).isEqualTo("3/10");
        assertThat(pass.getBackFields()).isNullOrEmpty();
    }

    // ── Punkte-Pass ──────────────────────────────────────────────────────

    private RewardService katalog(Card card) {
        RewardService rs = mock(RewardService.class);
        when(rs.list(card)).thenReturn(List.of(
                Reward.create(card, "Kaffee", 10_000, 0),
                Reward.create(card, "Kuchen", 25_000, 1)));
        return rs;
    }

    @Test
    void punktePass_zeigtStandUndZiel() {
        Card card = punktekarte();
        var pass = dienst(katalog(card)).baueFelder(card, karte(card, 0, 15_000), false);

        assertThat(pass.getHeaderFields().get(0).getLabel()).isEqualTo("PUNKTE");
        assertThat(pass.getHeaderFields().get(0).getValue()).isEqualTo("150");
        // Stand 150: Kaffee (100) ist bezahlt, Kuchen (250) ist das Ziel.
        assertThat(pass.getSecondaryFields().get(0).getValue()).isEqualTo("Kuchen");
        assertThat(pass.getPrimaryFields().get(0).getValue()).isEqualTo("100");
    }

    @Test
    void punktePass_traegtDenKatalogAufDerRueckseite() {
        Card card = punktekarte();
        var pass = dienst(katalog(card)).baueFelder(card, karte(card, 0, 15_000), false);

        // Zwei Praemien plus die Kurs-Zeile
        assertThat(pass.getBackFields()).hasSize(3);
        // Bezahlbares traegt einen Haken
        assertThat(pass.getBackFields().get(0).getLabel()).startsWith("✓");
        assertThat(pass.getBackFields().get(0).getLabel()).contains("Kaffee");
        assertThat(pass.getBackFields().get(1).getLabel()).isEqualTo("Kuchen");
        assertThat(pass.getBackFields().get(2).getValue()).isEqualTo("1 Punkt pro 5 Euro");
    }

    @Test
    void punktePass_meldetSichErstWennEtwasErreichbarIst() {
        Card card = punktekarte();
        // Stand 50: noch nichts bezahlbar
        var leer = dienst(katalog(card)).baueFelder(card, karte(card, 0, 5_000), false);
        assertThat(leer.getHeaderFields().get(0).getChangeMessage())
                .doesNotContain("einloesen");

        // Stand 150: Kaffee ist drin
        var voll = dienst(katalog(card)).baueFelder(card, karte(card, 0, 15_000), false);
        assertThat(voll.getHeaderFields().get(0).getChangeMessage())
                .contains("einloesen");
    }

    @Test
    void punktePass_ohneKatalogZeigtNurDenStand() {
        Card card = punktekarte();
        RewardService leer = mock(RewardService.class);
        when(leer.list(card)).thenReturn(List.of());

        var pass = dienst(leer).baueFelder(card, karte(card, 0, 15_000), false);

        // Kein erfundenes Ziel: ein Laden ohne Praemien hat schlicht keines.
        assertThat(pass.getSecondaryFields()).isNullOrEmpty();
        assertThat(pass.getPrimaryFields().get(0).getValue()).isEqualTo("150");
        // Nur die Kurs-Zeile hinten
        assertThat(pass.getBackFields()).hasSize(1);
    }

    @Test
    void punktePass_allesBezahlbarZeigtBereit() {
        Card card = punktekarte();
        var pass = dienst(katalog(card)).baueFelder(card, karte(card, 0, 99_900), false);

        assertThat(pass.getSecondaryFields().get(0).getValue()).isEqualTo("Kuchen");
        assertThat(pass.getPrimaryFields().get(0).getLabel()).isEqualTo("BEREIT");
    }

    @Test
    void punktePass_raeumtDenRasterStilNichtEin() {
        // Auch mit grid=true gibt es bei Punkten kein Stempelraster - der
        // Aufrufer darf den Stil setzen, er hat hier nur keine Wirkung.
        Card card = punktekarte();
        var pass = dienst(katalog(card)).baueFelder(card, karte(card, 0, 15_000), true);
        assertThat(pass.getHeaderFields().get(0).getLabel()).isEqualTo("PUNKTE");
    }

    // ── Sperrbildschirm ──────────────────────────────────────────────────

    @Test
    void sperrbildschirm_fuelltDieselbenPlatzhalterMitPunkten() {
        // Der Laden schreibt seinen Text einmal und erwartet, dass er auf
        // beiden Kartentypen funktioniert. {stamps} traegt bei Punktekarten
        // die fehlende Punktzahl, {reward} den Namen der naechsten Praemie.
        // Kein neuer Platzhalter, keine Migration.
        Shop shop = mock(Shop.class);
        when(shop.isLockScreenActive()).thenReturn(true);
        when(shop.getLatitude()).thenReturn(52.5);
        when(shop.getLongitude()).thenReturn(13.4);
        when(shop.getLockScreenTextProgress()).thenReturn("Noch {stamps} Punkte bis: {reward}");

        Card card = Card.createPoints(shop, "Bistro", "Punkte", 20, PointsRounding.GENAU);
        RewardService rs = mock(RewardService.class);
        when(rs.list(card)).thenReturn(List.of(Reward.create(card, "Kuchen", 25_000, 0)));

        var orte = dienst(rs).sperrbildschirmOrte(shop, card, karte(card, 0, 9_000));

        assertThat(orte).hasSize(1);
        assertThat(orte.get(0).getRelevantText()).isEqualTo("Noch 160 Punkte bis: Kuchen");
    }

    @Test
    void sperrbildschirm_ohneKatalogBleibtLeer() {
        // Kein Ziel, kein sinnvoller Text. Lieber keine Ortsbindung als
        // "Noch 0 Punkte bis: null".
        Shop shop = mock(Shop.class);
        when(shop.isLockScreenActive()).thenReturn(true);
        Card card = Card.createPoints(shop, "Bistro", "Punkte", 20, PointsRounding.GENAU);
        RewardService rs = mock(RewardService.class);
        when(rs.list(card)).thenReturn(List.of());

        assertThat(dienst(rs).sperrbildschirmOrte(shop, card, karte(card, 0, 9_000))).isEmpty();
    }

    @Test
    void sperrbildschirm_stempelkarteUnveraendert() {
        // Die Einzahl-Form muss bleiben: "Noch 1 Stempel bis:", nicht
        // "Noch 1 Stempel bis" mit Mehrzahl-Wort.
        Shop shop = mock(Shop.class);
        when(shop.isLockScreenActive()).thenReturn(true);
        when(shop.getLatitude()).thenReturn(52.5);
        when(shop.getLongitude()).thenReturn(13.4);

        Card card = Card.create(shop, "Kaffee", "10 Stempel", 10, "Gratis Kaffee");

        var eins = dienst(mock(RewardService.class))
                .sperrbildschirmOrte(shop, card, karte(card, 9, 0));
        assertThat(eins.get(0).getRelevantText()).isEqualTo("Noch 1 Stempel bis: Gratis Kaffee");

        var mehrere = dienst(mock(RewardService.class))
                .sperrbildschirmOrte(shop, card, karte(card, 7, 0));
        assertThat(mehrere.get(0).getRelevantText()).isEqualTo("Noch 3 Stempel bis: Gratis Kaffee");

        var voll = dienst(mock(RewardService.class))
                .sperrbildschirmOrte(shop, card, karte(card, 10, 0));
        assertThat(voll.get(0).getRelevantText()).isEqualTo("Gratis Kaffee wartet auf dich");
    }

    @Test
    void sperrbildschirm_ausgeschaltetBleibtLeer() {
        Shop shop = mock(Shop.class);
        when(shop.isLockScreenActive()).thenReturn(false);
        Card card = stempelkarte();
        assertThat(dienst(mock(RewardService.class))
                .sperrbildschirmOrte(shop, card, karte(card, 3, 0))).isEmpty();
    }
}
