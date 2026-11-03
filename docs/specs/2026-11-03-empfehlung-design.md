# Kunde wirbt Kunde – Design

Stand: 2026-11-03 (nur Backend; Frontend folgt im Sammel-Durchgang)

## Ziel

Jede Kundenkarte hat einen Empfehlungscode und -link. Meldet sich ein
Freund darüber für dieselbe Karte an und kommt zum ersten Mal, bekommen
beide den Bonus (Stempel bei Stempelkarten, Punkte bei Punktekarten).

## Regeln

- Pro Karte (Card) einstellbar: `empfehlungAktiv`, `empfehlungBonus`
  (1..10, bei Stempelkarten kleiner als die Schwelle). Standard aus, Bonus 1.
- Code: 6 Zeichen aus `ABCDEFGHJKLMNPQRSTUVWXYZ23456789` (kein I, O, 0, 1),
  je Kundenkarte, wird beim Anlegen vergeben und bei alten Karten beim
  nächsten Öffnen der Kartenseite oder Scan nachgeholt.
- Link: `{baseUrl}/karte-neu/{cardId}?ref={code}`. Die Anmeldeseite liest
  `ref` aus der URL und zeigt bei eingeschalteter Empfehlung zusätzlich ein
  Feld „Empfehlungscode (optional)“.
- Verknüpfen bei der Anmeldung (nur NEUE Kundenkarte): Code bekannt,
  Werber hat dieselbe Karte (Card), Werber ist nicht der Kunde selbst,
  Empfehlung an. Sonst wird der Code still ignoriert – eine Anmeldung
  scheitert nie am Code.
- Bonus beim ERSTEN echten Besuch des Geworbenen (erster Stempel bzw.
  erster Einkauf), nicht bei der Anmeldung: keine Boni für Karteileichen.
  Einmal pro geworbener Karte (`empfehlungBonusAm`). Ist die Empfehlung
  inzwischen aus oder der Werber gelöscht, wird nur der Merker gesetzt.
- Stempel-Bonus wird bei der Schwelle gedeckelt (eine volle Karte wird
  nicht „voller“). Punkte-Bonus = Bonus × 100 (ganze Punkte), ohne
  lastVisitAt – der Werber war nicht da.
- Der Werber bekommt sein Wallet-Update nach dem Commit (Apple „Update!
  Dein Stempelstand“, Google „Neuer Stempel“). Der Scanner zeigt beim
  Geworbenen „+n Empfehlungsbonus“ in der Meldung.

## Oberfläche (Backend-seitig, weil Server-HTML)

- Kartenseite (Stempel und Punkte): Block „Freunde werben“ mit Link und
  „Link kopieren“, nur bei eingeschalteter Empfehlung.
- Apple: Rückseitenfeld `empfehlen` („FREUNDE WERBEN“), ohne changeMessage.
- Google: Textbaustein `empfehlen`.

## API

- `GET /api/shop/cards/{cardId}/empfehlung` → `{aktiv, bonus, geworben,
  belohnt, top: [{name, anzahl}]}`.
- `PUT /api/shop/cards/{cardId}/empfehlung {aktiv, bonus}`.

## Technik

- `cards.empfehlung_aktiv`, `empfehlung_bonus`.
- `customer_cards.empfehlungs_code` (unique), `geworben_von`
  (customerCardId des Werbers), `geworben_am`, `empfehlung_bonus_am`.
- `EmpfehlungService`: `codeSicherstellen`, `link`, `verknuepfen`,
  `belohnen` (TransactionSynchronization für den Push nach Commit),
  `stand`.

## Frontend offen

- Karten-Editor: Block „Kunde wirbt Kunde“ (Schalter, Bonus, Zahlen, Top-Werber).
- Kundenliste: Marke „geworben“ wäre nett, nicht nötig.
