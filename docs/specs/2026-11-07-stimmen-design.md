# Stimmen von Läden für die Startseite (2026-11-07)

## Ziel

Auf der Startseite steht ein Abschnitt „Das sagen Läden, die Stampit nutzen“. Die Zitate kommen von den
Läden selbst, aus der App, und erscheinen erst nach Freigabe durch den Admin. Nichts wird erfunden oder
umgeschrieben: eine frisierte Kundenstimme wäre abmahnfähig (UWG) und würde das Vertrauen zerstören, auf dem
der Abschnitt steht.

## Entscheidungen (User, 2026-11-07)

- Der Laden schreibt sein Zitat in der App (eingeloggt), es landet im Admin, der User gibt frei, dann steht
  es auf der Startseite.
- Eine Stimme je Laden. Ändert der Laden den Text, muss er neu freigegeben werden.
- Der Admin ändert den Wortlaut nicht. Passt etwas nicht: ablehnen, der Laden sieht das und reicht neu ein.

## Backend

- `model.Stimme` (Tabelle `stimmen`): `shop_id` eindeutig (OneToOne), `zitat` (10 bis 320 Zeichen), `person`
  (optional, max. 100, z. B. „Ayşe, Inhaberin“), `ort` (Pflicht, max. 80), `status` OFFEN / FREIGEGEBEN /
  ABGELEHNT, `einverstanden_am` (Zeitpunkt des Hakens „darf mit Ladenname und Ort auf stampit-app.de
  erscheinen“, der Beleg), `created_at`, `geaendert_am`, `entschieden_am`.
- `StimmenService`: `einreichen` bereinigt (Steuerzeichen raus, Leerraum zusammen, Anführungszeichen am Rand
  weg, die Seite setzt eigene), prüft Längen und Einverständnis (sonst 400 mit Klartext), legt an oder ändert
  (zurück auf OFFEN, Entscheidung weg). `zurueckziehen` löscht. Admin: `alle`, `freigeben`, `ablehnen`.
  Website: `oeffentlich` = höchstens sechs FREIGEGEBEN, zuletzt freigegebene zuerst, nur zitat, laden
  (Shop-Name), ort, person.
- `StimmenController`: `GET|PUT|DELETE /api/shop/stimme` (JWT), `GET /api/admin/stimmen`,
  `POST /api/admin/stimmen/{id}/freigeben|ablehnen` (Admin), `GET /api/public/stimmen` (frei, Cache-Control
  public max-age 300).
- Keine Mail an den Admin: wie bei Kontaktanfragen steht es im Admin-Panel.

## Frontend

- Profil: Block „Deine Stimme für die Stampit-Website“ mit Zitat (Zähler bis 320), Name und Rolle (optional),
  Ort, Haken Einverständnis, „Einreichen“ bzw. „Ändern“, Status-Zeile (wartet auf Freigabe / auf der Website /
  abgelehnt, bitte anpassen), „Zurückziehen“.
- Admin: Liste der Stimmen mit Laden, Ort, Person, Zitat, Einverständnis-Zeitpunkt, Status, Knöpfe
  „Freigeben“ und „Ablehnen“.
- Startseite `sections/Stimmen.jsx`: holt `GET /api/public/stimmen`, zeigt zusätzlich die statische Liste
  aus `stimmen.js` (für Zitate, die nicht über die App kommen). Ohne Einträge kein Abschnitt.

## Tests

`StimmenServiceTest` (Einreichen, Bereinigung, Einverständnis Pflicht, Grenzen, Änderung setzt Freigabe
zurück, Zurückziehen, Admin frei/ablehnen, öffentlich nur FREIGEGEBEN ohne Internes, Helfer),
`StimmenControllerTest` (Durchreichen, Cache-Header).
