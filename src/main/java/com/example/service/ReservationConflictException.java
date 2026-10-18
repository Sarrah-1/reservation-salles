package com.example.service;

/** Levée quand le créneau demandé chevauche une réservation existante pour la même salle. */
public class ReservationConflictException extends ReservationException {

    private static final long serialVersionUID = 1L;

    public ReservationConflictException(String message) {
        super(message);
    }
}
