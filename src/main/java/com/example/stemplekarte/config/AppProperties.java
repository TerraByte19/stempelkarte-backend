package com.example.stemplekarte.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "stempelkarte")
public record AppProperties(
        int rewardThreshold,
        String baseUrl,
        Apple apple,
        Google google,
        Apns apns
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

    public record Apns(
            boolean enabled,
            String keyId,
            String teamId,
            String authKeyPath,
            boolean useSandbox
    ) {}
}