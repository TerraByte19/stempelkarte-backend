# Arabisch / RTL / Sprache pro Laden — Design

**Datum:** 2026-08-30
**Projekt:** Stampit (v1). Betrifft `C:\Project SK\Stemplekarte` (Backend) +
`C:\Project SK\stempelkarte-frontend` (Laden-App). **Nicht** die `_2.0`-Ordner —
die werden nach dieser + der Landing-Aufgabe gelöscht.
**Anlass:** Kunden in Saudi-Arabien. Alles was ein Endkunde sieht muss auf
Arabisch (rechts-nach-links) darstellbar sein: die Stempelkarte, die
Anmeldeseite, die Bestätigungsseiten, alle E-Mails.

## 1. Ziel

Ein Laden stellt seinen Account auf Arabisch. Danach ist **alles für diesen
Laden** arabisch + RTL: jede Kundenkarte, jede Mail, jede öffentliche Seite.
Deutsch bleibt Standard und für alle bestehenden Läden unverändert.

### Erfolgskriterien

1. Neues Feld `Shop.language` (`de` | `ar`), einstellbar in der Laden-App.
2. Kundenkarte (`/karte/{customerId}/{cardId}`), Anmeldeseite
   (`/karte-neu/{cardId}`), Bestätigungs-/Ergebnis-Seiten (`/mail/*`) und alle
   drei E-Mail-Typen erscheinen vollständig auf Arabisch **und** rechts-nach-links,
   wenn die Sprache `ar` ist.
3. `ddl-auto: update` läuft ohne DB-Reset durch (neue Spalten nullable).
4. Bestehende deutsche Läden: keine sichtbare Änderung.
5. Arabische Texte sind da, aber klar als „prüfen" markiert — vor echtem
   Go-Live liest ein Muttersprachler gegen, besonders die Lösch-/DSGVO-Mail.

## 2. Getroffene Entscheidungen

| Entscheidung | Gewählt | Begründung |
|---|---|---|
| Sprach-Ebene | **Pro Laden** (`Shop.language`) steuert; pro Kunde (`Customer.language`) speichert den beim Anmelden übernommenen Wert | Ein Laden = eine Sprache (Wunsch). `Customer.language` hält den Kunden stabil, auch wenn der Laden später umstellt, und deckt die kontoweite Lösch-Mail ab (die keinen Shop kennt). |
| Sprachen | **nur `de` + `ar`** im Backend/den Kunden-Flächen | Englisch bleibt allein in der Laden-App (die hat es schon). Weniger zu übersetzen und zu testen. |
| Übersetzungs-Mechanismus Backend | **Eine Java-Klasse `Texts`** — `Map<lang, Map<key, String>>` + `t(lang, key, vars...)`, spiegelt das Frontend-`i18n.js` | Kein neues Framework, kein `.properties`-Encoding-Kram, alles in einer prüfbaren Datei. Für 2 Sprachen ist Spring `MessageSource` Zeremonie; Thymeleaf ein großer Umbau. Wechsel später ist mechanisch. |
| Ziffern | westlich `0–9` überall | Konsistent mit dem Frontend (`localeTag` erzwingt `ar-u-nu-latn`). Moderne saudische Apps nutzen westliche Ziffern. |
| Schrift | System-Sans-Stack mit arabisch-fähigem Fallback (`-apple-system, "Segoe UI", Tahoma, Arial, sans-serif`) — **kein Webfont** | Rendert Arabisch auf Handys und in Gmail/Apple Mail/Outlook. Webfonts in E-Mails sind unzuverlässig. |
| Arabische Inhalte | Claude entwirft, jeder String als `// TODO ar-review` markiert | Muttersprachler-Gegenlesen vor Go-Live, v. a. Rechtstexte. |

### Bewusst nicht im Umfang

- Kein Umzug der Karte/Anmeldung nach React (v2s Idee). v1 rendert weiter
  HTML im Backend. Wenn das später umzieht, wird nur diese eine Seite neu übersetzt.
- Kein Reviews-Feature (separates v2-Paket, kann jederzeit frisch gebaut werden).
- Keine dritte Sprache.
- Kein Sprach-Umschalter für den Endkunden auf der Karte (der Laden bestimmt).

## 3. Backend-Änderung (`Stemplekarte`)

### 3.1 Neue Felder

- **`Shop.language`** — `@Column(length = 5)`, nullable. Werte `"de"` | `"ar"`.
  `null` gilt als `"de"`. Getter `getLanguageOrDefault()` → `null/blank → "de"`.
- **`Customer.language`** — `@Column(name = "language", length = 5)`, nullable.
  Bei `registerForCard(...)` gesetzt auf `card.getShop().getLanguageOrDefault()`.
  `null` (Altbestand) → Fallback-Kette beim Rendern (siehe 3.3).

Beide nullable → `ddl-auto: update` ohne Reset.

### 3.2 `Texts`-Klasse (neu)

`src/main/java/com/example/stemplekarte/i18n/Texts.java`:

```java
public final class Texts {
    private static final Map<String, Map<String, String>> T = Map.of(
        "de", Map.ofEntries(
            Map.entry("card.progress", "{0} von {1} Stempeln"),
            Map.entry("card.reward_available", "\uD83C\uDF89 {0} verfügbar!"),
            Map.entry("card.reward_left", "Noch {0} Stempel bis: {1}"),
            Map.entry("card.apple_wallet", "Zu Apple Wallet hinzufügen"),
            // ... alle Keys der 4 Flächen
        ),
        "ar", Map.ofEntries(
            Map.entry("card.progress", "{0} من {1} أختام"),        // TODO ar-review
            // ...
        )
    );

    public static String t(String lang, String key, Object... vars) {
        String base = T.getOrDefault(norm(lang), T.get("de")).get(key);
        if (base == null) base = T.get("de").get(key);
        if (base == null) return key;
        for (int i = 0; i < vars.length; i++) {
            base = base.replace("{" + i + "}", String.valueOf(vars[i]));
        }
        return base;
    }

    private static String norm(String lang) {
        return "ar".equalsIgnoreCase(lang) ? "ar" : "de";
    }
    public static boolean isRtl(String lang) { return "ar".equalsIgnoreCase(lang); }
    private Texts() {}
}
```

Key-Namen nach Fläche gruppiert: `card.*`, `signup.*`, `page.*`, `mail.*`.
Geschätzt 40–60 Keys gesamt.

### 3.3 `LandingController` — Kundenkarte + Anmeldeseite

- **Sprache bestimmen** (Fallback-Kette):
  `String lang = firstNonBlank(customer.getLanguage(), card.getShop().getLanguageOrDefault(), "de");`
  (Anmeldeseite `/karte-neu/{cardId}` hat keinen Kunden → `card.getShop().getLanguageOrDefault()`.)
- **Alle deutschen Literale** im HTML-Textblock durch `Texts.t(lang, "...")` ersetzen.
  Das betrifft in `/karte/...`: Fortschritt („X von Y Stempeln"), Belohnungszeile,
  Apple-/Google-Wallet-Knopf, „Karte nicht gefunden". Und den JS-Block (die
  `renderStamps`-Strings „von … Stempeln", „verfügbar", „Noch … bis:").
  In `/karte-neu/...`: Überschrift, Info-Text, Formularfelder, Buttons, „Karte
  nicht gefunden".
- **RTL:** `<html lang="{lang}" dir="{Texts.isRtl(lang) ? "rtl" : "ltr"}">`.
  Im `<style>`: `body { direction: {ltr|rtl}; }` schon durch `dir` am `<html>`
  abgedeckt. Der `.shop-header` (flex mit `gap`) kippt automatisch. `.stamps-grid`
  bleibt unverändert (Grid, richtungsneutral). Wo `text-align: left/right` fest
  steht → gegen `text-align: start/end` tauschen.
- **JS-Sicherheit:** die bestehende `toJsString(...)`-Escaping-Hilfe auch für die
  übersetzten Strings verwenden, die in den `<script>`-Block eingesetzt werden.

### 3.4 `PublicEmailController` — `page(...)`-Ergebnisseiten

- `page(icon, title, message)` bekommt einen `lang`-Parameter.
  Aufrufer: `confirm(...)` kennt `customerId` → `customer.getLanguage()`;
  `delete-confirm` / `unsubscribe` → `customer.getLanguage()` (der Kunde ist
  über den Token auflösbar). Fallback `"de"`.
- Titel/Text/„Fenster schließen" über `Texts.t(lang, "page.*")`.
- `<html lang dir>` wie in 3.3.

### 3.5 `EmailService` — die drei Mail-Typen

- **`sendConfirmationMail(customer, shop, cardId)`** → `lang` aus
  `firstNonBlank(customer.getLanguage(), shop.getLanguageOrDefault(), "de")`.
- **`sendNewsletterMail(...)`** hat `shop` → `shop.getLanguageOrDefault()`
  (plus `customer.language`, falls der Aufrufer den Kunden kennt — sonst Shop).
- **`sendDeletionMail(customer)`** hat **keinen Shop** → `customer.getLanguage()`,
  Fallback `"de"`. Das ist der Grund für `Customer.language`.
- **`wrap(...)`** und `emailHeader(...)`: `lang` durchreichen. Der äußere
  Container bekommt `dir="rtl"` und `style="... text-align:right"` bei `ar`.
  `button(...)`-Text und alle festen Zeilen (Footer „Diese Mail wurde über
  StampIT…", Lösch-Link-Text, Abmelde-Link-Text) über `Texts.t`.
- Betreffzeilen (`"Deine Stempelkarte – " + shopName` etc.) übersetzen:
  `Texts.t(lang, "mail.confirm_subject", shopName)`.

### 3.6 `CustomerService.registerForCard`

Nach `getOrCreate(...)`: `customer.setLanguage(card.getShop().getLanguageOrDefault())`
**nur wenn** `customer.getLanguage()` noch leer ist (einen bestehenden Kunden mit
Karte bei einem DE-Laden nicht ungewollt auf `ar` ziehen — beim allerersten
Anmelden ist es leer, das ist der Normalfall).

### 3.7 Admin/Shop-Endpunkt

Der bestehende Shop-Update-Endpunkt (Profil speichern) nimmt `language` mit an
und validiert `de|ar` (sonst 400). Falls es getrennte Endpunkte gibt: dort wo
`Shop.name` gespeichert wird.

## 4. Frontend-Änderung (`stempelkarte-frontend`, Laden-App)

Die App hat `i18n.js` (de/en/ar) + `LangContext` (setzt `documentElement.dir`
aus dem `languages`-Array) schon. Es fehlt: **die Sprache am Account festmachen**
statt nur im `localStorage`.

### 4.1 `LangContext` — Sprache vom Shop laden

- `/api/me` bzw. der Shop-Load beim Login liefert `shop.language`.
- Beim Einloggen: wenn `shop.language` gesetzt → `setLang(shop.language)` als
  Startwert (überschreibt den `localStorage`-Default einmalig nach Login).
- Danach wie gehabt: der Nutzer kann in der App weiter umschalten (nur Anzeige),
  aber die **gespeicherte** Sprache ist die vom Account.

### 4.2 Profil-Seite — Sprach-Dropdown

- In `Profil.jsx` neben „Laden-Name": `<select>` Deutsch / العربية.
- Speichern → `PUT` auf den Shop mit `language`.
- Nach Erfolg: `setLang(neu)` (die ganze App kippt sofort auf RTL, ist schon
  verdrahtet).
- i18n-Keys für Label/Optionen in allen drei Sprachen ergänzen.

### 4.3 QA-Durchlauf RTL

Die App hat RTL nie im Ernst benutzt. Ein Durchgang durch Dashboard, Karten,
Statistik, Profil, Scanner auf `ar`: gespiegelte Icons/Pfeile prüfen
(`dirArrow` gibt's schon), abgeschnittene Buttons, `text-align`. Fixes nur wo
wirklich kaputt.

## 5. Arabische Texte

Claude schreibt einen ersten Entwurf für alle Keys (Backend `Texts` +
Frontend-i18n-Ergänzungen). Jeder arabische String im Code mit
`// TODO ar-review` bzw. einem Sammel-Kommentar markiert. **Vor Go-Live:**
Muttersprachler liest gegen. Priorität: Lösch-Mail + DSGVO-/Rechtstext, dann
Anmelde-Formular, dann Rest.

## 6. Prüfung

- `./mvnw test` grün (Backend).
- `npm run build` grün (Frontend).
- Manuell: Test-Laden auf `ar` stellen →
  - Anmeldeseite `/karte-neu/{cardId}` arabisch + RTL, Formular absendbar.
  - Bestätigungs-Mail kommt arabisch (lokal via `MAIL_ENABLED=false` + Log-Link).
  - Link → Bestätigungsseite arabisch → Karte `/karte/{cid}/{cardId}` arabisch +
    RTL, Stempelstand stimmt, Live-Aktualisierung läuft.
  - Ein Stempel per Scanner → Karte aktualisiert sich (weiterhin, RTL egal).
  - Laden-App auf `ar`: Profil-Dropdown, RTL-Layout, kein abgeschnittener Text.
- Test-Laden auf `de`: alles unverändert wie heute.

## 7. Reihenfolge

1. Backend: `Shop.language` + `Customer.language` + `Texts`-Gerüst (alle Keys,
   `ar` erst mal = `de`-Text als Platzhalter) + Fallback-Ketten verdrahten.
   → messbar: `de` unverändert, `ar` zeigt (noch deutschen) Text mit `dir="rtl"`.
2. Backend: arabische Entwürfe in `Texts` füllen, `TODO ar-review`.
3. Frontend: `LangContext` vom Shop, Profil-Dropdown, i18n-Keys.
4. RTL-QA-Durchlauf Frontend, punktuelle Fixes.
5. Gesamttest nach Abschnitt 6.
