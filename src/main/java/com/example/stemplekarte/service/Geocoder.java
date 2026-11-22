package com.example.stemplekarte.service;

import java.util.Optional;

/**
 * Adresse -> Koordinaten. Schnittstelle, damit der Dienst dahinter
 * austauschbar ist und Tests nie ins Netz gehen.
 */
public interface Geocoder {

    /** anzeige = was der Dienst gefunden hat, zur Kontrolle durch den Menschen. */
    record Punkt(double latitude, double longitude, String anzeige) {}

    /** Leer, wenn nichts gefunden wurde oder der Dienst nicht antwortet. Wirft nie. */
    Optional<Punkt> suche(String adresse);
}
