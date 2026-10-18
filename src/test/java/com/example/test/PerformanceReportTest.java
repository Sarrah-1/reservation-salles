package com.example.test;

import com.example.util.PerformanceReport;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PerformanceReportTest extends AbstractPersistenceTest {

    @Test
    void rapportContientLesIndicateursCles() {
        service.listerSalles();
        service.reservationsAVenirParSalle("Salle A", LocalDateTime.now());

        String rapport = new PerformanceReport(em).buildReport();

        assertTrue(rapport.contains("=== RAPPORT DE PERFORMANCE ==="));
        assertTrue(rapport.contains("Requêtes exécutées"));
        assertTrue(rapport.contains("Cache 2e niveau - hits"));
        assertTrue(rapport.contains("Cache 2e niveau - misses"));
        assertTrue(rapport.contains("Cache 2e niveau - mises à jour"));
        assertTrue(rapport.contains("Transactions"));
        assertTrue(rapport.contains("Durée totale des requêtes (ms)"));
        assertTrue(!rapport.contains("statistiques désactivées"));
    }
}
