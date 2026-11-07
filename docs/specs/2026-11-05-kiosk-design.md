# Selbst-Stempeln am Tablet (2026-11-05)

## Ziel

Ein Tablet an der Theke, der Kunde hält seine Karte vor die Kamera und bekommt den Stempel ohne Personal.
Wie bei gobonki („Self-Service“) und Bonuno, aber ohne eigene App.

## Entscheidungen (User, Nacht auf 2026-11-05)

- Sperrzeit nach einem Stempel stellt der Laden selbst ein: 1 bis 24 Stunden, Standard 2 (`Shop.kioskSperreStunden`, Spalte `kiosk_sperre_stunden`, nullable).
- Das Tablet darf nur stempeln. Prämie, Punkte, Aufladen, Willkommensgeschenk, Reset: weiter nur das Personal.
- Nur Stempelkarten. Mitglieds-, 10er- und Punktekarten zeigt der Kunde dem Personal.

## Backend

- `StaffToken.ROLLE_KIOSK` (neben MITARBEITER und AUSHILFE). `istKiosk()`, `darfEinloesen()` ist dann false.
  `NUR_PERSONAL_TEXT` = „Das macht das Personal am Tresen.“ (403) in `/scan/aufladen`, `/scan/willkommen`, `/scan/reset` und allen Punkte-Endpunkten.
- `KioskService.stempeln(shop, qr, jetzt, label, ortId)` läuft in `POST /api/scan` statt `processScan`, wenn der Zugang KIOSK ist; `count` ist dann immer 1. Eine Transaktion: erst die Zeile der Kundenkarte sperren (`CustomerCardRepository.sperrePerId` bzw. `sperrePerKundeUndKarte`, natives `select id … for update`, lädt nichts), dann `pruefe`, dann `CustomerService.processScan`. Zwei Tablets mit derselben Karte im selben Moment: das zweite wartet an der Sperre und meldet danach die Sperrzeit (Fix 2026-11-07; vorher lief die Prüfung ohne Transaktion, beide kamen durch, zwei Stempel). Noch keine Zeile (erste Karte): nichts zu sperren, die eindeutige Spalte Kunde+Karte fängt den zweiten Versuch.
  `pruefe(shop, qr, jetzt)` lehnt ab (400, Klartext in der Sprache des Kunden, `Texts kiosk.*`):
  - keine Stempelkarte
  - Willkommensgeschenk offen
  - Karte voll (ein Scan auf einer vollen Karte würde die Prämie einlösen)
  - letzter Scan dieser Karte im Laden liegt kürzer zurück als die Sperrzeit (`ScanLogRepository.findTopByShopIdAndCustomerIdAndCardIdOrderByScannedAtDesc`), Meldung nennt die Uhrzeit, ab der es wieder geht
- `GET /api/scan/einstellungen` liefert zusätzlich `rolle`, `kiosk`, `kioskSperreStunden`, `label`, `shopName`.
- `PUT /api/shop/me/kiosk {stunden}`; `/api/shop/me` liefert `kioskSperreStunden`.
- Standort-Pflicht und Geo-Prüfung gelten wie beim Personal, Testphasen-Sperre auch.

## Frontend

- Team: Rolle „Tablet (Kunde stempelt selbst)“ beim Zugang, darunter die Sperrzeit (1, 2, 3, 4, 6, 12, 24 h).
- Scanner: meldet `/einstellungen` `kiosk: true`, zeigt die Seite `Kiosk.jsx`: dunkler Vollbild-Modus, Kamera läuft dauernd, Stempel ohne Rückfrage, Ergebnis 4 Sekunden groß, Server-Meldungen (voll, Sperrzeit) 6 Sekunden, dann wieder Kamera. „Tablet-Modus beenden“ klein unten rechts mit Rückfrage.
- Einrichtung wie jeder Zugang: Scanner-Link auf dem Tablet öffnen, zum Homescreen legen.

## Tests

`KioskServiceTest` (14): Normalfall, erste Karte, volle Karte, Sperrzeit mit Uhrzeit, Kundensprache, nur Stempelkarten, Willkommensgeschenk, fremder Laden, Rolle, Einstellung; `stempeln`: Reihenfolge Sperre → Protokoll → ein Stempel (langer und kurzer QR), in der Sperrzeit kein Stempel, Unsinn im QR weder Sperre noch Stempel. `KioskSperreAbfragenTest` (DataJpaTest): die beiden Sperr-Abfragen laufen gegen H2, liefern die ID und laden keine Entity.
