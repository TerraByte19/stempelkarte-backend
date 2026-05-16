package com.example.stemplekarte.model;

public sealed interface ScanResult {
    CustomerCard customerCard();
    String message();

    record Stamped(CustomerCard customerCard, String message) implements ScanResult {}
    record Redeemed(CustomerCard customerCard, String message) implements ScanResult {}
    record Full(CustomerCard customerCard, String message) implements ScanResult {}
}