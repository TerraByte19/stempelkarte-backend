# Push-Kampagnen - Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> Nachtrag 2026-11-02: Nach dem ersten Bau um Zielgruppen (`service.Zielgruppe`), eine festgeschriebene Empfaengerliste (`kampagne_empfaenger`) und das Limit "eine Nachricht pro Karte und Tag" statt "eine Kampagne pro Laden und Tag" erweitert. Massgeblich ist die Spec.

**Goal:** Der Laden schickt eine freie Nachricht als Wallet-Mitteilung an eine Zielgruppe seiner Kunden, jede Karte hoechstens einmal am Tag, mit Verlauf.

**Architecture:** Das Rueckseitenfeld `nachricht` der Rueckhol-Nachricht wird zum allgemeinen Nachrichtenfeld (Shop.nachrichtenSeit schaltet es dauerhaft ein). Eine `Kampagne`-Zeile pro Versand, ein Laeufer alle 30 s versorgt 25 Karten pro Lauf (iPhone mit altem Pass: erst still auffrischen). Google ueber `sendeNachricht`. Eigener Controller `/api/shop/kampagnen`, eigene Dashboard-Sektion.

**Tech Stack:** Spring Boot 3.5 / Java 21 / jpasskit / Google Walletobjects; React + Vite.

Spec: `docs/specs/2026-11-02-push-kampagnen-design.md`

## Global Constraints

- Kopf-Key bleibt `stamps` / `stamps-header`. Paesse von Laeden ohne Rueckhol und ohne Kampagne bleiben Feld fuer Feld unveraendert.
- Neue Spalten nullable. Keine Gedankenstriche in Nutzertexten. Kommentare deutsch, ae/oe/ue.
- Ein fehlgeschlagener Push bricht nichts ab.
- Backend: `./mvnw -o -q test` gruen. Frontend: `npm run build`, `node --test src/i18n.test.js`, Lint pro geaenderter Datei nicht schlechter.
- Kein Push ohne Freigabe. Backend vor Frontend.

---

### Task 1: Shop.nachrichtenSeit und CustomerCard-Felder

**Files:** Modify `model/Shop.java`, `model/CustomerCard.java`; Test `model/KampagnenKarteTest.java`

**Produces:**
- `Shop`: `Instant getNachrichtenSeit()`, `void nachrichtenEinschalten(Instant)` (nur wenn leer), `boolean hatNachrichtenFeld()`, `Instant nachrichtenFeldSeit()`; `updateRueckhol` setzt beim Einschalten auch `nachrichtenSeit`.
- `CustomerCard`: `void kampagneErhalten(String text, Instant jetzt)`, `String getKampagneText()`, `Instant getKampagneAm()`, `String nachrichtText()` (null = keine), `boolean nachrichtFrisch()`.

- [ ] Test: ohne alles null/false; Kampagne -> Text, frisch; nach Stempel nicht frisch, Text bleibt; Rueckhol nach Kampagne -> Rueckholtext; Kampagne nach Rueckhol -> Kampagnentext; 500 Zeichen -> 300; `kampagneErhalten` zaehlt nicht als Besuch; Shop: Rueckhol einschalten setzt nachrichtenSeit, zweites Einschalten laesst es stehen; `nachrichtenFeldSeit` = min.
- [ ] Implementieren.
- [ ] `./mvnw -o -q test -Dtest=KampagnenKarteTest` gruen.

### Task 2: Apple-Feld

**Files:** Modify `wallet/ApplePassService.java` (`nachrichtFeld`); Test `wallet/KampagnenFeldTest.java`, anpassen `wallet/RueckholFeldTest.java`

- [ ] `nachrichtFeld`: Bedingung `shop.hatNachrichtenFeld()`; Wert `cc.nachrichtText()` oder Ruhetext; `changeMessage("%@")` nur bei `cc.nachrichtFrisch()`.
- [ ] `RueckholFeldTest` auf `hatNachrichtenFeld` / `nachrichtText` / `nachrichtFrisch` umstellen.
- [ ] Tests gruen.

### Task 3: Kampagne-Entity, Repository, Kandidaten-Abfrage

**Files:** Create `model/Kampagne.java`, `repository/KampagneRepository.java`; Modify `repository/CustomerCardRepository.java`; Test `repository/KampagnenAbfragenTest.java`

**Produces:**
- `Kampagne.starte(Shop, String text, int empfaenger)`, `getId/getShop/getText/getStatus/getEmpfaenger/getGesendet/getErstelltAm/getFertigAm`, `void gesendet(int n)`, `void beenden(Instant)`; Konstanten `LAEUFT = "RUNNING"`, `FERTIG = "DONE"`.
- `KampagneRepository`: `List<Kampagne> findByStatus(String)`, `boolean existsByShopAndErstelltAmGreaterThanEqual(Shop, Instant)`, `List<Kampagne> findTop20ByShopOrderByErstelltAmDesc(Shop)`.
- `CustomerCardRepository.findKampagnenKandidaten(Shop, Instant erstelltAm, Pageable)` und `countKampagnenEmpfaenger(Shop)`.

- [ ] @DataJpaTest: gesperrte Karte fehlt, inaktive Karte fehlt, schon versorgte (kampagneAm > erstelltAm) fehlt, alte Zeile (NULL) dabei, gestern versorgte dabei; Zaehler stimmt.
- [ ] Implementieren, gruen.

### Task 4: KampagnenService

**Files:** Create `service/KampagnenService.java`; Modify `wallet/GoogleWalletService.java` (`sendeNachricht` mit Kennung); Test `service/KampagnenServiceTest.java`

**Produces:**
- `Kampagne starte(Shop shop, String text)`: `sauber` (Cntrl -> Leerzeichen, trim), 1..160 sonst IllegalArgumentException; `heuteMoeglich(shop)` sonst IllegalArgumentException("Heute wurde schon eine Kampagne verschickt"); `shop.nachrichtenEinschalten(jetzt)` + save; empfaenger = count; Kampagne LAEUFT speichern.
- `boolean heuteMoeglich(Shop)`: keine Kampagne seit Tagesbeginn Europe/Berlin.
- `@Scheduled(fixedDelay = 30_000) void lauf()`: fuer jede LAEUFT-Kampagne `verarbeite(k, jetzt)`.
- `void verarbeite(Kampagne, Instant)`: tx (TransactionTemplate) markiert eine Seite (`SEITE = 25`), danach Pushes, leere Seite -> `beenden`.
- `GoogleWalletService.sendeNachricht(String ccId, String titel, String text, String kennung)`; 3-arg delegiert mit "winback".

- [ ] Tests mit Mocks wie `RueckholTest`: Textpruefung; Tageslimit; nachrichtenSeit gesetzt; nur Google -> `kampagneErhalten` + `sendeNachricht(..., "kampagne")` + apns nie; iPhone alter Pass -> erster Lauf nur apns, Text leer, zweiter Lauf Text; leere Seite -> FERTIG + fertigAm; Push-Fehler bricht nicht ab.
- [ ] Implementieren, gruen.

### Task 5: Controller

**Files:** Create `controller/KampagnenController.java`; Test `controller/KampagnenControllerTest.java`

- `GET /api/shop/kampagnen` -> `KampagnenStand(int empfaenger, boolean heuteMoeglich, List<KampagneItem> items)`
- `POST /api/shop/kampagnen` `KampagneRequest(String text)` -> `KampagneItem(id, text, status, empfaenger, gesendet, erstelltAm, fertigAm)`.

- [ ] Test: POST ruft `starte` und gibt Item; GET liefert Zaehler und Liste.
- [ ] Implementieren, komplette Suite `./mvnw -o -q test` gruen.

### Task 6: Frontend

**Files:** Create `src/components/KampagnenSection.jsx`; Modify `src/pages/Dashboard.jsx`, `src/i18n.js` (de/en/ar)

- [ ] Sektion: Kopf (Titel, "N Kunden erreichbar"), Knopf Verlauf / Schreiben; Textarea 160 + Zaehler; Vorschau Sperrbildschirm; Senden mit Rueckfrage (`confirm` wie Newsletter); Fehlerfall 400 zeigt Servertext; Verlauf mit Status, Polling bei RUNNING; alte Server (404) -> Hinweis "Server wird gerade aktualisiert".
- [ ] i18n-Schluessel `kamp_*` in allen drei Sprachen.
- [ ] `node --test src/i18n.test.js`, `npm run build`, Lint der neuen Datei sauber.
