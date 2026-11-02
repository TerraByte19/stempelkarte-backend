# Anmeldefelder: Geburtstag und Handynummer

Stand: 2026-11-02.

## Ziel

Der Laden entscheidet, ob das Anmeldeformular (`/karte-neu/{cardId}`)
zusaetzlich Geburtstag und Handynummer abfragt - je Feld aus, optional oder
Pflicht. Name und E-Mail bleiben immer Pflicht (Pass und Double-Opt-In).
Der Geburtstag traegt die Kampagnen-Zielgruppe "Geburtstag diesen Monat".

## Daten

- `Shop.signup_birthday`, `Shop.signup_phone` (varchar 10, nullable):
  `OFF` / `OPTIONAL` / `REQUIRED`, null = OFF.
- `CustomerCard.birthday` (date, nullable), `CustomerCard.phone` (varchar 30,
  nullable). **An der Karte, nicht am Customer:** der Kunde ist
  ladenuebergreifend (eine E-Mail = ein Datensatz), die Angaben macht er
  gegenueber EINEM Laden. Ein zweiter Laden, bei dem er ohne diese Felder
  eine Karte holt, sieht sie nicht.
- `anmeldedatenErgaenzen` fuellt nur leere Felder. Das Formular ist offen
  (keine Anmeldung): wer eine fremde E-Mail eintippt, darf damit nicht die
  Nummer eines bestehenden Kunden umschreiben.

## Pruefung (`service.Anmeldedaten`)

- Geburtstag: ISO `JJJJ-MM-TT`, nicht in der Zukunft, nicht aelter als 120
  Jahre. Leer = null.
- Nummer: hoechstens 30 Zeichen, nur `+ 0-9 ( ) / . -` und Leerzeichen,
  mindestens eine Ziffer. Leer = null.
- Ausgeschaltete Felder werden ignoriert, auch wenn ein alter Tab sie
  schickt. Pflichtfeld leer -> 400 mit Meldung, die das Formular anzeigt.

## API

- `GET /api/shop/me`: `signupBirthday`, `signupPhone`.
- `PUT /api/shop/me/anmeldefelder` `{birthday, phone}` (OFF/OPTIONAL/REQUIRED).
- `POST /karte-neu/register`: zusaetzlich `birthday`, `phone` (optional).
- Kundenliste (`GET /api/shop/kunden`): `birthday`, `phone` je Zeile.

## Formular

`LandingController.anmeldeFelder(shop)` haengt die Felder hinter die
E-Mail: `<input type=date id=birthday>` mit Beschriftung ("Geburtstag" oder
"Geburtstag (optional)"), `<input type=tel id=phone>`. `data-required`
prueft das JS vor dem Senden, der Server noch einmal.

## Dashboard

Profil > "Anmeldeformular": je Feld drei Knoepfe. Kundenliste zeigt
Geburtstag und Nummer (als tel-Link), wenn vorhanden. Kampagnen: Gruppe
GEBURTSTAG (Monat des Geburtstags = laufender Monat, Europe/Berlin).

## Automatischer Geburtstagsgruss

`Shop.birthday_greeting_enabled` / `birthday_greeting_text` (160, leer =
Standardtext), `PUT /api/shop/me/geburtstag`. `GeburtstagsService` prueft
alle 15 Minuten: ab 9 Uhr (Europe/Berlin) startet er pro Laden einmal am
Tag eine normale Kampagne mit der internen Zielgruppe `GEBURTSTAG_HEUTE`
(Tag und Monat gleich, 29. Februar in Nicht-Schaltjahren am 28.). Damit
laufen Versand, iPhone-Auffrischen, Tageslimit pro Person und Verlauf ueber
denselben Weg wie beim Laden selbst; im Verlauf steht "Geburtstagsgruss
(automatisch)". Kein Cron um Punkt 9: schlaeft die Instanz da, holt der
naechste Lauf es nach. Doppelt geht nichts: "heute schon" steht als
Kampagne in der Datenbank, der Merker im Speicher spart nur die Abfrage.
`GEBURTSTAG_HEUTE` ist im Dashboard nicht waehlbar (`Zielgruppe.waehlbar`).
