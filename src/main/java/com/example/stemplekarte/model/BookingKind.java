package com.example.stemplekarte.model;

public enum BookingKind {
    /** Einkauf gebucht. */
    EARN,
    /** Praemie abgebucht. */
    REDEEM,
    /** Handkorrektur mit Vorzeichen. */
    CORRECTION,
    /** Gegenbuchung zu einer frueheren Buchung. */
    REVERSAL
}
