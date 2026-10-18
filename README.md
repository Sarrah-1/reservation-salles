# TP Capstone — Réservation de salles (JPA & Hibernate, JEE pur)

Projet Maven sans Spring : JPA 2.2 + Hibernate 5.6, cache de second niveau (JCache/Ehcache),
migration SQL via JDBC, rapport de performance, tests JUnit 5.

## Lancer le projet

Prérequis : JDK 11+ et Maven.

```bash
mvn test                      # lance les tests (base H2 en mémoire, aucune installation requise)
mvn compile exec:java         # lance MainApp : migration, données, démo, rapport de performance
mvn compile exec:java -DshowSql=true     # idem avec les requêtes SQL affichées
```

Par défaut la base est **H2 en mémoire** : rien à installer. Pour utiliser **MySQL** comme dans le TP :

```bash
mvn compile exec:java \
  -Ddb.url="jdbc:mysql://localhost:3306/reservation?createDatabaseIfNotExist=true" \
  -Ddb.user=root -Ddb.password=password \
  -Ddb.driver=com.mysql.cj.jdbc.Driver \
  -Ddb.dialect=org.hibernate.dialect.MySQL8Dialect
```

Voir « Remarques MySQL » plus bas pour le script de migration.

## Correspondance avec les étapes du TP

| Étape | Contenu | Fichiers |
|-------|---------|----------|
| 1. Modèle de données | Entités, `@Version`, `LAZY`, contraintes, index | `model/Utilisateur`, `Salle`, `Reservation` |
| 2. Cache de second niveau | JCache + Ehcache, `@Cacheable`, `READ_WRITE`, cache de requêtes, logs SQL | `META-INF/persistence.xml` |
| 3. Jeu de données | 3 utilisateurs, 3 salles, 15 réservations, dates dynamiques | `util/DataInitializer` |
| 4. Scénarios de test | JUnit 5 : création, conflit, modification, annulation, cache, verrouillage, concurrence | `src/test/java/com/example/test/` |
| 5. Script de migration | Colonne `description`, index unique sur l'email | `db/migration/v2_0_add_description.sql` |
| 6. Exécution de la migration | Lecture du script, exécution JDBC, suivi dans `schema_migrations` | `util/MigrationExecutor` |
| 7. Rapport de performance | Statistiques Hibernate (requêtes, cache, transactions) | `util/PerformanceReport` |
| 8. Application complète | Orchestration | `MainApp` |
| 9. JPQL & Criteria API | Requêtes paramétrées, `JOIN FETCH`, `NOT EXISTS`, recherche dynamique | `service/ReservationService` |
| 10. Transactions | `RESOURCE_LOCAL`, rollback, `OptimisticLockException` | `service/ReservationService`, `OptimisticLockingTest` |
| 11. Logs et erreurs | `java.util.logging`, exceptions métier | `service/*Exception`, `ReservationService` |

## Points d'attention (écarts volontaires avec le code du TP)

- **`RollbackException` au commit** : quand un conflit de version est détecté pendant `commit()`,
  Hibernate lève une `RollbackException` dont la *cause* est l'`OptimisticLockException`. Un simple
  `catch (OptimisticLockException)` autour du `commit()` ne l'attrape donc pas toujours ;
  le code parcourt la chaîne des causes (`ReservationService.aPourCause`).
- **`PerformanceReport`** : l'API `Statistics` d'Hibernate 5 n'a pas de méthode
  `getQueryExecutionTotalTime()` ; la durée totale est calculée à partir des statistiques de
  chaque requête (moyenne × nombre d'exécutions).
- **`MigrationExecutor`** : en plus de l'exemple du TP, il découpe le script instruction par
  instruction, enregistre chaque script appliqué dans `schema_migrations` (relancer
  l'application n'applique donc pas deux fois la migration), annule en cas d'erreur et lève une
  erreur claire si le script est introuvable (au lieu d'une `NullPointerException`).
- **Double réservation** : `@Version` ne protège pas contre deux *insertions* simultanées du même
  créneau. `creerReservation` verrouille donc la ligne de la salle (`PESSIMISTIC_WRITE`) avant de
  vérifier les conflits ; `ConcurrenceTest` vérifie que, sur 6 demandes simultanées, une seule réussit.
- **Entités mises en cache** : `Utilisateur` et `Salle` (données qui changent peu).
  `Reservation` ne l'est pas (modifiée souvent).
- **Migration idempotente** : le script de migration est idempotent (`IF NOT EXISTS`) car l'entité `Salle`
  contient déjà `description` et Hibernate (`hbm2ddl.auto=update`) crée le schéma au démarrage.

## Remarques MySQL

Le script `v2_0_add_description.sql` utilise `ADD COLUMN IF NOT EXISTS` / `CREATE INDEX IF NOT EXISTS`
(H2, PostgreSQL, MariaDB). MySQL 8 ne les supporte pas : retirez `IF NOT EXISTS`, et comme Hibernate
(`hbm2ddl.auto=update`) crée déjà la colonne `description`, mettez `-Dhibernate.hbm2ddl.auto=validate`
côté production, avec un schéma v1 créé à part, pour que la migration soit réellement la seule à modifier la structure.

## Structure

```
src/main/java/com/example/
  MainApp.java
  model/      Utilisateur, Salle, Reservation
  service/    ReservationService, ReservationException, ReservationConflictException
  util/       DataInitializer, MigrationExecutor, PerformanceReport
src/main/resources/
  META-INF/persistence.xml
  db/migration/v2_0_add_description.sql
src/test/java/com/example/test/
  ReservationTest, QueryTest, OptimisticLockingTest, SecondLevelCacheTest,
  ConcurrenceTest, MigrationExecutorTest, PerformanceReportTest
```
