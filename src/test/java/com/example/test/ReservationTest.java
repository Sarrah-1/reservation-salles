package com.example.test;

import com.example.model.Reservation;
import com.example.model.Salle;
import com.example.model.Utilisateur;
import com.example.service.ReservationConflictException;
import com.example.service.ReservationException;
import com.example.util.DataInitializer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReservationTest extends AbstractPersistenceTest {

    private static LocalDateTime creneau(int joursPlusTard, int heure) {
        return LocalDateTime.now().plusDays(joursPlusTard).withHour(heure).withMinute(0).withSecond(0).withNano(0);
    }

    private Utilisateur nouvelUtilisateur() {
        String suffixe = UUID.randomUUID().toString().substring(0, 8);
        return service.creerUtilisateur("Test " + suffixe, "test." + suffixe + "@example.com");
    }

    @Test
    void jeuDeDonneesInitial() {
        assertEquals(DataInitializer.NB_UTILISATEURS,
                em.createQuery("SELECT COUNT(u) FROM Utilisateur u", Long.class).getSingleResult().intValue());
        assertEquals(DataInitializer.NB_SALLES,
                em.createQuery("SELECT COUNT(s) FROM Salle s", Long.class).getSingleResult().intValue());
        assertEquals(DataInitializer.NB_RESERVATIONS,
                em.createQuery("SELECT COUNT(r) FROM Reservation r", Long.class).getSingleResult().intValue());
    }

    /** Version « brute » du test du TP : persistance directe avec l'EntityManager. */
    @Test
    void testCreateReservation() {
        em.getTransaction().begin();

        Utilisateur user = new Utilisateur("Test User", "test.user." + UUID.randomUUID() + "@example.com");
        em.persist(user);

        Salle salle = em.createQuery("SELECT s FROM Salle s WHERE s.nom = 'Salle A'", Salle.class).getSingleResult();

        Reservation reservation = new Reservation();
        reservation.setUtilisateur(user);
        reservation.setSalle(salle);
        reservation.setDateDebut(creneau(20, 8));
        reservation.setDateFin(creneau(20, 9));
        em.persist(reservation);
        em.getTransaction().commit();

        assertNotNull(reservation.getId());
        assertEquals(0, reservation.getVersion());
    }

    @Test
    void creationViaService() {
        Salle salle = service.trouverSalleParNom("Salle B");
        Utilisateur user = nouvelUtilisateur();

        Reservation r = service.creerReservation(salle.getId(), user.getId(), creneau(21, 10), creneau(21, 12));

        assertNotNull(r.getId());
        List<Reservation> trouvees = service.rechercher(salle.getId(), user.getId(), null, null);
        assertEquals(1, trouvees.size());
        assertEquals(r.getId(), trouvees.get(0).getId());
    }

    @Test
    void conflitDeReservationDetecte() {
        Salle salle = service.trouverSalleParNom("Salle C");
        Utilisateur user = nouvelUtilisateur();
        service.creerReservation(salle.getId(), user.getId(), creneau(22, 10), creneau(22, 12));

        // chevauchement partiel
        assertThrows(ReservationConflictException.class, () ->
                service.creerReservation(salle.getId(), user.getId(), creneau(22, 11), creneau(22, 13)));
        // créneau inclus
        assertThrows(ReservationConflictException.class, () ->
                service.creerReservation(salle.getId(), user.getId(), creneau(22, 10), creneau(22, 11)));
        // créneaux adjacents : pas de conflit
        Assertions.assertDoesNotThrow(() ->
                service.creerReservation(salle.getId(), user.getId(), creneau(22, 12), creneau(22, 13)));
        // même créneau mais autre salle : pas de conflit
        Salle autre = service.trouverSalleParNom("Salle B");
        Assertions.assertDoesNotThrow(() ->
                service.creerReservation(autre.getId(), user.getId(), creneau(22, 10), creneau(22, 12)));
    }

    @Test
    void datesInvalidesRefusees() {
        Salle salle = service.trouverSalleParNom("Salle A");
        Utilisateur user = nouvelUtilisateur();

        assertThrows(ReservationException.class, () ->
                service.creerReservation(salle.getId(), user.getId(), creneau(23, 12), creneau(23, 10)));
        assertThrows(ReservationException.class, () ->
                service.creerReservation(salle.getId(), user.getId(), null, creneau(23, 10)));
        assertThrows(ReservationException.class, () ->
                service.creerReservation(salle.getId(), user.getId(),
                        LocalDateTime.now().minusDays(1), LocalDateTime.now().minusHours(20)));
    }

    @Test
    void salleOuUtilisateurInconnu() {
        Salle salle = service.trouverSalleParNom("Salle A");
        Utilisateur user = nouvelUtilisateur();

        assertThrows(ReservationException.class, () ->
                service.creerReservation(-1L, user.getId(), creneau(24, 10), creneau(24, 11)));
        assertThrows(ReservationException.class, () ->
                service.creerReservation(salle.getId(), -1L, creneau(24, 10), creneau(24, 11)));
    }

    @Test
    void modificationIncrementeLaVersion() {
        Salle salle = service.trouverSalleParNom("Salle A");
        Utilisateur user = nouvelUtilisateur();
        Reservation r = service.creerReservation(salle.getId(), user.getId(), creneau(25, 9), creneau(25, 10));

        Reservation modifiee = service.modifierReservation(r.getId(), creneau(25, 14), creneau(25, 16));

        assertEquals(creneau(25, 14), modifiee.getDateDebut());
        assertEquals(1, modifiee.getVersion());
        assertEquals(salle.getId(), modifiee.getSalle().getId());
    }

    @Test
    void modificationEnConflitRefusee() {
        Salle salle = service.trouverSalleParNom("Salle A");
        Utilisateur user = nouvelUtilisateur();
        service.creerReservation(salle.getId(), user.getId(), creneau(26, 9), creneau(26, 11));
        Reservation r2 = service.creerReservation(salle.getId(), user.getId(), creneau(26, 13), creneau(26, 14));

        assertThrows(ReservationConflictException.class, () ->
                service.modifierReservation(r2.getId(), creneau(26, 10), creneau(26, 12)));
        // le déplacement sur son propre créneau ne doit pas être vu comme un conflit
        Assertions.assertDoesNotThrow(() ->
                service.modifierReservation(r2.getId(), creneau(26, 13), creneau(26, 15)));
    }

    @Test
    void annulationSupprimeLaReservation() {
        Salle salle = service.trouverSalleParNom("Salle B");
        Utilisateur user = nouvelUtilisateur();
        Reservation r = service.creerReservation(salle.getId(), user.getId(), creneau(27, 9), creneau(27, 10));

        service.annulerReservation(r.getId());

        assertNull(em.find(Reservation.class, r.getId()));
        assertThrows(ReservationException.class, () -> service.annulerReservation(r.getId()));
    }

    @Test
    void emailDejaUtiliseRefuse() {
        Utilisateur user = nouvelUtilisateur();
        ReservationException e = assertThrows(ReservationException.class, () ->
                service.creerUtilisateur("Doublon", user.getEmail()));
        assertTrue(e.getMessage().toLowerCase().contains("contrainte"));
    }

    @Test
    void sallesDisponiblesExclutLesSallesOccupees() {
        Salle salle = service.trouverSalleParNom("Salle C");
        Utilisateur user = nouvelUtilisateur();
        service.creerReservation(salle.getId(), user.getId(), creneau(28, 10), creneau(28, 12));

        List<Salle> libres = service.sallesDisponibles(creneau(28, 11), creneau(28, 13), 40);

        // Seule la Salle C a 50 places et elle est occupée : aucune salle libre de 40 places ou plus
        assertTrue(libres.isEmpty());
        List<Salle> libresPetites = service.sallesDisponibles(creneau(28, 11), creneau(28, 13), 1);
        assertEquals(DataInitializer.NB_SALLES - 1, libresPetites.size());
    }
}
