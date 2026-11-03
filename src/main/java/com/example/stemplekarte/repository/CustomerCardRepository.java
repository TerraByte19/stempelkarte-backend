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

    // Kampagnen-Ergebnis: zu Kundenkarten-IDs die Personen-IDs, ohne die
    // Entities zu laden (ScanLog kennt nur die Personen-ID).
    @Query("select cc.id, cc.customer.id from CustomerCard cc where cc.id in :ids")
    List<Object[]> kundenIdsVon(java.util.Collection<String> ids);

    // Dashboard "Neu dabei": die juengsten Kundenkarten mit Name und Karte.
    @EntityGraph(attributePaths = {"card", "customer"})
    List<CustomerCard> findTop5ByCard_ShopOrderByCreatedAtDesc(Shop shop);

    // Kundenliste: aktive Karten eines Ladens, Suche in Name und E-Mail
    // (q = "%suchwort%" in Kleinbuchstaben, "%" = alles), zuletzt gesehene
    // zuerst. Immer ein Muster uebergeben statt ":q is null" - Postgres
    // kann den Typ eines Parameters, der nur mit null verglichen wird,
    // nicht bestimmen.
    @EntityGraph(attributePaths = {"card", "customer"})
    @Query("select cc from CustomerCard cc where cc.card.shop = :shop and cc.card.active = true "
            + "and (lower(cc.customer.name) like :q or lower(cc.customer.email) like :q) "
            + "order by coalesce(cc.lastVisitAt, cc.updatedAt) desc")
    org.springframework.data.domain.Page<CustomerCard> findKunden(
            Shop shop, String q, org.springframework.data.domain.Pageable seite);

    // Kunde wirbt Kunde: der Werber ueber seinen Code, mit Karte und Laden
    // (die Pruefung "gleiche Karte, nicht derselbe Kunde" braucht beide).
    @EntityGraph(attributePaths = {"card", "card.shop", "customer"})
    Optional<CustomerCard> findByEmpfehlungsCode(String empfehlungsCode);
    boolean existsByEmpfehlungsCode(String empfehlungsCode);
    long countByCardAndGeworbenVonIsNotNull(Card card);
    long countByCardAndEmpfehlungBonusAmIsNotNull(Card card);

    // Die fleissigsten Werber einer Karte: [werberCardId, anzahl].
    @Query("select cc.geworbenVon, count(cc) from CustomerCard cc where cc.card = :card "
            + "and cc.geworbenVon is not null group by cc.geworbenVon order by count(cc) desc")
    List<Object[]> topWerber(Card card, org.springframework.data.domain.Pageable seite);

    // Kundenliste mit Gruppenfilter: alle aktiven Karten des Ladens, die
    // Gruppe wird in Java bestimmt (service.Kundengruppe), danach gesucht
    // und seitenweise geschnitten. Gleiche Sortierung wie findKunden.
    @EntityGraph(attributePaths = {"card", "customer"})
    @Query("select cc from CustomerCard cc where cc.card.shop = :shop and cc.card.active = true "
            + "order by coalesce(cc.lastVisitAt, cc.updatedAt) desc")
    List<CustomerCard> findAlleKunden(Shop shop);

    // Kampagne: die Grundmenge, aus der die Zielgruppe in Java gefiltert wird
    // (service.Zielgruppe). Aktive Karten, Geschenk nicht offen - eine
    // gesperrte Karte hat noch nichts gesammelt, "Praemie wartet" oder
    // "30 Tage nicht da" ergeben dort keinen Sinn.
    @EntityGraph(attributePaths = {"card", "card.shop", "customer"})
    @Query("select cc from CustomerCard cc where cc.card.shop = :shop and cc.card.active = true "
            + "and (cc.welcomePending is null or cc.welcomePending = false) "
            + "order by cc.createdAt")
    List<CustomerCard> findKampagnenBasis(Shop shop);

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

    // Gesperrte Karten, deren Pass nach einer Design-Aenderung neu muss.
    List<CustomerCard> findByCardAndWelcomePendingTrue(Card card);
}