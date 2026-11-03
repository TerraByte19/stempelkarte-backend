# Stufen-Karte – Design

Stand: 2026-11-03 (nur Backend; Frontend folgt im Sammel-Durchgang)

## Ziel

Der Kunde hat einen Status (Bronze, Silber, Gold), der mit den Besuchen
steigt. Er sieht ihn auf der Karte, der Laden sieht ihn in der Kundenliste
und kann die höchste Stufe gezielt anschreiben. Beim Aufstieg kommt eine
automatische Nachricht.

## Regeln

- Nur Stempelkarten (Punktekarten zählen keine Besuche – wie beim
  Willkommensgeschenk).
- Je Karte (Card): `stufenAktiv`, drei Namen, zwei Schwellen:
  Stufe 1 ab 0 Besuchen (Standard „Bronze“), Stufe 2 ab `stufe2Ab`
  (Standard „Silber“, 10), Stufe 3 ab `stufe3Ab` (Standard „Gold“, 25).
  Schwellen 1..1000, Stufe 3 > Stufe 2. Namen ≤ 20 Zeichen, leer = Standard.
- Besuche = alle je gesammelten Stempel
  (`Kundengruppe.besucheStempelkarte`), Korrekturen zählen mit.
- Status nur Anzeige: keine eigenen Prämien je Stufe (bewusst nicht –
  erst sehen, ob Läden das wollen).

## Wo sichtbar

- Apple: Rückseitenfeld `status` („STATUS“): „Silber · noch 8 Besuche bis
  Gold“ bzw. „Gold · 27 Besuche“. Ohne changeMessage. Nur wenn Stufen an.
- Google: Textbaustein `status`.
- Kartenseite: Zeile mit dem Status.
- Kundenliste: Feld `stufe` je Zeile.
- Kampagne: Zielgruppe `STUFE_TOP` (wählbar): alle Karten in der höchsten
  Stufe einer Karte mit Stufen an.
- Automat `STUFE` (AutoNachrichtTyp, Zielgruppe `AUTO_STUFE`): am Tag nach
  dem Besuch, mit dem die Stufe 2 oder 3 erreicht wurde, einmal je Stufe
  (`customer_cards.stufe_benachrichtigt` = höchste gemeldete Stufe).
  Standardtext „Glückwunsch, {name}! Du bist jetzt {stufe} bei uns.“
  Neuer Platzhalter `{stufe}`.

## API

- `GET /api/shop/cards/{cardId}/stufen` → `{aktiv, stufen: [{name, ab,
  anzahl}]}` (anzahl = Kundenkarten in der Stufe).
- `PUT /api/shop/cards/{cardId}/stufen {aktiv, stufe1Name, stufe2Name,
  stufe2Ab, stufe3Name, stufe3Ab}`.

## Technik

- `cards.stufen_aktiv`, `stufe1_name`, `stufe2_name`, `stufe2_ab`,
  `stufe3_name`, `stufe3_ab`.
- `service.Stufen`: `von(card, cc)` → `Stufe(index 1..3, name, ab,
  naechsteName, naechsteAb)` oder null; `text(stufe, besuche)`.

## Frontend offen

- Karten-Editor: Block „Stufen“ (Schalter, Namen, Schwellen, Zahlen).
- Kundenliste: Stufe als Marke.
- Kampagnen: Name `kamp_g_stufe_top`, `kamp_g_auto_stufe` im Verlauf.
- Automaten-Block: sechste Zeile STUFE.
