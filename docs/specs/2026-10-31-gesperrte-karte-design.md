# Gesperrte Karte: eigenes Design

Stand: 2026-10-31. Baut auf `2026-10-29-willkommensgeschenk-design.md` auf.

## Ziel

Die gesperrte Karte (Willkommensgeschenk offen, noch keine Stempel) bekommt ein eigenes,
vom Ladenbesitzer einstellbares Aussehen. Mit Live-Vorschau im Editor.

## Aussehen

### Apple (iPhone)
- Kopf: Label leer, Wert = Ueberschrift (Standard "Willkommen"), kein Emoji.
  Key bleibt `stamps` (Raster) bzw. `stamps-header`, damit beim Freischalten die
  Mitteilung "Karte freigeschaltet" weiter kommt (Wert springt auf "0/10").
- Kein Primary-Feld mehr (der grosse Text faellt weg).
- Secondary: `🎁 GESCHENK` = Geschenktext, `SO GEHT'S` = Hinweistext
  (Standard "An der Kasse vorzeigen").
- Auxiliary: `KUNDE` = Name (wie bisher).
- Streifen: eigenes Willkommensbild, falls gesetzt. Sonst kein Streifen (wie bisher).
- Farben: Hintergrund/Text/Beschriftung der gesperrten Karte, falls gesetzt.
  Jede Farbe einzeln, leer = normale Kartenfarbe.

### Google (Android)
- Hero-Bild am LoyaltyObject = Willkommensbild, nur solange gesperrt
  (beim Freischalten auf null patchen).
- Textzeilen: Ueberschrift, Geschenk, Hinweis.
- Keine eigenen Farben: Google kennt Farben nur an der Klasse (ein Laden = eine Klasse).

### Freischalten
Bild, Sonderfarben und Texte verschwinden, normale Stempelkarte erscheint. Ablauf und
Mitteilung wie bisher.

## Daten

Neue, optionale Spalten an `Card` (leer = Standard):

| Feld | Spalte | Grenze |
|---|---|---|
| welcomeImageUrl | welcome_image_url | Cloudinary-URL, `BildUrl.pruefe` |
| welcomeTitle | welcome_title | 18 Zeichen |
| welcomeHint | welcome_hint | 40 Zeichen |
| welcomeColorBackground | welcome_color_bg | #RRGGBB |
| welcomeColorForeground | welcome_color_fg | #RRGGBB |
| welcomeColorLabel | welcome_color_label | #RRGGBB |

Die Werte werden live von der Karte gelesen (nicht pro Kunde eingefroren), damit
Aenderungen auch bei schon gesperrten Kunden ankommen. Der Geschenktext bleibt wie
bisher pro Kunde gespeichert.

Schema per `ddl-auto: update` (nur neue nullable Spalten).

## API

- `GET/PUT /api/shop/cards/{id}/willkommen`: Request und Response um
  `title`, `hint`, `colorBackground`, `colorForeground`, `colorLabel`, `imageUrl` erweitert.
  Alte Clients ohne diese Felder: `null` = unveraendert.
  Leerer String = zuruecksetzen auf Standard. Steuerzeichen raus, Laengen und
  Farbformat pruefen (400 bei Fehler).
- `POST /api/shop/cards/{id}/welcome-image`: Upload wie `/strip` (Base64, Cloudinary,
  Public-ID `welcome-{cardId}`), setzt `welcomeImageUrl`.
- Nach dem Speichern: alle offenen gesperrten Kundenkarten dieser Karte still
  aktualisieren (Apple-Push ohne changeMessage, Google-Patch ohne Mitteilung).

## Editor (Frontend)

Im Block "Willkommensgeschenk", nur sichtbar wenn eingeschaltet, unter dem Geschenktext:
- Bild-Upload (mit Entfernen)
- Ueberschrift, Hinweistext
- Drei Farbwaehler mit Hinweis "nur iPhone"
- Die bestehende Vorschau der gesperrten Karte (iPhone + Android) zeigt alles live.
- Speichern ueber den bestehenden Speichern-Knopf (`saveEditDesign`).
- Texte de/en/ar.

## Tests

- `WillkommenPassTest`: Kopf ohne Emoji + eigene Ueberschrift, kein Primary-Feld,
  Secondary mit Geschenk und Hinweis, Standardwerte, Key unveraendert, Farben
  ueberschrieben nur im gesperrten Zustand.
- `PassTemplateGenerator.brauchtStreifen`: gesperrt mit Bild = Streifen, ohne Bild = keiner.
- Google: Hero-Bild gesetzt im gesperrten, null im freigeschalteten Objekt.
- Controller: Validierung (Laenge, Farbformat, leer = Standard, null = unveraendert).
- Frontend: Build, i18n-Test, Lint nicht schlechter als 4 Fehler.

## Nicht Teil davon

Eigene Google-Farben, mehrere Bilder, Bildbearbeitung (Zuschneiden) ueber das hinaus,
was der Strip-Upload heute schon kann.
