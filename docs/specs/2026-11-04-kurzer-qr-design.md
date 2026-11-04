# Kürzerer QR-Code (2026-11-04)

## Ziel

Der QR-Code auf der Wallet-Karte war `{"cid":"CUST-<UUID>","cardId":"..."}`, also etwa 90 Zeichen.
Das ergibt einen dichten Code mit kleinen Punkten, der bei Sonne, Sprung im Glas oder schwacher Kamera schlecht liest.

## Lösung

- Neuer Inhalt: `SK:` + Kundenkarten-ID, z. B. `SK:CC-1A2B3C4D` (14 Zeichen).
  - Nur Großbuchstaben, Ziffern und `:-`, deshalb Alphanumerik-Modus. Das ist die kleinste QR-Stufe (21×21 statt rund 41×41), mit viel größeren Punkten.
  - Die Kunden-ID steht nicht mehr im Code. Sie öffnet die Kartenseite und soll nicht offen herumliegen.
- `QrPayload.fuer(cc)` baut den Inhalt (Apple und Google). `QrPayload.parse(text, customerCardRepo)` liest beide Formen. Die kurze wird über die Kundenkarte aufgelöst, danach läuft alles wie bisher über Kunde und Karte.
- **Alte Karten** mit dem langen JSON werden dauerhaft weiter gelesen. Sie bekommen den neuen Code beim nächsten Update der Karte von selbst.
- Sicherheit: wie vorher. Scannen geht nur mit Mitarbeiter-Zugang, und die Karte muss zum Laden gehören.
- Frontend: keine Änderung, der Scanner schickt den Text unverändert an den Server.
