# Gratis-Tool „Google-Bewertungslink“ (2026-11-07)

## Ziel

Eine Seite auf stampit-app.de (`/tools/bewertungslink`), auf der ein Laden seinen Namen eingibt und den
fertigen Link „Bewerte uns bei Google“ plus QR-Code zum Ausdrucken bekommt. Kostenlos, ohne Anmeldung.
Zweck für uns: Läden suchen genau danach bei Google, landen bei Stampit und sehen darunter die Stempelkarte.
So macht es Tappi.

## Backend

- `BewertungsLinkService`: Text Search der Places API (New) mit Feldmaske `places.id, places.displayName,
  places.formattedAddress`, `regionCode DE`, höchstens 5 Treffer. Schlüssel aus `GOOGLE_PLACES_API_KEY`
  (`stempelkarte.google.places-api-key`); ohne Schlüssel `aktiv=false`, die Seite bietet dann nur die Eingabe
  einer Place-ID von Hand. Ergebnisse 24 h im Speicher (gleiche Suche ohne Groß/Klein), Drossel 20 echte
  Anfragen je IP in 10 Minuten; Treffer aus dem Speicher zählen nicht. Google-Fehler = 503 mit Klartext.
- Link: `https://search.google.com/local/writereview?placeid={id}` (wie `ShopService.PLACE_ID_LINK`),
  Place-ID geprüft (`[A-Za-z0-9_-]{10,300}`).
- `BewertungsLinkController`: `GET /api/public/bewertungslink?q=`, `GET /api/public/bewertungslink/link?placeId=`,
  `GET /api/public/bewertungslink/qr?placeId=` (PNG 600 px, 1 Tag cachebar).
- Kosten: Text Search mit diesen Feldern fällt unter „Pro“; die monatliche Gratis-Menge reicht für tausende
  Suchen, der Speicher dämpft Wiederholungen.

## Frontend

- `/tools/bewertungslink`: Suchfeld, Trefferliste (Name, Adresse), nach Auswahl Link mit „Kopieren“, QR-Bild
  mit „Herunterladen“ und Druckhinweis, darunter der Stampit-Kasten „Noch mehr Stammkunden?“ mit Link zur
  Demo und zum Kontakt. Ohne Schlüssel: Feld für die Place-ID mit Anleitung (Google Maps → Laden → Teilen).
  Deutsch, Rahmen wie die Textseiten. Link in der Fußzeile.
- Datenschutzerklärung: Hinweis, dass die Eingabe an Google Places geht (von unserem Server, nicht mit der
  IP des Besuchers).

## Tests

`BewertungsLinkServiceTest`: aus ohne Schlüssel, Treffer mit Link und ID-Prüfung, Speicher 24 h, Drossel
nur für echte Anfragen, zu kurz/kaputt, Link und QR-PNG.
