package com.example.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Exécute un script SQL de migration via JDBC.
 * Chaque script appliqué est enregistré dans la table "schema_migrations" : relancer
 * l'application n'applique donc jamais deux fois la même migration.
 */
public class MigrationExecutor {

    private static final Logger LOGGER = Logger.getLogger(MigrationExecutor.class.getName());

    private final String jdbcUrl;
    private final String username;
    private final String password;

    public MigrationExecutor(String jdbcUrl, String username, String password) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
    }

    /**
     * Ouvre sa propre connexion JDBC (url / utilisateur / mot de passe du constructeur).
     *
     * @param scriptPath chemin du script dans le classpath (ex : "db/migration/v2_0_add_description.sql")
     * @return true si le script a été appliqué, false s'il l'avait déjà été
     */
    public boolean executeMigration(String scriptPath) throws Exception {
        try (Connection conn = DriverManager.getConnection(jdbcUrl, username, password)) {
            return executeMigration(conn, scriptPath);
        }
    }

    /**
     * Applique la migration sur une connexion déjà ouverte (par exemple celle d'Hibernate).
     * La connexion n'est pas fermée ; son mode auto-commit d'origine est rétabli à la fin.
     */
    public boolean executeMigration(Connection conn, String scriptPath) throws Exception {
        List<String> instructions = decouper(lireScript(scriptPath));

        boolean autoCommitInitial = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            creerTableDeSuivi(conn);
            if (dejaAppliquee(conn, scriptPath)) {
                LOGGER.info("Migration déjà appliquée, ignorée : " + scriptPath);
                conn.commit();
                return false;
            }
            try (Statement stmt = conn.createStatement()) {
                for (String instruction : instructions) {
                    LOGGER.fine("Exécution : " + instruction);
                    stmt.execute(instruction);
                }
            }
            enregistrer(conn, scriptPath);
            conn.commit();
            LOGGER.info("Migration appliquée : " + scriptPath + " (" + instructions.size() + " instruction(s))");
            return true;
        } catch (Exception e) {
            conn.rollback();
            LOGGER.log(Level.SEVERE, "Échec de la migration " + scriptPath + " : " + e.getMessage(), e);
            throw e;
        } finally {
            conn.setAutoCommit(autoCommitInitial);
        }
    }

    private String lireScript(String scriptPath) throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(scriptPath)) {
            if (is == null) {
                throw new IllegalArgumentException("Script de migration introuvable dans le classpath : " + scriptPath);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                StringBuilder sql = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sql.append(line).append("\n");
                }
                return sql.toString();
            }
        }
    }

    /** Retire les commentaires "--" puis découpe sur ";" (les scripts ne doivent pas contenir de ";" dans des chaînes). */
    static List<String> decouper(String script) {
        StringBuilder sansCommentaires = new StringBuilder();
        for (String ligne : script.split("\n")) {
            if (!ligne.trim().startsWith("--")) {
                sansCommentaires.append(ligne).append("\n");
            }
        }
        List<String> instructions = new ArrayList<>();
        for (String morceau : sansCommentaires.toString().split(";")) {
            String instruction = morceau.trim();
            if (!instruction.isEmpty()) {
                instructions.add(instruction);
            }
        }
        return instructions;
    }

    private void creerTableDeSuivi(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS schema_migrations ("
                    + "script_name VARCHAR(255) NOT NULL PRIMARY KEY, "
                    + "applied_at TIMESTAMP NOT NULL)");
        }
    }

    private boolean dejaAppliquee(Connection conn, String scriptPath) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM schema_migrations WHERE script_name = ?")) {
            ps.setString(1, scriptPath);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    private void enregistrer(Connection conn, String scriptPath) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO schema_migrations (script_name, applied_at) VALUES (?, ?)")) {
            ps.setString(1, scriptPath);
            ps.setTimestamp(2, Timestamp.valueOf(LocalDateTime.now()));
            ps.executeUpdate();
        }
    }
}