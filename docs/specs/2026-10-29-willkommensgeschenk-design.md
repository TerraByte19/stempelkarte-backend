# Willkommensgeschenk ("Erster Kaffee geht auf uns")

Stand: 29.10.2026 - Spec, freigegeben im Gespraech.

## Ziel

Laufkundschaft vom Aushang in den Laden holen. Wer den Aushang-QR scannt und
sich anmeldet, bekommt eine **gesperrte** Karte: sie zeigt nur das Geschenk
und den QR-Code. An der Kasse loest das Personal das Geschenk ein, ab dann
ist es eine normale Stempelkarte mit 0 Stempeln.

## Entscheidungen

| Frage | Entscheidung |
|---|---|
| Startstand nach dem Geschenk | 0 Stempel |
| Kartentypen | nur Stempelkarten (Punktekarten unberuehrt) |
| Einstellung | pro Karte, Schalter + Text (max. 60 Zeichen) |
| Laden schaltet aus | bereits gesperrte Karten bleiben einloesbar |

## Harte Regeln

1. **Keine bestehende Kundenkarte wird je gesperrt.** Gesperrt wird nur eine
   Kundenkarte, die in `registerForCard` NEU entsteht, waehrend die Karte das
   Angebot an hat.
2. Alle neuen Spalten nullable (`ddl-auto: update`, Postgres mit Bestand).
   `null` heisst: wie bisher.
3. Der Stempel-Weg aendert sich fuer nicht gesperrte Karten um kein Feld,
   keinen Text, keine Meldung.
4. Einloesen geht genau einmal - auch bei zwei Geraeten gleichzeitig.

## Daten

`Card`
- `welcome_enabled` Boolean (null = aus)
- `welcome_text` varchar(60) (null = Standard "Erster Kaffee geht auf uns")
- `isWillkommenAktiv()` = an UND Stempelkarte

`CustomerCard`
- `welcome_pending` Boolean (null/false = offen wie bisher)
- `welcome_text` varchar(60): das Versprechen zum Zeitpunkt der Anmeldung.
  Aendert der Laden spaeter seinen Text, behaelt der Kunde seins.
- `welcome_redeemed_at` Instant
- `willkommenVormerken(text)`, `isWillkommenOffen()`, `willkommenEinloesen(jetzt)`
  (setzt pending=false, redeemedAt, updatedAt, lastVisitAt)
- `istFrischFreigeschaltet()`: eingeloest, 0 Stempel, 0 Belohnungen - steuert
  die "Karte freigeschaltet"-Meldung im Pass.

## Ablauf und betroffene Stellen

### Anmeldung (`CustomerService.registerForCard`)
- Nur im `orElseGet`-Zweig (Kundenkarte neu): bei `card.isWillkommenAktiv()`
  vormerken, Text aus der Karte kopieren.
- Bestehende Kundenkarte: nichts aendern (zweite Anmeldung = kein zweites Geschenk).
- `getOrCreateCustomerCard` (Scan, Kartenseite, Pass-Download) sperrt NIE.
- Kein Pruefen auf `emailConfirmed` beim Einloesen: `requireNewConfirmation`
  setzt das bei jeder weiteren Kartenanmeldung zurueck - ein Kunde, der sich
  fuer Karte B anmeldet und den Link noch nicht klickt, verloere sonst das
  Geschenk von Karte A. Der Kunde kommt ohnehin nur ueber den Mail-Link an
  seinen QR.

### Bestaetigungs-Mail (`EmailService.sendConfirmationMail`)
- Neuer optionaler Parameter `willkommenText`. Gesetzt: ein Satz "Dein
  Willkommensgeschenk: ... - zeig die Karte einfach an der Kasse." Sonst
  unveraendert. Layout bleibt Tabellen (MailVorlageTest).

### Anmeldeseite `/karte-neu/{cardId}`
- Angebot an: Info-Box zeigt "🎁 <Text>" + "Melde dich an und zeig die Karte
  an der Kasse." Sonst unveraendert.

### Kundenseite `/karte/{cid}/{cardId}` (Stempel-Zweig)
- Gesperrt: Geschenk-Box statt Raster/Fortschritt/Belohnung. Wallet-Knoepfe
  bleiben (so kommt der Pass aufs Handy).
- Raster-HTML liegt trotzdem im DOM (versteckt). Das Polling bekommt
  `willkommenOffen` im JSON; wechselt es auf false -> Box ausblenden, Raster
  zeigen, `renderStamps`. Kein Reload (Regel aus Hotfix 1).
- SSE `stamps`-Ereignis: bei gesperrter Seite zusaetzlich `check()`.

### `GET /api/customer/{cid}/card/{cardId}`
- `CustomerCardResponse` + `willkommenOffen` (boolean). Feld nur angehaengt.

### Apple-Pass (`ApplePassService.baueFelder`)
- Gesperrte Stempelkarte, eigener Zweig VOR dem normalen:
  - Header: gleicher Key wie der Stil (`stamps` bei grid, sonst
    `stamps-header`), Label "WILLKOMMEN", Wert "🎁". Gleicher Key ist
    Absicht: beim Freischalten aendert sich genau dieses Feld, und nur so
    zeigt iOS die Meldung.
  - Primaer `welcome`: Label "GESCHENK FÜR DICH", Wert = Text der Kundenkarte.
  - Sekundaer `welcome-how`: "SO GEHT'S" / "An der Kasse vorzeigen".
  - Auxiliary `name` wie bisher. Rueckhol-Feld wie bisher (nachrichtFeld).
- Frisch freigeschaltet: changeMessage am Header
  "🎉 Karte freigeschaltet! Stempel: %@" statt "Update! ...".
  Danach (erster Stempel) wieder der normale Text.
- Kein Streifen bei gesperrter Karte (`PassTemplateGenerator`: Streifen-Zweig
  nur, wenn nicht gesperrt; `deleteStripImages` laeuft immer).
- Sperrbildschirm: gesperrt -> relevantText "<Text> wartet auf dich".
- QR-Inhalt unveraendert (cid + cardId).

### Google-Pass (`GoogleWalletService.createOrUpdateObject`)
- Gesperrt: Haupt "Willkommen" = Text, Neben "So geht's" = "An der Kasse
  vorzeigen". QR unveraendert.
- Freischalten: `notifyFreigeschaltet` per addMessage (wie notifyCardFull),
  404 = Kunde hat keine Google-Karte, still.

### Scanner-Backend
- `PointsService.state`: Stempel-Zweig liefert `willkommenOffen` und
  `willkommenText` (am Ende des Records angehaengt).
- `CustomerService.processScan`: gesperrt -> IllegalArgumentException (400)
  "Erst das Willkommensgeschenk einlösen - bitte Scanner neu laden."
  Liegt in processScan, damit JEDER Stempel-Weg (auch der NFC-Branch, der
  processScan aufruft) gesperrt ist. NICHT IllegalStateException: die wird
  als 503 gemeldet und saehe nach Serverausfall aus.
- Neu: `CustomerService.willkommenEinloesen(qr, shop)`:
  Karte des Ladens? aktiv? Kundenkarte mit `@Lock(PESSIMISTIC_WRITE)` laden,
  pending pruefen, einloesen, speichern. Nicht offen -> 400 "Das
  Willkommensgeschenk wurde schon eingelöst." Keine Kundenkarte -> 404 wie
  beim Punkte-Weg.
- Neu: `POST /api/scan/willkommen` {qrPayload, lat, lon}: gleiche Staff-Token-,
  Standort- und Geo-Pruefung wie `/api/scan`. Danach
  `notifier.nachStempelAenderung(cc)` + Google-Meldung. Antwort ScanResponse
  mit action "welcome".
- `resetCard` laesst die Sperre stehen (setzt nur Staende zurueck).
- Kein ScanLog fuer das Einloesen: der ScanLog zaehlt vergebene Stempel, ein
  Eintrag mit 0 wuerde Statistiken verzerren.

### Rueckhol-Nachricht
- Gesperrte Karten ausgenommen - in `findRueckholKandidaten` UND in
  `CustomerCard#rueckholFaellig` (beide Regeln muessen gleich bleiben).
  Grund: der Text sagt sonst "Noch 10 Stempel bis ...". Nach dem Einloesen
  ist `lastVisitAt` gesetzt, die Pause zaehlt ab dem Gratis-Besuch.

### Laden-Einstellung
- `PUT /api/shop/cards/{cardId}/willkommen` {enabled, text}. Punktekarte -> 400.
  Text getrimmt, Steuerzeichen raus, max. 60, leer = Standard.
- `GET /api/shop/cards/{cardId}/willkommen` -> {enabled, text, offen,
  eingeloest}. Eigene Route statt Felder in `CardResponse`: die Zaehler
  kosten Abfragen, die Kartenliste soll nicht pro Karte dafuer zahlen.

### Frontend
- `components/WillkommenSettings.jsx`: Schalter, Text, Hinweis, Zaehler.
  In `Karten.jsx` nur im Stempel-Zweig des Editors und nur wenn die
  GET-Route antwortet (Frontend ist oft neuer als Backend - bei 404 bleibt
  der Abschnitt weg). Speichern mit dem bestehenden Speichern-Knopf, nur bei
  Aenderung.
- `Scanner.jsx`: `scanState?.willkommenOffen === true` -> eigene Maske
  (Geschenk, Name, Knopf "Gratis ausgegeben - Karte freischalten").
  Ergebnis-Box "Karte freigeschaltet". Ohne state-Antwort bleibt alles wie
  bisher; der Server lehnt den Stempel dann mit klarer Meldung ab.
- Alle Texte de, en, ar.

## Deploy

1. Backend mergen, Render-Deploy abwarten (10-20 Min), pruefen:
   `/v3/api-docs` enthaelt `/api/scan/willkommen`.
2. Dann Frontend (Vercel).
3. Testladen: Angebot an, mit echtem iPhone + Android anmelden, gesperrten
   Pass pruefen, einloesen, Push + Freischaltung pruefen, ersten Stempel
   setzen.
4. Erst danach echten Laeden zeigen.

Rueckweg: Schalter aus. Gesperrte Karten bleiben einloesbar, neue Anmeldungen
bekommen normale Karten. Kein Datenbank-Eingriff noetig.

## Nicht drin

Punktekarten, eigene Statistik-Seite, Rueckhol-Text fuer gesperrte Karten,
Arabische Backend-Texte (Backend ist bisher nur deutsch, siehe RTL-Spec).

## Restrisiko

Wer mehrere echte Postfaecher hat, kann mehrfach abraeumen. Ebenso nach einer
DSGVO-Loeschung. Die Mail-Bestaetigung bremst, verhindert es nicht.
