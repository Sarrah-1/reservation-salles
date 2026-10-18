package com.example.service;

import com.example.model.Reservation;
import com.example.model.Salle;
import com.example.model.Utilisateur;
import org.hibernate.Hibernate;
import org.hibernate.exception.ConstraintViolationException;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.EntityTransaction;
import javax.persistence.LockModeType;
import javax.persistence.OptimisticLockException;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Root;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Couche métier : toutes les opérations ouvrent leur propre EntityManager et leur propre
 * transaction RESOURCE_LOCAL (pas de framework, JEE/JPA pur).
 */
public class ReservationService {

    private static final Logger LOGGER = Logger.getLogger(ReservationService.class.getName());

    private final EntityManagerFactory emf;

    public ReservationService(EntityManagerFactory emf) {
        this.emf = emf;
    }

    // ------------------------------------------------------------------
    // Utilisateurs et salles
    // ------------------------------------------------------------------

    public Utilisateur creerUtilisateur(String nom, String email) {
        return inTransaction(em -> {
            Utilisateur u = new Utilisateur(nom, email);
            em.persist(u);
            LOGGER.info("Utilisateur créé : " + email);
            return u;
        });
    }

    public Salle creerSalle(String nom, int capacite, String description) {
        return inTransaction(em -> {
            Salle s = new Salle(nom, capacite, description);
            em.persist(s);
            LOGGER.info("Salle créée : " + nom);
            return s;
        });
    }

    public Salle trouverSalleParNom(String nom) {
        EntityManager em = emf.createEntityManager();
        try {
            List<Salle> res = em.createQuery("SELECT s FROM Salle s WHERE s.nom = :nom", Salle.class)
                    .setParameter("nom", nom)
                    .getResultList();
            return res.isEmpty() ? null : res.get(0);
        } finally {
            em.close();
        }
    }

    public Utilisateur trouverUtilisateurParEmail(String email) {
        EntityManager em = emf.createEntityManager();
        try {
            List<Utilisateur> res = em.createQuery(
                            "SELECT u FROM Utilisateur u WHERE u.email = :email", Utilisateur.class)
                    .setParameter("email", email)
                    .getResultList();
            return res.isEmpty() ? null : res.get(0);
        } finally {
            em.close();
        }
    }

    /** Liste des salles ; le résultat est stocké dans le cache de requêtes. */
    public List<Salle> listerSalles() {
        EntityManager em = emf.createEntityManager();
        try {
            return em.createQuery("SELECT s FROM Salle s ORDER BY s.nom", Salle.class)
                    .setHint("org.hibernate.cacheable", true)
                    .getResultList();
        } finally {
            em.close();
        }
    }

    /** Salles de capacité suffisante et libres sur tout le créneau demandé. */
    public List<Salle> sallesDisponibles(LocalDateTime debut, LocalDateTime fin, int capaciteMin) {
        validerPeriode(debut, fin, false);
        EntityManager em = emf.createEntityManager();
        try {
            return em.createQuery(
                            "SELECT s FROM Salle s WHERE s.capacite >= :cap AND NOT EXISTS ("
                                    + " SELECT r.id FROM Reservation r WHERE r.salle = s"
                                    + " AND r.dateDebut < :fin AND r.dateFin > :debut) ORDER BY s.nom",
                            Salle.class)
                    .setParameter("cap", capaciteMin)
                    .setParameter("debut", debut)
                    .setParameter("fin", fin)
                    .getResultList();
        } finally {
            em.close();
        }
    }

    // ------------------------------------------------------------------
    // Réservations : création, modification, annulation
    // ------------------------------------------------------------------

    public Reservation creerReservation(Long salleId, Long utilisateurId,
                                        LocalDateTime debut, LocalDateTime fin) {
        validerPeriode(debut, fin, true);
        return inTransaction(em -> {
            // Verrou sur la ligne de la salle : deux réservations simultanées de la même salle
            // sont sérialisées, ce qui évite la double réservation d'un même créneau.
            Salle salle = em.find(Salle.class, salleId, LockModeType.PESSIMISTIC_WRITE);
            if (salle == null) {
                throw new ReservationException("Salle introuvable : " + salleId);
            }
            Utilisateur utilisateur = em.find(Utilisateur.class, utilisateurId);
            if (utilisateur == null) {
                throw new ReservationException("Utilisateur introuvable : " + utilisateurId);
            }
            if (existeConflit(em, salleId, debut, fin, null)) {
                throw new ReservationConflictException("La salle " + salle.getNom()
                        + " est déjà réservée sur le créneau " + debut + " -> " + fin);
            }
            Reservation r = new Reservation(salle, utilisateur, debut, fin);
            em.persist(r);
            LOGGER.info("Création de la réservation réussie pour utilisateur : " + utilisateur.getEmail());
            return r;
        });
    }

    public Reservation modifierReservation(Long reservationId, LocalDateTime debut, LocalDateTime fin) {
        validerPeriode(debut, fin, true);
        return inTransaction(em -> {
            Reservation r = chargerReservation(em, reservationId);
            em.lock(r.getSalle(), LockModeType.PESSIMISTIC_WRITE);
            if (existeConflit(em, r.getSalle().getId(), debut, fin, r.getId())) {
                throw new ReservationConflictException("Le nouveau créneau " + debut + " -> " + fin
                        + " chevauche une autre réservation de la salle " + r.getSalle().getNom());
            }
            r.setDateDebut(debut);
            r.setDateFin(fin);
            LOGGER.info("Réservation " + reservationId + " modifiée");
            return r;
        });
    }

    public void annulerReservation(Long reservationId) {
        inTransaction(em -> {
            Reservation r = em.find(Reservation.class, reservationId);
            if (r == null) {
                throw new ReservationException("Réservation introuvable : " + reservationId);
            }
            em.remove(r);
            LOGGER.info("Réservation " + reservationId + " annulée");
            return null;
        });
    }

    // ------------------------------------------------------------------
    // Requêtes (JPQL et Criteria API)
    // ------------------------------------------------------------------

    /** JPQL : réservations futures d'une salle, avec JOIN FETCH pour éviter le problème N+1. */
    public List<Reservation> reservationsAVenirParSalle(String nomSalle, LocalDateTime date) {
        EntityManager em = emf.createEntityManager();
        try {
            return em.createQuery(
                            "SELECT r FROM Reservation r JOIN FETCH r.salle JOIN FETCH r.utilisateur"
                                    + " WHERE r.salle.nom = :nomSalle AND r.dateDebut > :date"
                                    + " ORDER BY r.dateDebut", Reservation.class)
                    .setParameter("nomSalle", nomSalle)
                    .setParameter("date", date)
                    .getResultList();
        } finally {
            em.close();
        }
    }

    /** Criteria API : recherche dynamique, chaque critère à null est ignoré. */
    public List<Reservation> rechercher(Long salleId, Long utilisateurId,
                                        LocalDateTime debutApres, LocalDateTime debutAvant) {
        EntityManager em = emf.createEntityManager();
        try {
            CriteriaBuilder cb = em.getCriteriaBuilder();
            CriteriaQuery<Reservation> cq = cb.createQuery(Reservation.class);
            Root<Reservation> root = cq.from(Reservation.class);
            root.fetch("salle");
            root.fetch("utilisateur");

            List<Predicate> predicats = new ArrayList<>();
            if (salleId != null) {
                predicats.add(cb.equal(root.get("salle").get("id"), salleId));
            }
            if (utilisateurId != null) {
                predicats.add(cb.equal(root.get("utilisateur").get("id"), utilisateurId));
            }
            if (debutApres != null) {
                predicats.add(cb.greaterThanOrEqualTo(root.<LocalDateTime>get("dateDebut"), debutApres));
            }
            if (debutAvant != null) {
                predicats.add(cb.lessThan(root.<LocalDateTime>get("dateDebut"), debutAvant));
            }
            cq.select(root)
                    .where(predicats.toArray(new Predicate[0]))
                    .orderBy(cb.asc(root.get("dateDebut")));
            return em.createQuery(cq).getResultList();
        } finally {
            em.close();
        }
    }

    // ------------------------------------------------------------------
    // Utilitaires internes
    // ------------------------------------------------------------------

    private Reservation chargerReservation(EntityManager em, Long id) {
        List<Reservation> res = em.createQuery(
                        "SELECT r FROM Reservation r JOIN FETCH r.salle JOIN FETCH r.utilisateur"
                                + " WHERE r.id = :id", Reservation.class)
                .setParameter("id", id)
                .getResultList();
        if (res.isEmpty()) {
            throw new ReservationException("Réservation introuvable : " + id);
        }
        Reservation r = res.get(0);
        Hibernate.initialize(r.getSalle());
        Hibernate.initialize(r.getUtilisateur());
        return r;
    }

    /** Deux créneaux se chevauchent si chacun commence avant la fin de l'autre. */
    private boolean existeConflit(EntityManager em, Long salleId, LocalDateTime debut,
                                  LocalDateTime fin, Long reservationExclue) {
        String jpql = "SELECT COUNT(r) FROM Reservation r WHERE r.salle.id = :salleId"
                + " AND r.dateDebut < :fin AND r.dateFin > :debut"
                + (reservationExclue != null ? " AND r.id <> :exclue" : "");
        javax.persistence.TypedQuery<Long> q = em.createQuery(jpql, Long.class)
                .setParameter("salleId", salleId)
                .setParameter("debut", debut)
                .setParameter("fin", fin);
        if (reservationExclue != null) {
            q.setParameter("exclue", reservationExclue);
        }
        return q.getSingleResult() > 0;
    }

    private void validerPeriode(LocalDateTime debut, LocalDateTime fin, boolean exigerFutur) {
        if (debut == null || fin == null) {
            throw new ReservationException("Les dates de début et de fin sont obligatoires.");
        }
        if (!fin.isAfter(debut)) {
            throw new ReservationException("La date de fin doit être postérieure à la date de début.");
        }
        if (exigerFutur && !debut.isAfter(LocalDateTime.now())) {
            throw new ReservationException("La date de début doit être dans le futur.");
        }
    }

    /** Exécute le travail dans une transaction RESOURCE_LOCAL avec rollback et logs en cas d'erreur. */
    private <T> T inTransaction(Function<EntityManager, T> travail) {
        EntityManager em = emf.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        try {
            tx.begin();
            T resultat = travail.apply(em);
            tx.commit();
            return resultat;
        } catch (RuntimeException e) {
            if (tx.isActive()) {
                tx.rollback();
            }
            throw traduire(e);
        } finally {
            em.close();
        }
    }

    /**
     * Traduit les exceptions techniques en exceptions métier lisibles.
     * Note : à la validation (commit), Hibernate enveloppe l'OptimisticLockException dans une
     * RollbackException, d'où la recherche dans la chaîne des causes.
     */
    private RuntimeException traduire(RuntimeException e) {
        if (e instanceof ReservationException) {
            LOGGER.warning("Opération refusée : " + e.getMessage());
            return e;
        }
        if (aPourCause(e, OptimisticLockException.class)) {
            LOGGER.log(Level.WARNING, "Conflit de verrouillage optimiste", e);
            return new ReservationException("Conflit de modification détecté, veuillez réessayer.", e);
        }
        if (aPourCause(e, ConstraintViolationException.class)) {
            LOGGER.log(Level.WARNING, "Contrainte d'intégrité violée", e);
            return new ReservationException("Contrainte d'intégrité violée (valeur déjà utilisée ?).", e);
        }
        LOGGER.log(Level.SEVERE, "Erreur technique : " + e.getMessage(), e);
        return e;
    }

    static boolean aPourCause(Throwable t, Class<? extends Throwable> type) {
        for (Throwable c = t; c != null; c = (c.getCause() == c ? null : c.getCause())) {
            if (type.isInstance(c)) {
                return true;
            }
        }
        return false;
    }
}
