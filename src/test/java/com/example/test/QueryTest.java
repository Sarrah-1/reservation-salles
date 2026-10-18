package com.example.test;

import com.example.model.Reservation;
import com.example.model.Salle;
import com.example.model.Utilisateur;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Vérifie que JPQL et Criteria API donnent les mêmes résultats. */
class QueryTest extends AbstractPersistenceTest {

    @Test
    void jpqlEtCriteriaDonnentLesMemesReservations() {
        Salle salleA = service.trouverSalleParNom("Salle A");
        LocalDateTime maintenant = LocalDateTime.now();

        List<Reservation> jpql = service.reservationsAVenirParSalle("Salle A", maintenant);
        List<Reservation> criteria = service.rechercher(salleA.getId(), null, maintenant, null);

        assertFalse(jpql.isEmpty());
        assertEquals(
                jpql.stream().map(Reservation::getId).collect(Collectors.toList()),
                criteria.stream().map(Reservation::getId).collect(Collectors.toList()));
    }

    @Test
    void resultatsChargesSansLazyInitializationException() {
        // salle et utilisateur sont accessibles après fermeture de l'EntityManager grâce au JOIN FETCH
        List<Reservation> res = service.reservationsAVenirParSalle("Salle B", LocalDateTime.now());
        assertFalse(res.isEmpty());
        for (Reservation r : res) {
            assertEquals("Salle B", r.getSalle().getNom());
            assertTrue(r.getUtilisateur().getEmail().contains("@"));
        }
    }

    @Test
    void criteriaFiltreParUtilisateurEtPeriode() {
        Utilisateur alice = service.trouverUtilisateurParEmail("ouaday.sara@gmail.com");

        List<Reservation> toutes = service.rechercher(null, alice.getId(), null, null);
        assertFalse(toutes.isEmpty());
        toutes.forEach(r -> assertEquals(alice.getId(), r.getUtilisateur().getId()));

        List<Reservation> aucune = service.rechercher(null, alice.getId(), null, LocalDateTime.now());
        assertTrue(aucune.isEmpty(), "toutes les réservations d'Alice sont dans le futur");
    }

    @Test
    void sansCritereToutesLesReservationsSontRetournees() {
        assertEquals(15, service.rechercher(null, null, null, null).size());
    }
}
