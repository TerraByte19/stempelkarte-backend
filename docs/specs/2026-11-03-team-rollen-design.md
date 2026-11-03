# Mitarbeiter-Zugänge mit Rollen – Design

Stand: 2026-11-03

## Ziel

Der Laden verwaltet seine Scanner-Zugänge (StaffToken) selbst: Name, Rolle,
zuletzt benutzt, Scans der letzten 7 Tage, Link zum Einrichten, entfernen.
Und er sieht im Dashboard, wer gestempelt hat.

Kein zweites Login-System: das Token bleibt die Gerätekennung. Kundendaten
(Kundenliste, Rückmeldungen) bleiben hinter dem Inhaber-Login – ein
Scanner-Gerät an der Theke ist ein geteiltes Gerät, und ein verlorenes
Gerät darf keine Kundenliste preisgeben.

## Rollen

- **MITARBEITER** (Standard, auch für alle bestehenden Zugänge): stempeln,
  Prämie einlösen, Karte zurücksetzen, Punkte buchen, korrigieren und
  zurücknehmen.
- **AUSHILFE**: nur stempeln und Einkäufe buchen. `POST /api/scan/reset`,
  `/api/points/redeem`, `/correct`, `/undo` antworten 403 mit Klartext
  („Dieser Zugang darf nur stempeln…“), der Scanner zeigt ihn an.

Die Controller fragen `istAushilfe()` ab, nicht `darfEinloesen()`: ein
Zugang ohne Rolle (alte Zeilen, Mocks) ist keine Aushilfe und verhält sich
wie bisher.

## Technik

- `staff_tokens.rolle` (String, nullable = MITARBEITER), `last_used_at`
  (vom StaffTokenFilter bei POST gesetzt, höchstens alle 10 Minuten, im
  catch – ein DB-Fehler hält keinen Scan auf).
- `scan_log.staff_label`: Name des Zugangs beim Scan. Zähler je Zugang läuft
  über den Namen; nach Umbenennen beginnt er neu. Das Token selbst steht
  nie im Protokoll.
- `TeamService` / `TeamController` unter `/api/shop/team`: GET Liste,
  POST {label, rolle}, PUT /{token} {label?, rolle?}, DELETE /{token}.
  Der letzte Zugang lässt sich nicht entfernen (409). Obergrenze bleibt
  `Shop.maxTokens` (Tarif, vom Admin).
- Alte Endpunkte `/staff-token`, `/staff-tokens` bleiben (Login, Scanner-QR
  im Dashboard).
- Dashboard „Zuletzt passiert“ zeigt „· von Kasse 1“.
- Profil: Block „Mitarbeiter-Zugänge“ ersetzt den alten Token-Block.
