package com.example.stemplekarte.service;

import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.Reward;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * Wer eine Kampagne bekommt. Die Regeln laufen in Java ueber die Grundmenge
 * (CustomerCardRepository#findKampagnenBasis), nicht in JPQL: "Praemie
 * wartet" braucht bei Punktekarten den Katalog, und die Besuchsregel haengt
 * an letzterBesuch(), das schon zwei Spalten zusammenfasst.
 *
 * Kein Geburtstag: den fragen wir bei der Anmeldung nicht ab.
 */
public enum Zielgruppe {
    /** Jede aktive Karte des Ladens. */
    ALLE,
    /** Karte in den letzten 14 Tagen angelegt. */
    NEU,
    /** Geburtstag im laufenden Monat. Nur, wenn der Laden ihn bei der Anmeldung abfragt. */
    GEBURTSTAG,
    /** Geburtstag genau heute. Nur fuer den automatischen Gruss (GeburtstagsService), nicht waehlbar. */
    GEBURTSTAG_HEUTE,
    INAKTIV_30,
    INAKTIV_60,
    INAKTIV_90,
    /** Genau ein Stempel, seit 14 Tagen nicht mehr da. Nur Stempelkarten. */
    EINMAL,
    /** Schon einmal eine Praemie geholt, seit 21 Tagen nicht da. */
    STAMM_RUHIG,
    /** Karte voll beziehungsweise genug Punkte fuer eine Praemie. */
    PRAEMIE_WARTET,
    /** Noch 1 oder 2 Stempel bis zur Praemie. Nur Stempelkarten. */
    FAST_VOLL,
    /** Hoechste Stufe (Gold) einer Karte mit Stufen (service.Stufen). */
    STUFE_TOP,
    /** 10er-Karte: Paket aufgebraucht, schon einmal gekauft. Zeit fuer ein neues. */
    PAKET_LEER,
    /**
     * Bewertungs-Erinnerung faellig: Besuch 2 Stunden bis 3 Tage her, genug
     * Besuche (Shop#getBewertungErinnerungAb), noch nie erinnert. Nur fuer
     * den Automaten (BewertungsErinnerungService), nicht waehlbar.
     */
    BEWERTUNG,
    /**
     * Automatische Nachrichten (AutoNachrichtenService, AutoNachrichtTyp),
     * nicht waehlbar. Die Regeln stehen in {@link #autoFaellig}.
     */
    AUTO_WILLKOMMEN, AUTO_ZWEITER_BESUCH, AUTO_FAST_VOLL, AUTO_PRAEMIE, AUTO_VIP, AUTO_STUFE, AUTO_PAKET_LEER,
    /** Tagesangebot (TagesangebotService): wie ALLE, aber im Verlauf als eigene Gruppe. Nicht waehlbar. */
    TAGESANGEBOT,
    /**
     * Eigene Automation (AutomationService): die Empfaenger bestimmt der
     * Automat selbst und uebergibt sie fertig (KampagnenService#starteFuer).
     * passt() ist deshalb immer false. Nicht waehlbar.
     */
    AUTOMATION;

    private static final java.time.ZoneId ZONE = java.time.ZoneId.of("Europe/Berlin");
    static final int NEU_TAGE = 14;
    static final int EINMAL_TAGE = 14;
    static final int STAMM_RUHIG_TAGE = 21;
    static final int BEWERTUNG_MIN_STUNDEN = 2;
    static final int BEWERTUNG_MAX_TAGE = 3;
    /** Automaten: "am Tag danach" = 20 Stunden bis 3 Tage nach dem Besuch. */
    static final int AUTO_MIN_STUNDEN = 20;
    static final int AUTO_MAX_TAGE = 3;
    /** Fast voll / Praemie bereit: erst, wenn der Kunde ein paar Tage nicht zurueck war. */
    static final int AUTO_ERINNERUNG_AB_TAGEN = 2;
    static final int AUTO_FAST_VOLL_BIS_TAGE = 10;
    static final int AUTO_PRAEMIE_BIS_TAGE = 14;

    /** Im Dashboard anbietbar; GEBURTSTAG_HEUTE, BEWERTUNG, AUTO_* und TAGESANGEBOT gehoeren den Automaten. */
    public boolean waehlbar() {
        return this != GEBURTSTAG_HEUTE && this != BEWERTUNG && this != TAGESANGEBOT && this != AUTOMATION
                && !name().startsWith("AUTO_");
    }

    public static List<Zielgruppe> waehlbare() {
        return java.util.Arrays.stream(values()).filter(Zielgruppe::waehlbar).toList();
    }

    /** Leer = ALLE. Unbekannt = IllegalArgumentException (400). */
    public static Zielgruppe von(String key) {
        if (key == null || key.isBlank()) return ALLE;
        try {
            return valueOf(key.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unbekannte Zielgruppe: " + key);
        }
    }

    /**
     * jetzt kommt von aussen, damit der Test eine Pause simulieren kann,
     * ohne an lastVisitAt zu drehen. katalog liefert die aktiven Praemien
     * einer Punktekarte - der Aufrufer darf das zwischenspeichern.
     */
    public boolean passt(CustomerCard cc, Instant jetzt, Function<Card, List<Reward>> katalog) {
        Card card = cc.getCard();
        return switch (this) {
            case ALLE, TAGESANGEBOT -> true;
            case AUTOMATION -> false;
            case NEU -> cc.getCreatedAt() != null
                    && cc.getCreatedAt().isAfter(jetzt.minus(Duration.ofDays(NEU_TAGE)));
            case GEBURTSTAG -> {
                // Von der Karte, nicht vom Kunden: nur, was er DIESEM Laden gesagt hat.
                java.time.LocalDate g = cc.getBirthday();
                yield g != null && g.getMonth() == jetzt.atZone(ZONE).getMonth();
            }
            case GEBURTSTAG_HEUTE -> {
                java.time.LocalDate g = cc.getBirthday();
                java.time.LocalDate heute = jetzt.atZone(ZONE).toLocalDate();
                // 29. Februar: in Nicht-Schaltjahren am 28. gratulieren.
                yield g != null && g.getMonth() == heute.getMonth()
                        && (g.getDayOfMonth() == heute.getDayOfMonth()
                            || (g.getDayOfMonth() == 29 && heute.getDayOfMonth() == 28 && !heute.isLeapYear()));
            }
            case INAKTIV_30 -> pauseSeit(cc, jetzt, 30);
            case INAKTIV_60 -> pauseSeit(cc, jetzt, 60);
            case INAKTIV_90 -> pauseSeit(cc, jetzt, 90);
            case EINMAL -> !card.isPoints() && cc.getTotalRewards() == 0 && cc.getStamps() == 1
                    && pauseSeit(cc, jetzt, EINMAL_TAGE);
            case STAMM_RUHIG -> cc.getTotalRewards() >= 1 && pauseSeit(cc, jetzt, STAMM_RUHIG_TAGE);
            case PRAEMIE_WARTET -> praemieWartet(card, cc, katalog);
            case FAST_VOLL -> {
                if (!card.isStamp()) yield false;
                int offen = card.getRewardThreshold() - cc.getStamps();
                yield offen >= 1 && offen <= 2;
            }
            case STUFE_TOP -> {
                Stufen.Stufe s = Stufen.von(card, cc);
                yield s != null && s.hoechste();
            }
            case PAKET_LEER -> paketLeer(card, cc);
            case BEWERTUNG -> bewertungFaellig(card, cc, jetzt);
            case AUTO_WILLKOMMEN, AUTO_ZWEITER_BESUCH, AUTO_FAST_VOLL, AUTO_PRAEMIE, AUTO_VIP, AUTO_STUFE,
                 AUTO_PAKET_LEER ->
                    autoFaellig(card, cc, jetzt, katalog);
        };
    }

    /** Nur 10er-Karten, die schon einmal aufgeladen waren - eine nie gekaufte ist nicht "aufgebraucht". */
    private static boolean paketLeer(Card card, CustomerCard cc) {
        return card.isPackage() && cc.getPaketeGekauft() >= 1 && cc.getPaketRest() <= 0;
    }

    /**
     * Regeln der Automaten. Willkommen und zweiter Besuch kommen am Tag
     * danach; fast voll und Praemie bereit erst, wenn der Kunde seit ein
     * paar Tagen nicht zurueck war - direkt nach dem Stempel hat der
     * Stempel-Push das schon gesagt. VIP braucht keinen Merker, der Laeufer
     * startet die Kampagne nur einmal im Monat.
     */
    private boolean autoFaellig(Card card, CustomerCard cc, Instant jetzt, Function<Card, List<Reward>> katalog) {
        Instant besuch = cc.letzterBesuch();
        if (besuch == null) return false;
        return switch (this) {
            case AUTO_WILLKOMMEN -> cc.getAutoWillkommenAm() == null
                    && cc.getTotalRewards() == 0
                    && cc.getCreatedAt() != null && cc.getCreatedAt().isAfter(jetzt.minus(Duration.ofDays(NEU_TAGE)))
                    && (card.isPoints() ? cc.getPointsX100() > 0 : cc.getStamps() >= 1)
                    && tagDanach(besuch, jetzt);
            case AUTO_ZWEITER_BESUCH -> cc.getAutoZweiterAm() == null
                    && !card.isPoints() && cc.getTotalRewards() == 0 && cc.getStamps() >= 2
                    // Der Besuch lag deutlich nach der Anlage: er war wirklich wieder da.
                    && cc.getCreatedAt() != null && besuch.isAfter(cc.getCreatedAt().plus(Duration.ofHours(AUTO_MIN_STUNDEN)))
                    && tagDanach(besuch, jetzt);
            case AUTO_FAST_VOLL -> {
                if (!card.isStamp() || rundeSchonErinnert(cc.getAutoFastVollBei(), cc)) yield false;
                int offen = card.getRewardThreshold() - cc.getStamps();
                yield offen >= 1 && offen <= 2 && pauseZwischen(besuch, jetzt, AUTO_ERINNERUNG_AB_TAGEN, AUTO_FAST_VOLL_BIS_TAGE);
            }
            case AUTO_PRAEMIE -> !rundeSchonErinnert(cc.getAutoPraemieBei(), cc)
                    && praemieWartet(card, cc, katalog)
                    && pauseZwischen(besuch, jetzt, AUTO_ERINNERUNG_AB_TAGEN, AUTO_PRAEMIE_BIS_TAGE);
            case AUTO_VIP -> {
                Kundengruppe.Regeln regeln = card.getShop() != null
                        ? Kundengruppe.Regeln.von(card.getShop()) : Kundengruppe.Regeln.STANDARD;
                if (besuch.isBefore(jetzt.minus(Duration.ofDays(regeln.ruhigTage())))) yield false;
                yield card.isPoints()
                        ? cc.getTotalRewards() >= 2
                        : Kundengruppe.besucheStempelkarte(card, cc) >= 2 * regeln.stammAb();
            }
            case AUTO_STUFE -> {
                // Am Tag nach dem Besuch, mit dem Stufe 2 oder 3 erreicht
                // wurde - einmal je Stufe (Merker = hoechste gemeldete).
                Stufen.Stufe s = Stufen.von(card, cc);
                if (s == null || s.index() < 2) yield false;
                int gemeldet = cc.getStufeBenachrichtigt() != null ? cc.getStufeBenachrichtigt() : 1;
                yield s.index() > gemeldet && tagDanach(besuch, jetzt);
            }
            // Wie "Praemie bereit": ein paar Tage nach dem letzten Besuch, einmal
            // je Paket (Merker = Anzahl gekaufter Pakete beim Versand).
            case AUTO_PAKET_LEER -> paketLeer(card, cc)
                    && !(cc.getAutoPaketLeerBei() != null && cc.getAutoPaketLeerBei() == cc.getPaketeGekauft())
                    && pauseZwischen(besuch, jetzt, AUTO_ERINNERUNG_AB_TAGEN, AUTO_PRAEMIE_BIS_TAGE);
            default -> false;
        };
    }

    private static boolean tagDanach(Instant besuch, Instant jetzt) {
        return !besuch.isAfter(jetzt.minus(Duration.ofHours(AUTO_MIN_STUNDEN)))
                && !besuch.isBefore(jetzt.minus(Duration.ofDays(AUTO_MAX_TAGE)));
    }

    private static boolean pauseZwischen(Instant besuch, Instant jetzt, int abTagen, int bisTage) {
        return !besuch.isAfter(jetzt.minus(Duration.ofDays(abTagen)))
                && !besuch.isBefore(jetzt.minus(Duration.ofDays(bisTage)));
    }

    /** Merker traegt den Praemienstand von damals: gleicher Stand = diese Runde schon erinnert. */
    private static boolean rundeSchonErinnert(Integer bei, CustomerCard cc) {
        return bei != null && bei == cc.getTotalRewards();
    }

    /**
     * Nicht sofort nach dem Stempel (der Push dafuer ist gerade erst
     * gekommen), aber auch nicht Wochen spaeter: wer beim Einschalten lange
     * nicht da war, bekommt die Erinnerung nach seinem naechsten Besuch.
     */
    private static boolean bewertungFaellig(Card card, CustomerCard cc, Instant jetzt) {
        if (cc.getBewertungErinnertAm() != null) return false;
        int ab = card.getShop() != null ? card.getShop().getBewertungErinnerungAb() : 0;
        if (ab <= 0) return false;
        Instant besuch = cc.letzterBesuch();
        if (besuch == null
                || besuch.isAfter(jetzt.minus(Duration.ofHours(BEWERTUNG_MIN_STUNDEN)))
                || besuch.isBefore(jetzt.minus(Duration.ofDays(BEWERTUNG_MAX_TAGE)))) return false;
        // Punktekarten zaehlen keine Besuche: wer schon eine Praemie geholt
        // hat, kommt sicher wieder. Stempelkarten: alle je gesammelten Stempel.
        if (card.isPoints()) return cc.getTotalRewards() >= 1;
        return cc.getStamps() + cc.getTotalRewards() * card.getRewardThreshold() >= ab;
    }

    private static boolean pauseSeit(CustomerCard cc, Instant jetzt, int tage) {
        Instant besuch = cc.letzterBesuch();
        return besuch != null && besuch.isBefore(jetzt.minus(Duration.ofDays(tage)));
    }

    private static boolean praemieWartet(Card card, CustomerCard cc, Function<Card, List<Reward>> katalog) {
        if (card.isMember() || card.isPackage()) return false;
        if (!card.isPoints()) return cc.getStamps() >= card.getRewardThreshold();
        Reward ziel = RewardService.naechstesZiel(katalog.apply(card), cc.getPointsX100());
        return ziel != null && cc.getPointsX100() >= ziel.getCostPointsX100();
    }
}
