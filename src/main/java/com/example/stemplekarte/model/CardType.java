package com.example.stemplekarte.model;

/**
 * Was eine Karte zaehlt. Beim Anlegen gewaehlt, danach fest - ein Wechsel
 * wuerde bestehende Staende bedeutungslos machen (7 Stempel sind keine
 * 7 Punkte).
 *
 * MEMBER: Mitgliedskarte ohne Stempel und Punkte - ein Ausweis im Wallet.
 * Der Scan zaehlt nur den Besuch (in "stamps", ohne Schwelle und Praemie).
 *
 * PACKAGE: 10er-Karte. Der Kunde kauft ein Paket Besuche (reward_threshold
 * = Paketgroesse), jeder Scan bucht einen ab (CustomerCard.paketRest).
 * "stamps" zaehlt wie bei MEMBER die Besuche insgesamt hoch.
 */
public enum CardType {
    STAMP,
    POINTS,
    MEMBER,
    PACKAGE
}
