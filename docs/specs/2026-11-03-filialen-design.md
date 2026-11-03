# Filialen (mehrere Standorte) – Design

Stand: 2026-11-03 (nur Backend; Frontend folgt im Sammel-Durchgang)

## Ziel

Ein Konto, eine Karte, mehrere Läden. Der Kunde sammelt überall, der Laden
sieht, in welcher Filiale gestempelt wurde, und ordnet Mitarbeiter-Zugänge
einer Filiale zu. Wie viele Filialen ein Konto hat, stellt der Admin ein.

## Entscheidung: Filiale = Standort

Keine neue Tabelle. Filialen sind die Standorte, die es schon gibt: der
Hauptstandort (`Shop.latitude/longitude`) plus die weiteren Orte
(`ShopOrt`, bisher nur für Nähe-Erinnerung und Geo-Prüfung). Eine Adresse,
eine Liste, ein Limit.

- Admin-Limit: `Shop.maxOrte` (0–9, `POST /admin/shops/{id}/max-orte`)
  bleibt die Stellschraube. Filialen = 1 + maxOrte. Hoch- und
  Runterstellen wirkt sofort: beim Runterstellen zählen nur die ersten n
  Orte (Reihenfolge nach Anlage) für Pass, Geo-Prüfung und Zuordnung.
- Eigene Karten je Filiale gibt es bewusst nicht: eine Karte gilt überall.

## Regeln

- `StaffToken.ort_id` (nullable, nur ID): Zugang gehört zu einer Filiale.
  Null = Hauptstandort. `TeamService.anlegen/aendern(..., ortId)`: `HAUPT`
  oder leer = Hauptstandort, sonst muss der Ort zum Laden gehören (400
  „Standort nicht gefunden“). `Zugang` trägt `ortId` (`HAUPT` oder Ort-ID)
  und `ortLabel`.
- `ScanLog.ort_id` (nullable): wo gestempelt bzw. gebucht wurde.
  `CustomerService.processScan(..., staffLabel, ortId)`,
  `PointsService.earn/redeem(..., staffLabel, ortId)`.
- Zuordnung beim Scan (`FilialenService.ortFuer`): Zugang einer Filiale
  zugeordnet → die. Sonst der nächste Standort im Radius der Geo-Prüfung
  (100 m), wenn das Gerät seinen Ort schickt und der Tarif weitere Orte
  erlaubt. Sonst Hauptstandort (null). API-Schlüssel: immer Hauptstandort.
- Ort löschen (`OrteService.loeschen`): Zugänge an diesem Ort fallen auf
  den Hauptstandort zurück. Alte Scans behalten die ID und erscheinen als
  „Gelöschter Standort“.
- Webhooks `stamp.added`, `points.earned` usw. tragen `locationId` (nur
  wenn gesetzt).

## API

- `GET /api/shop/filialen` → `{max, genutzt, filialen: [{id, label, haupt,
  geloescht, scans30d, stempel30d, kunden30d, letzterScan, zugaenge}]}`.
  `id` = `HAUPT` oder Ort-ID. max = 1 + Admin-Wert, genutzt = 1 + Orte.
- `POST/PUT /api/shop/team` nimmt `ortId`; `GET` liefert `ortId`, `ortLabel`.
- Anlegen/Löschen der Standorte weiter über `/api/shop/me/orte`.

## Später (nicht gebaut)

- Kampagnen-Zielgruppe „Kunden der Filiale X“ (Stammfiliale aus dem
  Protokoll).
- Spalte „Filiale“ in „Zuletzt passiert“ (Dashboard-Aktivitäten) und in
  der Kundenliste.
- API-Schlüssel je Filiale.

## Frontend offen

- Admin-Panel: Feld „weitere Orte“ als „Filialen“ beschriften (Anzeige
  1 + Wert), hoch/runter wie bisher über `max-orte`.
- Profil: Block „Orte“ in „Standorte / Filialen“ umbenennen; Hauptstandort
  als erste Zeile zeigen.
- Team: Auswahl „Filiale“ je Zugang (Hauptstandort + Orte), Spalte in der
  Liste.
- Dashboard: Kachel/Block „Filialen“ aus `GET /api/shop/filialen` (Scans,
  Stempel, Kunden 30 Tage, Zugänge je Filiale), nur wenn max > 1.
