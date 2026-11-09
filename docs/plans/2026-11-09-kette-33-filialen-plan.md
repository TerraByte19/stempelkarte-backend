# Plan: Stampit fuer Ketten (Anlass: BackBaron, 33 Filialen)

Stand 2026-11-09. Noch nichts gebaut.

## Kernidee
Pilot mit 3 Filialen geht HEUTE schon (Grenze liegt bei 10). Verkaufen kann also
sofort starten; Bauen laeuft waehrend des Pilots. Erst der Ausbau auf alle 33
braucht die Punkte unter "Muss".

## Was heute im Code begrenzt
- `OrteService.MAX_WEITERE = 9` -> hoechstens 10 Filialen (Admin-Select 0-9, `AdminController.setMaxOrte` prueft dasselbe).
- `ApplePassService.orte()`: alle Filialen in Anlagereihenfolge, max. 10 (`MAX_ORTE`). Apple-Grenze: 10 Orte pro Pass.
- Wir wissen nicht, in welcher Filiale ein Kunde sich angemeldet hat (nur ScanLog.ortId pro Scan).
- Statistik (`StatsService`) kennt keine Filialen; `FilialenService` zaehlt nur Scans + Zugaenge je Ort.
- Mail-Limit `stempelkarte.mail.daily-limit` = 300/Tag (Brevo Free). Jede Anmeldung per Mail = 1 Bestaetigungsmail.
- Ein Inhaber-Login pro Laden; Rollen nur fuer den Tresen (MITARBEITER/AUSHILFE/KIOSK).

## MUSS (vor Ausrollen auf 33)
1. **Mehr als 10 Filialen** - Grenze fuer Tarif KETTE anheben (z. B. 60), Admin-Feld als Zahl statt Select.
2. **Stammfiliale je Kunde** - neue Spalte `CustomerCard.stamm_ort_id` (nullable).
   Gesetzt bei Anmeldung (Filial-QR, Punkt 4), sonst aus den Scans (Filiale mit den meisten Scans, faellt auf Haupt zurueck).
3. **10 naechste Filialen im Pass** - `ApplePassService.orte()` sortiert alle Filialen nach Entfernung zur Stammfiliale und nimmt die 10 naechsten.
   Aenderung wirkt beim naechsten ohnehin faelligen Pass-Update, kein Extra-Push.
4. **Anmelde-QR pro Filiale** - Anmeldeseite mit `?f=<ortId>`, Aufsteller/Aufkleber (AufkleberService) je Filiale druckbar. Liefert Stammfiliale + Statistik "Neue Kunden je Filiale".
5. **Filialen in einem Rutsch anlegen** - Liste einfuegen (Name; Breite; Laenge oder Adresse). Adresse -> Koordinaten nur, wenn wir einen Geocoder waehlen (Entscheidung). Sonst Koordinaten aus Google Maps kopieren.
6. **Statistik je Filiale** - Tabelle: Scans, neue Kunden, eingeloeste Praemien, aktive Kunden je Filiale, Zeitraum waehlbar, sortierbar. Das will die Zentrale sehen.
7. **Mail-Kontingent** - Brevo-Plan hoch + `daily-limit` setzen. Ohne das sind nach ~300 Anmeldungen am Tag die Bestaetigungsmails weg (Google-/Apple-Login braucht keine Mail).
8. **Last pruefen** - Testlauf mit kuenstlichen Daten (z. B. 20.000 Karten, Kampagne an alle): Dauer Apple-/Google-Push, DB, Render-Plan. Ergebnis entscheidet, ob Render/DB hochgestuft werden muss.
9. **Datenschutz fuer die Rechtsabteilung** - AVV (Muster vorhanden), Server-Region klaeren und benennen (ist noch offen), Loeschfristen, TOMs. Ketten fragen das als Erstes.

## SOLLTE (macht den Verkauf an Ketten leichter)
10. **Filialleitung-Zugang** - eigener Login je Filiale: sieht nur ihre Statistik, verwaltet ihre Tresen-Zugaenge. Zentrale (Inhaber) sieht alles.
11. **Kampagnen/Tagesangebot je Filiale** - Zielgruppe "Kunden der Filiale X" (ueber Stammfiliale). Z. B. Neueroeffnung, lokales Angebot.
12. **Filial-Rangliste im Dashboard** - beste/schwaechste Filiale der Woche.
13. **Rechnung Kette** - Preis pro Filiale im Admin hinterlegen, Monatsbetrag anzeigen (Abrechnung bleibt von Hand).

## KANN (spaeter / gegen Aufpreis)
14. **Kassenanbindung** - erst Kassensystem von BackBaron erfragen (Baeckereien oft Vectron o. ae.). Eigenes Projekt.
15. **Filialfinder auf der Kartenrueckseite** - Link zu Liste/Karte aller Filialen.
16. **Oeffnungszeiten je Filiale** auf der Rueckseite.

## Reihenfolge (Vorschlag)
1 -> 2 -> 4 -> 3 -> 5 -> 6 -> 7 -> 8 -> 9, danach 10 -> 11 -> 12 -> 13.
Backend zuerst, Frontend am Ende in einem Guss (wie immer).

## Offene Entscheidungen fuer den User
- Stammfiliale: Anmeldefiliale vor "meiste Scans"? (Vorschlag: ja)
- Geocoder fuer Adressen (OSM/Nominatim gratis mit Limits) oder Koordinaten von Hand?
- Filialleitung-Login gleich mitbauen oder erst wenn BackBaron fragt?
- Preis Kette pro Filiale (Vorschlag 15-19 EUR netto, 12 Monate).
