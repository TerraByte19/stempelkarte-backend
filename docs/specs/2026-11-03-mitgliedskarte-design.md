# Mitgliedskarte – Design

Stand: 2026-11-03 (nur Backend; Frontend folgt im Sammel-Durchgang)

## Ziel

Ein Ausweis im Wallet ohne Stempel und Punkte: Name, Mitglied seit,
Mitgliedsnummer, Besuche. Für Studios, Vereine, Clubs. Der Scan zählt den
Besuch; Nachrichten, Geburtstag, Bewertung, Kundengruppen, Stufen laufen
wie bei jeder Karte.

## Regeln

- `CardType.MEMBER`, `Card.createMember(shop, name, description)` mit
  Schwelle 1 und leerem Prämientext (wie Punktekarte, damit alte Rechenwege
  nicht durch 0 teilen). `Card.isMember()`, `Card.isStamp()`.
- `stamps` = Besuche. Ein Scan = ein Besuch, egal welche Anzahl der Scanner
  schickt. Kein „Karte voll“, kein Einlösen, kein Reset-Bedarf.
  `ScanResult.Visited`, Scanner-Aktion `visited`, Meldung „Besuch erfasst (n)“.
- Nicht für Mitgliedskarten: Willkommensgeschenk (nur Stempel), Kunde wirbt
  Kunde, Stempel-Korrektur in der Kundenliste, Prämie-wartet / fast-voll
  (Zielgruppen und Automaten), Rückhol-/Sperrbildschirm-Texte mit Ziel
  (`KartenFortschritt` = null → Standardtext ohne Ziel).
- Dafür möglich: Stufen (nach Besuchen), Kundengruppen (Besuche = stamps),
  Willkommen-/zweiter-Besuch-/VIP-Automat, Bewertungs-Erinnerung.
- ScanLog mit stampsAdded 0 (wie Punkte) – Stempel-Statistik bleibt sauber,
  Statistik-Zeile je Karte mit `type: MEMBER` und `besuche`.

## Karte

- Apple: Kopf `visits-header` „BESUCHE“ mit changeMessage „Besuch erfasst!
  Du hast jetzt %@ Besuche“, Hauptfeld `member-name`, `member-since`
  (MM/yyyy), `member-no` (Kundenkarten-ID ohne Präfix). Kein Streifen.
  Rückseite: Nachricht, Rückmeldung, Tagesangebot, Status wie sonst.
- Google: Hauptwert „Besuche“, Nebenwert „Mitglied seit“, Meldung `BESUCH`
  „Besuch bei …“.
- Kundenseite: eigene kleine Vorlage (Name, Mitglied seit, Besuche,
  Wallet-Knöpfe, Status, Bewertungslinks).

## API

- `POST /api/shop/cards/member {name, description, Farben, logoUrl,
  heroImageUrl}` → CardResponse mit `type: MEMBER`.
- Scan: `POST /api/scan` und `POST /api/v1/stamps` liefern `action:
  "visited"`.

## Frontend offen

- Karten anlegen: dritter Typ „Mitgliedskarte“ (nur Name, Beschreibung,
  Design). Karten-Editor ohne Stempel-/Prämien-Felder.
- Scanner: Ergebnis `visited` anzeigen („Besuch erfasst, 12. Besuch“), keine
  Knöpfe für Einlösen/Reset.
- Dashboard-Kachel: „Besuche“ statt Stempel/Prämien bei `type: MEMBER`.
- Kundenliste: „n Besuche“ statt Stempelstand, keine Korrektur.
