# API und Webhooks – Design

Stand: 2026-11-03 (nur Backend; Frontend folgt im Sammel-Durchgang)

## Ziel

Kassen, CRM und Zapier sollen stempeln, Punkte buchen und Kunden nachsehen
können (API) und erfahren, wenn etwas passiert (Webhooks). Premium-Funktion:
der Admin schaltet sie je Laden frei (`Shop.apiFreigeschaltet`, wie maxOrte).

## API-Schlüssel

- Tabelle `api_keys`: id, shop, label, prefix (Anzeige), hash (SHA-256 des
  Schlüssels), created_at, last_used_at, revoked_at. Der Schlüssel selbst
  wird nur einmal beim Anlegen gezeigt und nie gespeichert.
- Schlüssel: `sk_` + 40 Zeichen. Höchstens 5 aktive je Laden.
- `ApiKeyFilter`: Header `X-Api-Key` → Hash → aktiver Schlüssel → Laden
  aktiv und freigeschaltet → `ROLE_API` mit `ApiPrincipal(shop, label)`.
  „Zuletzt benutzt“ gedrosselt alle 10 Minuten, nie blockierend.
- Verwaltung (Inhaber-Login): `GET /api/shop/api-keys`, `POST {label}` →
  `{id, label, key}` (einmalig), `DELETE /{id}` (widerrufen, Zeile bleibt).

## Externe API `/api/v1` (nur mit `X-Api-Key`)

- `GET /cards` – Karten des Ladens.
- `GET /customers?email=` / `GET /customers/{customerId}` – Kunde mit
  seinen Karten bei diesem Laden (Stempel, Prämien, Punkte, letzter Besuch).
- `POST /stamps {customerId, cardId, count}` – stempeln wie der Scanner
  (Geschenk-Sperre, Karte voll, Einlösen, Empfehlungsbonus), Protokoll mit
  Zugang „API: label“. Wallet-Update wie im Scanner. Keine Geo-Prüfung:
  Server-zu-Server.
- `POST /points {customerId, cardId, amountCents}` – Einkauf buchen.
- Fehler wie überall: 400 Klartext, 404 unbekannt, 401 ohne Schlüssel.

## Webhooks

- Je Laden eine URL (https) und ein Geheimnis (`shops.webhook_url`,
  `webhook_secret`). Geheimnis wird beim Einrichten einmal gezeigt.
- Schutz vor SSRF (der Server ruft eine Adresse auf, die der Laden
  eingetragen hat): nur https, kein Benutzername in der URL, Hostname muss
  auflösbar sein und darf auf keine interne Adresse zeigen (localhost,
  10/8, 172.16/12, 192.168/16, 169.254/16, 100.64/10, 0/8, ::1, fc00::/7,
  fe80::/10, IPv4-gemappt). Geprüft beim Einrichten UND vor jedem Versand
  (DNS-Rebinding) – zeigt der Name inzwischen nach innen, wird das Ereignis
  verworfen. Keine Weiterleitungen. Fehlertexte werden nie roh gespeichert.
- Ausgangstabelle `webhook_ereignisse`: id, shop_id, typ, payload (JSON),
  status OFFEN/GESENDET/FEHLER, versuche, naechster_versuch, erstellt_am,
  gesendet_am, antwort (Status + Kurztext). Die Zeile entsteht in derselben
  Transaktion wie der Stempel – kein Ereignis ohne Stempel, kein Stempel
  ohne Ereignis.
- Läufer alle 20 s: fällige OFFEN senden (POST, JSON, 8 s Timeout), Kopf
  `X-Stampit-Event`, `X-Stampit-Delivery`, `X-Stampit-Signature:
  sha256=HMAC(secret, body)`. 2xx = GESENDET, sonst Wiederholung nach
  1 min, 5 min, 30 min, 2 h, 12 h; nach 5 Versuchen FEHLER.
- Ereignisse: `stamp.added`, `card.full`, `reward.redeemed`,
  `customer.signup`, `points.earned`, `points.redeemed`, `test`.
  Payload: `{id, typ, zeit, shopId, daten{…}}`. E-Mail nur bei
  `customer.signup` (der Laden hat sie ohnehin).
- Verwaltung: `GET /api/shop/webhook` (URL, Zahlen, letzte 20 Zustellungen,
  nie das Geheimnis), `PUT {url}` → mit Geheimnis, `DELETE`, `POST /test`.
- Die Dienste rufen `WebhookService.melde(shop, typ, daten)`; der Dienst
  ist in CustomerService und PointsService optional (`required = false`),
  damit Unit-Tests ohne Spring unverändert laufen.

## Admin

- `POST /api/admin/shops/{id}/api {freigeschaltet}`, Liste zeigt
  `apiFreigeschaltet`. Löschen räumt Schlüssel und Ereignisse mit ab.

## Frontend offen

- Profil/Bereich „Schnittstellen“ (nur wenn freigeschaltet): Schlüssel
  anlegen (einmal anzeigen, kopieren), Liste mit Widerrufen; Webhook-URL
  setzen (Geheimnis einmal anzeigen), Test-Knopf, Zustell-Verlauf.
- Admin: Schalter „API“ je Laden.
- Kurze Doku-Seite der Endpunkte (Swagger gibt es schon unter /swagger-ui).
