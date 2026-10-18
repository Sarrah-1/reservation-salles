package com.example.test;

import com.example.model.Reservation;
import com.example.model.Salle;
import com.example.model.Utilisateur;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;

import javax.persistence.EntityManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecondLevelCacheTest extends AbstractPersistenceTest {

    private Statistics stats() {
        return emf.unwrap(SessionFactory.class).getStatistics();
    }

    @Test
    void entiteMiseEnCachePuisServieParLeCache() {
        Long salleId = service.trouverSalleParNom("Salle B").getId();
        emf.getCache().evictAll();
        stats().clear();

        EntityManager em1 = emf.createEntityManager();
        em1.find(Salle.class, salleId);   // lecture en base puis mise en cache
        em1.close();
        assertTrue(emf.getCache().contains(Salle.class, salleId));
        assertTrue(stats().getSecondLevelCachePutCount() >= 1);
        assertTrue(stats().getSecondLevelCacheMissCount() >= 1);
        assertEquals(0, stats().getSecondLevelCacheHitCount());

        EntityManager em2 = emf.createEntityManager(); // autre session : cache de 1er niveau vide
        em2.find(Salle.class, salleId);   // servi par le cache de second niveau
        em2.close();

        assertTrue(stats().getSecondLevelCacheHitCount() >= 1, "la 2e lecture doit venir du cache");
    }

    @Test
    void entiteEvincteEstRelueEnBase() {
        Long userId = service.trouverUtilisateurParEmail("ouaday.sara@gmail.com").getId();
        emf.getCache().evictAll();
        stats().clear();

        EntityManager em1 = emf.createEntityManager();
        em1.find(Utilisateur.class, userId);
        em1.close();
        assertTrue(emf.getCache().contains(Utilisateur.class, userId));

        emf.getCache().evict(Utilisateur.class, userId);
        assertFalse(emf.getCache().contains(Utilisateur.class, userId));

        EntityManager em2 = emf.createEntityManager();
        em2.find(Utilisateur.class, userId);
        em2.close();

        assertEquals(0, stats().getSecondLevelCacheHitCount(), "après éviction, aucune lecture depuis le cache");
    }

    @Test
    void reservationsNeSontPasMisesEnCache() {
        Long reservationId = service.rechercher(null, null, null, null).get(0).getId();
        EntityManager e = emf.createEntityManager();
        e.find(Reservation.class, reservationId);
        e.close();
        assertFalse(emf.getCache().contains(Reservation.class, reservationId));
    }

    @Test
    void cacheDeRequetes() throws InterruptedException {
        Thread.sleep(100); // laisse passer les horodatages des écritures du jeu de données
        emf.getCache().evictAll();
        stats().clear();

        service.listerSalles();  // miss + mise en cache du résultat
        service.listerSalles();  // hit sur le cache de requêtes

        assertTrue(stats().getQueryCacheMissCount() >= 1);
        assertTrue(stats().getQueryCachePutCount() >= 1);
        assertTrue(stats().getQueryCacheHitCount() >= 1, "le 2e appel doit utiliser le cache de requêtes");
    }
}
