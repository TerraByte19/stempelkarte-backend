# Tagesangebot auf der Karte – Design

Stand: 2026-11-03 (nur Backend; Frontend folgt im Sammel-Durchgang)

## Ziel

Der Laden tippt morgens einen Satz („Heute: Cappuccino 2,50 €“). Er steht
bis Tagesende auf der Karte, und das Setzen löst eine Mitteilung auf allen
Karten aus.

## Verhalten

- `PUT /api/shop/tagesangebot {text}`: speichert Text (≤ 80 Zeichen,
  Steuerzeichen raus) mit Gültigkeit bis 23:59 Berlin, startet dann eine
  Kampagne mit Text „Heute: …“ und interner Zielgruppe `TAGESANGEBOT`
  (trifft jede aktive Karte). Versand, iPhone-Auffrischen, Tageslimit pro
  Person und Verlauf wie bei jeder Kampagne. Hat heute schon jede Person
  eine Nachricht bekommen (zweite Änderung am selben Tag), wird das Angebot
  trotzdem gespeichert, nur ohne Push – die Antwort sagt `empfaenger: 0`.
- `GET /api/shop/tagesangebot` → `{text, bis, aktiv}`; abgelaufen = `aktiv:
  false`, Text bleibt als Vorschlag für morgen stehen.
- `DELETE /api/shop/tagesangebot`: Angebot weg, kein Push.

## Karte

- Apple: Rückseitenfeld `heute` („HEUTE“) bei jedem Laden, der die Funktion
  je benutzt hat (`Shop#hatTagesangebotFeld`), immer vorhanden: Angebot oder
  Ruhetext „Heute kein besonderes Angebot.“ Ohne changeMessage – die
  Mitteilung kommt über das Nachrichtenfeld der Kampagne, sonst klingelt
  es doppelt. Kopf-Key bleibt `stamps` / `stamps-header`.
- Google: Textbaustein `heute` („Heute“) im Objekt, nur solange das Angebot
  gilt; die Mitteilung kommt als Kampagnen-Message. Der Baustein erscheint
  mit dem nächsten Objekt-Update (Stempel), die Mitteilung sofort.
- Abgelaufen: beim nächsten Pass-Bau fällt das Feld auf den Ruhetext, ohne
  Push. Kein eigener Lauf dafür.

## Technik

- `shops.tagesangebot_text` (80), `tagesangebot_am`, `tagesangebot_bis`.
- `TagesangebotService#setzen(shopId, text, jetzt)`, `#loeschen`, `#stand`.
- `Zielgruppe.TAGESANGEBOT`: passt immer, nicht wählbar.

## Frontend offen

- Dashboard-Block „Tagesangebot“: ein Eingabefeld, Knopf „Heute setzen“
  (löst Push aus, Hinweis darauf), Anzeige „gilt bis heute 23:59“, Knopf
  „Entfernen“.
- Kampagnen-Verlauf: Name für Gruppe `TAGESANGEBOT` (`kamp_g_tagesangebot`).
