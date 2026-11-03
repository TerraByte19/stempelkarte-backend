package com.example.stemplekarte.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "shops")
public class Shop {

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(nullable = false)
    private String name;

    @Column(name = "logo_url")
    private String logoUrl;

    @Column(name = "hero_image_url")
    private String heroImageUrl;

    // Unbeschnittene Ausgangsbilder - fuer spaeteres erneutes Zuschneiden.
    @Column(name = "logo_original_url")
    private String logoOriginalUrl;

    @Column(name = "hero_original_url")
    private String heroOriginalUrl;

    @Column(name = "stamp_icon_original_url")
    private String stampIconOriginalUrl;

    @Column(name = "color_background", length = 32)
    private String colorBackground;

    @Column(name = "color_foreground", length = 32)
    private String colorForeground;

    @Column(name = "color_label", length = 32)
    private String colorLabel;

    // --- Karten-Design ---
    @Column(name = "wallet_style", length = 16)
    private String walletStyle;        // "grid" oder "number"

    @Column(name = "stamp_icon_type", length = 16)
    private String stampIconType;      // "preset" oder "upload"

    @Column(name = "stamp_preset", length = 32)
    private String stampPreset;        // "coffee","star","heart","dot","square"

    @Column(name = "stamp_icon_url")
    private String stampIconUrl;       // bei eigenem Bild

    @Column(name = "stamp_color", length = 32)
    private String stampColor;

    @Column(name = "empty_stamp_style", length = 16)
    private String emptyStampStyle;    // "number" oder "faded"

    @Column(nullable = false)
    private boolean active;

    @Column(name = "max_tokens", nullable = false, columnDefinition = "integer default 3")
    private int maxTokens = 3;

    // Reihenfolge im Admin-Panel, per Drag and Drop gesetzt. Ohne das kam
    // die Liste in der Reihenfolge der Datenbank - also praktisch zufaellig
    // und bei jedem Aufruf womoeglich anders.
    @Column(name = "sort_order", nullable = false, columnDefinition = "integer default 0")
    private int sortOrder = 0;

    // Sprache des Ladens: "de" oder "ar". null/leer = "de" (alle Bestandslaeden).
    // Steuert Kundenkarte, Anmeldeseite, Bestaetigungsseiten und E-Mails.
    // Nullable -> ddl-auto:update braucht keinen DB-Reset.
    @Column(name = "language", length = 5)
    private String language;

    // ── Sperrbildschirm-Erinnerung ────────────────────────────────────────
    // Ist die Karte voll, soll sie sich in Ladennaehe von selbst auf dem
    // Sperrbildschirm melden. Technisch: "locations" im Apple-Pass. Das Handy
    // erledigt den Rest, wir schicken keine Benachrichtigung.
    //
    // Pro Laden abschaltbar, weil es ohne Koordinaten nicht geht und nicht
    // jeder Laden das will. Alle drei Felder nullable -> ddl-auto:update
    // braucht keinen DB-Reset, Bestandslaeden bleiben unberuehrt (= aus).
    @Column(name = "lock_screen_enabled")
    private Boolean lockScreenEnabled;

    @Column(name = "latitude")
    private Double latitude;

    @Column(name = "longitude")
    private Double longitude;

    // Eigener Text fuer die Sperrbildschirm-Erinnerung. Zwei Faelle, weil die
    // Karte in beiden Zustaenden in Ladennaehe auftaucht: volle Karte und noch
    // am Sammeln. Leer/null = Standardtext (siehe ApplePassService).
    // Platzhalter: {belohnung} = Belohnungstext, {stempel} = fehlende Stempel.
    @Column(name = "lock_screen_text_full", length = 120)
    private String lockScreenTextFull;

    @Column(name = "lock_screen_text_progress", length = 120)
    private String lockScreenTextProgress;

    // Ab welcher Naehe iOS die Karte auf dem Sperrbildschirm anbietet, in
    // Metern (ApplePassService.ERLAUBTE_RADIEN). Null = Standard.
    @Column(name = "lock_screen_radius_meter")
    private Integer lockScreenRadiusMeter;

    // Rueckhol-Nachricht: wer so viele Tage nicht da war, bekommt eine
    // Wallet-Nachricht (RueckholService). Nullable -> Bestandslaeden = aus.
    @Column(name = "winback_enabled")
    private Boolean winbackEnabled;

    // Seit wann an. Nur Paesse, die danach ausgeliefert wurden, tragen das
    // Nachrichtenfeld - aeltere bekommen es erst still nachgereicht.
    @Column(name = "winback_enabled_at")
    private Instant winbackEnabledAt;

    @Column(name = "winback_days")
    private Integer winbackDays;

    // Eigener Text, Platzhalter wie beim Sperrbildschirm. Null = Standard.
    @Column(name = "winback_text", length = 160)
    private String winbackText;

    // Seit wann jeder neu gebaute Pass das Nachrichtenfeld traegt (Rueckhol
    // UND Kampagnen). Einmal gesetzt, bleibt es: schaltet der Laden Rueckhol
    // wieder aus, verlieren die Paesse das Feld nicht - sonst muesste die
    // naechste Kampagne alle iPhones erst wieder still auffrischen.
    @Column(name = "nachrichten_seit")
    private Instant nachrichtenSeit;

    // Sonntag aus der Statistik ausrechnen (Ø/Tag, staerkster/ruhigster Tag) -
    // viele Laeden haben sonntags zu, dann ist Sonntag trivial immer "ruhigster
    // Tag". Pro Laden vom Laden selbst umschaltbar; das Admin-Panel zeigt nur
    // den Wert, den der Laden gesetzt hat (kein eigener Schalter dort).
    @Column(name = "exclude_sunday_from_stats")
    private Boolean excludeSundayFromStats;

    // Eigene Farbe fuer den Ladennamen (#RRGGBB). Null = wie bisher: auf der
    // Kundenseite die Kontrastfarbe, im Apple-Pass die Textfarbe der Karte.
    @Column(name = "name_color", length = 7)
    private String nameColor;

    // Link zur Google-Bewertung ("Bewerte uns"). Steht auf der Anmeldeseite
    // und der Kartenseite des Kunden. Null = kein Link.
    @Column(name = "google_review_url", length = 500)
    private String googleReviewUrl;

    // Anmeldeformular: welche Zusatzfelder abgefragt werden. OFF / OPTIONAL /
    // REQUIRED, null = OFF. Name und E-Mail sind immer Pflicht.
    @Column(name = "signup_birthday", length = 10)
    private String signupBirthday;

    @Column(name = "signup_phone", length = 10)
    private String signupPhone;

    // Automatischer Geburtstagsgruss: am Geburtstag eine Kampagne an alle
    // Karten, die heute Geburtstag haben (GeburtstagsService). Null = aus.
    @Column(name = "birthday_greeting_enabled")
    private Boolean birthdayGreetingEnabled;

    @Column(name = "birthday_greeting_text", length = 160)
    private String birthdayGreetingText;

    // Bewertungs-Erinnerung: ab dem wievielten Besuch die Karte einmalig
    // "Magst du uns bei Google bewerten?" bekommt (BewertungsErinnerungService).
    // Null oder 0 = aus.
    @Column(name = "review_reminder_visits")
    private Integer reviewReminderVisits;

    // Wie viele weitere Orte (ShopOrt) der Laden anlegen darf - vom Admin je
    // nach Tarif gesetzt. Null = 0 = Funktion nicht freigeschaltet.
    @Column(name = "max_orte")
    private Integer maxOrte;

    // Private Rueckmeldung (Sterne + Satz) vom Kunden an den Laden. Vom
    // Laden selbst eingeschaltet. Null = aus: kein Link auf Kartenseite
    // und Wallet-Karte, die Seite nimmt nichts an.
    @Column(name = "rueckmeldung_aktiv")
    private Boolean rueckmeldungAktiv;

    // Vom Admin befreit: kein Ladenort noetig, keine Geo-Pruefung beim Scan.
    // Null = nicht befreit, also Pflicht wie fuer alle.
    @Column(name = "geo_befreit")
    private Boolean geoBefreit;

    // Kundengruppen (service.Kundengruppe): ab wie vielen Besuchen jemand
    // Stammgast ist, nach wie vielen Tagen ohne Besuch er "wird ruhig" bzw.
    // "verloren" ist. Null = Standard (5 / 30 / 90).
    @Column(name = "gruppe_stamm_ab")
    private Integer gruppeStammAb;

    @Column(name = "gruppe_ruhig_tage")
    private Integer gruppeRuhigTage;

    @Column(name = "gruppe_verloren_tage")
    private Integer gruppeVerlorenTage;

    // Tagesangebot: ein Satz, der bis Tagesende auf der Karte steht
    // (Rueckseite bei Apple, Textbaustein bei Google) und beim Setzen als
    // Kampagne aufs Handy geht. tagesangebotAm != null = Laden hat die
    // Funktion je benutzt, dann traegt jeder Pass das Feld (auch leer).
    @Column(name = "tagesangebot_text", length = 80)
    private String tagesangebotText;

    @Column(name = "tagesangebot_am")
    private Instant tagesangebotAm;

    @Column(name = "tagesangebot_bis")
    private Instant tagesangebotBis;

    // Schnittstelle (API-Schluessel, Webhooks): vom Admin je Tarif
    // freigeschaltet. Null = aus. Webhook-URL und Geheimnis setzt der Laden.
    @Column(name = "api_freigeschaltet")
    private Boolean apiFreigeschaltet;

    // Demo-Laden fuer die Website: Besucher holen sich eine Karte und
    // stempeln sich selbst (DemoService). Vom Admin gesetzt. Null = nein.
    @Column(name = "demo")
    private Boolean demo;

    @Column(name = "webhook_url", length = 500)
    private String webhookUrl;

    @Column(name = "webhook_secret", length = 64)
    private String webhookSecret;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Shop() {}

    public static Shop create(String email, String passwordHash, String name, int maxTokens) {
        Shop s = new Shop();
        s.id = "SHOP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        s.email = email;
        s.passwordHash = passwordHash;
        s.name = name;
        s.logoUrl = null;
        s.heroImageUrl = null;
        s.colorBackground = "#3C3489";
        s.colorForeground = "#FFFFFF";
        s.colorLabel = "#FAC875";
        s.walletStyle = "number";
        s.stampIconType = "preset";
        s.stampPreset = "coffee";
        s.stampIconUrl = null;
        s.stampColor = "#6F4E37";
        s.emptyStampStyle = "number";
        s.active = true;
        s.maxTokens = maxTokens;
        s.createdAt = Instant.now();
        s.updatedAt = s.createdAt;
        return s;
    }

    public void update(String name, String logoUrl, String colorBackground,
                       String colorForeground, String colorLabel) {
        if (name != null && !name.isBlank()) this.name = name;
        if (logoUrl != null) this.logoUrl = logoUrl;
        if (colorBackground != null) this.colorBackground = colorBackground;
        if (colorForeground != null) this.colorForeground = colorForeground;
        if (colorLabel != null) this.colorLabel = colorLabel;
        this.updatedAt = Instant.now();
    }

    public void updateDesign(String walletStyle, String stampIconType, String stampPreset,
                             String stampColor, String emptyStampStyle) {
        if (walletStyle != null) this.walletStyle = walletStyle;
        if (stampIconType != null) this.stampIconType = stampIconType;
        if (stampPreset != null) this.stampPreset = stampPreset;
        if (stampColor != null) this.stampColor = stampColor;
        if (emptyStampStyle != null) this.emptyStampStyle = emptyStampStyle;
        this.updatedAt = Instant.now();
    }

    public void setActive(boolean active) {
        this.active = active;
        this.updatedAt = Instant.now();
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
        this.updatedAt = Instant.now();
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
        this.updatedAt = Instant.now();
    }

    public int getSortOrder() { return sortOrder; }

    /**
     * Setzt die Position im Admin-Panel. Ruehrt updatedAt bewusst NICHT an:
     * Umsortieren ist keine Aenderung am Laden selbst.
     */
    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    public void setHeroImageUrl(String heroImageUrl) {
        this.heroImageUrl = heroImageUrl;
        this.updatedAt = Instant.now();
    }

    public void setStampIconUrl(String stampIconUrl) {
        this.stampIconUrl = stampIconUrl;
        this.updatedAt = Instant.now();
    }

    public void setLogoOriginalUrl(String v) { this.logoOriginalUrl = v; this.updatedAt = Instant.now(); }
    public void setHeroOriginalUrl(String v) { this.heroOriginalUrl = v; this.updatedAt = Instant.now(); }
    public void setStampIconOriginalUrl(String v) { this.stampIconOriginalUrl = v; this.updatedAt = Instant.now(); }
    public String getLogoOriginalUrl() { return logoOriginalUrl; }
    public String getHeroOriginalUrl() { return heroOriginalUrl; }
    public String getStampIconOriginalUrl() { return stampIconOriginalUrl; }

    public String getId() { return id; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public String getName() { return name; }
    public String getLogoUrl() { return logoUrl; }
    public String getHeroImageUrl() { return heroImageUrl; }
    public String getColorBackground() { return colorBackground; }
    public String getColorForeground() { return colorForeground; }
    public String getColorLabel() { return colorLabel; }
    public String getWalletStyle() { return walletStyle; }
    public String getStampIconType() { return stampIconType; }
    public String getStampPreset() { return stampPreset; }
    public String getStampIconUrl() { return stampIconUrl; }
    public String getStampColor() { return stampColor; }
    public String getEmptyStampStyle() { return emptyStampStyle; }
    public boolean isActive() { return active; }
    public int getMaxTokens() { return maxTokens; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
    /** null/leer (Bestandslaeden) gilt als Deutsch. */
    public String getLanguageOrDefault() {
        return (language == null || language.isBlank()) ? "de" : language;
    }

    // ── Sperrbildschirm-Erinnerung ────────────────────────────────────────

    public Boolean getLockScreenEnabled() { return lockScreenEnabled; }
    public Double getLatitude() { return latitude; }
    public Double getLongitude() { return longitude; }

    /**
     * An ist die Erinnerung nur, wenn der Laden sie eingeschaltet hat UND
     * Koordinaten hinterlegt sind. Ohne Koordinaten gibt es nichts, worauf
     * sich das Handy beziehen koennte - dann ist der Schalter wirkungslos.
     */
    public boolean isLockScreenActive() {
        return Boolean.TRUE.equals(lockScreenEnabled)
                && latitude != null && longitude != null;
    }

    /**
     * Koordinaten setzen und Schalter umlegen. Schaltet der Laden ab, bleiben
     * die Koordinaten stehen - beim Wiedereinschalten muss er sie nicht neu
     * erfassen.
     */
    public void updateLockScreen(Boolean enabled, Double latitude, Double longitude) {
        if (enabled != null) this.lockScreenEnabled = enabled;
        if (latitude != null) this.latitude = latitude;
        if (longitude != null) this.longitude = longitude;
        this.updatedAt = Instant.now();
    }

    public String getLockScreenTextFull() { return lockScreenTextFull; }
    public String getLockScreenTextProgress() { return lockScreenTextProgress; }

    public Integer getLockScreenRadiusMeter() { return lockScreenRadiusMeter; }

    public void updateLockScreenRadius(int meter) {
        this.lockScreenRadiusMeter = meter;
        this.updatedAt = Instant.now();
    }

    /** Leerer Text = zurueck auf den Standardtext. */
    public void updateLockScreenTexts(String full, String progress) {
        this.lockScreenTextFull = (full == null || full.isBlank()) ? null : full.trim();
        this.lockScreenTextProgress = (progress == null || progress.isBlank()) ? null : progress.trim();
        this.updatedAt = Instant.now();
    }

    // ── Rueckhol-Nachricht ────────────────────────────────────────────────

    public boolean isRueckholAktiv() { return Boolean.TRUE.equals(winbackEnabled); }
    public Instant getWinbackEnabledAt() { return winbackEnabledAt; }
    public Integer getWinbackDays() { return winbackDays; }
    public String getWinbackText() { return winbackText; }

    public void updateRueckhol(boolean enabled, int days, String text) {
        if (enabled && !isRueckholAktiv()) {
            this.winbackEnabledAt = Instant.now();
            nachrichtenEinschalten(this.winbackEnabledAt);
        }
        this.winbackEnabled = enabled;
        this.winbackDays = days;
        this.winbackText = (text == null || text.isBlank()) ? null : text.trim();
        this.updatedAt = Instant.now();
    }

    // ── Nachrichtenfeld (Rueckhol + Kampagnen) ──────────────────────────

    public Instant getNachrichtenSeit() { return nachrichtenSeit; }

    /** Nur das erste Mal zaehlt: davor gebaute Paesse haben das Feld nicht. */
    public void nachrichtenEinschalten(Instant jetzt) {
        if (this.nachrichtenSeit == null) this.nachrichtenSeit = jetzt;
    }

    /** Traegt ein jetzt gebauter Pass dieses Ladens das Feld "nachricht"? */
    public boolean hatNachrichtenFeld() {
        return isRueckholAktiv() || getNachrichtenSeit() != null;
    }

    /**
     * Seit wann Paesse das Feld tragen. Null = noch nie. Bestandslaeden mit
     * Rueckhol von vor der Spalte nachrichten_seit haben nur winbackEnabledAt -
     * das gilt dann, solange Rueckhol an ist.
     */
    public Instant nachrichtenFeldSeit() {
        Instant seit = getNachrichtenSeit();
        Instant rueckhol = isRueckholAktiv() ? getWinbackEnabledAt() : null;
        if (seit == null) return rueckhol;
        if (rueckhol == null) return seit;
        return rueckhol.isBefore(seit) ? rueckhol : seit;
    }

    // ── Standort-Pflicht ────────────────────────────────────────────────

    public boolean isGeoBefreit() { return Boolean.TRUE.equals(geoBefreit); }

    public void setGeoBefreit(boolean befreit) {
        this.geoBefreit = befreit;
        this.updatedAt = Instant.now();
    }

    // ── Farbe des Ladennamens ───────────────────────────────────────────

    public String getNameColor() { return nameColor; }

    /** Null oder leer = automatisch. Geprueft wird im ShopService. */
    public void updateNameColor(String color) {
        this.nameColor = (color == null || color.isBlank()) ? null : color.trim().toUpperCase();
        this.updatedAt = Instant.now();
    }

    // ── Google-Bewertung ────────────────────────────────────────────────

    public String getGoogleReviewUrl() { return googleReviewUrl; }

    /** Null oder leer = kein Link. Geprueft wird im ShopService. */
    public void updateGoogleReviewUrl(String url) {
        this.googleReviewUrl = (url == null || url.isBlank()) ? null : url.trim();
        this.updatedAt = Instant.now();
    }

    // ── Anmeldefelder ───────────────────────────────────────────────────

    public static final String FELD_AUS = "OFF";
    public static final String FELD_OPTIONAL = "OPTIONAL";
    public static final String FELD_PFLICHT = "REQUIRED";

    public String getSignupBirthday() { return signupBirthday != null ? signupBirthday : FELD_AUS; }
    public String getSignupPhone() { return signupPhone != null ? signupPhone : FELD_AUS; }

    /** Werte geprueft im ShopService. */
    public void updateAnmeldefelder(String birthday, String phone) {
        this.signupBirthday = birthday;
        this.signupPhone = phone;
        this.updatedAt = Instant.now();
    }

    // ── Geburtstagsgruss ────────────────────────────────────────────────

    public static final String GEBURTSTAG_STANDARD =
            "Alles Gute zum Geburtstag! Schau heute bei uns vorbei, es wartet eine kleine Überraschung auf dich.";

    public boolean isGeburtstagsgrussAktiv() { return Boolean.TRUE.equals(birthdayGreetingEnabled); }
    public String getBirthdayGreetingText() { return birthdayGreetingText; }

    /** Text des Grusses, eigener oder Standard. */
    public String geburtstagsText() {
        return (birthdayGreetingText != null && !birthdayGreetingText.isBlank())
                ? birthdayGreetingText : GEBURTSTAG_STANDARD;
    }

    public void updateGeburtstagsgruss(boolean enabled, String text) {
        this.birthdayGreetingEnabled = enabled;
        this.birthdayGreetingText = (text == null || text.isBlank()) ? null : text.trim();
        this.updatedAt = Instant.now();
    }

    // ── Bewertungs-Erinnerung ───────────────────────────────────────────

    /** 0 = aus, sonst ab dem wievielten Besuch. */
    public int getBewertungErinnerungAb() {
        return reviewReminderVisits != null ? Math.max(0, reviewReminderVisits) : 0;
    }

    /** Bereich prueft der ShopService. */
    public void updateBewertungErinnerung(int ab) {
        this.reviewReminderVisits = ab;
        this.updatedAt = Instant.now();
    }

    // ── Weitere Orte (Tarif) ────────────────────────────────────────────

    public int getMaxOrte() { return maxOrte != null ? Math.max(0, maxOrte) : 0; }

    /** Vom Admin. Bereich prueft der Aufrufer. */
    public void setMaxOrte(int maxOrte) {
        this.maxOrte = maxOrte;
        this.updatedAt = Instant.now();
    }

    // ── Rueckmeldung ────────────────────────────────────────────────────

    public boolean isRueckmeldungAktiv() { return Boolean.TRUE.equals(rueckmeldungAktiv); }

    public void setRueckmeldungAktiv(boolean aktiv) {
        this.rueckmeldungAktiv = aktiv;
        this.updatedAt = Instant.now();
    }

    // ── Tagesangebot ────────────────────────────────────────────────────

    public static final int TAGESANGEBOT_MAX = 80;

    /** Das Angebot, solange es gilt; sonst null. */
    public String tagesangebot(Instant jetzt) {
        if (tagesangebotText == null || tagesangebotBis == null) return null;
        return jetzt.isBefore(tagesangebotBis) ? tagesangebotText : null;
    }

    /** Je benutzt: dann steht das Feld dauerhaft im Pass (leer oder mit Angebot). */
    public boolean hatTagesangebotFeld() { return tagesangebotAm != null; }

    public String getTagesangebotText() { return tagesangebotText; }
    public Instant getTagesangebotAm() { return tagesangebotAm; }
    public Instant getTagesangebotBis() { return tagesangebotBis; }

    /** Laenge prueft der TagesangebotService. */
    public void setzeTagesangebot(String text, Instant jetzt, Instant bis) {
        this.tagesangebotText = text;
        this.tagesangebotAm = jetzt;
        this.tagesangebotBis = bis;
        this.updatedAt = jetzt;
    }

    /** Angebot weg, das Feld bleibt (tagesangebotAm steht). */
    public void loescheTagesangebot(Instant jetzt) {
        this.tagesangebotText = null;
        this.tagesangebotBis = null;
        this.updatedAt = jetzt;
    }

    // ── Schnittstelle ───────────────────────────────────────────────────

    public boolean isApiFreigeschaltet() { return Boolean.TRUE.equals(apiFreigeschaltet); }

    /** Vom Admin. */
    public void setApiFreigeschaltet(boolean frei) {
        this.apiFreigeschaltet = frei;
        this.updatedAt = Instant.now();
    }

    public String getWebhookUrl() { return webhookUrl; }
    public String getWebhookSecret() { return webhookSecret; }

    // ── Demo ────────────────────────────────────────────────────────────

    public boolean isDemo() { return Boolean.TRUE.equals(demo); }

    /** Vom Admin. */
    public void setDemo(boolean demo) {
        this.demo = demo;
        this.updatedAt = Instant.now();
    }

    /** Geprueft vom WebhookService. */
    public void webhookSetzen(String url, String secret) {
        this.webhookUrl = url;
        this.webhookSecret = secret;
        this.updatedAt = Instant.now();
    }

    public void webhookEntfernen() {
        this.webhookUrl = null;
        this.webhookSecret = null;
        this.updatedAt = Instant.now();
    }

    // ── Kundengruppen ───────────────────────────────────────────────────

    public static final int GRUPPE_STAMM_AB_STANDARD = 5;
    public static final int GRUPPE_RUHIG_TAGE_STANDARD = 30;
    public static final int GRUPPE_VERLOREN_TAGE_STANDARD = 90;

    public int getGruppeStammAb() {
        return gruppeStammAb != null && gruppeStammAb > 0 ? gruppeStammAb : GRUPPE_STAMM_AB_STANDARD;
    }

    public int getGruppeRuhigTage() {
        return gruppeRuhigTage != null && gruppeRuhigTage > 0 ? gruppeRuhigTage : GRUPPE_RUHIG_TAGE_STANDARD;
    }

    public int getGruppeVerlorenTage() {
        return gruppeVerlorenTage != null && gruppeVerlorenTage > 0 ? gruppeVerlorenTage : GRUPPE_VERLOREN_TAGE_STANDARD;
    }

    /** Bereich und Reihenfolge prueft Kundengruppe.Regeln#pruefe. */
    public void updateKundengruppen(int stammAb, int ruhigTage, int verlorenTage) {
        this.gruppeStammAb = stammAb;
        this.gruppeRuhigTage = ruhigTage;
        this.gruppeVerlorenTage = verlorenTage;
        this.updatedAt = Instant.now();
    }

    // ── Statistik-Einstellungen ─────────────────────────────────────────

    public Boolean getExcludeSundayFromStats() { return excludeSundayFromStats; }

    public void setExcludeSundayFromStats(boolean enabled) {
        this.excludeSundayFromStats = enabled;
        this.updatedAt = Instant.now();
    }
}