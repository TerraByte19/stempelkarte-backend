package com.example.stemplekarte.model;

public sealed interface ScanResult {
    CustomerCard customerCard();
    String message();

    /**
     * Wie oft die Karte WÄHREND dieses Scans voll wurde (kann >0 sein, auch
     * wenn das finale Ergebnis "Stamped" oder "Redeemed" ist — z.B. wenn
     * mehrere Stempel in einem Scan dazukommen und die Karte zwischendurch
     * voll wurde und direkt neu gestartet ist).
     */
    int rewardsEarnedThisScan();

    record Stamped(CustomerCard customerCard, String message, int rewardsEarnedThisScan) implements ScanResult {}
    /** Mitgliedskarte: Besuch gezaehlt, kein Stempel, keine Praemie. */
    record Visited(CustomerCard customerCard, String message, int rewardsEarnedThisScan) implements ScanResult {}
    /** 10er-Karte: Besuch(e) vom Paket abgebucht. */
    record Debited(CustomerCard customerCard, String message, int rewardsEarnedThisScan) implements ScanResult {}
    /** 10er-Karte: Paket leer, nichts gebucht - das Personal muss erst aufladen. */
    record Empty(CustomerCard customerCard, String message, int rewardsEarnedThisScan) implements ScanResult {}
    record Redeemed(CustomerCard customerCard, String message, int rewardsEarnedThisScan) implements ScanResult {}
    record Full(CustomerCard customerCard, String message, int rewardsEarnedThisScan) implements ScanResult {}
}