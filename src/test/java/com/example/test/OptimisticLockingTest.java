package com.example.test;

import com.example.model.Reservation;
import com.example.model.Salle;
import org.junit.jupiter.api.Test;

import javax.persistence.EntityManager;
import javax.persistence.OptimisticLockException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OptimisticLockingTest extends AbstractPersistenceTest {

    @Test
    void miseAJourConcurrenteDetecteeParLaVersion() {
        Long salleId = service.trouverSalleParNom("Salle A").getId();
        EntityManager em1 = emf.createEntityManager();
        EntityManager em2 = emf.createEntityManager();
        try {
            Salle s1 = em1.find(Salle.class, salleId);
            Salle s2 = em2.find(Salle.class, salleId);
            assertEquals(s1.getVersion(), s2.getVersion());

            em1.getTransaction().begin();
            s1.setDescription("Première modification");
            em1.getTransaction().commit();
            assertEquals(s2.getVersion() + 1, s1.getVersion());

            // em2 travaille sur une version périmée : le commit doit échouer
            em2.getTransaction().begin();
            s2.setDescription("Seconde modification (périmée)");
            RuntimeException e = assertThrows(RuntimeException.class, () -> em2.getTransaction().commit());
            // Hibernate enveloppe l'OptimisticLockException dans une RollbackException au commit
            assertTrue(aPourCause(e, OptimisticLockException.class),
                    "OptimisticLockException attendue dans la chaîne des causes : " + e);
        } finally {
            em1.close();
            em2.close();
        }

        // La première modification est bien celle qui a été conservée
        EntityManager verif = emf.createEntityManager();
        try {
            assertEquals("Première modification", verif.find(Salle.class, salleId).getDescription());
        } finally {
            verif.close();
        }
    }

    @Test
    void laVersionAugmenteAChaqueModification() {
        Long salleId = service.trouverSalleParNom("Salle B").getId();
        EntityManager e = emf.createEntityManager();
        try {
            Salle salle = e.find(Salle.class, salleId);
            int avant = salle.getVersion();
            e.getTransaction().begin();
            salle.setCapacite(salle.getCapacite() + 1);
            e.getTransaction().commit();
            assertEquals(avant + 1, salle.getVersion());
        } finally {
            e.close();
        }
    }

    @Test
    void reservationEstAussiVersionnee() {
        Reservation r = service.rechercher(null, null, null, null).get(0);
        assertEquals(0, r.getVersion());
    }
}
