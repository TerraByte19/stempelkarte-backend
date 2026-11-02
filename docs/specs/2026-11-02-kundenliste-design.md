# Kundenliste mit Stempel-Korrektur

Stand: 2026-11-02.

## Ziel

Der Laden sieht im Dashboard, wer eine Karte von ihm hat: Name, E-Mail, Karte,
Stand, Praemien, letzter Besuch. Suche nach Name oder E-Mail. Bei
Stempelkarten kann er den Stand von Hand setzen ("mein Stempel fehlt", "aus
Versehen zwei gegeben").

## Entscheidungen

- **Eine Zeile pro Kundenkarte**, nicht pro Person: der Stand haengt an der
  Karte. Hat jemand zwei Karten, stehen beide untereinander (gleicher Name).
- **Nur aktive Karten.** Kunden einer deaktivierten Karte sind nicht mehr
  bedienbar.
- **Korrektur = absoluter Wert 0..Schwelle.** Kein Plus/Minus-Protokoll, keine
  Begruendung. Die Schwelle ist die Obergrenze: "voll" heisst beim naechsten
  Scan einloesen, wie nach einem normalen Stempel.
- **Keine Korrektur bei** Punktekarten (geht im Scanner, `/api/points/correct`)
  und gesperrten Willkommenskarten (erst Geschenk einloesen).
- **Kein ScanLog-Eintrag.** Eine Korrektur ist kein Besuch; `lastVisitAt`
  bleibt stehen, die Statistik zaehlt keinen Scan. Wie beim Reset.
- **Wallet:** nach der Korrektur stiller Push (Apple zeigt ueber das
  changeMessage "Update! Dein Stempelstand: n/m", Google bekommt nur das
  Update, keine Mitteilung "Neuer Stempel").

## API

`KundenController`, `/api/shop/kunden`, JWT wie ShopController.

- `GET ?q=&page=0&size=25` -> `{ items: [Kunde], page, totalPages, totalItems }`,
  sortiert nach letztem Besuch (neueste zuerst). `size` 1..100.
  Kunde: `customerCardId, customerId, name, email, cardId, cardName, type,
  stamps, rewardThreshold, pointsX100, pointsText, totalRewards, lastVisitAt,
  createdAt, marketingConsent, emailConfirmed, welcomePending, apple`.
  `apple` = iPhone hat sich fuer den Pass registriert (Android ist nicht
  erkennbar).
- `PUT /{customerCardId}/stempel` `{stamps}` -> Kunde. 400 bei Punktekarte,
  gesperrter Karte oder Wert ausserhalb 0..Schwelle; 404 bei fremder Karte.

## Daten

Keine neuen Spalten. `CustomerCard.korrigiereStempel(int)` setzt `stamps`
und bewegt `updatedAt` (Pass-Cache), nicht `lastVisitAt`.

## Dashboard

Neue Seite `/kunden` (Navigation "Kunden"): Suchfeld, Liste mit Name, E-Mail,
Karte, Stand, Praemien, letzter Besuch, Abzeichen (Geschenk offen, iPhone,
Werbung ok, E-Mail unbestaetigt). Bei Stempelkarten Knopf "Stempel
korrigieren" mit Zahlenfeld, -1/+1 und Speichern. Seitenweise, 25 pro Seite.
Texte de/en/ar. Altes Backend (404): Hinweis "Server wird aktualisiert".

## Tests

- `KundenAbfragenTest` (@DataJpaTest): Suche nach Name und E-Mail ohne
  Gross/Klein, nur eigener Laden, nur aktive Karten, Reihenfolge.
- `KundenServiceTest`: Bereich, Punktekarte, gesperrt, fremder Laden,
  lastVisitAt unveraendert, kein ScanLog, Zeile fuer das Dashboard.
