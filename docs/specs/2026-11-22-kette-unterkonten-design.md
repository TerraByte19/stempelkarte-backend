# Kette mit Unterkonten – Design

Stand: 2026-11-22. Noch nichts gebaut. Anlass: BackBaron, 33 Filialen, Start mit 3.
Ersetzt die Punkte 1–6 und 10 aus `docs/plans/2026-11-09-kette-33-filialen-plan.md`.

## Ziel

Eine Kette läuft als **ein** Laden mit **einer** Karte und **einem** QR-Code.
Das Hauptkonto darf alles wie heute. Jede Filiale bekommt ein eigenes
Unterkonto mit wenigen Rechten. Die Zahl der Filialen ist nicht begrenzt.
Für die Kette geht keine einzige E-Mail raus.

**Oberste Regel:** Für bestehende Läden ändert sich nichts. Alles Neue greift
nur bei Tarif KETTE oder bei gesetztem Schalter.

## Entscheidungen (Adham, 2026-11-22)

| Thema | Entscheidung |
|---|---|
| Aufbau | Ein Laden, ein Hauptkonto, eine Karte, ein Anmelde-QR für alle Filialen |
| Unterkonto darf | scannen, eigene Statistik, eigene Scanner-Geräte, Stempel korrigieren |
| Unterkonto darf nicht | Karten, Nachrichten, Mails, Einstellungen, Schnittstellen, andere Filialen |
| Login Unterkonto | Benutzername + Passwort, vom Hauptkonto vergeben, keine Mail |
| E-Mails | für die Kette komplett aus; im Admin je Laden ein- und ausblendbar |
| Push | nur Hauptkonto |
| Kunden | Karte ohne Anmeldung („Kunde 12345“) |
| Statistik | je Filiale: Stempel, neue Kunden, eingelöste Prämien, aktive Kunden; Hauptkonto sieht Tabelle aller Filialen plus Summe |
| Neuer Kunde | zählt in der Filiale des ersten Stempels |
| Wallet-Orte | vor dem ersten Stempel keine; danach 10 Filialen, lernend (Entscheidung 2026-11-09) |
| Filialen eintragen | Liste einfügen (Name + Adresse), System sucht den Ort, Kontrolle vor dem Speichern |
| Aktiv-Schalter | je Filiale; schalten dürfen nur das Hauptkonto und der Admin, die Filiale selbst nicht; keine Obergrenze |
| Hauptstandort | Schalter im Hauptkonto „Hauptstandort ist auch eine Filiale“, Standard ja |
| Stempel-Korrektur | Filiale darf frei korrigieren, jede Korrektur steht mit Filiale im Protokoll |
| Wer verwaltet | Hauptkonto selbst; Admin kann eingreifen (anlegen, Passwort neu, sperren) |
| Später | Download der Statistik, Kurven, Rangliste, Kampagnen je Filiale |

## Stand heute (aus dem Code-Durchgang 2026-11-22)

- Ein `Shop` = ein Login. `JwtAuthFilter` liest die Rolle im Token **nicht**:
  jedes gültige Token mit einer Laden-ID als `sub` wird Inhaber (`ROLE_SHOP`).
- 93 Endpunkte unter `/api/shop/**`, alle nur per `hasRole("SHOP")` geschützt.
  Controller holen den Laden per Cast auf `ShopPrincipal`; ein anderer
  Principal-Typ ergäbe HTTP 500.
- Ein ausgestelltes Token gilt 7 Tage, es gibt keinen Widerruf.
- Filialen = `ShopOrt` plus Hauptstandort am `Shop` (in der DB `ort_id = null`,
  in der API `"HAUPT"`). Grenze 10: `OrteService.MAX_WEITERE = 9`,
  `AdminController.setMaxOrte`, `ApplePassService.MAX_ORTE`, Admin-Auswahl 0–9.
- `Shop.maxTokens` = 3 je Laden, nach dem Anlegen nicht mehr änderbar.
- `ScanLog` hat `ort_id`, aber keine Ereignis-Art. Eine eingelöste Prämie ist
  von einem Stempel nicht zu unterscheiden. Korrektur, Reset, Aufladen und
  Willkommensgeschenk schreiben kein `ScanLog`.
- Statistik (`StatsService`) ist ladenweit und lädt alle Kundenkarten in den
  Speicher. `FilialenService.stand` zählt in Java statt in der Datenbank.
- Mails: genau 5 Auslöser, alle über `EmailService.send(...)`:
  Anmelde-Bestätigung, Lösch-Mail, Newsletter, Test-Newsletter,
  Testphasen-Erinnerung. Push läuft getrennt.
- Frontend: jedes 401/403 loggt aus (`src/api.js:26-30`), keine Rollen, alle
  Menüpunkte immer sichtbar, Login holt das erste Scanner-Gerät des Ladens.

## Bauteile

### 1. Unterkonto (Backend)

Neue Entität `FilialZugang`, Tabelle `filial_zugaenge`:

- `id` („FZ-…“), `shop` (Pflicht), `ort_id` (null = Hauptstandort)
- `benutzername` (eindeutig über alle Läden, klein geschrieben, 3–40 Zeichen,
  Buchstaben/Ziffern/Bindestrich), `passwort_hash` (BCrypt)
- `gesperrt` (Boolean), `token_version` (int), `created_at`, `last_login_at`

Login: `POST /api/auth/login` nimmt im selben Feld E-Mail oder Benutzername.
Enthält der Wert kein `@`, wird ein `FilialZugang` gesucht. Dieselbe
Sperre gegen Rateversuche (`LoginAttemptService`). Antwort wie heute, dazu
`rolle: "FILIALE"`, `ortId`, `ortLabel`.

Token: `sub` = FilialZugang-ID, `role = "FILIALE"`, `shop`, `ver`
(token_version), Laufzeit 7 Tage.

`JwtAuthFilter` wird streng:

- `role = "SHOP"` → wie heute `ShopPrincipal`, `ROLE_SHOP`.
- `role = "FILIALE"` → Zugang laden; nur wenn vorhanden, nicht gesperrt,
  `ver` gleich, Laden aktiv, Tarif KETTE und Filiale aktiv → `FilialPrincipal(zugang, shop)`
  mit `ROLE_FILIALE`. Sonst anonym.
- Fehlende oder unbekannte Rolle → anonym. (Alte Laden-Tokens tragen schon
  `role = "SHOP"`, niemand wird abgemeldet.)

Passwort neu setzen oder Sperren erhöht `token_version`: alte Tokens sind
sofort tot.

**Eigener Pfad statt Ausnahmen:** Unterkonten erreichen nur
`/api/filiale/**` (`hasRole("FILIALE")`). Unter `/api/shop/**` bleibt alles
bei `hasRole("SHOP")`. So kann kein bestehender Endpunkt versehentlich offen
sein, und kein Cast bricht.

Endpunkte `/api/filiale`:

| Methode | Pfad | Zweck |
|---|---|---|
| GET | `/ich` | Name der Kette, eigene Filiale |
| GET | `/statistik?von&bis` | die vier Zahlen der eigenen Filiale |
| GET | `/geraete` | Scanner-Zugänge **nur** der eigenen Filiale |
| POST | `/geraete` | neues Gerät, Filiale wird fest gesetzt |
| PUT / DELETE | `/geraete/{id}` | umbenennen, Rolle, entfernen – nur eigene |
| POST | `/korrektur/karte` | Karten-QR auflösen: Stand der Karte, ohne Name |
| PUT | `/korrektur/{customerCardId}` | Stempel setzen |

Geräte: Obergrenze je Filiale statt je Laden (`Shop.maxTokensJeFiliale`,
null = 5, im Admin änderbar). `{id}` ist eine neue kurze Kennung am
`StaffToken`, nicht mehr der geheime Wert in der Adresse.

Scannen bleibt wie heute über `X-Staff-Token`. Das Unterkonto legt beim
ersten Öffnen des Scanners ein Gerät „Dieses Gerät“ für seine Filiale an.

### 2. Hauptkonto verwaltet Filialen (`/api/shop/...`)

- `ShopOrt` bekommt `adresse` (nullable) und `aktiv` (Boolean, **null = aktiv**,
  damit bestehende Standorte bleiben, wie sie sind).
- `PUT /me/orte/{id}`: umbenennen, Koordinaten, aktiv.
- `POST /me/orte/vorschau`: Liste (eine Filiale je Zeile, „Name, Adresse“ oder
  „Name; Breite; Länge“) → Treffer mit Koordinaten, speichert nichts.
- `POST /me/orte/sammel`: speichert die geprüfte Liste, neue Filialen sind
  zunächst **nicht aktiv**.
- `GET|POST|PUT|DELETE /filial-zugaenge`: Logins anlegen, Passwort setzen,
  sperren, löschen. Passwort wird nur beim Setzen einmal gezeigt.
- `GET /filialen/statistik?von&bis`: Tabelle aller Filialen plus Summe.

Adresse → Ort: OpenStreetMap-Suche (Nominatim), höchstens 1 Anfrage je
Sekunde, eigener Dienst `Geocoder` mit Schnittstelle, damit er austauschbar
ist. Übertragen wird nur die Adresse der Filiale, keine Kundendaten. Findet
er nichts, bleibt die Zeile rot und kann von Hand ergänzt werden.

Grenze: bei Tarif KETTE zählt `MAX_WEITERE` nicht mehr. Technische
Notbremse 500 Filialen. Für BASIC/PRO bleibt alles wie heute (Test
`OrteServiceTest.tarif_entscheidet` wird angepasst).

**Nicht aktive Filiale:** nicht in der Wallet-Karte, nicht in
`GeoPruefung`/`ortFuer`, keine Zeile in der Statistik-Tabelle, Scannen an
ihren Geräten wird abgelehnt („Diese Filiale ist nicht eingeschaltet“). Das
Unterkonto kann sich nicht einloggen und bekommt denselben Hinweis; ein
laufendes Token wird im Filter abgewiesen. Einschalten kann nur das
Hauptkonto oder der Admin.

**Hauptstandort:** `Shop.hauptIstFiliale` (Boolean, null = ja).
`PUT /api/shop/me/haupt-filiale`. Bei nein fehlt der Hauptstandort in
Statistik-Tabelle, Wallet-Orten und in der Filial-Auswahl für Geräte und
Logins. Nur bei Tarif KETTE wirksam.

### 3. Statistik je Filiale

`ScanLog` bekommt zwei nullable Spalten:

- `eingeloest` (Integer): eingelöste Prämien in diesem Vorgang.
- `art` (String, null = normaler Scan): `KORREKTUR`, `RESET`.

Gesetzt in `CustomerService.stempeln` (Einlösen im Stempel-Lauf),
`PointsService.redeem`, `resetCard` und in der Korrektur.

Zahlen je Filiale, alle per `GROUP BY ort_id` in der Datenbank:

- **Stempel:** Summe `stamps_added`, ohne `art = KORREKTUR`.
- **Eingelöste Prämien:** Summe `eingeloest`. Gilt erst ab Einführung.
- **Aktive Kunden:** verschiedene Kunden mit mindestens einem Scan im Zeitraum.
- **Neue Kunden:** Kundenkarten, deren allererster Scan in dieser Filiale und
  im Zeitraum liegt.

Neuer Index `idx_scanlog_ort(shop_id, ort_id, scanned_at)`.
`FilialenService.stand` wird auf dieselben Abfragen umgestellt.

### 4. Wallet-Orte, lernend

Nur bei Tarif KETTE. Sonst bleibt `ApplePassService.orte()` unverändert.

- Kein Scan für diese Kundenkarte → keine Orte.
- Sonst: Filialen mit Besuchen, sortiert nach Anzahl, bei Gleichstand jüngster
  Besuch; höchstens 10. Freie Plätze füllen die nächsten aktiven Filialen zur
  meistbesuchten (Luftlinie, `GeoPruefung.distanzMeter`).
- Nur aktive Filialen.
- Eine Abfrage je Pass (`ScanLog` nach `ort_id` gruppiert, über
  `idx_scanlog_kunde`). Wirkt beim nächsten ohnehin fälligen Pass-Update.

Keine neue Spalte `stamm_ort_id` nötig: die Stammfiliale ergibt sich aus den
Scans.

### 5. Mails aus

- `Shop.mailsAus` (Boolean, null = Mails an). Admin:
  `POST /api/admin/shops/{id}/mails`. Geht mit in `GET /api/shop/me` und
  `GET /api/shop/tarif` (`funktionen.newsletter = false`, neu
  `funktionen.mails`).
- Sperre **vor** dem Versand, nicht erst in `send()`:
  - `POST /newsletter`, `/newsletter/test` → 402 `code: "MAILS_AUS"`.
  - `TestphaseErinnerungService` überspringt den Laden.
  - `CustomerService.anmelden`: keine Bestätigungsmail.
  - `PublicEmailController.deleteRequest`: keine Lösch-Mail, wenn **alle**
    Läden dieses Kunden Mails aus haben.
- Zusätzlich als Netz: `EmailService`-Methoden mit `Shop` prüfen den Schalter
  noch einmal.
- Bei Mails aus **und** „Karte ohne Anmeldung“ lehnt
  `POST /karte-neu/register` die Anmeldung mit Name/E-Mail ab und leitet auf
  die Ein-Knopf-Seite. (Schließt die heutige Lücke, dass dort trotz Schalter
  eine Mail rausgeht.)

### 6. Admin-Panel

Je Laden:

- Schalter **Mails** (an/aus).
- Tarif KETTE schaltet Unterkonten und die freie Filialen-Zahl frei. Die
  Auswahl „Filialen 0–9“ bleibt für BASIC/PRO, bei KETTE steht dort
  „aktiv: 3 von 33“.
- Feld **Geräte je Filiale**.
- Aufklappbar **Filialen**: Liste mit aktiv ja/nein, Login-Name, zuletzt
  eingeloggt; Knöpfe „Filiale anlegen“, „Passwort neu“, „Login sperren“.

Neue Admin-Endpunkte unter `/api/admin/shops/{id}/filialen...`, jeder Eingriff
wird wie der Support-Zugang in `AdminZugriff` protokolliert.

### 7. Frontend

Zuerst die Grundlage, sonst fliegt jedes Unterkonto raus:

- `src/api.js`: nur noch 401 loggt aus. 403 zeigt eine Meldung.
- Neue `src/rolle.js` nach dem Muster von `tarif.js`: liest `role` aus dem
  Token; wird in `Login` und `supportStarten` zurückgesetzt.
- `Login.jsx`: Feld „E-Mail oder Benutzername“. Für Unterkonten kein Abruf
  von `/api/shop/staff-tokens`.
- `App.jsx` / `Layout.jsx`: Menü und Routen aus einer Liste je Rolle.
  Unterkonto sieht: **Scanner, Statistik, Geräte, Korrektur**.
  Alle anderen Pfade leiten auf die Startseite des Unterkontos.

Seiten Unterkonto: Statistik (vier Kacheln, Zeitraum), Geräte (Liste, neu,
Link), Korrektur (Karte scannen → Stand ändern).

Hauptkonto, Seite Team: Tabelle „Filialen“ mit Schalter aktiv und
Umbenennen, Knopf „Liste einfügen“ mit Vorschau, Block „Filial-Logins“.
Seite Statistik: neue Tabelle aller Filialen plus Summe.

Mails aus: `NewsletterSection`, Kachel „Newsletter-Reichweite“, die
Mail-Hinweise im Profil und die Abzeichen „Werbung ok“ / „E-Mail
unbestätigt“ werden ausgeblendet.

Neue Texte in `i18n.ausbau.js`, alle vier Sprachen (Test erzwingt das).

## Fehlerfälle

- Benutzername vergeben → 409 mit Klartext.
- Unterkonto gesperrt oder Passwort geändert → nächster Aufruf 401, Login-Seite.
- Unterkonto greift auf fremdes Gerät/fremde Karte zu → 404, nicht 403.
- Filiale mit Geräten oder Login wird gelöscht → Geräte fallen wie heute auf
  den Hauptstandort zurück, Login wird gesperrt. Scans behalten ihre `ort_id`.
- Tarif wird von KETTE zurückgestellt → Unterkonten können sich nicht mehr
  einloggen, Daten bleiben.

## Tests

Backend, je Bauteil vor dem nächsten:

- Filter: SHOP-, FILIALE-, rollenloses, gesperrtes, veraltetes Token
  (Muster `SupportTokenFilterTest`).
- Durch die echte Filterkette: Unterkonto bekommt auf **jedem** der 93
  `/api/shop`-Endpunkte 403; Inhaber bekommt auf `/api/filiale/**` 403
  (Muster `EntfernteEndpunkteTest`).
- Unterkonto A sieht und ändert nichts von Filiale B.
- Statistik-Abfragen gegen H2 mit zwei Filialen und Hauptstandort.
- Wallet-Orte: kein Scan, ein Scan, 12 besuchte Filialen, inaktive Filiale.
- Mails aus: jeder der 5 Auslöser sendet nichts; Push läuft weiter.
- Bestehende Läden: alle heutigen Tests bleiben grün, bis auf den einen
  angepassten Grenz-Test.

Frontend: node-Tests für `rolle.js` und die Menü-Liste, i18n-Test, Build.
Am Ende gesammelt auf dem Handy, nur mit Testkarten in einem Test-Laden.

## Reihenfolge

Backend zuerst, Frontend am Ende in einem Guss.

1. Filter streng + `FilialZugang` + Login + `/api/filiale/ich`
2. `ShopOrt.aktiv`/`adresse`, Grenze bei KETTE, Bearbeiten, Sammel-Anlage
3. `ScanLog.eingeloest`/`art`, Statistik je Filiale
4. Geräte und Korrektur fürs Unterkonto
5. Wallet-Orte lernend
6. Mails aus
7. Admin-Endpunkte
8. Frontend
9. Gesamttest

Gepusht wird nur auf Ansage, Backend vor Frontend.

## Nicht Teil dieses Plans

- Download, Kurven, Rangliste, Kampagnen je Filiale, Kassenanbindung.
- Lasttest mit vielen Karten: vor dem Ausrollen auf alle 33 empfohlen, weil
  `StatsService.summary` und die Karten-Kacheln heute alle Kundenkarten bzw.
  Scans laden.
- Unterlagen für die Rechtsabteilung (AVV, Server-Region, Löschfristen).
- Nebenfund: Link `/scanner-setup` hat im Frontend keine Route mehr. Eigener
  kleiner Fix, unabhängig von der Kette.

## Offene Fragen

Keine. Geklärt am 2026-11-22: Aktiv-Schalter nur Hauptkonto und Admin,
Hauptstandort per Schalter, Korrektur frei mit Protokoll.
