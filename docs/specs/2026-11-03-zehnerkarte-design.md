# 10er-Karte (Besuchspaket) – Design

Stand: 2026-11-03 (nur Backend; Frontend folgt im Sammel-Durchgang)

## Ziel

Umgekehrte Stempelkarte: der Kunde kauft vorab ein Paket Besuche (z. B.
10 Yoga-Kurse, 10 Haarwäschen), jeder Scan bucht einen ab, die Karte zeigt
„Noch 7 Kurse übrig“. Bei 0 ist sie leer, das Personal lädt an der Kasse
ein neues Paket. Für Studios, Sport, Kosmetik, Autowäsche.

## Regeln

- `CardType.PACKAGE`, `Card.createPackage(shop, name, description, groesse,
  einheit)`. Paketgröße = `reward_threshold` (1–100), Prämientext leer.
  Neue Spalte `cards.paket_einheit` (Mehrzahl, max. 20 Zeichen, leer =
  „Besuche“). `Card.isPackage()`, `getPaketEinheit()`, `paketText()`
  („10 Kurse“), `updatePaket(groesse, einheit)` wirkt nur auf künftige
  Aufladungen.
- `CustomerCard`: `paket_rest` (übrig), `paket_aufgeladen_am`,
  `pakete_gekauft`, `auto_paket_leer_bei` – alle nullable, null = 0.
  `stamps` zählt wie bei der Mitgliedskarte alle Besuche hoch (Gruppen,
  Stufen, Bewertungs-Erinnerung rechnen damit). `totalRewards` bleibt 0.
- Aufladen (`paketAufladen`): Rest + Paketgröße (addiert, nichts verfällt),
  Pakete + 1, zählt als Besuch, bewegt updatedAt. Deckel 500 übrig.
- Abbuchen (`paketAbbuchen(anzahl)`): höchstens so viele wie übrig; die
  Scanner-Anzahl gilt (2 Personen auf einer Karte). 0 übrig → nichts.
- Scan auf leerem Paket: `ScanResult.Empty`, Aktion `empty`, Meldung
  „Paket leer – neues Paket (10 Kurse) aufladen?“. Kein Besuch, kein
  ScanLog, kein Webhook, kein Wallet-Update.
- Scan mit Guthaben: `ScanResult.Debited`, Aktion `debited`, Meldung
  „Abgebucht · noch 9 Kurse übrig“ bzw. „9 abgebucht · Paket leer, neues
  Paket an der Kasse“. ScanLog mit stampsAdded 0 (wie Mitglied/Punkte).
  Webhook `package.debited` mit `remaining`.
- Aufladen: Scanner `POST /api/scan/aufladen {qrPayload, lat, lon}` (Staff-
  Token, Standort- und Geo-Prüfung wie beim Stempeln, auch Aushilfen) und
  Kundenliste `POST /api/shop/kunden/{id}/aufladen`. Antwort Aktion `topup`,
  Meldung „Paket aufgeladen · noch 12 Kurse übrig“. Webhook `package.topup`
  mit `packageSize`, `remaining`, `packagesBought`.
- Korrektur: `PUT /api/shop/kunden/{id}/paket {rest}` (0–500), kein Besuch,
  kein Paket. `korrigiereStempel` lehnt 10er-Karten ab.
- Nicht für 10er-Karten: Willkommensgeschenk, Kunde wirbt Kunde,
  Prämie-wartet / fast-voll (Zielgruppen und Automaten), Rückhol-Texte mit
  Ziel (`KartenFortschritt` = null).
- Platzhalter `{offen}` = übrige Besuche.
- Zielgruppe `PAKET_LEER` (wählbar): Paket schon einmal gekauft und 0
  übrig. Automat `AutoNachrichtTyp.PAKET_LEER` / `Zielgruppe.AUTO_PAKET_LEER`:
  2–14 Tage nach dem letzten Besuch, einmal je gekauftem Paket (Merker =
  Pakete beim Versand). Standardtext „Dein Paket ist aufgebraucht. Hol dir
  beim nächsten Besuch ein neues – wir freuen uns auf dich!“
- Statistik-Zeile je Karte: `type: PACKAGE`, `besuche`, `paketEinheit`,
  `paketeGekauft`, `restGesamt`, `leer` (Karten mit 0 übrig).

## Karte

- Apple: Kopf `rest-header` „ÜBRIG“ mit changeMessage je Zustand
  („Abgebucht! Noch %@ Kurse übrig“ / „Paket aufgeladen! Noch %@ Kurse
  übrig“ / „Paket aufgebraucht (%@ übrig). Ein neues Paket gibt's an der
  Kasse!“), Hauptfeld `rest-big` (Zahl oder „Leer“), `paket` („10 Kurse“),
  `name`. Kein Streifen. Rückseite wie sonst (Nachricht, Rückmeldung,
  Tagesangebot, Status). Der Kopf-Key bleibt bei jedem Update gleich.
- Google: Hauptwert „Übrig“, Nebenwert „Paket“. Meldungen `PAKET`
  („Abgebucht bei …“, „Noch 7 Kurse übrig.“ bzw. „Dein Paket ist
  aufgebraucht …“) und `AUFGELADEN` („10 Kurse dazu. Noch 12 Kurse übrig.“).
- Kundenseite: Rest groß, Einheit, Hinweis bei leer („aufgebraucht“ /
  „noch kein Paket“), Paket, Genutzt, Name, Wallet-Knöpfe, Status,
  Bewertungslinks.

## API

- `POST /api/shop/cards/package {name, description, groesse, einheit,
  Farben, logoUrl, heroImageUrl}` → CardResponse mit `type: PACKAGE`,
  `paketEinheit`.
- `PUT /api/shop/cards/{id}/paket {groesse, einheit}`.
- `POST /api/scan` / `POST /api/v1/stamps`: Aktionen `debited` / `empty`,
  Antwort trägt `paketRest` bzw. `remaining`. `POST /api/scan/state` liefert
  jetzt den echten Kartentyp plus `paketRest`, `paketEinheit`.
- Kundenliste (`Kunde`): `paketRest`, `paketeGekauft`. API v1
  `KundenkarteView.remaining`. Kundenseite-JSON `paketRest`.

## Frontend offen

- Karten anlegen: vierter Typ „10er-Karte“ (Name, Beschreibung,
  Paketgröße, Einheit, Design). Karten-Editor: Paketgröße/Einheit statt
  Stempel/Prämie (`PUT /cards/{id}/paket`).
- Scanner: Ergebnis `debited` („Abgebucht · noch 9 Kurse übrig“) und
  `empty` mit Knopf „Paket aufladen“ (`POST /api/scan/aufladen`); nach
  `state` bei `type: PACKAGE` den Rest und den Aufladen-Knopf zeigen; keine
  Knöpfe für Einlösen/Reset.
- Dashboard-Kachel: „Übrig gesamt“, „Pakete gekauft“, „leer“ bei
  `type: PACKAGE`.
- Kundenliste: „7 von 10 übrig“ statt Stempelstand, Knöpfe „Aufladen“ und
  „Guthaben korrigieren“.
- Kampagnen: Zielgruppe `PAKET_LEER` („Paket aufgebraucht“), Automat
  `PAKET_LEER` in der Liste der automatischen Nachrichten.
- Verlauf/Kampagnen-Namen (`kamp_g_*`) für `PAKET_LEER`, `AUTO_PAKET_LEER`.
