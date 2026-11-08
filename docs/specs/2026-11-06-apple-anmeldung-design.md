# Anmeldung mit Apple beim Kartenholen (2026-11-06)

## Ziel

Auf der Anmeldeseite `/karte-neu/{cardId}` steht neben „Mit Google anmelden“ ein Apple-Knopf. Der Kunde
bestätigt in Apples Fenster, die Karte liegt sofort in der Wallet, keine Bestätigungs-Mail. Gleicher Aufbau
wie die Google-Anmeldung (`docs/specs/2026-11-05-google-anmeldung-design.md`), dieselbe
`CustomerService.registerForCardBestaetigt`.

## Was noch fehlt (User, im Apple-Developer-Konto)

1. **App-ID** anlegen oder die bestehende nehmen, Capability „Sign in with Apple“ anhaken.
2. **Services-ID** anlegen (Identifiers → Services IDs), z. B. `de.stampit.web`. Das ist unsere Client-ID.
   Darin „Sign in with Apple“ konfigurieren:
   - Primary App ID: die App-ID von Schritt 1.
   - Domains: `stempelkarte-backend.onrender.com` (dort läuft die Anmeldeseite), später die eigene Domain.
   - Return URLs: `https://stempelkarte-backend.onrender.com/karte-neu/apple`.
3. Die Services-ID als Umgebungsvariable `APPLE_LOGIN_CLIENT_ID` bei Render eintragen. Ohne die Variable
   gibt es keinen Knopf, alles bleibt wie bisher.
4. **Mails an Apple-Weiterleitungsadressen:** Kunden dürfen ihre Adresse verstecken, dann bekommen wir
   `xyz@privaterelay.appleid.com`. Apple leitet Mails dorthin nur weiter, wenn unsere Absender-Domain
   eingetragen ist: Services → Sign in with Apple for Email Communication → Absender-Domain und
   -Adresse (die Brevo-Absenderadresse) eintragen. Sonst kommen Willkommens- und Erinnerungsmails bei
   diesen Kunden nicht an. Die Karte funktioniert trotzdem.

## Ablauf

1. Die Seite bindet Apples Skript ein (`appleid.cdn-apple.com/.../appleid/1/<de_DE|en_US|ar_SA|tr_TR>/appleid.auth.js`,
   asynchron, `onload` ruft `AppleID.auth.init` mit Client-ID, `scope: name email`, Rücksprung-Adresse
   und `usePopup: true`). Apple rendert den Knopf in `#appleid-signin`. Wie bei Googles Skript gibt es
   keinen Subresource-Integrity-Hash: Apple liefert das Skript laufend neu, ein fester Hash würde den
   Knopf beim nächsten Update stillschweigend abschalten.
2. Erfolg kommt als Ereignis `AppleIDSignInOnSuccess` mit `authorization.id_token` und, nur beim ersten
   Mal, `user.name`. Die Seite schickt Token, Name, Karte, Einwilligung, Geburtstag/Telefon und
   Empfehlungscode an `POST /karte-neu/register-apple` (gemeinsame Funktion `sozialAnmeldung` mit Google).
   Abbruch durch den Kunden (`popup_closed_by_user`, `user_cancelled_authorize`) ist kein Fehler.
   Ein `AppleIDSignInOnFailure` **vor** der ersten Berührung des Knopfs wird ignoriert (nur `console.warn`): auf dem
   iPhone kam nach dem QR-Scan sofort „Apple-Anmeldung fehlgeschlagen“, ohne dass jemand getippt hatte (2026-11-08).
3. `AppleAnmeldung.pruefe` prüft das Token selbst (jjwt): Signatur gegen Apples öffentliche Schlüssel
   (`appleid.apple.com/auth/keys`, 24 h im Speicher, bei unbekanntem `kid` neu laden, höchstens einmal
   je Minute), `iss = https://appleid.apple.com`, `aud` = unsere Services-ID, Ablauf (60 s Spielraum),
   `email_verified`. Der Name kommt vom Kunden (bereinigt, max. 100 Zeichen), sonst aus der Adresse.
4. Drossel je IP wie bei Google (20 Versuche in 10 Minuten, gemeinsame Klasse `Drossel`).
5. Antwort `{status: ok, url: /karte/{customerId}/{cardId}}`, die Seite springt dorthin.
6. `GET|POST /karte-neu/apple`: die eingetragene Rücksprung-Adresse. Im Popup-Ablauf kommt das Ergebnis
   per Ereignis zurück; landet ein Browser doch dort, steht nur „Fenster schließen“.

## Anmeldeseite

Ein Block `sozial` **unter** dem Knopf „Karte holen“ (User 2026-11-06 nach dem ersten Test: klein, kein
Erklärtext): „oder“, dann Google-Knopf (falls eingerichtet, medium, 200 px), dann Apple-Knopf (falls
eingerichtet, 200 × 36 px). Ohne beides kein Block. Die Sprachleiste DE · EN · AR · TR steht auf der
Anmeldeseite ganz unten, wie auf den Kartenseiten.

## Grenzen

- Kein `nonce`: die Seite ist statisch, ein Nonce bräuchte Server-Zustand. Token sind kurzlebig und
  nur über HTTPS unterwegs; ein abgefangenes Token könnte denselben Kunden an eine Karte hängen, mehr nicht.
- Private-Relay-Adressen siehe oben: ohne Eintrag bei Apple keine Mails an diese Kunden.

## Tests

`AppleAnmeldungTest` (eigenes RSA-Paar, gültig/fremde Zielgruppe/fremder Herausgeber/abgelaufen/fremder
Schlüssel/unbekannter kid/nicht bestätigt, JWKS-Parsen, Drossel), `AppleAnmeldeSeiteTest` (kein Knopf ohne
ID, Knopf mit, Weg zur Karte, ein Hinweis bei beiden Knöpfen, Rücksprungseite).
