# Automations-Baukasten – Design

Stand: 2026-11-03 (nur Backend; Frontend folgt im Sammel-Durchgang)

## Ziel

Der Laden baut eigene Automationen: Auslöser plus Zahl, optional nur eine
Karte und nur eine Kundengruppe, dazu der Text. Pausieren und ändern
jederzeit. Ergänzt die festen Automaten (Willkommen, zweiter Besuch, …),
ersetzt sie nicht.

## Regeln

- `model.Automation` (Tabelle `automationen`): name (40), aktiv, ausloeser,
  wert, cardId (null = alle Karten), gruppe (Kundengruppe, null = alle),
  text (160, Platzhalter erlaubt), zuletztAm, gesendet. Höchstens 10 je
  Laden.
- Auslöser (`service.AutomationAusloeser`, wert in Klammern):
  - `NACH_ANMELDUNG` (n Tage): n bis n+3 Tage nach Anlage der Karte, einmal
    je Karte.
  - `KEIN_BESUCH_SEIT` (n Tage): letzter Besuch länger als n Tage her,
    einmal je Pause (nach dem Versand erst wieder nach einem neuen Besuch).
  - `BESUCH_NR` (n): ab dem n-ten Besuch, am Tag danach (20 h – 3 Tage),
    einmal je Karte. Nicht für Punktekarten.
  - `STEMPEL_STAND` (n): Stempelstand genau n, am Tag danach, einmal je
    Runde. Nur Stempelkarten.
  - `GEBURTSTAG_IN_TAGEN` (n): Geburtstag in n Tagen (0 = heute), einmal
    im Jahr. 29. Februar in Nicht-Schaltjahren am 28.
  - `PAKET_REST` (n): 10er-Karte mit noch n übrig, am Tag danach, einmal
    je Paket.
- „Schon dran gewesen“ kommt aus den Kampagnen, die die Automation gestartet
  hat (`Kampagne.automationId` + Empfängerzeilen, Abfrage
  `letzterVersandJeKarte`). Kein eigener Merker an der Kundenkarte.
- Läufer (`AutomationService.lauf`) alle 15 Minuten, ab 10 Uhr, je
  Automation einmal am Tag (Merker im Speicher plus Datenbank-Prüfung nach
  Neustart). Grundmenge wie bei Kampagnen (`findKampagnenBasis`): aktive
  Karten, keine gesperrten. Eine Karte je Person (zuletzt benutzte),
  Personen mit Nachricht von heute werden übersprungen.
- Versand als Kampagne mit fertiger Liste: `KampagnenService.starteFuer(shop,
  text, empf, automationId)`, Gruppe `AUTOMATION` (nicht wählbar), keine
  Kontrollgruppe. Platzhalter werden wie immer je Karte gefüllt. Im
  Kampagnen-Verlauf erscheint sie mit Gruppe AUTOMATION.
- Kundengruppen-Filter rechnet Besuche wie die Kundenliste für Stempel-,
  Mitglieds- und 10er-Karten (Stempel plus Prämien × Schwelle); bei
  Punktekarten zählt dort nur die Prämienzahl.

## API

- `GET /api/shop/automationen/ausloeser` → `[{key, titel, einheit, min, max}]`.
- `GET /api/shop/automationen` → `[{id, name, aktiv, ausloeser,
  ausloeserTitel, wert, cardId, gruppe, text, erstellt, zuletzt, gesendet,
  trifftJetzt}]` (trifftJetzt = Personen, die beim nächsten Lauf dran
  wären, ohne Tageslimit).
- `POST /api/shop/automationen {name, ausloeser, wert, cardId, gruppe, text,
  aktiv}`; `PUT /{id}` mit denselben Feldern (null = nicht ändern, cardId/
  gruppe "" = alle, aktiv=false = pausieren); `DELETE /{id}`.
- Admin: Löschen eines Ladens räumt `automationen` mit.

## Frontend offen

- Seite „Automationen“ (neben den festen Automaten): Liste mit Schalter
  (Pause), „trifft gerade n Kunden“, Bearbeiten, Löschen.
- Formular: Name, Auslöser (Dropdown aus `/ausloeser`, Wert-Feld mit
  Einheit und Bereich), Karte (alle oder eine), Kundengruppe (alle oder
  eine), Text mit Platzhalter-Hilfe, Vorschau „trifft gerade n“.
- Kampagnen-Verlauf: Gruppe `AUTOMATION` beschriften („Automation“), gern
  mit Name der Automation (über `automationId` nachschlagen).
