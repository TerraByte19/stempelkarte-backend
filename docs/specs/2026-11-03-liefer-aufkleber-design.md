# Aufkleber für Liefertüten – Design

Stand: 2026-11-03 (nur Backend; Frontend folgt im Sammel-Durchgang)

## Ziel

Für Lieferando, Uber Eats und Co.: ein Aufkleber auf der Tüte mit QR-Code
zur Anmeldeseite der Karte, damit auch Lieferkunden die Karte holen. Der
Laden druckt einen A4-Bogen auf Etikettenpapier oder nutzt das Einzelbild.

## Regeln

- `AufkleberService`: Quadrat mit grauem Schnittrand, Überschrift (fett),
  QR-Code (`{baseUrl}/karte-neu/{cardId}`), Erklärzeile (bis zwei Zeilen),
  unten klein Laden · Karte. Schrift verkleinert sich, bis der Text passt.
- Bogen A4 bei 300 dpi (2480 × 3508 px), Größen: `KLEIN` 45 mm (4 × 6 =
  24 je Bogen), `GROSS` 70 mm (2 × 3 = 6 je Bogen), 3 mm Abstand, mittig.
- Einzelbild 600 × 600 px.
- Texte: `titel` max. 30 Zeichen (leer = „Stempel sammeln!“), `zeile` max.
  70 (leer = „QR-Code scannen und die Karte aufs Handy holen“),
  Steuerzeichen raus.

## API

- `GET /api/shop/cards/{cardId}/aufkleber?titel=&zeile=&groesse=KLEIN|GROSS
  &einzeln=false` → `image/png`, `Content-Disposition: inline; filename=…`.
  Nur eigene Karten.

## Frontend offen

- Unter „QR-Codes / Poster“ ein Block „Aufkleber für Liefertüten“:
  Überschrift und Text eingeben, Größe wählen, Vorschau (einzeln=true),
  Knopf „Bogen drucken“ (öffnet das PNG, Drucken im Browser bei 100 %).
- Hinweis auf passendes Etikettenpapier (z. B. 45 × 45 mm oder 70 × 70 mm
  Quadrate) oder einfach Papier und Schere.
