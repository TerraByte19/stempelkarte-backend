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

    // ── Rueckmeldung: Rueckseitenfeld nur, wenn der Laden es einschaltet ──

    @Test
    void rueckmeldungFeld_nurWennEingeschaltet() {
        Shop shop = mock(Shop.class);
        Card card = Card.create(shop, "Kaffee", "10 Stempel", 10, "Gratis Kaffee");
        CustomerCard cc = karte(card, 3, 0);
        when(cc.getCustomer().getId()).thenReturn("CUST-1");
        AppProperties props = mock(AppProperties.class);
        when(props.baseUrl()).thenReturn("https://stampit.test");
        ApplePassService dienst = new ApplePassService(props, mock(PassTemplateGenerator.class),
                mock(RewardService.class));

        // Aus (Standard): Pass bleibt wie er ist.
        assertThat(dienst.baueFelder(card, cc, false).getBackFields()).isNullOrEmpty();

        when(shop.isRueckmeldungAktiv()).thenReturn(true);
        var pass = dienst.baueFelder(card, cc, false);
        assertThat(pass.getBackFields()).hasSize(1);
        assertThat(pass.getBackFields().get(0).getKey()).isEqualTo("feedback");
        assertThat(pass.getBackFields().get(0).getValue())
                .isEqualTo("Wie war dein Besuch? Sag es uns: https://stampit.test/bewertung/CUST-1/" + card.getId());
        // Nie klingeln - das Feld erscheint still.
        assertThat(pass.getBackFields().get(0).getChangeMessage()).isNull();
        // Kopf-Feld unveraendert, inklusive Key.
        assertThat(pass.getHeaderFields().get(0).getKey()).isEqualTo("stamps-header");
    }

    // ── Tagesangebot: Rueckseitenfeld nur, wenn je benutzt; nie klingeln ──

    @Test
    void tagesangebotFeld_nurWennJeBenutzt_ohneChangeMessage() {
        Shop shop = mock(Shop.class);
        Card card = Card.create(shop, "Kaffee", "10 Stempel", 10, "Gratis Kaffee");
        CustomerCard cc = karte(card, 3, 0);
        ApplePassService dienst = dienst(mock(RewardService.class));

        // Nie benutzt: Pass bleibt wie er ist.
        assertThat(dienst.baueFelder(card, cc, false).getBackFields()).isNullOrEmpty();

        // Benutzt, aber gerade kein Angebot: Ruhetext, damit iOS den alten Wert kennt.
        when(shop.hatTagesangebotFeld()).thenReturn(true);
        var ruhe = dienst.baueFelder(card, cc, false);
        assertThat(ruhe.getBackFields()).hasSize(1);
        assertThat(ruhe.getBackFields().get(0).getKey()).isEqualTo("heute");
        assertThat(ruhe.getBackFields().get(0).getValue()).isEqualTo("Heute kein besonderes Angebot.");
        assertThat(ruhe.getBackFields().get(0).getChangeMessage()).isNull();

        // Mit Angebot.
        when(shop.tagesangebot(org.mockito.ArgumentMatchers.any())).thenReturn("Cappuccino 2,50 €");
        var pass = dienst.baueFelder(card, cc, false);
        assertThat(pass.getBackFields().get(0).getValue()).isEqualTo("Cappuccino 2,50 €");
        assertThat(pass.getBackFields().get(0).getChangeMessage()).isNull();
        assertThat(pass.getHeaderFields().get(0).getKey()).isEqualTo("stamps-header");
    }

    // ── Kunde wirbt Kunde: Rueckseitenfeld nur, wenn an und Code da ──────

    @Test
    void empfehlungFeld_nurWennAnUndCodeDa_ohneChangeMessage() {
        Shop shop = mock(Shop.class);
        Card card = Card.create(shop, "Kaffee", "10 Stempel", 10, "Gratis Kaffee");
        CustomerCard cc = karte(card, 3, 0);
        AppProperties props = mock(AppProperties.class);
        when(props.baseUrl()).thenReturn("https://stampit.test");
        ApplePassService dienst = new ApplePassService(props, mock(PassTemplateGenerator.class),
                mock(RewardService.class));

        assertThat(dienst.baueFelder(card, cc, false).getBackFields()).isNullOrEmpty();
        card.updateEmpfehlung(true, 2);
        assertThat(dienst.baueFelder(card, cc, false).getBackFields()).isNullOrEmpty();   // kein Code

        when(cc.getEmpfehlungsCode()).thenReturn("ABC234");
        var pass = dienst.baueFelder(card, cc, false);
        assertThat(pass.getBackFields()).hasSize(1);
        assertThat(pass.getBackFields().get(0).getKey()).isEqualTo("empfehlen");
        assertThat(pass.getBackFields().get(0).getValue())
                .isEqualTo("Für jeden Freund, der mitmacht, gibt's 2 Stempel für euch beide. "
                        + "Dein Link: https://stampit.test/karte-neu/" + card.getId() + "?ref=ABC234");
        assertThat(pass.getBackFields().get(0).getChangeMessage()).isNull();
        assertThat(pass.getHeaderFields().get(0).getKey()).isEqualTo("stamps-header");
    }

    // ── Stufen: Rueckseitenfeld nur bei Karten mit Stufen; nie klingeln ──

    @Test
    void statusFeld_nurMitStufen_ohneChangeMessage() {
        Card card = stempelkarte();
        CustomerCard cc = karte(card, 7, 0);
        ApplePassService dienst = dienst(mock(RewardService.class));
        assertThat(dienst.baueFelder(card, cc, false).getBackFields()).isNullOrEmpty();

        card.updateStufen(true, null, null, 5, null, 20);
        var pass = dienst.baueFelder(card, cc, false);
        assertThat(pass.getBackFields()).hasSize(1);
        assertThat(pass.getBackFields().get(0).getKey()).isEqualTo("status");
        assertThat(pass.getBackFields().get(0).getValue()).isEqualTo("Silber · noch 13 Besuche bis Gold");
        assertThat(pass.getBackFields().get(0).getChangeMessage()).isNull();
        assertThat(pass.getHeaderFields().get(0).getKey()).isEqualTo("stamps-header");
    }

    // ── Mitgliedskarte: eigener Zweig, Besuche im Kopf, kein Raster ──────

    @Test
    void mitgliedskarte_besucheImKopf_keineRueckseite() {
        Shop shop = mock(Shop.class);
        Card card = Card.createMember(shop, "Clubkarte", "Ausweis");
        CustomerCard cc = karte(card, 12, 0);
        when(cc.getId()).thenReturn("CC-ABCD1234");
        when(cc.getCreatedAt()).thenReturn(java.time.Instant.parse("2026-03-15T10:00:00Z"));
        ApplePassService dienst = dienst(mock(RewardService.class));

        var pass = dienst.baueFelder(card, cc, true);
        assertThat(pass.getHeaderFields()).hasSize(1);
        assertThat(pass.getHeaderFields().get(0).getKey()).isEqualTo("visits-header");
        assertThat(pass.getHeaderFields().get(0).getValue()).isEqualTo("12");
        assertThat(pass.getHeaderFields().get(0).getChangeMessage()).contains("%@");
        assertThat(pass.getPrimaryFields().get(0).getKey()).isEqualTo("member-name");
        assertThat(pass.getPrimaryFields().get(0).getValue()).isEqualTo("Adham");
        assertThat(pass.getSecondaryFields().get(0).getValue()).isEqualTo("03/2026");
        assertThat(pass.getAuxiliaryFields().get(0).getValue()).isEqualTo("ABCD1234");
        assertThat(pass.getBackFields()).isNullOrEmpty();

        // Google-Mitteilung zum Besuch.
        when(cc.getCard().getShop().getName()).thenReturn("Club Nord");
        String[] text = GoogleWalletService.meldungText(cc, GoogleWalletService.Meldung.BESUCH);
        assertThat(text[0]).isEqualTo("Besuch bei Club Nord");
        assertThat(text[1]).contains("12. Besuch");
    }

    @Test
    void zehnerKarte_restImKopf_textJeZustand() {
        Shop shop = mock(Shop.class);
        Card card = Card.createPackage(shop, "Yoga 10er", "Paket", 10, "Kurse");
        CustomerCard cc = karte(card, 3, 0);
        when(cc.getPaketRest()).thenReturn(7);
        ApplePassService dienst = dienst(mock(RewardService.class));

        var pass = dienst.baueFelder(card, cc, false);
        assertThat(pass.getHeaderFields()).hasSize(1);
        assertThat(pass.getHeaderFields().get(0).getKey()).isEqualTo("rest-header");
        assertThat(pass.getHeaderFields().get(0).getValue()).isEqualTo("7");
        assertThat(pass.getHeaderFields().get(0).getChangeMessage()).contains("%@").contains("Abgebucht").contains("Kurse");
        assertThat(pass.getPrimaryFields().get(0).getKey()).isEqualTo("rest-big");
        assertThat(pass.getPrimaryFields().get(0).getValue()).isEqualTo("7");
        assertThat(pass.getSecondaryFields().get(0).getValue()).isEqualTo("10 Kurse");
        assertThat(pass.getAuxiliaryFields().get(0).getValue()).isEqualTo("Adham");
        assertThat(pass.getBackFields()).isNullOrEmpty();

        // Gerade aufgeladen: anderer Text, gleicher Key.
        when(cc.paketFrischAufgeladen()).thenReturn(true);
        pass = dienst.baueFelder(card, cc, false);
        assertThat(pass.getHeaderFields().get(0).getKey()).isEqualTo("rest-header");
        assertThat(pass.getHeaderFields().get(0).getChangeMessage()).contains("%@").contains("aufgeladen");

        // Leer.
        when(cc.paketFrischAufgeladen()).thenReturn(false);
        when(cc.getPaketRest()).thenReturn(0);
        pass = dienst.baueFelder(card, cc, false);
        assertThat(pass.getHeaderFields().get(0).getKey()).isEqualTo("rest-header");
        assertThat(pass.getHeaderFields().get(0).getValue()).isEqualTo("0");
        assertThat(pass.getHeaderFields().get(0).getChangeMessage()).contains("%@").contains("aufgebraucht");
        assertThat(pass.getPrimaryFields().get(0).getValue()).isEqualTo("Leer");

        // Google-Mitteilungen.
        when(shop.getName()).thenReturn("Yoga Nord");
        String[] text = GoogleWalletService.meldungText(cc, GoogleWalletService.Meldung.PAKET);
        assertThat(text[0]).isEqualTo("Abgebucht bei Yoga Nord");
        assertThat(text[1]).contains("aufgebraucht");
        when(cc.getPaketRest()).thenReturn(12);
        assertThat(GoogleWalletService.meldungText(cc, GoogleWalletService.Meldung.PAKET)[1])
                .isEqualTo("Noch 12 Kurse übrig.");
        text = GoogleWalletService.meldungText(cc, GoogleWalletService.Meldung.AUFGELADEN);
        assertThat(text[0]).isEqualTo("Paket aufgeladen bei Yoga Nord");
        assertThat(text[1]).isEqualTo("10 Kurse dazu. Noch 12 Kurse übrig.");
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
                .doesNotContain("einlösen");

        // Stand 150: Kaffee ist drin. Mit Umlaut: der Text steht so auf dem
        // iPhone des Kunden, "einloesen" war ein Tippfehler in der Oberflaeche.
        var voll = dienst(katalog(card)).baueFelder(card, karte(card, 0, 15_000), false);
        assertThat(voll.getHeaderFields().get(0).getChangeMessage())
                .contains("einlösen");
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
