package com.example.stemplekarte.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "stempelkarte")
public record AppProperties(
        int rewardThreshold,
        String baseUrl,
        Apple apple,
        Google google,
        Apns apns,
        Nfc nfc
) {
    public record Apple(
            String passTypeIdentifier,
            String teamIdentifier,
            String organizationName,
            String certPath,
            String certPassword,
            String wwdrCertPath,
            String templatePath,
            String authSecret
    ) {}

    public record Google(
            String issuerId,
            String classSuffix,
            String serviceAccountPath
    ) {}

    /**
     * NFC-Karte am Tresen.
     *
     * @param cookieSecret   signiert das Kunden-Cookie. Wird er geaendert,
     *                       sind alle Cookies ungueltig und die Kunden
     *                       muessen ihre Karte einmal wieder oeffnen.
     * @param cooldownSeconds Mindestabstand zwischen zwei Stempeln desselben
     *                        Kunden auf derselben Karte.
     */
    public record Nfc(
            boolean enabled,
            String cookieSecret,
            int cooldownSeconds
    ) {}

    public record Apns(
            boolean enabled,
            String keyId,
            String teamId,
            String authKeyPath,
            boolean useSandbox
    ) {}
}