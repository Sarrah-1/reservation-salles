package com.example.test;

import com.example.model.Salle;
import com.example.model.Utilisateur;
import com.example.service.ReservationConflictException;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConcurrenceTest extends AbstractPersistenceTest {

    @Test
    void unSeulUtilisateurObtientLeCreneauConvoite() throws Exception {
        final int nbThreads = 6;
        Salle salle = service.trouverSalleParNom("Salle C");
        LocalDateTime debut = LocalDateTime.now().plusDays(40).withHour(10).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime fin = debut.plusHours(2);

        List<Long> utilisateurs = new ArrayList<>();
        for (int i = 0; i < nbThreads; i++) {
            String suffixe = UUID.randomUUID().toString().substring(0, 8);
            Utilisateur u = service.creerUtilisateur("Concurrent " + i, "concurrent." + suffixe + "@example.com");
            utilisateurs.add(u.getId());
        }

        ExecutorService pool = Executors.newFixedThreadPool(nbThreads);
        CountDownLatch depart = new CountDownLatch(1);
        List<Future<Boolean>> resultats = new ArrayList<>();
        try {
            for (Long utilisateurId : utilisateurs) {
                resultats.add(pool.submit(() -> {
                    depart.await();
                    try {
                        service.creerReservation(salle.getId(), utilisateurId, debut, fin);
                        return true;
                    } catch (ReservationConflictException e) {
                        return false;
                    }
                }));
            }
            depart.countDown();

            int reussites = 0;
            for (Future<Boolean> f : resultats) {
                if (f.get(60, TimeUnit.SECONDS)) {
                    reussites++;
                }
            }
            assertEquals(1, reussites, "exactement une réservation doit réussir");
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, service.rechercher(salle.getId(), null, debut, debut.plusMinutes(1)).size());
    }
}
