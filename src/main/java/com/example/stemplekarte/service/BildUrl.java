package com.example.stemplekarte.service;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;

/**
 * Prueft Bild-Adressen, die ein Laden selbst setzen darf.
 *
 * <p>Logo, Banner, Stempel-Bild und Streifenbild stehen als URL in der
 * Datenbank, und der Server LAEDT diese Adresse, wenn er den Pass baut
 * (siehe PassTemplateGenerator.ladeBild). Ohne Pruefung kann ein
 * angemeldeter Laden den Server also auf jede beliebige Adresse schicken -
 * auch auf solche, die nur von innen erreichbar sind: die eigene
 * Verwaltungsschnittstelle, Nachbardienste bei Render, die
 * Metadaten-Adresse der Hosting-Umgebung. Das Bild landet danach sichtbar
 * auf der Karte, die Antwort kaeme also beim Angreifer an.
 *
 * <p>Deshalb: nur https, und die Adresse muss ausserhalb liegen.
 */
public final class BildUrl {

    private BildUrl() {}

    /** Nur fuer Tests: so laesst sich ohne echtes DNS pruefen. */
    interface Aufloeser {
        InetAddress[] aufloesen(String host) throws UnknownHostException;
    }

    private static final Aufloeser DNS = InetAddress::getAllByName;

    public static boolean istErlaubt(String url) {
        return istErlaubt(url, DNS);
    }

    static boolean istErlaubt(String url, Aufloeser aufloeser) {
        if (url == null || url.isBlank()) return false;

        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException e) {
            return false;
        }

        // http waere im Klartext und erlaubt ausserdem Umleitungen auf
        // interne Ziele, die wir hier gar nicht erst sehen.
        if (!"https".equalsIgnoreCase(uri.getScheme())) return false;

        // https://interner-host@fremder-host/ - manche Bibliotheken lesen den
        // Teil vor dem @ als Ziel. Wir brauchen das nie.
        if (uri.getUserInfo() != null) return false;

        String host = uri.getHost();
        if (host == null || host.isBlank()) return false;

        try {
            InetAddress[] adressen = aufloeser.aufloesen(host);
            if (adressen.length == 0) return false;
            for (InetAddress adresse : adressen) {
                if (istIntern(adresse)) return false;
            }
        } catch (UnknownHostException e) {
            return false;
        }
        return true;
    }

    /**
     * Wirft, wenn die Adresse nicht passt. Leer bedeutet "Bild entfernen"
     * und ist erlaubt.
     */
    public static void pruefe(String url, String feld) {
        if (url == null || url.isBlank()) return;
        if (!istErlaubt(url)) {
            throw new IllegalArgumentException(
                    "Bild-Adresse nicht erlaubt (" + feld + "): nur https und keine internen Adressen.");
        }
    }

    private static boolean istIntern(InetAddress adresse) {
        if (adresse.isAnyLocalAddress()      // 0.0.0.0, ::
                || adresse.isLoopbackAddress()   // 127.0.0.0/8, ::1
                || adresse.isLinkLocalAddress()  // 169.254.0.0/16 - die Metadaten-Adresse
                || adresse.isSiteLocalAddress()  // 10/8, 172.16/12, 192.168/16
                || adresse.isMulticastAddress()) {
            return true;
        }
        byte[] b = adresse.getAddress();
        if (b.length == 4) {
            int erstes = b[0] & 0xFF, zweites = b[1] & 0xFF;
            // 100.64.0.0/10 - Carrier-Grade-NAT, zaehlt auch nicht als aussen.
            if (erstes == 100 && zweites >= 64 && zweites <= 127) return true;
        } else if (b.length == 16) {
            // fc00::/7 - private IPv6-Adressen; isSiteLocalAddress deckt nur fec0::/10 ab.
            if ((b[0] & 0xFE) == 0xFC) return true;
        }
        return false;
    }
}
