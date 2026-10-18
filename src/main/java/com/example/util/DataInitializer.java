package com.example.util;

import com.example.model.Reservation;
import com.example.model.Salle;
import com.example.model.Utilisateur;

import javax.persistence.EntityManager;
import javax.persistence.EntityTransaction;
import java.time.LocalDateTime;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Insère un jeu de données : 3 utilisateurs, 3 salles et 15 réservations réparties sur
 * les prochains jours (dates dynamiques, donc toujours dans le futur, et sans chevauchement).
 */
public class DataInitializer {

    private static final Logger LOGGER = Logger.getLogger(DataInitializer.class.getName());

    public static final int NB_UTILISATEURS = 3;
    public static final int NB_SALLES = 3;
    public static final int NB_RESERVATIONS = 15;

    private final EntityManager em;

    public DataInitializer(EntityManager em) {
        this.em = em;
    }

    public void initData() {
        EntityTransaction tx = em.getTransaction();
        try {
            tx.begin();

            Long existants = em.createQuery("SELECT COUNT(u) FROM Utilisateur u", Long.class).getSingleResult();
            if (existants > 0) {
                LOGGER.info("Jeu de données déjà présent : initialisation ignorée.");
                tx.rollback();
                return;
            }

            String[][] donneesUtilisateurs = {
                    {"OUADAY Sara", "ouaday.sara@gmail.com"},
                    {"OUMANSOUR Rayhana", "ouamnsour.rayhana@gmail.com"},
                    {"KOUBRI Hatim", "koubri.hatim@gmail.com"},
            };
            Utilisateur[] utilisateurs = new Utilisateur[donneesUtilisateurs.length];
            for (int i = 0; i < utilisateurs.length; i++) {
                utilisateurs[i] = new Utilisateur(donneesUtilisateurs[i][0], donneesUtilisateurs[i][1]);
                em.persist(utilisateurs[i]);
            }

            Salle[] salles = {
                    new Salle("Salle A", 20, "Salle de réunion avec écran et visioconférence"),
                    new Salle("Salle B", 10, "Petite salle de réunion"),
                    new Salle("Salle C", 50, "Amphithéâtre pour conférences")
            };
            for (Salle salle : salles) {
                em.persist(salle);
            }

            // Chaque salle est utilisée un jour différent à chaque tour => aucun chevauchement.
            LocalDateTime base = LocalDateTime.now().plusDays(1)
                    .withHour(9).withMinute(0).withSecond(0).withNano(0);
            for (int i = 0; i < NB_RESERVATIONS; i++) {
                LocalDateTime debut = base.plusDays(i / 3).plusHours((i % 3) * 3L);
                Reservation r = new Reservation(
                        salles[i % salles.length],
                        utilisateurs[i % utilisateurs.length],
                        debut,
                        debut.plusHours(2));
                em.persist(r);
            }

            tx.commit();
            LOGGER.info("Jeu de données créé : " + NB_UTILISATEURS + " utilisateurs, " + NB_SALLES
                    + " salles, " + NB_RESERVATIONS + " réservations.");
        } catch (RuntimeException e) {
            if (tx.isActive()) {
                tx.rollback();
            }
            LOGGER.log(Level.SEVERE, "Échec de l'initialisation des données : " + e.getMessage(), e);
            throw e;
        }
    }
}
