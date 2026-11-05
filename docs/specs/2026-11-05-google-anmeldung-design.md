# Anmeldung mit Google beim Kartenholen (2026-11-05)

## Ziel

Auf der Anmeldeseite `/karte-neu/{cardId}` tippt der Kunde auf „Mit Google anmelden“ statt Name und
E-Mail einzutippen und auf die Bestätigungs-Mail zu warten. Google hat die Adresse schon bestätigt,
die Karte liegt sofort in der Wallet. Entscheidung User (Nacht auf 2026-11-05): zuerst für Kunden, Apple später.

## Was noch fehlt (User)

Eine **Client-ID** aus der Google Cloud Console: APIs & Dienste → Anmeldedaten → OAuth-Client-ID, Typ
„Webanwendung“. Autorisierte JavaScript-Quellen: `https://stempelkarte-backend.onrender.com` (dort läuft
die Anmeldeseite) und später die eigene Domain. Die ID als Umgebungsvariable `GOOGLE_CLIENT_ID` bei Render
eintragen. Ohne die Variable gibt es keinen Knopf, alles bleibt wie bisher.

## Ablauf

1. Googles Skript (`accounts.google.com/gsi/client`) rendert den Knopf (`g_id_signin`, Sprache der Seite).
   Für dieses Skript gibt es kein Subresource-Integrity-Hash, Google liefert es laufend neu.
2. Google ruft `window.onGoogleCredential(token)`. Die Seite schickt Token, Karte, Einwilligung,
   Geburtstag/Telefon (falls der Laden sie abfragt) und Empfehlungscode an `POST /karte-neu/register-google`.
3. `GoogleAnmeldung.pruefe` fragt Googles `tokeninfo` (prüft Signatur und Ablauf) und prüft selbst
   Herausgeber, `aud` = unsere Client-ID und `email_verified`.
4. `CustomerService.registerForCardBestaetigt(...)`: wie die normale Anmeldung, aber `confirmEmail()` sofort
   und keine Mail. Antwort `{status: ok, url: /karte/{customerId}/{cardId}}`, die Seite springt dorthin.
5. Fehler (falsches Token, Pflichtfeld fehlt) erscheinen in der roten Zeile; der E-Mail-Weg bleibt daneben.

## Tests

`GoogleAnmeldungTest` (Token-Prüfung ohne Netz, Anmeldung ohne Mail), `GoogleAnmeldeSeiteTest`
(kein Knopf ohne Client-ID, Knopf mit, Weg zur Karte).
