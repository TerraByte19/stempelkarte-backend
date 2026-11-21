package com.example.stemplekarte.service;

import com.example.stemplekarte.model.BookingKind;
import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.Customer;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.PointsBooking;
import com.example.stemplekarte.model.PointsRounding;
import com.example.stemplekarte.model.Reward;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.repository.CardRepository;
import com.example.stemplekarte.repository.CustomerCardRepository;
import com.example.stemplekarte.repository.PointsBookingRepository;
import com.example.stemplekarte.repository.ScanLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Nagelt die vier Buchungswege fest.
 *
 * Der Grund fuer die Buchungstabelle ist der Vertipper an der Kasse: 1450
 * statt 145 ist schneller getippt, als man denkt, und bei Stempeln war ein
 * Fehlgriff ein Schulterzucken - bei Punkten sind es zehnfache Punkte.
 * Deshalb muessen Korrektur und Ruecknahme genauso sicher sitzen wie das
 * Buchen selbst.
 *
 * Doppelte Ruecknahme ist der Fall, der im Laden wirklich passiert: zwei
 * Kassen, beide sehen dieselbe Buchung, beide druecken zurueck.
 */
class PunkteBuchungTest {

    private CustomerCardRepository customerCardRepo;
    private CardRepository cardRepo;
    private PointsBookingRepository bookingRepo;
    private ScanLogRepository scanLogRepo;
    private RewardService rewardService;
    private PointsService service;

    private Shop shop;
    private Card card;
    private CustomerCard cc;
    private Reward kaffee;
    private Reward kuchen;

    private static final String QR = "{\"cid\":\"CUST-1\",\"cardId\":\"CARD-1\"}";

    @BeforeEach
    void aufbau() {
        customerCardRepo = mock(CustomerCardRepository.class);
        cardRepo = mock(CardRepository.class);
        bookingRepo = mock(PointsBookingRepository.class);
        scanLogRepo = mock(ScanLogRepository.class);
        rewardService = mock(RewardService.class);

        shop = mock(Shop.class);
        when(shop.getId()).thenReturn("SHOP-1");

        card = Card.createPoints(shop, "Bistro", "Punkte", 100, PointsRounding.GENAU);
        Customer customer = mock(Customer.class);
        when(customer.getId()).thenReturn("CUST-1");
        when(customer.getName()).thenReturn("Adham");

        cc = CustomerCard.create(customer, card);

        kaffee = Reward.create(card, "Kaffee", 10_000, 0);
        kuchen = Reward.create(card, "Kuchen", 25_000, 1);

        // Achtung: Card.create vergibt eine zufaellige ID (CARD-XXXXXXXX).
        // Der QR traegt "CARD-1", darueber findet cardRepo die Karte - aber
        // die Kundenkarte wird ueber card.getId() gesucht, also ueber die
        // echte ID. Beide Stubs muessen das auseinanderhalten.
        when(cardRepo.findById("CARD-1")).thenReturn(Optional.of(card));
        when(customerCardRepo.findByCustomer_IdAndCard_Id("CUST-1", card.getId()))
                .thenReturn(Optional.of(cc));
        when(customerCardRepo.save(any(CustomerCard.class))).thenAnswer(i -> i.getArgument(0));
        when(bookingRepo.save(any(PointsBooking.class))).thenAnswer(i -> i.getArgument(0));
        when(rewardService.list(card)).thenReturn(List.of(kaffee, kuchen));

        service = new PointsService(customerCardRepo, cardRepo, bookingRepo,
                scanLogRepo, rewardService);
    }

    /**
     * Die zuletzt GESPEICHERTE Buchung. Geprueft wird die Entity, nicht die
     * zurueckgegebene Ansicht: was in der Datenbank steht, ist die Wahrheit,
     * an der spaeter eine Ruecknahme oder ein Streit an der Theke haengt.
     */
    private PointsBooking letzteGespeicherte() {
        ArgumentCaptor<PointsBooking> captor = ArgumentCaptor.forClass(PointsBooking.class);
        verify(bookingRepo, atLeastOnce()).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void einkaufBuchtPunkteUndSchreibtBuchung() {
        var ergebnis = service.earn(QR, shop, 14_500, "Kasse 1");

        // 145,00 Euro bei 1 Euro = 1 Punkt
        assertThat(cc.getPointsX100()).isEqualTo(14_500);
        assertThat(ergebnis.pointsText()).isEqualTo("145");

        PointsBooking gespeichert = letzteGespeicherte();
        assertThat(gespeichert.getKind()).isEqualTo(BookingKind.EARN);
        assertThat(gespeichert.getDeltaPointsX100()).isEqualTo(14_500);
        assertThat(gespeichert.getAmountCents()).isEqualTo(14_500);
        // Abschrift des Kurses, damit eine spaetere Kursaenderung die
        // Historie nicht umschreibt
        assertThat(gespeichert.getPointsPerEuroX100()).isEqualTo(100);
        assertThat(gespeichert.getStaffLabel()).isEqualTo("Kasse 1");
    }

    @Test
    void ergebnisTraegtKeineEntitaeten() {
        // Der Controller sitzt ausserhalb der Transaktion. Kaeme hier ein
        // Reward oder eine CustomerCard heraus, traefe er beim Abbilden auf
        // einen Lazy-Proxy und die Session waere zu - genau der Absturz vom
        // 12.09., nachdem der Stempel bereits gesetzt war.
        var ergebnis = service.earn(QR, shop, 14_500, "Kasse 1");

        assertThat(ergebnis.katalog()).allSatisfy(r ->
                assertThat(r).isInstanceOf(PointsService.RewardView.class));
        assertThat(ergebnis.booking()).isInstanceOf(PointsService.BookingView.class);
        assertThat(ergebnis.customerCardId()).isEqualTo(cc.getId());
    }

    @Test
    void einkaufMeldetNeuErreichtesZiel() {
        // Von 0 auf 145 Punkte: der Kaffee (100) wird erreichbar.
        var ergebnis = service.earn(QR, shop, 14_500, "Kasse 1");
        assertThat(ergebnis.neuesZielErreicht()).isTrue();
        // Naechstes Ziel ist jetzt der Kuchen, es fehlen 105 Punkte.
        assertThat(ergebnis.ziel().name()).isEqualTo("Kuchen");
        assertThat(ergebnis.fehlendX100()).isEqualTo(10_500);
        assertThat(ergebnis.fehlendText()).isEqualTo("105");
    }

    @Test
    void einkaufOhneNeuesZiel_meldetKeines() {
        service.earn(QR, shop, 2_000, "Kasse 1");   // 20 Punkte, nichts erreicht
        var zweiter = service.earn(QR, shop, 1_000, "Kasse 1"); // 30 Punkte
        assertThat(zweiter.neuesZielErreicht()).isFalse();
    }

    @Test
    void einloesenZiehtDenPreisAbUndZaehltDieBelohnung() {
        service.earn(QR, shop, 30_000, "Kasse 1");  // 300 Punkte
        when(rewardService.getByIdAndCard("RW-KUCHEN", card)).thenReturn(kuchen);

        service.redeem(QR, shop, "RW-KUCHEN", "Kasse 1");

        assertThat(cc.getPointsX100()).isEqualTo(5_000);   // 300 - 250 = 50
        assertThat(cc.getTotalRewards()).isEqualTo(1);

        PointsBooking gespeichert = letzteGespeicherte();
        assertThat(gespeichert.getKind()).isEqualTo(BookingKind.REDEEM);
        // Abschrift von Name und Preis: benennt der Laden "Kuchen" spaeter
        // in "Gebaeck" um, erzaehlt die Historie trotzdem die Wahrheit.
        assertThat(gespeichert.getRewardName()).isEqualTo("Kuchen");
        assertThat(gespeichert.getRewardCostPointsX100()).isEqualTo(25_000);
    }

    @Test
    void einloesenOhneDeckungScheitert() {
        service.earn(QR, shop, 5_000, "Kasse 1");   // 50 Punkte
        when(rewardService.getByIdAndCard("RW-KUCHEN", card)).thenReturn(kuchen);

        assertThatThrownBy(() -> service.redeem(QR, shop, "RW-KUCHEN", "Kasse 1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Nicht genug Punkte");

        assertThat(cc.getPointsX100()).isEqualTo(5_000);  // unveraendert
        assertThat(cc.getTotalRewards()).isZero();
    }

    @Test
    void korrekturMitNegativemBetrag() {
        service.earn(QR, shop, 14_500, "Kasse 1");
        service.correct(QR, shop, -13_500L, null, "Kasse 1");

        assertThat(cc.getPointsX100()).isEqualTo(1_000);  // 145 - 135 = 10
        PointsBooking gespeichert = letzteGespeicherte();
        assertThat(gespeichert.getKind()).isEqualTo(BookingKind.CORRECTION);
        assertThat(gespeichert.getDeltaPointsX100()).isEqualTo(-13_500);
    }

    @Test
    void korrekturMitPunktenDirekt() {
        service.correct(QR, shop, null, 5_000L, "Kasse 1");
        assertThat(cc.getPointsX100()).isEqualTo(5_000);
        assertThat(letzteGespeicherte().getAmountCents()).isNull();
    }

    @Test
    void korrekturBrauchtGenauEineAngabe() {
        assertThatThrownBy(() -> service.correct(QR, shop, 100L, 100L, "Kasse 1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("entweder");
        assertThatThrownBy(() -> service.correct(QR, shop, null, null, "Kasse 1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("entweder");
    }

    @Test
    void zuruecknehmenDrehtDieBuchungUm() {
        service.earn(QR, shop, 14_500, "Kasse 1");
        PointsBooking original = letzteGespeicherte();
        when(bookingRepo.findById(original.getId())).thenReturn(Optional.of(original));

        service.undo(QR, shop, original.getId(), "Kasse 1");

        assertThat(cc.getPointsX100()).isZero();
        PointsBooking gegenbuchung = letzteGespeicherte();
        assertThat(gegenbuchung.getKind()).isEqualTo(BookingKind.REVERSAL);
        assertThat(gegenbuchung.getReversalOfId()).isEqualTo(original.getId());
        assertThat(original.istZurueckgenommen()).isTrue();
    }

    @Test
    void zweimalZuruecknehmenScheitert() {
        // Der Fall, der im Laden wirklich passiert: zwei Kassen, beide sehen
        // dieselbe Buchung, beide druecken zurueck.
        service.earn(QR, shop, 14_500, "Kasse 1");
        PointsBooking original = letzteGespeicherte();
        when(bookingRepo.findById(original.getId())).thenReturn(Optional.of(original));

        service.undo(QR, shop, original.getId(), "Kasse 1");

        assertThatThrownBy(() -> service.undo(QR, shop, original.getId(), "Kasse 2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bereits zurueckgenommen");
    }

    @Test
    void zuruecknehmenEinerRuecknahmeScheitert() {
        service.earn(QR, shop, 14_500, "Kasse 1");
        PointsBooking original = letzteGespeicherte();
        when(bookingRepo.findById(original.getId())).thenReturn(Optional.of(original));

        service.undo(QR, shop, original.getId(), "Kasse 1");
        PointsBooking gegenbuchung = letzteGespeicherte();
        when(bookingRepo.findById(gegenbuchung.getId()))
                .thenReturn(Optional.of(gegenbuchung));

        assertThatThrownBy(() -> service.undo(QR, shop, gegenbuchung.getId(), "Kasse 1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Gegenbuchung");
    }

    @Test
    void zuruecknehmenEinerEinloesungGibtDieBelohnungZurueck() {
        service.earn(QR, shop, 30_000, "Kasse 1");
        when(rewardService.getByIdAndCard("RW-KUCHEN", card)).thenReturn(kuchen);
        service.redeem(QR, shop, "RW-KUCHEN", "Kasse 1");
        PointsBooking einloesung = letzteGespeicherte();
        when(bookingRepo.findById(einloesung.getId())).thenReturn(Optional.of(einloesung));

        service.undo(QR, shop, einloesung.getId(), "Kasse 1");

        assertThat(cc.getPointsX100()).isEqualTo(30_000);
        assertThat(cc.getTotalRewards()).isZero();
    }

    @Test
    void stempelkarteAmPunkteWegScheitert() {
        Card stempelkarte = Card.create(shop, "Kaffee", "10 Stempel", 10, "Gratis Kaffee");
        when(cardRepo.findById("CARD-1")).thenReturn(Optional.of(stempelkarte));

        assertThatThrownBy(() -> service.earn(QR, shop, 1_000, "Kasse 1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sammelt Stempel");
    }

    @Test
    void fremdeKarteScheitert() {
        Shop andererShop = mock(Shop.class);
        when(andererShop.getId()).thenReturn("SHOP-2");

        assertThatThrownBy(() -> service.earn(QR, andererShop, 1_000, "Kasse 1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("gehoert nicht");
    }

    @Test
    void betragUeberGrenzeScheitert() {
        assertThatThrownBy(() -> service.earn(QR, shop, 10_000_000L, "Kasse 1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Betrag");
    }

    @Test
    void negativerBetragBeimBuchenScheitert() {
        assertThatThrownBy(() -> service.earn(QR, shop, -100, "Kasse 1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Korrektur");
    }
}
