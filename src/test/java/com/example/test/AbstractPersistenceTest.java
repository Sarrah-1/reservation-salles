package com.example.test;

import com.example.service.ReservationService;
import com.example.util.DataInitializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.Persistence;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Base des tests : chaque classe de test dispose de sa propre base H2 en mémoire,
 * créée puis détruite (create-drop) et alimentée par le DataInitializer.
 */
abstract class AbstractPersistenceTest {

    protected static EntityManagerFactory emf;
    protected static ReservationService service;
    protected EntityManager em;

    @BeforeAll
    static void initEmf() {
        Map<String, Object> props = new HashMap<>();
        props.put("javax.persistence.jdbc.url",
                "jdbc:h2:mem:test_" + UUID.randomUUID().toString().replace("-", "")
                        + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        props.put("hibernate.hbm2ddl.auto", "create-drop");
        props.put("hibernate.show_sql", "false");
        props.put("hibernate.format_sql", "false");
        emf = Persistence.createEntityManagerFactory("ReservationPU", props);
        // Le cache JCache est partagé dans la JVM : on repart d'un cache vide pour chaque classe.
        emf.getCache().evictAll();

        EntityManager initEm = emf.createEntityManager();
        try {
            new DataInitializer(initEm).initData();
        } finally {
            initEm.close();
        }
        service = new ReservationService(emf);
    }

    @AfterAll
    static void closeEmf() {
        if (emf != null && emf.isOpen()) {
            emf.close();
        }
    }

    @BeforeEach
    void setUp() {
        em = emf.createEntityManager();
    }

    @AfterEach
    void tearDown() {
        if (em != null && em.isOpen()) {
            em.close();
        }
    }

    /** Vrai si l'exception, ou l'une de ses causes, est du type demandé. */
    protected static boolean aPourCause(Throwable t, Class<? extends Throwable> type) {
        for (Throwable c = t; c != null; c = (c.getCause() == c ? null : c.getCause())) {
            if (type.isInstance(c)) {
                return true;
            }
        }
        return false;
    }
}
