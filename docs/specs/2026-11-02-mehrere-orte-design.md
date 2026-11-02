# Mehrere Orte fuer Naehe-Erinnerung und Geo-Pruefung

Stand: 2026-11-02.

## Ziel

Ein Laden hat neben dem Hauptort weitere Punkte (Marktstand, zweiter
Eingang, Food-Truck). An jedem meldet sich die Apple-Karte auf dem
Sperrbildschirm, und an jedem darf das Personal scannen. Keine Filialen:
keine eigenen Karten, keine eigene Statistik - nur Punkte auf der Karte.

## Daten

Tabelle `shop_orte` (`ShopOrt`): id `ORT-...`, shop_id, label (40),
latitude, longitude, created_at. Hauptort bleibt `Shop.latitude/longitude`.
Hoechstens 9 weitere (Apple: zehn Orte pro Pass).

## Tarif (Admin)

`Shop.max_orte` (null = 0): wie viele weitere Orte der Laden anlegen darf.
Setzt der Admin im Panel je Laden (`POST /api/admin/shops/{id}/max-orte`
`{max}`, 0..9). 0 = Funktion aus, das Dashboard zeigt den Block nicht.
Senkt der Admin die Zahl, bleiben vorhandene Orte gespeichert, aber nur die
ersten `max` (nach Anlagedatum) landen im Pass und zaehlen beim Scan; der
Laden sieht sie noch und kann sie loeschen, aber keine neuen anlegen.

## Pass

`ApplePassService.sperrbildschirmOrte` liefert einen PKLocation je Ort,
alle mit demselben Text, gedeckelt auf zehn. Der Radius (`maxDistance`)
gilt fuer alle. Wirkt auf bestehende Karten erst mit dem naechsten Stempel
(wie jede Aenderung an der Naehe-Erinnerung).

## Geo-Pruefung

`GeoPruefung.sperre`: Entfernung zum NAECHSTEN Ort (Hauptort plus weitere),
Radius 100 m wie bisher. Gemeldet wird die kleinste Entfernung.

## API

`OrteController`, JWT: `GET /api/shop/me/orte`, `POST /api/shop/me/orte`
`{label, latitude, longitude}` (400 bei leerem Namen, Koordinaten
ausserhalb -90..90 / -180..180 oder mehr als 9), `DELETE /api/shop/me/orte/{id}`.
Alle liefern die aktuelle Liste.

## Dashboard

Profil > Sperrbildschirm > "Weitere Orte": Liste mit Entfernen, Formular
mit Name, Breiten-/Laengengrad und "Meinen Standort nehmen" (Browser-GPS).
Koordinaten von Hand erlaubt, weil der Laden beim Einrichten nicht am
Marktstand steht.

## Tests

`MehrereOrteTest` (Pass: Hauptort plus weitere, Deckel 10, ohne Repository
nur Hauptort; Geo: Scan am zweiten Ort geht durch, weit weg meldet den
naechsten), `OrteServiceTest` (Name, Koordinaten, Maximum, nur eigene
loeschen).
