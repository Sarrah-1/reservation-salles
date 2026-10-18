package com.example;

import com.example.model.Reservation;
import com.example.model.Salle;
import com.example.model.Utilisateur;
import com.example.service.ReservationConflictException;
import com.example.service.ReservationException;
import com.example.service.ReservationService;
import com.example.util.DataInitializer;
import com.example.util.MigrationExecutor;
import com.example.util.PerformanceReport;

import org.hibernate.Session;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.OptimisticLockException;
import javax.persistence.Persistence;
import javax.persistence.RollbackException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
public class MainApp {

    private static final Logger LOGGER = Logger.getLogger(MainApp.class.getName());

    public static void main(String[] args) {
        Map<String, Object> surcharges = new HashMap<>();
        copier("db.url", "javax.persistence.jdbc.url", surcharges);
        copier("db.user", "javax.persistence.jdbc.user", surcharges);
        copier("db.password", "javax.persistence.jdbc.password", surcharges);
        copier("db.driver", "javax.persistence.jdbc.driver", surcharges);
        copier("db.dialect", "hibernate.dialect", surcharges);
        surcharges.put("hibernate.show_sql", System.getProperty("showSql", "false"));
        String scriptMigration = System.getProperty("db.migration", "db/migration/v2_0_add_description.sql");

        EntityManagerFactory emf = null;
        EntityManager em = null;
        try {
            emf = Persistence.createEntityManagerFactory("ReservationPU", surcharges);

            executerMigration(emf, scriptMigration);

            em = emf.createEntityManager();
            new DataInitializer(em).initData();

            ReservationService service = new ReservationService(emf);
            demonstration(emf, service);

            // 4. Rapport de performance
            System.out.println();
            new PerformanceReport(em).printReport();
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Erreur fatale : " + e.getMessage(), e);
            System.exit(1);
        } finally {
            if (em != null && em.isOpen()) {
                em.close();
            }
            if (emf != null && emf.isOpen()) {
                emf.close();
            }
        }
    }

    /**
     * La migration utilise la connexion d'Hibernate (session.doWork) : mêmes identifiants et même
     * base que le reste de l'application, sans avoir à les relire dans la configuration.
     */
    private static void executerMigration(EntityManagerFactory emf, String script) throws Exception {
        MigrationExecutor executor = new MigrationExecutor(null, null, null);
        boolean[] appliquee = new boolean[1];
        Exception[] erreur = new Exception[1];

        EntityManager emMigration = emf.createEntityManager();
        try {
            emMigration.unwrap(Session.class).doWork(connection -> {
                try {
                    appliquee[0] = executor.executeMigration(connection, script);
                } catch (Exception e) {
                    erreur[0] = e;
                }
            });
        } finally {
            emMigration.close();
        }
        if (erreur[0] != null) {
            throw erreur[0];
        }
        System.out.println("Migration " + script + " : " + (appliquee[0] ? "appliquée" : "déjà appliquée"));
    }

    private static Utilisateur exiger(Utilisateur utilisateur, String email) {
        if (utilisateur == null) {
            throw new IllegalStateException("Utilisateur introuvable : " + email
                    + " (vérifiez qu'il est créé dans DataInitializer)");
        }
        return utilisateur;
    }

    private static void demonstration(EntityManagerFactory emf, ReservationService service) {
        System.out.println("\nSalles (2 appels : le 2e est servi par le cache de requêtes)");
        service.listerSalles();
        for (Salle s : service.listerSalles()) {
            System.out.println("  " + s + " - " + s.getDescription());
        }

        LocalDateTime debut = LocalDateTime.now().plusDays(10).withHour(10).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime fin = debut.plusHours(2);

        System.out.println("\nSalles disponibles (>= 15 places) du " + debut + " au " + fin);
        for (Salle s : service.sallesDisponibles(debut, fin, 15)) {
            System.out.println("  " + s);
        }

        Salle salleA = service.trouverSalleParNom("Salle A");
        Utilisateur alice = exiger(service.trouverUtilisateurParEmail("ouaday.sara@gmail.com"), "ouaday.sara@gmail.com");
        Utilisateur bruno = exiger(service.trouverUtilisateurParEmail("ouamnsour.rayhana@gmail.com"), "ouamnsour.rayhana@gmail.com");

        System.out.println("\nCréation d'une réservation puis tentative en conflit");
        Reservation r = service.creerReservation(salleA.getId(), alice.getId(), debut, fin);
        System.out.println("  Créée : " + r);
        try {
            service.creerReservation(salleA.getId(), bruno.getId(), debut.plusHours(1), fin.plusHours(1));
        } catch (ReservationConflictException e) {
            System.out.println("Conflit détecté comme prévu : " + e.getMessage());
        }

        System.out.println("\nJPQL : réservations à venir de la Salle A");
        List<Reservation> parSalle = service.reservationsAVenirParSalle("Salle A", LocalDateTime.now());
        parSalle.forEach(x -> System.out.println("  " + x + " par " + x.getUtilisateur().getNom()));

        System.out.println("\nCriteria API : réservations d'Alice");
        service.rechercher(null, alice.getId(), null, null)
                .forEach(x -> System.out.println("  " + x + " en " + x.getSalle().getNom()));

        System.out.println("\nModification puis annulation");
        Reservation modifiee = service.modifierReservation(r.getId(), debut.plusHours(4), fin.plusHours(4));
        System.out.println("  Modifiée : " + modifiee + " (version " + modifiee.getVersion() + ")");
        service.annulerReservation(r.getId());
        System.out.println("  Annulée : réservation " + r.getId());

        System.out.println("\nVerrouillage optimiste");
        demoVerrouillageOptimiste(emf, salleA.getId());

        System.out.println("\nErreur métier : dates invalides");
        try {
            service.creerReservation(salleA.getId(), alice.getId(), fin, debut);
        } catch (ReservationException e) {
            System.out.println("  Refusé : " + e.getMessage());
        }
    }
    private static void demoVerrouillageOptimiste(EntityManagerFactory emf, Long salleId) {
        EntityManager em1 = emf.createEntityManager();
        EntityManager em2 = emf.createEntityManager();
        try {
            Salle s1 = em1.find(Salle.class, salleId);
            Salle s2 = em2.find(Salle.class, salleId);

            em1.getTransaction().begin();
            s1.setDescription("Modifiée par la session 1");
            em1.getTransaction().commit();
            System.out.println("  Session 1 : commit OK (version " + s1.getVersion() + ")");

            try {
                em2.getTransaction().begin();
                s2.setDescription("Modifiée par la session 2");
                em2.getTransaction().commit();
            } catch (RollbackException | OptimisticLockException e) {
                if (em2.getTransaction().isActive()) {
                    em2.getTransaction().rollback();
                }
                System.out.println("Session 2 : conflit détecté, veuillez réessayer.");
            }
        } finally {
            em1.close();
            em2.close();
        }
    }

    private static void copier(String propSysteme, String propJpa, Map<String, Object> cible) {
        String valeur = System.getProperty(propSysteme);
        if (valeur != null) {
            cible.put(propJpa, valeur);
        }
    }
}