package com.ikeda.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opening a database created before a column was added must upgrade it rather
 * than failing on the first statement that mentions the column.
 */
class MigrationTest {

    @TempDir
    Path dir;

    /** The candidate table exactly as it stood before bccwj_rank was introduced. */
    private static final String OLD_SCHEMA = """
            CREATE TABLE candidate (
                term_id             INTEGER PRIMARY KEY,
                corpus_frequency    INTEGER NOT NULL,
                document_frequency  INTEGER NOT NULL,
                example_sentence_id INTEGER,
                status              TEXT NOT NULL DEFAULT 'PENDING',
                decided_at          TEXT
            )
            """;

    @Test
    @DisplayName("adds a missing column to an existing database")
    void upgradesOlderDatabase() throws Exception {
        Path file = dir.resolve("stale.db");
        try (Connection raw = DriverManager.getConnection("jdbc:sqlite:" + file);
             Statement statement = raw.createStatement()) {
            statement.executeUpdate(OLD_SCHEMA);
        }
        assertThat(columnsOf(file)).doesNotContain("bccwj_rank");

        try (Database database = Database.open(file)) {
            assertThat(database.count("candidate")).isZero();
        }

        assertThat(columnsOf(file)).contains("bccwj_rank");
    }

    @Test
    @DisplayName("preserves rows already in the older database")
    void preservesExistingRows() throws Exception {
        Path file = dir.resolve("stale.db");
        try (Connection raw = DriverManager.getConnection("jdbc:sqlite:" + file);
             Statement statement = raw.createStatement()) {
            statement.executeUpdate(OLD_SCHEMA);
            statement.executeUpdate(
                    "INSERT INTO candidate VALUES (1, 10, 5, NULL, 'WORTH_LEARNING', NULL)");
        }

        try (Database database = Database.open(file)) {
            assertThat(database.count("candidate")).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("is a no-op on a database already at the current schema")
    void isIdempotent() throws Exception {
        Path file = dir.resolve("current.db");
        try (Database database = Database.open(file)) {
            assertThat(database.count("candidate")).isZero();
        }
        try (Database database = Database.open(file)) {
            assertThat(database.count("candidate")).isZero();
        }
        assertThat(columnsOf(file)).contains("bccwj_rank");
    }

    private static List<String> columnsOf(Path file) throws Exception {
        var columns = new ArrayList<String>();
        try (Connection raw = DriverManager.getConnection("jdbc:sqlite:" + file);
             Statement statement = raw.createStatement();
             var rs = statement.executeQuery("PRAGMA table_info(candidate)")) {
            while (rs.next()) {
                columns.add(rs.getString("name"));
            }
        }
        return columns;
    }
}
