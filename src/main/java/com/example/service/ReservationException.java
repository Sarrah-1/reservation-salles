package com.example.service;

/** Erreur métier liée aux réservations (données invalides, conflit de modification, etc.). */
public class ReservationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ReservationException(String message) {
        super(message);
    }

    public ReservationException(String message, Throwable cause) {
        super(message, cause);
    }
}
