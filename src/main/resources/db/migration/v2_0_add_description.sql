-- Migration version 2.0 : ajout de la colonne "description" à la table "salles"
-- (syntaxe H2 / PostgreSQL / MariaDB ; voir README pour MySQL 8)
ALTER TABLE salles ADD COLUMN IF NOT EXISTS description VARCHAR(255);

-- Ajout d'un index unique sur la colonne "email" de la table "utilisateurs"
CREATE UNIQUE INDEX IF NOT EXISTS idx_utilisateur_email ON utilisateurs(email);
