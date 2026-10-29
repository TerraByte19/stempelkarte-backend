# Willkommensgeschenk - Umsetzungsplan

> Ausfuehrung inline, Task fuer Task, Tests nach jedem Task. Spec:
> `docs/specs/2026-10-29-willkommensgeschenk-design.md`.

**Ziel:** Gesperrte Stempelkarte nach Anmeldung, Freischaltung durch das
Personal beim Gratis-Artikel, danach normale Karte mit 0 Stempeln.

**Architektur:** Zustand liegt auf `CustomerCard` (drei nullable Spalten),
Angebot auf `Card` (zwei nullable Spalten). Jeder Ausgabeweg (Apple, Google,
Kundenseite, Scanner) bekommt einen Zweig fuer "gesperrt", der normale Weg
bleibt Zeichen fuer Zeichen.

**Stack:** Spring Boot 3.5 / Java 21 / JPA (H2 Test, Postgres prod), jpasskit,
Google Walletobjects; React 18 + Vite.

## Globale Regeln

- Neue Spalten nullable, `null` = Verhalten wie bisher.
- Gesperrt wird NUR in `registerForCard`, NUR bei neu angelegter Kundenkarte.
- Fachfehler als IllegalArgumentException (400) oder NoSuchElementException
  (404), nie IllegalStateException (503).
- Kommentare deutsch, Warum statt Was, Umlaute umschrieben; Nutzertexte mit
  Umlauten, keine Geviertstriche.
- Frontend: neue Felder nie voraussetzen; jeder Text in de, en, ar.
- Kein Push. Backend: `./mvnw -o test`, Frontend: `npm run build` + `npm run lint`.

---

### Task 1: Datenmodell

**Dateien:** `model/Card.java`, `model/CustomerCard.java`,
Test `model/WillkommenKarteTest.java` (neu)

**Produziert:**
- `Card.WILLKOMMEN_STANDARD = "Erster Kaffee geht auf uns"`
- `Card#isWillkommenAktiv()`, `Card#getWillkommenText()` (nie null),
  `Card#updateWillkommen(boolean enabled, String text)` (Text null/leer = Standard)
- `CustomerCard#willkommenVormerken(String text)`, `#isWillkommenOffen()`,
  `#getWillkommenText()`, `#getWillkommenEingeloestAt()`,
  `#willkommenEinloesen(Instant jetzt)` (IllegalArgumentException wenn nicht offen),
  `#istFrischFreigeschaltet()`
- `rueckholFaellig` liefert false bei offener Sperre

**Tests:** Standardtext; Punktekarte nie aktiv; neue Karte nicht gesperrt;
vormerken + einloesen setzt Zeitpunkte, lastVisitAt, updatedAt; zweimal
einloesen wirft; frisch freigeschaltet nur bei 0/0; Rueckhol nicht faellig
solange gesperrt.

### Task 2: Anmeldung + Stempel-Sperre + Einloesen (Service)

**Dateien:** `service/CustomerService.java`, `repository/CustomerCardRepository.java`,
`service/EmailService.java` (Parameter), Test `service/WillkommenServiceTest.java` (neu)

**Produziert:**
- `registerForCard`: vormerken nur im Neu-Zweig
- `processScan`: gesperrt -> IllegalArgumentException
  `"Erst das Willkommensgeschenk einlösen - bitte Scanner neu laden."`
- `CustomerService#willkommenEinloesen(String qrPayload, Shop shop): CustomerCard`
- Repository: `@Lock(PESSIMISTIC_WRITE) findWithLockByCustomer_IdAndCard_Id`,
  Counts `countByCardAndWelcomePendingTrue`, `countByCardAndWelcomeRedeemedAtIsNotNull`,
  Rueckhol-Query um Sperre ergaenzt
- `EmailService#sendConfirmationMail(Customer, Shop, String cardId, String willkommenText)`

**Tests:** neue Kundenkarte bei aktivem Angebot gesperrt; bestehende bleibt
offen; Angebot aus -> offen; Stempel auf gesperrte Karte abgelehnt und kein
save/ScanLog; einloesen: fremder Laden, inaktive Karte, keine Kundenkarte,
schon eingeloest; Mail bekommt Text.

### Task 3: Scanner-Endpunkte

**Dateien:** `controller/ScanController.java`, `service/PointsService.java`
(PointsState + 2 Felder), `wallet/WalletNotifier.java`, `wallet/GoogleWalletService.java`
(`notifyFreigeschaltet`), Test `controller/WillkommenScanTest.java` (neu)

**Produziert:** `POST /api/scan/willkommen` (WillkommenRequest qrPayload, lat, lon)
-> ScanResponse action "welcome"; `PointsState.willkommenOffen`, `.willkommenText`;
`WalletNotifier#nachFreischaltung(CustomerCard)`.

**Tests:** ohne Token 401; Geo-Sperre 403 ohne Einloesen; Erfolg ruft
Notifier; state liefert Felder.

### Task 4: Apple-Pass + Vorlage

**Dateien:** `wallet/ApplePassService.java`, `wallet/PassTemplateGenerator.java`,
Test `wallet/WillkommenPassTest.java` (neu)

**Tests:** gesperrt: Header-Key je Stil, Wert "🎁", Primaer = Text der
Kundenkarte, kein Countdown-Feld; offen unveraendert (Feldvergleich gegen
nicht gesperrte Karte); frisch freigeschaltet -> changeMessage
"🎉 Karte freigeschaltet! Stempel: %@"; nach erstem Stempel normaler Text;
Sperrbildschirm-Text; Streifen-Entscheidung `brauchtStreifen`.

### Task 5: Google-Objekt

**Dateien:** `wallet/GoogleWalletService.java` (Felder als paketsichtbare
statische Methode `stempelFelder` herausgezogen), Test `wallet/WillkommenGoogleTest.java`

**Tests:** gesperrt -> "Willkommen"/Text, "So geht's"; offen -> wie bisher.

### Task 6: Kundenseiten + JSON

**Dateien:** `controller/LandingController.java`, `controller/CustomerController.java`,
Test `controller/WillkommenSeiteTest.java`

**Tests:** gesperrte Seite zeigt Box + escaped Text, Raster versteckt; offene
Seite ohne Box; Anmeldeseite zeigt Angebot nur wenn aktiv; JSON-Feld.

### Task 7: Laden-Einstellung

**Dateien:** `controller/StampDesignController.java` oder `ShopController.java`
(`PUT /api/shop/cards/{cardId}/willkommen`), `CardResponse` + 4 Felder,
Test im bestehenden Stil.

**Tests:** Punktekarte 400; Text > 60 -> 400; leer -> Standard.

### Task 8: Frontend

**Dateien:** `src/components/WillkommenSettings.jsx` (neu), `src/pages/Karten.jsx`,
`src/pages/Scanner.jsx`, `src/i18n.js`

**Pruefung:** `npm run build`, `npm run lint`, i18n-Test (`src/i18n.test.js`).

### Task 9: Abschluss

Ganze Suite, Selbst-Review des Diffs (beide Repos), Vault-Notiz
`Projects/Stampit.md` ergaenzen. Kein Push ohne Freigabe.
