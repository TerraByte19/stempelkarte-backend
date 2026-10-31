# Gesperrte Karte: eigenes Design - Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ladenbesitzer gestaltet die gesperrte Willkommenskarte selbst (Bild, Ueberschrift, Hinweis, Farben nur iPhone) mit Live-Vorschau.

**Architecture:** 6 nullable Spalten an `Card`, live gelesen beim Pass-Bau. Apple: eigener Zweig `baueFelderGesperrt` + Farben/Streifen nur im gesperrten Zustand. Google: Objekt-Hero nur bei Karten mit Willkommens-Historie. `PUT /willkommen` erweitert, Upload-Endpunkt nur fuer die URL, gespeichert wird mit dem Speichern-Knopf. Nach Design-Aenderung werden offene gesperrte Paesse still aufgefrischt.

**Tech Stack:** Spring Boot 3.5 / Java 21 / jpasskit / Google Walletobjects; React + Vite.

Spec: `docs/specs/2026-10-31-gesperrte-karte-design.md`

## Global Constraints

- Normale (nicht gesperrte) Paesse bleiben Feld fuer Feld unveraendert. Google-Objekte ohne Willkommens-Historie bekommen kein `heroImage`.
- Kopf-Key bleibt `stamps` (Raster) bzw. `stamps-header`.
- Leer = Standard, `null` im Request = unveraendert.
- Ueberschrift max 18, Hinweis max 40, Farben `#RRGGBB`, Bild via `BildUrl.pruefe`.
- Kommentare deutsch, ae/oe/ue; keine Gedankenstriche in Nutzertexten; i18n de/en/ar.
- Backend: `./mvnw -o -q test` gruen. Frontend: `npm run build`, `node --test src/i18n.test.js`, Lint nicht ueber 4 Fehler.

---

### Task 1: Card-Felder

**Files:** Modify `model/Card.java`; Test `model/WillkommenKarteTest.java`

**Produces:**
- `Card.WILLKOMMEN_TITEL_STANDARD = "Willkommen"`, `WILLKOMMEN_HINWEIS_STANDARD = "An der Kasse vorzeigen"`, `WILLKOMMEN_TITEL_MAX = 18`, `WILLKOMMEN_HINWEIS_MAX = 40`
- `String getWillkommenTitel()` / `getWillkommenHinweis()` (nie null, Standard)
- Rohwerte (null wenn ungesetzt): `getWelcomeTitle()`, `getWelcomeHint()`, `getWelcomeImageUrl()`, `getWelcomeColorBackground()`, `getWelcomeColorForeground()`, `getWelcomeColorLabel()`
- `void updateWillkommenDesign(String titel, String hinweis, String bildUrl, String bg, String fg, String label)` - null = unveraendert, blank = zuruecksetzen

- [ ] Test: Standards ohne Werte; null laesst stehen; "" setzt zurueck; trimmt.
- [ ] Implementieren (Spalten `welcome_title` 18, `welcome_hint` 40, `welcome_image_url` 500, `welcome_color_bg`/`_fg`/`_label` 7).
- [ ] Test gruen.

### Task 2: Apple-Pass

**Files:** Modify `wallet/ApplePassService.java`, `wallet/PassTemplateGenerator.java`; Test `wallet/WillkommenPassTest.java`

**Produces:**
- `static String[] PassTemplateGenerator.passFarben(Card, Shop, CustomerCard)` -> {bg, fg, label}; gesperrt: eigene Farbe, falls gesetzt
- `static boolean PassTemplateGenerator.brauchtWillkommensBild(CustomerCard)`
- `baueFelderGesperrt(Card, CustomerCard, boolean)`: Kopf label null, value = Titel; kein Primary; Secondary `welcome` (label "🎁 GESCHENK") + `welcome-how` (label "SO GEHT'S", value = Hinweis); Auxiliary `name`.

- [ ] Tests anpassen/ergaenzen: Kopf ohne Emoji, eigener Titel, kein Primary, Secondary mit Geschenk und Hinweis, Farben nur gesperrt, Bild-Streifen nur gesperrt mit Bild.
- [ ] buildPass + generateTemplate auf `passFarben`; Template schreibt bei `brauchtWillkommensBild` den Streifen per `renderFoto` (Fallback: kein Streifen).
- [ ] Tests gruen.

### Task 3: Google

**Files:** Modify `wallet/GoogleWalletService.java`; Test `wallet/WillkommenGoogleTest.java`

**Produces:**
- `willkommenFelder(cc)`: label = Titel, Neben-Feld value = Hinweis
- `static Image willkommenHero(CustomerCard cc, boolean bildErreichbar)`: keine Historie -> `null` (Feld nicht anfassen); gesperrt + Bild -> Image; sonst `Data.nullOf(Image.class)` (Override loeschen, Klassen-Hero gilt)

- [ ] Tests, Implementierung, gruen.

### Task 4: API + stilles Auffrischen

**Files:** Modify `controller/ShopController.java`, `controller/StampDesignController.java`, `repository/CustomerCardRepository.java`; Create `service/GesperrteKartenAuffrischer.java`; Test `controller/WillkommenEinstellungTest.java` (+ zweiter Konstruktor-Aufruf)

**Produces:**
- `WillkommenRequest(boolean enabled, String text, String title, String hint, String imageUrl, String colorBackground, String colorForeground, String colorLabel)`; Response gleiche Felder + offen/eingeloest
- `POST /api/shop/cards/{id}/welcome-image` -> `{welcomeImageUrl}` (nur Upload, kein Speichern)
- `GesperrteKartenAuffrischer.auffrischen(Card)`: offene gesperrte Kundenkarten `passAuffrischen`, speichern, APNs + Google `notifyUpdate` (still). Nur aufgerufen, wenn sich das Design wirklich geaendert hat.

- [ ] Tests: Validierung (Laenge, Farbformat, Bild-URL), leer = Standard, null = unveraendert, Auffrischen nur bei Aenderung.
- [ ] Implementieren, volle Suite gruen, commit.

### Task 5: Frontend

**Files:** Modify `components/WillkommenSettings.jsx`, `components/CardPreview.jsx`, `pages/Karten.jsx`, `i18n.js`

- [ ] WillkommenSettings: Bild (Upload mit BildCropper 375:144, Entfernen), Ueberschrift (18), Hinweis (40), 3 Farbwaehler + Hinweis "nur iPhone". Braucht `cardId`.
- [ ] Karten.jsx: laden/vergleichen/senden aller Felder; Vorschau bekommt `willkommenDesign`.
- [ ] CardPreview: Apple Kopf = Titel ohne Emoji, Bild-Streifen, "🎁 GESCHENK"-Zeile klein, Hinweis, Farben; Google Label = Titel, Hinweis, Hero = Bild.
- [ ] i18n de/en/ar, Build, i18n-Test, Lint.
- [ ] commit.
