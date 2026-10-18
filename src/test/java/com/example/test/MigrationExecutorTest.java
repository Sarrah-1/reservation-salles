package com.example.test;

import com.example.util.MigrationExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationExecutorTest {

    private static final String URL = "jdbc:h2:mem:migration_test;DB_CLOSE_DELAY=-1";
    private static final String SCRIPT = "db/migration/v2_0_add_description.sql";

    /** Schéma « version 1 » : tables sans la colonne description et sans index unique. */
    @BeforeEach
    void schemaVersion1() throws SQLException {
        try (Connection c = DriverManager.getConnection(URL, "sa", "");
             Statement s = c.createStatement()) {
            s.execute("DROP ALL OBJECTS");
            s.execute("CREATE TABLE salles (id BIGINT PRIMARY KEY, nom VARCHAR(100) NOT NULL)");
            s.execute("CREATE TABLE utilisateurs (id BIGINT PRIMARY KEY, email VARCHAR(150) NOT NULL, nom VARCHAR(100))");
        }
    }

    private MigrationExecutor executor() {
        return new MigrationExecutor(URL, "sa", "");
    }

    private boolean colonneExiste(String table, String colonne) throws SQLException {
        try (Connection c = DriverManager.getConnection(URL, "sa", "");
             ResultSet rs = c.getMetaData().getColumns(null, null, table, colonne)) {
            return rs.next();
        }
    }

    @Test
    void ajouteLaColonneDescription() throws Exception {
        assertFalse(colonneExiste("SALLES", "DESCRIPTION"));

        assertTrue(executor().executeMigration(SCRIPT));

        assertTrue(colonneExiste("SALLES", "DESCRIPTION"));
    }

    @Test
    void migrationAppliqueeUneSeuleFois() throws Exception {
        assertTrue(executor().executeMigration(SCRIPT));
        assertFalse(executor().executeMigration(SCRIPT), "la seconde exécution doit être ignorée");

        try (Connection c = DriverManager.getConnection(URL, "sa", "");
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM schema_migrations")) {
            rs.next();
            assertEquals(1, rs.getInt(1));
        }
    }

    @Test
    void indexUniqueSurEmailApresMigration() throws Exception {
        executor().executeMigration(SCRIPT);

        try (Connection c = DriverManager.getConnection(URL, "sa", "");
             Statement s = c.createStatement()) {
            s.execute("INSERT INTO utilisateurs (id, email, nom) VALUES (1, 'a@example.com', 'A')");
            assertThrows(SQLException.class, () ->
                    s.execute("INSERT INTO utilisateurs (id, email, nom) VALUES (2, 'a@example.com', 'B')"));
        }
    }

    @Test
    void scriptIntrouvable() {
        assertThrows(IllegalArgumentException.class,
                () -> executor().executeMigration("db/migration/inexistant.sql"));
    }

    @Test
    void migrationEnEchecEstAnnuleEtNonEnregistree() throws Exception {
        // Table "salles" absente : le script échoue et ne doit pas être marqué comme appliqué
        try (Connection c = DriverManager.getConnection(URL, "sa", "");
             Statement s = c.createStatement()) {
            s.execute("DROP TABLE salles");
        }
        assertThrows(SQLException.class, () -> executor().executeMigration(SCRIPT));

        try (Connection c = DriverManager.getConnection(URL, "sa", "");
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM schema_migrations")) {
            rs.next();
            assertEquals(0, rs.getInt(1));
        }
    }
}
