package com.example.stemplekarte.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "cards")
public class Card {

    @Id
    @Column(length = 64)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shop_id", nullable = false)
    private Shop shop;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String description;

    @Column(name = "reward_threshold", nullable = false)
    private int rewardThreshold;

    @Column(name = "reward_text", nullable = false)
    private String rewardText;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    // ── Farben & Bilder (pro Karte) ───────────────────────────────────────
    @Column(name = "color_background", length = 32)
    private String colorBackground;

    @Column(name = "color_foreground", length = 32)
    private String colorForeground;

    @Column(name = "color_label", length = 32)
    private String colorLabel;

    @Column(name = "logo_url")
    private String logoUrl;

    @Column(name = "hero_image_url")
    private String heroImageUrl;

    // Unbeschnittene Ausgangsbilder - fuer spaeteres erneutes Zuschneiden.
    @Column(name = "logo_original_url")
    private String logoOriginalUrl;

    @Column(name = "hero_original_url")
    private String heroOriginalUrl;

    // Eigenes Bild fuer den Apple-Streifen. Bewusst getrennt vom Banner:
    // der Streifen ist 2,6:1 und traegt Text, das Banner ist breiter und
    // steht frei auf der Landingpage.
    @Column(name = "strip_image_url")
    private String stripImageUrl;

    @Column(name = "strip_original_url")
    private String stripOriginalUrl;

    // ── Stempel-Design (pro Karte) ────────────────────────────────────────
    @Column(name = "wallet_style", length = 16)
    private String walletStyle;

    @Column(name = "stamp_icon_type", length = 16)
    private String stampIconType;

    @Column(name = "stamp_preset", length = 32)
    private String stampPreset;

    @Column(name = "stamp_icon_url")
    private String stampIconUrl;

    @Column(name = "stamp_icon_original_url")
    private String stampIconOriginalUrl;

    @Column(name = "stamp_color", length = 32)
    private String stampColor;

    @Column(name = "empty_stamp_style", length = 16)
    private String emptyStampStyle;

    // ── Kartentyp und Punkte-Einstellungen ────────────────────────────────
    // type kommt per ddl-auto dazu und ist bei allen bestehenden Zeilen
    // zunaechst NULL. Der Getter faengt das ab, damit vorhandene Karten
    // sicher Stempelkarten bleiben.
    @Enumerated(EnumType.STRING)
    @Column(name = "type", length = 16, columnDefinition = "varchar(16) default 'STAMP'")
    private CardType type;

    @Column(name = "points_per_euro_x100")
    private Integer pointsPerEuroX100;

    @Enumerated(EnumType.STRING)
    @Column(name = "points_rounding", length = 16)
    private PointsRounding pointsRounding;

    // ── Willkommensgeschenk ("Erster Kaffee geht auf uns") ─────────────────
    // An: wer sich NEU fuer diese Karte anmeldet, bekommt eine gesperrte
    // Karte, die erst beim Gratis-Artikel an der Kasse freigeschaltet wird.
    // Nullable -> alle bestehenden Karten bleiben aus.
    @Column(name = "welcome_enabled")
    private Boolean welcomeEnabled;

    @Column(name = "welcome_text", length = WILLKOMMEN_MAX)
    private String welcomeText;

    // Aussehen der gesperrten Karte. Live gelesen beim Pass-Bau (nicht pro
    // Kunde eingefroren wie der Geschenktext), damit eine Aenderung auch bei
    // schon gesperrten Kunden ankommt. Alles nullable: leer = Standard.
    @Column(name = "welcome_title", length = WILLKOMMEN_TITEL_MAX)
    private String welcomeTitle;

    @Column(name = "welcome_hint", length = WILLKOMMEN_HINWEIS_MAX)
    private String welcomeHint;

    @Column(name = "welcome_image_url", length = 500)
    private String welcomeImageUrl;

    // Nur Apple: Google kennt Farben nur an der Klasse, und die teilen sich
    // alle Karten eines Ladens.
    @Column(name = "welcome_color_bg", length = 7)
    private String welcomeColorBackground;

    @Column(name = "welcome_color_fg", length = 7)
    private String welcomeColorForeground;

    @Column(name = "welcome_color_label", length = 7)
    private String welcomeColorLabel;

    // Welche Texte die gesperrte Karte zeigt. Nullable = aus: ohne Haken
    // zeigt die Karte nur Bild, Logo und Namen.
    @Column(name = "welcome_show_title")
    private Boolean welcomeShowTitle;

    @Column(name = "welcome_show_gift")
    private Boolean welcomeShowGift;

    @Column(name = "welcome_show_hint")
    private Boolean welcomeShowHint;

    // ── Kunde wirbt Kunde ───────────────────────────────────────────────────
    // An: jede Kundenkarte traegt Code und Link; wer darueber kommt und zum
    // ersten Mal da ist, bringt beiden den Bonus (Stempel bzw. Punkte).
    // Nullable -> alle bestehenden Karten bleiben aus.
    @Column(name = "empfehlung_aktiv")
    private Boolean empfehlungAktiv;

    @Column(name = "empfehlung_bonus")
    private Integer empfehlungBonus;

    public static final int EMPFEHLUNG_BONUS_STANDARD = 1;
    public static final int EMPFEHLUNG_BONUS_MAX = 10;

    // ── Stufen (Bronze, Silber, Gold nach Besuchen) ────────────────────────
    // Nur Stempelkarten. Stufe 1 ab 0 Besuchen, Stufe 2 ab stufe2Ab, Stufe 3
    // ab stufe3Ab. Namen leer = Standard. Nullable -> bestehende Karten aus.
    @Column(name = "stufen_aktiv")
    private Boolean stufenAktiv;

    @Column(name = "stufe1_name", length = 20)
    private String stufe1Name;

    @Column(name = "stufe2_name", length = 20)
    private String stufe2Name;

    @Column(name = "stufe2_ab")
    private Integer stufe2Ab;

    @Column(name = "stufe3_name", length = 20)
    private String stufe3Name;

    @Column(name = "stufe3_ab")
    private Integer stufe3Ab;

    public static final String[] STUFEN_STANDARD = {"Bronze", "Silber", "Gold"};
    public static final int STUFE2_AB_STANDARD = 10;
    public static final int STUFE3_AB_STANDARD = 25;

    // ── 10er-Karte ──────────────────────────────────────────────────────────
    // Was ein Besuch heisst, in der Mehrzahl ("Kurse", "Wäschen"). Nullable:
    // leer = "Besuche". Die Paketgroesse steckt in reward_threshold.
    @Column(name = "paket_einheit", length = PAKET_EINHEIT_MAX)
    private String paketEinheit;

    public static final String PAKET_EINHEIT_STANDARD = "Besuche";
    public static final int PAKET_EINHEIT_MAX = 20;
    public static final int PAKET_MAX = 100;

    public static final String WILLKOMMEN_STANDARD = "Erster Kaffee geht auf uns";
    public static final int WILLKOMMEN_MAX = 60;
    public static final String WILLKOMMEN_TITEL_STANDARD = "Willkommen";
    public static final String WILLKOMMEN_HINWEIS_STANDARD = "An der Kasse vorzeigen";
    // Der Titel steht rechts oben im Kopf - viel Platz ist da nicht.
    public static final int WILLKOMMEN_TITEL_MAX = 18;
    public static final int WILLKOMMEN_HINWEIS_MAX = 40;

    protected Card() {}

    public static Card create(Shop shop, String name, String description,
                              int rewardThreshold, String rewardText) {
        Card c = new Card();
        c.id = "CARD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        c.shop = shop;
        c.name = name;
        c.description = description;
        c.rewardThreshold = rewardThreshold;
        c.rewardText = rewardText;
        c.active = true;
        c.createdAt = Instant.now();
        // Farb-Defaults vom Shop übernehmen
        c.colorBackground = shop.getColorBackground() != null ? shop.getColorBackground() : "#3C3489";
        c.colorForeground = shop.getColorForeground() != null ? shop.getColorForeground() : "#FFFFFF";
        c.colorLabel = shop.getColorLabel() != null ? shop.getColorLabel() : "#FAC875";
        c.logoUrl = shop.getLogoUrl();
        c.heroImageUrl = shop.getHeroImageUrl();
        // Stempel-Defaults
        c.walletStyle = "number";
        c.stampIconType = "preset";
        c.stampPreset = "coffee";
        c.stampColor = "#6F4E37";
        c.emptyStampStyle = "number";
        c.stampIconUrl = null;
        return c;
    }

    /**
     * Punktekarte. reward_threshold und reward_text sind NOT NULL und
     * bleiben es - eine bestehende Spalte nachtraeglich nullable zu machen
     * schafft ddl-auto nicht zuverlaessig. Deshalb stehen sie hier auf
     * unauffaelligen Werten und werden nirgends angezeigt.
     *
     * Die Stempel-Design-Felder bleiben auf ihren Defaults stehen, damit
     * kein Zweig auf null laeuft - gezeichnet wird bei Punkten nichts davon.
     */
    public static Card createPoints(Shop shop, String name, String description,
                                    int pointsPerEuroX100, PointsRounding rounding) {
        Card c = create(shop, name, description, 1, "");
        c.type = CardType.POINTS;
        c.pointsPerEuroX100 = pointsPerEuroX100;
        c.pointsRounding = rounding;
        return c;
    }

    /**
     * Mitgliedskarte: kein Stempel, keine Praemie. Schwelle 1 und leerer
     * Praemientext wie bei der Punktekarte, damit alte Rechenwege nicht
     * durch 0 teilen; die Zweige fuer Mitglieder fragen isMember() ab.
     */
    public static Card createMember(Shop shop, String name, String description) {
        Card c = create(shop, name, description, 1, "");
        c.type = CardType.MEMBER;
        return c;
    }

    /**
     * 10er-Karte: groesse Besuche je Paket (reward_threshold), kein
     * Praemientext. Bereich prueft der CardService.
     */
    public static Card createPackage(Shop shop, String name, String description,
                                     int groesse, String einheit) {
        Card c = create(shop, name, description, groesse, "");
        c.type = CardType.PACKAGE;
        c.paketEinheit = einheitSauber(einheit);
        return c;
    }

    /** Nie null: ohne eigene Einheit gilt "Besuche". */
    public String getPaketEinheit() {
        return paketEinheit != null ? paketEinheit : PAKET_EINHEIT_STANDARD;
    }

    /** "10 Kurse" - Groesse und Einheit, wie sie auf der Karte stehen. */
    public String paketText() {
        return rewardThreshold + " " + getPaketEinheit();
    }

    /** Wirkt nur auf kuenftige Aufladungen; Bereich prueft der Aufrufer. */
    public void updatePaket(int groesse, String einheit) {
        this.rewardThreshold = groesse;
        this.paketEinheit = einheitSauber(einheit);
    }

    private static String einheitSauber(String einheit) {
        if (einheit == null || einheit.isBlank()) return null;
        String s = einheit.trim();
        return s.length() > PAKET_EINHEIT_MAX ? s.substring(0, PAKET_EINHEIT_MAX) : s;
    }

    /** Was nicht mitgeschickt wird, bleibt stehen - wie updateDesign. */
    public void updatePointsSettings(Integer pointsPerEuroX100, PointsRounding rounding) {
        if (pointsPerEuroX100 != null) this.pointsPerEuroX100 = pointsPerEuroX100;
        if (rounding != null) this.pointsRounding = rounding;
    }

    public void setActive(boolean active) { this.active = active; }

    /**
     * Nur fuer Stempelkarten. Punkte- und Mitgliedskarte haben keinen
     * gesperrten Zustand - selbst wenn die Spalte dort gesetzt waere,
     * greift sie nicht.
     */
    public boolean isWillkommenAktiv() {
        return Boolean.TRUE.equals(welcomeEnabled) && isStamp();
    }

    /** Nie null: ohne eigenen Text gilt der Standard. */
    public String getWillkommenText() {
        return (welcomeText == null || welcomeText.isBlank()) ? WILLKOMMEN_STANDARD : welcomeText;
    }

    /** Laenge prueft der Controller - hier wird nur getrimmt. */
    public void updateWillkommen(boolean enabled, String text) {
        this.welcomeEnabled = enabled;
        this.welcomeText = (text == null || text.isBlank()) ? null : text.trim();
    }

    // ── Kunde wirbt Kunde ───────────────────────────────────────────────────

    public boolean isEmpfehlungAktiv() { return Boolean.TRUE.equals(empfehlungAktiv); }

    /** Stempel bei Stempelkarten, ganze Punkte bei Punktekarten. Nie unter 1. */
    public int getEmpfehlungBonus() {
        return empfehlungBonus != null && empfehlungBonus > 0 ? empfehlungBonus : EMPFEHLUNG_BONUS_STANDARD;
    }

    /** Bereich prueft der Aufrufer (EmpfehlungService). */
    public void updateEmpfehlung(boolean aktiv, int bonus) {
        this.empfehlungAktiv = aktiv;
        this.empfehlungBonus = bonus;
    }

    // ── Stufen ──────────────────────────────────────────────────────────────

    /** Nur Stempelkarten: Punktekarten zaehlen keine Besuche. */
    public boolean isStufenAktiv() { return Boolean.TRUE.equals(stufenAktiv) && !isPoints(); }

    /** Drei Namen, nie leer. */
    public String[] getStufenNamen() {
        return new String[]{
                stufe1Name != null ? stufe1Name : STUFEN_STANDARD[0],
                stufe2Name != null ? stufe2Name : STUFEN_STANDARD[1],
                stufe3Name != null ? stufe3Name : STUFEN_STANDARD[2]};
    }

    public int getStufe2Ab() { return stufe2Ab != null && stufe2Ab > 0 ? stufe2Ab : STUFE2_AB_STANDARD; }

    /** Immer ueber Stufe 2, auch wenn die Spalte Unsinn traegt. */
    public int getStufe3Ab() {
        int ab3 = stufe3Ab != null && stufe3Ab > 0 ? stufe3Ab : STUFE3_AB_STANDARD;
        return Math.max(ab3, getStufe2Ab() + 1);
    }

    /** Geprueft und bereinigt vom Aufrufer (service.Stufen#pruefe); null-Name = Standard. */
    public void updateStufen(boolean aktiv, String name1, String name2, int ab2, String name3, int ab3) {
        this.stufenAktiv = aktiv;
        this.stufe1Name = name1;
        this.stufe2Name = name2;
        this.stufe2Ab = ab2;
        this.stufe3Name = name3;
        this.stufe3Ab = ab3;
    }

    /** Nie null: ohne eigenen Titel gilt "Willkommen". */
    public String getWillkommenTitel() {
        return welcomeTitle != null ? welcomeTitle : WILLKOMMEN_TITEL_STANDARD;
    }

    /** Nie null: ohne eigenen Hinweis gilt "An der Kasse vorzeigen". */
    public String getWillkommenHinweis() {
        return welcomeHint != null ? welcomeHint : WILLKOMMEN_HINWEIS_STANDARD;
    }

    /**
     * null = unveraendert (aelterer Client schickt das Feld nicht),
     * leer = zuruecksetzen auf Standard. Laenge und Format prueft der Controller.
     */
    public void updateWillkommenDesign(String titel, String hinweis, String bildUrl,
                                       String bg, String fg, String label) {
        if (titel != null) this.welcomeTitle = leerZuNull(titel);
        if (hinweis != null) this.welcomeHint = leerZuNull(hinweis);
        if (bildUrl != null) this.welcomeImageUrl = leerZuNull(bildUrl);
        if (bg != null) this.welcomeColorBackground = leerZuNull(bg);
        if (fg != null) this.welcomeColorForeground = leerZuNull(fg);
        if (label != null) this.welcomeColorLabel = leerZuNull(label);
    }

    /** null = unveraendert (aelterer Client schickt das Feld nicht). */
    public void updateWillkommenSichtbar(Boolean titel, Boolean geschenk, Boolean hinweis) {
        if (titel != null) this.welcomeShowTitle = titel;
        if (geschenk != null) this.welcomeShowGift = geschenk;
        if (hinweis != null) this.welcomeShowHint = hinweis;
    }

    public boolean zeigtWillkommenTitel() { return Boolean.TRUE.equals(welcomeShowTitle); }
    public boolean zeigtWillkommenGeschenk() { return Boolean.TRUE.equals(welcomeShowGift); }
    public boolean zeigtWillkommenHinweis() { return Boolean.TRUE.equals(welcomeShowHint); }

    private static String leerZuNull(String s) {
        return s.isBlank() ? null : s.trim();
    }

    public String getWelcomeTitle() { return welcomeTitle; }
    public String getWelcomeHint() { return welcomeHint; }
    public String getWelcomeImageUrl() { return welcomeImageUrl; }
    public String getWelcomeColorBackground() { return welcomeColorBackground; }
    public String getWelcomeColorForeground() { return welcomeColorForeground; }
    public String getWelcomeColorLabel() { return welcomeColorLabel; }

    public void updateDesign(String walletStyle, String stampIconType, String stampPreset,
                             String stampColor, String emptyStampStyle) {
        if (walletStyle != null) this.walletStyle = walletStyle;
        if (stampIconType != null) this.stampIconType = stampIconType;
        if (stampPreset != null) this.stampPreset = stampPreset;
        if (stampColor != null) this.stampColor = stampColor;
        if (emptyStampStyle != null) this.emptyStampStyle = emptyStampStyle;
    }

    public void updateColors(String colorBackground, String colorForeground, String colorLabel) {
        if (colorBackground != null) this.colorBackground = colorBackground;
        if (colorForeground != null) this.colorForeground = colorForeground;
        if (colorLabel != null) this.colorLabel = colorLabel;
    }

    public void setName(String name) { this.name = name; }
    public void setRewardText(String rewardText) { this.rewardText = rewardText; }
    public void setRewardThreshold(int rewardThreshold) { this.rewardThreshold = rewardThreshold; }
    public void setLogoUrl(String logoUrl) { this.logoUrl = logoUrl; }
    public void setHeroImageUrl(String heroImageUrl) { this.heroImageUrl = heroImageUrl; }
    public void setStampIconUrl(String stampIconUrl) { this.stampIconUrl = stampIconUrl; }
    public void setLogoOriginalUrl(String v) { this.logoOriginalUrl = v; }
    public void setHeroOriginalUrl(String v) { this.heroOriginalUrl = v; }
    public void setStripImageUrl(String v) { this.stripImageUrl = v; }
    public void setStripOriginalUrl(String v) { this.stripOriginalUrl = v; }
    public void setStampIconOriginalUrl(String v) { this.stampIconOriginalUrl = v; }

    public String getId() { return id; }
    public Shop getShop() { return shop; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public int getRewardThreshold() { return rewardThreshold; }
    public String getRewardText() { return rewardText; }
    public boolean isActive() { return active; }
    public Instant getCreatedAt() { return createdAt; }
    public String getColorBackground() { return colorBackground != null ? colorBackground : "#3C3489"; }
    public String getColorForeground() { return colorForeground != null ? colorForeground : "#FFFFFF"; }
    public String getColorLabel() { return colorLabel != null ? colorLabel : "#FAC875"; }
    public String getLogoUrl() { return logoUrl; }
    public String getHeroImageUrl() { return heroImageUrl; }
    public String getLogoOriginalUrl() { return logoOriginalUrl; }
    public String getHeroOriginalUrl() { return heroOriginalUrl; }
    public String getStripImageUrl() { return stripImageUrl; }
    public String getStripOriginalUrl() { return stripOriginalUrl; }
    public String getStampIconOriginalUrl() { return stampIconOriginalUrl; }
    public String getWalletStyle() { return walletStyle != null ? walletStyle : "number"; }
    public String getStampIconType() { return stampIconType != null ? stampIconType : "preset"; }
    public String getStampPreset() { return stampPreset != null ? stampPreset : "coffee"; }
    public String getStampIconUrl() { return stampIconUrl; }
    public String getStampColor() { return stampColor != null ? stampColor : "#6F4E37"; }
    public String getEmptyStampStyle() { return emptyStampStyle != null ? emptyStampStyle : "number"; }

    /** Nie null: bestehende Zeilen tragen NULL und sind Stempelkarten. */
    public CardType getType() { return type != null ? type : CardType.STAMP; }
    public boolean isPoints() { return getType() == CardType.POINTS; }
    public boolean isStamp() { return getType() == CardType.STAMP; }
    /** Mitgliedskarte: kein Stempel, keine Praemie, "stamps" zaehlt Besuche. */
    public boolean isMember() { return getType() == CardType.MEMBER; }
    /** 10er-Karte: Besuche werden vom Paket abgebucht, "stamps" zaehlt sie hoch. */
    public boolean isPackage() { return getType() == CardType.PACKAGE; }
    public Integer getPointsPerEuroX100() { return pointsPerEuroX100; }
    public PointsRounding getPointsRounding() {
        return pointsRounding != null ? pointsRounding : PointsRounding.GENAU;
    }
}