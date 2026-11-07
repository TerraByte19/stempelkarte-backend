# Vorführung buchen (2026-11-07)

## Ziel

Auf der Startseite kann ein Laden einen Termin für eine 15-Minuten-Vorführung anfragen: im Laden (Berlin), am
Telefon oder per Video. Die Anfrage landet im Admin, der User bestätigt von Hand (WhatsApp, Anruf, Mail).
Kein Kalender-Dienst: es gibt nichts, gegen das der Server prüfen könnte, und ein externes Tool wäre ein
weiteres Konto mit eigenem Branding.

## Backend

- `model.Terminanfrage` (Tabelle `terminanfragen`): name, laden, email oder telefon (eins Pflicht), `art`
  IM_LADEN / TELEFON / VIDEO, `wunsch_am` (LocalDate, morgen bis +90 Tage Berlin), `wunsch_zeit` „HH:mm“,
  adresse (Pflicht bei IM_LADEN), nachricht (optional, 1000), sprache der Seite, eingegangen_am, `status`
  OFFEN / BESTAETIGT / ERLEDIGT.
- `TerminController`: `POST /api/public/termin` (Honigtopf `website`, Drossel 3 je IP in 10 Minuten,
  400 mit Klartext bei Fehlern), Admin `GET /api/admin/termine`, `POST /api/admin/termine/{id}/status {status}`,
  `DELETE /api/admin/termine/{id}`.
- Keine Mail, wie bei Kontaktanfragen: steht im Admin-Panel.

## Frontend

- Startseite `sections/Vorfuehrung.jsx` (eigene CSS-Datei): Abschnitt „Lieber erst live sehen?“ vor dem
  Datenschutz-Block. Drei Chips für die Art, Wunschtag (morgen bis +90 Tage), Uhrzeit (09:00–20:00 in
  30-Minuten-Schritten), Name, Laden, Telefon/WhatsApp oder E-Mail, Adresse nur bei „Im Laden“, Nachricht
  optional. Texte `lp_termin_*` in de/en/ar/tr.
- Admin: Block „Vorführungen“ mit Datum, Uhrzeit, Art, Laden, Kontakt (tel:/mailto:), Adresse, Nachricht,
  Status-Knöpfe „Bestätigt“, „Erledigt“, „Löschen“.
- Admin-Notiz bei den Stimmen (User 2026-11-07): solange keine Stimme da ist, erinnert der Block daran,
  2–3 Läden um ein Zitat zu bitten.

## Tests

`TerminanfrageTest`: gültige Anfrage, Honigtopf, Pflichtfelder und Zeitfenster, Drossel, Admin-Liste/Status/Löschen.
