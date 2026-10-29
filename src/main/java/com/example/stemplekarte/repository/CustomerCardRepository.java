package com.example.stemplekarte.repository;

import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.Customer;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.Shop;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface CustomerCardRepository extends JpaRepository<CustomerCard, String> {

    @EntityGraph(attributePaths = {"card", "card.shop", "customer"})
    Optional<CustomerCard> findById(String id);

    Optional<CustomerCard> findByCustomerAndCard(Customer customer, Card card);

    // Kundenkarte ueber die beiden IDs aus dem QR-Code. Karte und Kunde werden
    // mitgeladen, weil der Aufrufer beide braucht und open-in-view aus ist.
    @EntityGraph(attributePaths = {"card", "card.shop", "customer"})
    Optional<CustomerCard> findByCustomer_IdAndCard_Id(String customerId, String cardId);

    List<CustomerCard> findByCustomer(Customer customer);
    List<CustomerCard> findByCard(Card card);
    Optional<CustomerCard> findByAuthTokenAndCard(String authToken, Card card);
    int countByCard_Shop(Shop shop);

    // Anzahl UNTERSCHIEDLICHER Personen eines Ladens (ueber alle Karten, aktiv
    // wie inaktiv). countByCard_Shop zaehlt customer_card-Zeilen - bei Laeden
    // mit mehreren Karten also dieselbe Person mehrfach.
    @Query("select count(distinct cc.customer.id) from CustomerCard cc where cc.card.shop = :shop")
    long countDistinctCustomersByShop(Shop shop);

    // Personen, die mindestens einmal eine Belohnung eingeloest haben (fuer die
    // Einloese-Quote - customer_card-Zeilen zu zaehlen kann bei Mehrfachkarten
    // ueber 100% gehen).
    @Query("select count(distinct cc.customer.id) from CustomerCard cc "
            + "where cc.card.shop = :shop and cc.totalRewards > 0")
    long countDistinctCustomersWithRewardByShop(Shop shop);

    // Newsletter: alle Karten eines Ladens, deren Kunde Werbung zugestimmt hat.
    // card wird mitgeladen, weil die Mail den Stempelstand nennt und
    // open-in-view aus ist - sonst knallt der Zugriff spaeter.
    @EntityGraph(attributePaths = {"customer", "card"})
    List<CustomerCard> findByCard_ShopAndMarketingConsentTrue(Shop shop);

    // Rueckhol-Nachricht: aktive Karten eines Ladens, deren Kunde seit vor der
    // Grenze nicht da war und seitdem keine Nachricht bekommen hat. Laengste
    // Pause zuerst. Dieselbe Regel steht als CustomerCard#rueckholFaellig.
    //
    // Gesperrte Karten (Willkommensgeschenk offen) muessen HIER raus, nicht
    // erst im Java-Filter danach: die Abfrage holt nur eine Seite, aelteste
    // zuerst. Ein Stapel nie eingeloester Willkommenskarten saesse sonst
    // dauerhaft vorne und schoebe echte Stammkunden aus jeder Seite.
    @EntityGraph(attributePaths = {"card", "card.shop", "customer"})
    @Query("select cc from CustomerCard cc where cc.card.shop = :shop and cc.card.active = true "
            + "and coalesce(cc.lastVisitAt, cc.updatedAt) < :grenze "
            + "and (cc.winbackSentAt is null or cc.winbackSentAt < coalesce(cc.lastVisitAt, cc.updatedAt)) "
            + "and (cc.welcomePending is null or cc.welcomePending = false) "
            + "order by coalesce(cc.lastVisitAt, cc.updatedAt)")
    List<CustomerCard> findRueckholKandidaten(Shop shop, java.time.Instant grenze,
                                              org.springframework.data.domain.Pageable seite);

    // Willkommensgeschenk einloesen. Gesperrt, damit zwei Kassen, die
    // dieselbe Karte im selben Moment scannen, nicht beide den Gratis-Artikel
    // freigeben: die zweite wartet und sieht danach "schon eingeloest".
    //
    // Bewusst OHNE @EntityGraph: der laedt per LEFT JOIN, und Postgres lehnt
    // FOR UPDATE auf der nullbaren Seite eines Outer Joins ab. Der Aufrufer
    // laedt Karte und Kunde vorher selbst in derselben Transaktion - die
    // Beziehungen zeigen dann auf diese schon geladenen Objekte.
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select cc from CustomerCard cc where cc.customer.id = :customerId and cc.card.id = :cardId")
    Optional<CustomerCard> findZumEinloesen(String customerId, String cardId);

    // Was der Aushang gebracht hat - im Karten-Editor des Ladens.
    long countByCardAndWelcomePendingTrue(Card card);
    long countByCardAndWelcomeRedeemedAtIsNotNull(Card card);
}