package com.example.util;

import org.hibernate.SessionFactory;
import org.hibernate.stat.QueryStatistics;
import org.hibernate.stat.Statistics;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;

/**
 * Rapport de performance basé sur les statistiques Hibernate
 * (nécessite hibernate.generate_statistics=true).
 */
public class PerformanceReport {

    private final Statistics stats;

    public PerformanceReport(EntityManager em) {
        this(em.getEntityManagerFactory());
    }

    public PerformanceReport(EntityManagerFactory emf) {
        this.stats = emf.unwrap(SessionFactory.class).getStatistics();
    }

    public String buildReport() {
        long hits = stats.getSecondLevelCacheHitCount();
        long misses = stats.getSecondLevelCacheMissCount();
        long queryCacheHits = stats.getQueryCacheHitCount();
        long queryCacheMisses = stats.getQueryCacheMissCount();

        long dureeTotaleRequetes = 0;
        for (String requete : stats.getQueries()) {
            QueryStatistics qs = stats.getQueryStatistics(requete);
            dureeTotaleRequetes += qs.getExecutionAvgTime() * qs.getExecutionCount();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== RAPPORT DE PERFORMANCE ===\n");
        if (!stats.isStatisticsEnabled()) {
            sb.append("(statistiques désactivées : activez hibernate.generate_statistics=true)\n");
        }
        sb.append("Requêtes exécutées               : ").append(stats.getQueryExecutionCount()).append('\n');
        sb.append("Durée totale des requêtes (ms)   : ").append(dureeTotaleRequetes).append('\n');
        sb.append("Requête la plus lente (ms)       : ").append(stats.getQueryExecutionMaxTime()).append('\n');
        sb.append("Requêtes SQL préparées           : ").append(stats.getPrepareStatementCount()).append('\n');
        sb.append("Entités chargées / insérées      : ").append(stats.getEntityLoadCount())
                .append(" / ").append(stats.getEntityInsertCount()).append('\n');
        sb.append("Transactions (réussies)          : ").append(stats.getTransactionCount())
                .append(" (").append(stats.getSuccessfulTransactionCount()).append(")\n");
        sb.append("Cache 2e niveau - hits           : ").append(hits).append('\n');
        sb.append("Cache 2e niveau - misses         : ").append(misses).append('\n');
        sb.append("Cache 2e niveau - mises à jour   : ").append(stats.getSecondLevelCachePutCount()).append('\n');
        sb.append("Cache 2e niveau - taux de succès : ").append(ratio(hits, hits + misses)).append('\n');
        sb.append("Cache de requêtes - hits/misses  : ").append(queryCacheHits)
                .append(" / ").append(queryCacheMisses).append('\n');
        return sb.toString();
    }

    public void printReport() {
        System.out.print(buildReport());
    }

    private static String ratio(long partie, long total) {
        return total == 0 ? "n/a" : String.format("%.1f %%", 100.0 * partie / total);
    }
}
