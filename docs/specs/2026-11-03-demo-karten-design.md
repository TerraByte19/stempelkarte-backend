# Demo-Karten – Design

Stand: 2026-11-03 (nur Backend; Frontend/Website folgt im Sammel-Durchgang)

## Ziel

Besucher der Website probieren eine Karte selbst aus: Karte holen, in die
Wallet legen, auf der Kartenseite „Nächster Schritt“ tippen und auf dem
Handy sehen, was ein Kunde sieht. Kein Scanner, kein Standort, kein
Personal.

## Regeln

- `Shop.demo` (nullable, Admin: `POST /admin/shops/{id}/demo {demo}`,
  Liste zeigt `demo`). Der Admin legt einen normalen Laden an, richtet die
  Karten ein (Stempel, Punkte, Mitglied, 10er) und schaltet Demo ein.
- Öffentlich (`/api/public/**` ist frei):
  - `GET /api/public/demo` → Karten aller aktiven Demo-Läden mit Link
    `{baseUrl}/karte-neu/{cardId}` (für die Website).
  - `POST /api/public/demo/stempel {customerId, cardId}` → macht, was an
    der Kasse als Nächstes passieren würde: Willkommensgeschenk einlösen
    (wenn offen), Punktekarte: Einkauf über 12,50 € buchen, 10er-Karte leer:
    Paket aufladen, sonst ein Scan (Stempel / Besuch / Abbuchen). Antwort
    `{action, message, stamps, totalRewards, rewardThreshold, pointsX100,
    paketRest}`. Alle Wallet-Wege laufen wie beim echten Scan (Apple-Push,
    Google-Mitteilung, Kartenseite).
  - Nur Karten von Demo-Läden (sonst 400), Protokoll mit Zugang „Demo“.
  - Drossel je Kundenkarte: mindestens 3 Sekunden Abstand, höchstens 30
    Schritte in 24 Stunden.
- Kartenseite: Block „Demo“ mit Knopf „Nächster Schritt“ nur bei Demo-
  Läden (`LandingController.demoBlock`), auf allen vier Kartentypen. Nach
  dem Schritt Meldung, dann Seite neu laden.
- Aufräumen (`DemoService.aufraeumen`, täglich 04:20): Kundenkarten von
  Demo-Läden älter als 7 Tage werden gelöscht, der Kunde mit, wenn er sonst
  keine Karte bei einem echten Laden hat (Apple-Registrierungen inklusive).

## Frontend offen

- Admin-Panel: Schalter „Demo-Laden“ je Laden.
- Website: Seite „Demo“ mit den Karten aus `GET /api/public/demo` (Name,
  Typ, Link/QR zur Anmeldeseite) und kurzem Ablauf („Karte holen, in die
  Wallet, dann auf der Kartenseite stempeln“).
- Dashboard: Hinweis im Demo-Laden, dass Kunden nach 7 Tagen gelöscht werden.
