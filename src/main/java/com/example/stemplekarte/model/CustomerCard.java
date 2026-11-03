package com.example.stemplekarte.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "customer_cards",
        uniqueConstraints = @UniqueConstraint(columnNames = {"customer_id", "card_id"}))
public class CustomerCard {

    @Id
    @Column(length = 64)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "card_id", nullable = false)
    private Card card;

    @Column(nullable = false)
    private int stamps;

    @Column(name = "total_rewards", nullable = false)
    private int totalRewards;

    // Punktestand in Hundertstel-Punkten (520 = 5,20 Punkte). Gilt nur fuer
    // Karten vom Typ POINTS; Stempelkarten lassen die Spalte auf 0 stehen.
    // Default in der Spaltendefinition, damit ddl-auto=update bei
    // bestehenden Zeilen nicht fehlschlaegt.
    @Column(name = "points_x100", nullable = false,
            columnDefinition = "bigint not null default 0")
    private long pointsX100;

    @Column(name = "auth_token", nullable = false, length = 128)
    private String authToken;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // ── Werbe-Einwilligung (pro Laden/Karte, DSGVO + UWG) ───────────────
    // Checkbox "Ich möchte Angebote per E-Mail erhalten" bei der Anmeldung.
    // columnDefinition mit Default, damit ddl-auto=update bei bestehenden
    // Zeilen in Postgres nicht fehlschlägt.
    @Column(name = "marketing_consent", nullable = false,
            columnDefinition = "boolean not null default false")
    private boolean marketingConsent;

    // Zeitpunkt der Einwilligung (Nachweis für Double-Opt-In)
    @Column(name = "consent_at")
    private Instant consentAt;

    // ── Rueckhol-Nachricht (RueckholService) ─────────────────────────────
    // Letzter Besuch = letzte Buchung durch das Personal. updatedAt taugt
    // dafuer nicht: das aendert sich auch beim Verschicken der Nachricht.
    // Null bei Karten von vor dieser Spalte - dann gilt updatedAt.
    @Column(name = "last_visit_at")
    private Instant lastVisitAt;

    @Column(name = "winback_sent_at")
    private Instant winbackSentAt;

    // Der Text, wie er verschickt wurde. Steht im Pass, bis der Kunde
    // wiederkommt - aendert der Laden seinen Text, bleibt die alte Nachricht.
    @Column(name = "winback_text", length = 300)
    private String winbackText;

    // ── Anmeldefelder (Shop.signup*) ─────────────────────────────────────
    // Pro Kundenkarte, nicht am Customer: die Angaben gelten gegenueber
    // DIESEM Laden. Ein anderer Laden, bei dem derselbe Kunde ohne diese
    // Felder eine Karte holt, bekommt sie nicht zu sehen.
    @Column(name = "birthday")
    private java.time.LocalDate birthday;

    @Column(name = "phone", length = 30)
    private String phone;

    // ── Kampagne (KampagnenService) ──────────────────────────────────────
    // Die letzte Kampagne, die diese Karte bekommen hat. Bleibt stehen, bis
    // die naechste kommt - der Kunde soll das Angebot auch noch nach dem
    // Besuch auf der Rueckseite nachlesen koennen.
    @Column(name = "kampagne_text", length = 300)
    private String kampagneText;

    @Column(name = "kampagne_am")
    private Instant kampagneAm;

    // Bewertungs-Erinnerung schon bekommen (einmal pro Karte). Null = noch nicht.
    @Column(name = "review_reminded_at")
    private Instant reviewRemindedAt;

    // Automatische Nachrichten (service.AutoNachrichtTyp): wann Willkommen
    // und "zweiter Besuch" kamen (einmal pro Karte), und bei welchem
    // Praemienstand "fast voll" bzw. "Praemie bereit" kam (einmal pro Runde:
    // steigt totalRewards, ist die Karte wieder faellig). Null = noch nie.
    @Column(name = "auto_willkommen_am")
    private Instant autoWillkommenAm;

    @Column(name = "auto_zweiter_am")
    private Instant autoZweiterAm;

    @Column(name = "auto_fast_voll_bei")
    private Integer autoFastVollBei;

    @Column(name = "auto_praemie_bei")
    private Integer autoPraemieBei;

    // Hoechste Stufe (service.Stufen), zu der schon gratuliert wurde. Null = keine.
    @Column(name = "stufe_benachrichtigt")
    private Integer stufeBenachrichtigt;

    // ── 10er-Karte (Card.isPackage) ──────────────────────────────────────
    // Was vom Paket noch uebrig ist, wann zuletzt aufgeladen wurde, wie viele
    // Pakete je gekauft wurden. Alles nullable: bestehende Zeilen tragen NULL
    // und sind keine 10er-Karten. "stamps" zaehlt die Besuche hoch.
    // autoPaketLeerBei: bei welchem Paketstand der "Paket leer"-Automat kam.
    @Column(name = "paket_rest")
    private Integer paketRest;

    @Column(name = "paket_aufgeladen_am")
    private Instant paketAufgeladenAm;

    @Column(name = "pakete_gekauft")
    private Integer paketeGekauft;

    @Column(name = "auto_paket_leer_bei")
    private Integer autoPaketLeerBei;

    // true = gerade aufgeladen, seitdem nichts abgebucht oder korrigiert.
    // Der Pass meldet dann "aufgeladen" statt "abgebucht". Eigener Merker
    // statt Zeitvergleich: zwei Buchungen in derselben Millisekunde saehen
    // sonst gleich aus.
    @Column(name = "paket_frisch")
    private Boolean paketFrisch;

    // Kunde wirbt Kunde (service.EmpfehlungService): eigener Code zum
    // Weitergeben; wer mich geworben hat (customerCardId) und wann; wann der
    // Bonus fuer beide gebucht wurde (einmal, beim ersten echten Besuch).
    @Column(name = "empfehlungs_code", length = 12, unique = true)
    private String empfehlungsCode;

    @Column(name = "geworben_von", length = 40)
    private String geworbenVon;

    @Column(name = "geworben_am")
    private Instant geworbenAm;

    @Column(name = "empfehlung_bonus_am")
    private Instant empfehlungBonusAm;

    // ── Willkommensgeschenk ──────────────────────────────────────────────
    // true = Karte gesperrt, bis das Personal das Geschenk einloest.
    // Nullable OHNE Default mit Absicht: null heisst "nicht gesperrt", und
    // genau das muss fuer jede Karte gelten, die vor dieser Spalte existierte.
    @Column(name = "welcome_pending")
    private Boolean welcomePending;

    // Das Versprechen, wie es bei der Anmeldung galt. Aendert der Laden
    // seinen Text danach, steht beim Kunden weiter, was ihm zugesagt wurde.
    @Column(name = "welcome_text", length = Card.WILLKOMMEN_MAX)
    private String welcomeText;

    @Column(name = "welcome_redeemed_at")
    private Instant welcomeRedeemedAt;

    protected CustomerCard() {}

    public static CustomerCard create(Customer customer, Card card) {
        CustomerCard cc = new CustomerCard();
        cc.id = "CC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        cc.customer = customer;
        cc.card = card;
        cc.stamps = 0;
        cc.totalRewards = 0;
        cc.authToken = UUID.randomUUID().toString().replace("-", "");
        cc.createdAt = Instant.now();
        cc.updatedAt = cc.createdAt;
        return cc;
    }

    public void addStamp() {
        this.stamps++;
        this.updatedAt = Instant.now();
        this.lastVisitAt = this.updatedAt;
    }

    /**
     * Loest eine Belohnung ein. Abgezogen wird genau die Schwelle, nicht auf 0
     * gesetzt: senkt ein Laden die Stempelzahl nachtraeglich (z.B. 10 -> 5),
     * hat ein Kunde ploetzlich mehr Stempel als noetig. Der Rest bleibt stehen
     * und zaehlt fuer die naechste Belohnung, statt zu verfallen.
     *
     * Im Normalfall (Karte genau voll) ist das Ergebnis unveraendert 0.
     */
    public void redeemReward(int threshold) {
        this.stamps = Math.max(0, this.stamps - Math.max(1, threshold));
        this.totalRewards++;
        this.updatedAt = Instant.now();
        this.lastVisitAt = this.updatedAt;
    }

    /**
     * Stand von Hand setzen (Kundenliste im Dashboard). Kein Besuch:
     * lastVisitAt bleibt, nur updatedAt bewegt sich, damit der Pass-Cache
     * den neuen Stand ausliefert. Bereich prueft der KundenService.
     */
    public void korrigiereStempel(int neu) {
        this.stamps = neu;
        this.updatedAt = Instant.now();
    }

    /**
     * Setzt die Karte komplett zurück wie eine neue Karte:
     * Stempel UND Belohnungszähler auf 0. Wird vom Reset-Button im Scanner
     * genutzt. Marketing-Einwilligung bleibt unangetastet.
     */
    public void resetAll() {
        this.stamps = 0;
        this.totalRewards = 0;
        this.pointsX100 = 0;
        this.updatedAt = Instant.now();
    }

    /**
     * Bucht Punkte auf oder ab und gibt zurueck, wie viel TATSAECHLICH
     * gebucht wurde.
     *
     * Der Bestand geht nie unter null - gleiches Muster wie redeemReward bei
     * den Stempeln. Bei einer Korrektur ueber mehr als den Bestand faellt
     * der Rueckgabewert deshalb kleiner aus als der Wunsch, und genau dieser
     * Rueckgabewert landet in der Buchungszeile: sie soll festhalten, was
     * passiert ist, nicht was gemeint war.
     */
    public long addPoints(long deltaX100) {
        long vorher = this.pointsX100;
        this.pointsX100 = Math.max(0, vorher + deltaX100);
        this.updatedAt = Instant.now();
        this.lastVisitAt = this.updatedAt;
        return this.pointsX100 - vorher;
    }

    public boolean kannBezahlen(long kostenX100) {
        return this.pointsX100 >= kostenX100;
    }

    /** Belohnung zaehlen, ohne am Stempelstand zu ruehren. Punktekarten
     *  ziehen den Preis ueber addPoints ab, nicht ueber eine Schwelle. */
    public void zaehleBelohnung() {
        this.totalRewards++;
        this.updatedAt = Instant.now();
        this.lastVisitAt = this.updatedAt;
    }

    /** Ruecknahme einer Einloesung. Geht nie unter null - sonst stuende auf
     *  einer Karte eine negative Zahl eingeloester Praemien. */
    public void nimmBelohnungZurueck() {
        this.totalRewards = Math.max(0, this.totalRewards - 1);
        this.updatedAt = Instant.now();
    }

    /** Checkbox bei der Anmeldung angekreuzt. */
    public void giveMarketingConsent() {
        this.marketingConsent = true;
        this.consentAt = Instant.now();
    }

    /** Klick auf den Abmelde-Link in einer Werbe-Mail. */
    public void revokeMarketingConsent() {
        this.marketingConsent = false;
    }

    public Instant letzterBesuch() {
        return lastVisitAt != null ? lastVisitAt : updatedAt;
    }

    /**
     * Seit vor der Grenze nicht mehr da UND seit dem letzten Besuch noch
     * keine Nachricht bekommen. Pro Pause also genau eine Nachricht.
     */
    public boolean rueckholFaellig(Instant grenze) {
        // Gesperrt: der Standardtext saehe "Noch 10 Stempel bis ..." vor,
        // obwohl der Kunde noch gar nicht stempeln kann. Dieselbe Regel steht
        // in CustomerCardRepository#findRueckholKandidaten.
        if (isWillkommenOffen()) return false;
        Instant besuch = letzterBesuch();
        return besuch != null && besuch.isBefore(grenze)
                && (winbackSentAt == null || winbackSentAt.isBefore(besuch));
    }

    /** Nachricht verschickt und der Kunde war seitdem nicht da. */
    public boolean rueckholSichtbar() {
        return winbackSentAt != null && winbackText != null
                && (lastVisitAt == null || winbackSentAt.isAfter(lastVisitAt));
    }

    /**
     * Pass neu ausliefern lassen, ohne etwas zu melden: bringt das
     * Nachrichtenfeld aufs Handy, bevor die erste Nachricht kommt. Der letzte
     * Besuch wird vorher festgehalten, sonst saehe updatedAt wie einer aus.
     */
    public void passAuffrischen(Instant jetzt) {
        if (lastVisitAt == null) lastVisitAt = updatedAt;
        this.updatedAt = jetzt;
    }

    /**
     * Zweite Karte derselben Person im selben Laden: die Nachricht ging auf
     * die andere Karte, diese hier gilt als erledigt - ohne Text, ohne
     * Pass-Aenderung, ohne Push. Sonst klingelt dasselbe Handy zweimal.
     * Faellig wird sie erst wieder nach dem naechsten Besuch.
     */
    public void rueckholUebersprungen(Instant jetzt) {
        if (lastVisitAt == null) lastVisitAt = updatedAt;
        this.winbackText = null;
        this.winbackSentAt = jetzt;
    }

    public void rueckholGesendet(String text, Instant jetzt) {
        if (lastVisitAt == null) lastVisitAt = updatedAt;
        // Spalte fasst 300. Der Ladentext hat hoechstens 160, aber die
        // Platzhalter koennen ihn verlaengern - ein zu langer Text liesse das
        // Speichern scheitern, und die Karte kaeme jede Stunde wieder dran.
        this.winbackText = (text != null && text.length() > 300) ? text.substring(0, 299) + "…" : text;
        this.winbackSentAt = jetzt;
        // updatedAt bewegen, damit iOS den Pass mit der Nachricht abholt.
        this.updatedAt = jetzt;
    }

    // ── Kampagne ─────────────────────────────────────────────────────────

    /** Wie rueckholGesendet: zaehlt nicht als Besuch, bewegt aber updatedAt. */
    public void kampagneErhalten(String text, Instant jetzt) {
        if (lastVisitAt == null) lastVisitAt = updatedAt;
        this.kampagneText = (text != null && text.length() > 300) ? text.substring(0, 299) + "…" : text;
        this.kampagneAm = jetzt;
        this.updatedAt = jetzt;
    }

    // ── Bewertungs-Erinnerung ────────────────────────────────────────────

    public Instant getBewertungErinnertAm() { return reviewRemindedAt; }

    /** Nur der Merker; der Pass aendert sich dadurch nicht, also kein updatedAt. */
    public void bewertungErinnert(Instant jetzt) { this.reviewRemindedAt = jetzt; }

    // ── Automatische Nachrichten (nur Merker, kein updatedAt) ────────────

    public Instant getAutoWillkommenAm() { return autoWillkommenAm; }
    public Instant getAutoZweiterAm() { return autoZweiterAm; }
    public Integer getAutoFastVollBei() { return autoFastVollBei; }
    public Integer getAutoPraemieBei() { return autoPraemieBei; }

    public void willkommenGeschickt(Instant jetzt) { this.autoWillkommenAm = jetzt; }
    public void zweiterBesuchGeschickt(Instant jetzt) { this.autoZweiterAm = jetzt; }
    /** Merkt den Praemienstand: nach der naechsten Praemie ist die Karte wieder dran. */
    public void fastVollGeschickt() { this.autoFastVollBei = totalRewards; }
    public void praemieBereitGeschickt() { this.autoPraemieBei = totalRewards; }

    public Integer getStufeBenachrichtigt() { return stufeBenachrichtigt; }
    public void stufeGemeldet(int index) { this.stufeBenachrichtigt = index; }

    // ── 10er-Karte ──────────────────────────────────────────────────────

    /** Nie null: ohne Aufladung ist das Paket leer. */
    public int getPaketRest() { return paketRest != null ? paketRest : 0; }
    public int getPaketeGekauft() { return paketeGekauft != null ? paketeGekauft : 0; }
    public Instant getPaketAufgeladenAm() { return paketAufgeladenAm; }
    public Integer getAutoPaketLeerBei() { return autoPaketLeerBei; }

    /**
     * Neues Paket gekauft: der Rest wird addiert, nicht ersetzt - zwei
     * uebrige Besuche verfallen nicht. Zaehlt als Besuch (der Kunde steht an
     * der Kasse) und bewegt updatedAt, damit der Pass den Stand zeigt.
     */
    public void paketAufladen(int groesse, Instant jetzt) {
        this.paketRest = getPaketRest() + Math.max(1, groesse);
        this.paketeGekauft = getPaketeGekauft() + 1;
        this.paketAufgeladenAm = jetzt;
        this.paketFrisch = true;
        this.updatedAt = jetzt;
        this.lastVisitAt = jetzt;
    }

    /**
     * Besuche abbuchen, hoechstens so viele wie uebrig. Gibt zurueck, wie
     * viele es wurden; 0 bei leerem Paket - dann hat sich nichts bewegt.
     */
    public int paketAbbuchen(int anzahl, Instant jetzt) {
        int abgebucht = Math.min(Math.max(0, anzahl), getPaketRest());
        if (abgebucht == 0) return 0;
        this.paketRest = getPaketRest() - abgebucht;
        this.stamps += abgebucht;
        this.paketFrisch = false;
        this.updatedAt = jetzt;
        this.lastVisitAt = jetzt;
        return abgebucht;
    }

    /** Rest von Hand setzen (Kundenliste). Kein Besuch, kein Paket gekauft. */
    public void korrigierePaketRest(int neu) {
        this.paketRest = Math.max(0, neu);
        this.paketFrisch = false;
        this.updatedAt = Instant.now();
    }

    /** Gerade aufgeladen und seitdem nichts abgebucht - der Pass meldet dann "aufgeladen" statt "abgebucht". */
    public boolean paketFrischAufgeladen() {
        return Boolean.TRUE.equals(paketFrisch);
    }

    /** Merker des "Paket leer"-Automaten: nach dem naechsten Kauf ist die Karte wieder dran. */
    public void paketLeerGeschickt() { this.autoPaketLeerBei = getPaketeGekauft(); }

    // ── Kunde wirbt Kunde ───────────────────────────────────────────────

    public String getEmpfehlungsCode() { return empfehlungsCode; }
    public String getGeworbenVon() { return geworbenVon; }
    public Instant getGeworbenAm() { return geworbenAm; }
    public Instant getEmpfehlungBonusAm() { return empfehlungBonusAm; }

    /** Nur der Code; kein updatedAt, der Pass soll deswegen nicht neu gebaut werden. */
    public void empfehlungsCodeSetzen(String code) { this.empfehlungsCode = code; }

    public void geworbenVon(String werberCardId, Instant jetzt) {
        this.geworbenVon = werberCardId;
        this.geworbenAm = jetzt;
    }

    public void empfehlungBelohnt(Instant jetzt) { this.empfehlungBonusAm = jetzt; }

    /**
     * Bonus-Stempel, gedeckelt bei der Schwelle: eine volle Karte wird nicht
     * "voller". Kein Besuch (lastVisitAt bleibt), aber updatedAt, damit der
     * Pass den neuen Stand ausliefert. Gibt zurueck, wie viele es wurden.
     */
    public int bonusStempel(int anzahl, int schwelle) {
        int vorher = this.stamps;
        this.stamps = Math.min(Math.max(schwelle, vorher), vorher + Math.max(0, anzahl));
        if (this.stamps != vorher) this.updatedAt = Instant.now();
        return this.stamps - vorher;
    }

    /** Bonus-Punkte ohne Besuch: addPoints wuerde lastVisitAt setzen. */
    public void bonusPunkte(long deltaX100) {
        if (deltaX100 <= 0) return;
        this.pointsX100 += deltaX100;
        this.updatedAt = Instant.now();
    }

    /**
     * Was im Nachrichtenfeld des Passes steht: die neuere von Rueckhol
     * (solange sichtbar) und Kampagne. Null = keine Nachricht, Ruhetext.
     */
    public String nachrichtText() {
        Nachricht n = nachricht();
        return n != null ? n.text() : null;
    }

    /**
     * Darf der Pass das changeMessage tragen? Nur solange der Kunde seit der
     * Nachricht nicht da war. Danach bleibt der Text zwar stehen, aber ohne
     * Mitteilung - sonst meldete iOS beim naechsten Stempel die alte Nachricht
     * noch einmal, wenn das Feld von Rueckhol auf Kampagne zurueckfaellt.
     */
    public boolean nachrichtFrisch() {
        Nachricht n = nachricht();
        return n != null && (lastVisitAt == null || n.am().isAfter(lastVisitAt));
    }

    private record Nachricht(String text, Instant am) {}

    private Nachricht nachricht() {
        Nachricht rueckhol = rueckholSichtbar() ? new Nachricht(winbackText, winbackSentAt) : null;
        Nachricht kampagne = (kampagneText != null && kampagneAm != null)
                ? new Nachricht(kampagneText, kampagneAm) : null;
        if (rueckhol == null) return kampagne;
        if (kampagne == null) return rueckhol;
        return rueckhol.am().isAfter(kampagne.am()) ? rueckhol : kampagne;
    }

    public String getKampagneText() { return kampagneText; }
    public Instant getKampagneAm() { return kampagneAm; }

    // ── Anmeldefelder ────────────────────────────────────────────────────

    /**
     * Nur leere Felder fuellen. Das Anmeldeformular ist offen (keine
     * Anmeldung noetig) - wer eine fremde E-Mail eintippt, darf damit nicht
     * die Nummer eines bestehenden Kunden umschreiben. Und wer sich selbst
     * ein zweites Mal anmeldet und das Feld leer laesst, verliert nichts.
     */
    public void anmeldedatenErgaenzen(java.time.LocalDate birthday, String phone) {
        if (this.birthday == null && birthday != null) this.birthday = birthday;
        if (this.phone == null && phone != null) this.phone = phone;
    }

    public java.time.LocalDate getBirthday() { return birthday; }
    public String getPhone() { return phone; }

    // ── Willkommensgeschenk ──────────────────────────────────────────────

    /** Nur bei der Anmeldung einer NEUEN Kundenkarte (CustomerService). */
    public void willkommenVormerken(String text) {
        this.welcomePending = true;
        this.welcomeText = text;
    }

    public boolean isWillkommenOffen() { return Boolean.TRUE.equals(welcomePending); }

    /**
     * Geschenk ausgegeben, Karte frei. Zaehlt als Besuch: die
     * Rueckhol-Nachricht soll ab dem Gratis-Besuch rechnen - genau dann ist
     * der Anstoss zum zweiten Besuch am meisten wert.
     *
     * Wirft statt still nichts zu tun: ein zweites Einloesen heisst ein
     * zweiter Gratis-Artikel ueber den Tresen, das muss das Personal sehen.
     */
    public void willkommenEinloesen(Instant jetzt) {
        if (!isWillkommenOffen()) {
            throw new IllegalArgumentException("Das Willkommensgeschenk wurde schon eingelöst.");
        }
        this.welcomePending = false;
        this.welcomeRedeemedAt = jetzt;
        this.updatedAt = jetzt;
        this.lastVisitAt = jetzt;
    }

    /**
     * Gerade freigeschaltet und noch nichts passiert. Der Pass meldet in
     * diesem Zustand "Karte freigeschaltet" statt "Update!" - mit dem ersten
     * Stempel faellt das von selbst weg.
     */
    public boolean istFrischFreigeschaltet() {
        // lastVisitAt == Einloesezeitpunkt: seitdem kein Besuch. Ohne diese
        // Bedingung meldete eine spaeter per Reset auf 0 gesetzte Karte
        // erneut "freigeschaltet" - resetAll fasst lastVisitAt nicht an.
        return welcomeRedeemedAt != null && welcomeRedeemedAt.equals(lastVisitAt)
                && stamps == 0 && totalRewards == 0;
    }

    public String getWillkommenText() { return welcomeText; }
    public Instant getWillkommenEingeloestAt() { return welcomeRedeemedAt; }

    public String getId() { return id; }
    public Customer getCustomer() { return customer; }
    public Card getCard() { return card; }
    public int getStamps() { return stamps; }
    public int getTotalRewards() { return totalRewards; }
    public long getPointsX100() { return pointsX100; }
    public String getAuthToken() { return authToken; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public boolean isMarketingConsent() { return marketingConsent; }
    public Instant getConsentAt() { return consentAt; }
    public Instant getLastVisitAt() { return lastVisitAt; }
    public Instant getWinbackSentAt() { return winbackSentAt; }
    public String getWinbackText() { return winbackText; }
}