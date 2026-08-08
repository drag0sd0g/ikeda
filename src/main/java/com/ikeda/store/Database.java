package com.ikeda.store;

import com.ikeda.support.Scripts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Owns the SQLite connection and the schema.
 *
 * <p>Separated from the stores because there is now more than one: {@link CorpusStore}
 * writes the corpus, {@link ReviewStore} manages candidates and verdicts, and both
 * need the same connection and the same transaction boundary.
 *
 * <p>Not thread safe: one connection, one writer.
 */
public final class Database implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Database.class);

    private static final String SCHEMA_RESOURCE = "/schema.sql";

    private final Connection connection;

    private Database(String jdbcUrl) {
        try {
            this.connection = DriverManager.getConnection(jdbcUrl);
            configure();
            applySchema();
        } catch (SQLException e) {
            throw new StoreException("cannot open database: " + jdbcUrl, e);
        }
    }

    public static Database open(Path path) {
        return new Database("jdbc:sqlite:" + path);
    }

    /** For tests: a private database that never touches disk. */
    public static Database inMemory() {
        return new Database("jdbc:sqlite::memory:");
    }

    Connection connection() {
        return connection;
    }

    private void configure() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            // SQLite leaves foreign keys unenforced unless asked, per connection.
            statement.execute("PRAGMA foreign_keys = ON");
            // WAL plus NORMAL avoids a disk sync per transaction. Silently ignored
            // for in-memory databases, which have no journal to speak of.
            statement.execute("PRAGMA journal_mode = WAL");
            statement.execute("PRAGMA synchronous = NORMAL");
        }
        connection.setAutoCommit(false);
    }

    private void applySchema() throws SQLException {
        migrate();
        try (InputStream in = Database.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + SCHEMA_RESOURCE);
            }
            String schema = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            try (Statement statement = connection.createStatement()) {
                for (String ddl : splitStatements(schema)) {
                    statement.executeUpdate(ddl);
                }
            }
            connection.commit();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + SCHEMA_RESOURCE, e);
        }
    }

    /**
     * Brings an older database up to the current schema.
     *
     * <p>{@code CREATE TABLE IF NOT EXISTS} is a no-op on a table that already
     * exists, so a column added to the schema never reaches a database created
     * before it. Without this, opening an older file fails on the first statement
     * that references the new column, with a message that gives no hint the file
     * is simply out of date.
     *
     * <p>Deliberately minimal: columns only, added in place, no version table.
     * The corpus can always be rebuilt from EDINET, so anything more involved
     * than an {@code ADD COLUMN} should be handled by deleting the file and
     * re-ingesting rather than by growing a migration framework here.
     */
    private void migrate() throws SQLException {
        addColumnIfMissing("candidate", "bccwj_rank", "INTEGER");
        if (addColumnIfMissing("term", "has_kanji", "INTEGER NOT NULL DEFAULT 1")) {
            backfillHasKanji();
        }
    }

    /**
     * Fills in {@code term.has_kanji} for rows that predate the column.
     *
     * <p>Runs once, immediately after the column is added. The default of 1 would
     * otherwise leave every existing kana-only term eligible as a candidate.
     */
    private void backfillHasKanji() throws SQLException {
        record Term(long id, boolean hasKanji) { }
        var terms = new java.util.ArrayList<Term>();

        try (Statement statement = connection.createStatement();
             var rs = statement.executeQuery("SELECT id, key FROM term")) {
            while (rs.next()) {
                terms.add(new Term(rs.getLong("id"), Scripts.containsKanji(rs.getString("key"))));
            }
        }
        try (var update = connection.prepareStatement(
                "UPDATE term SET has_kanji = ? WHERE id = ?")) {
            for (Term term : terms) {
                update.setInt(1, term.hasKanji() ? 1 : 0);
                update.setLong(2, term.id());
                update.addBatch();
            }
            update.executeBatch();
        }
        connection.commit();
        log.info("migrated: classified {} terms by script", terms.size());
    }

    /** @return true when the column was actually added */
    private boolean addColumnIfMissing(String table, String column, String type)
            throws SQLException {
        if (!tableExists(table) || columnExists(table, column)) {
            return false;
        }
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "ALTER TABLE %s ADD COLUMN %s %s".formatted(table, column, type));
        }
        connection.commit();
        log.info("migrated: added {}.{}", table, column);
        return true;
    }

    private boolean tableExists(String table) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            statement.setString(1, table);
            try (var rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }

    private boolean columnExists(String table, String column) throws SQLException {
        try (Statement statement = connection.createStatement();
             var rs = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equals(rs.getString("name"))) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Splits a DDL script into statements.
     *
     * <p>Line comments are stripped first: the driver executes one statement per
     * call, so a semicolon inside a comment would otherwise cut the comment in
     * half and leave its remainder to be parsed as SQL. Adequate for DDL, which
     * has no string literals — it is not a general SQL parser.
     */
    static List<String> splitStatements(String script) {
        String withoutComments = script.lines()
                .map(line -> {
                    int comment = line.indexOf("--");
                    return comment < 0 ? line : line.substring(0, comment);
                })
                .collect(Collectors.joining("\n"));

        return Arrays.stream(withoutComments.split(";"))
                .map(String::strip)
                .filter(statement -> !statement.isEmpty())
                .toList();
    }

    void commit() {
        try {
            connection.commit();
        } catch (SQLException e) {
            throw new StoreException("commit failed", e);
        }
    }

    void rollbackQuietly() {
        try {
            connection.rollback();
        } catch (SQLException e) {
            // The caller is already unwinding a failure; nothing useful to do here.
        }
    }

    /**
     * The tables {@link #count(Table)} may read.
     *
     * <p>An enum rather than a string because a table name cannot be bound as a
     * parameter and so has to be interpolated. Restricting the input to a closed
     * set keeps that safe by construction rather than by convention.
     */
    public enum Table {
        FILING("filing"), BLOCK("block"), SENTENCE("sentence"), TERM("term"),
        OCCURRENCE("occurrence"), CANDIDATE("candidate"), KNOWN_LEMMA("known_lemma");

        private final String name;

        Table(String name) {
            this.name = name;
        }
    }

    long count(Table table) {
        try (Statement statement = connection.createStatement();
             var rs = statement.executeQuery("SELECT COUNT(*) FROM " + table.name)) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new StoreException("cannot count " + table.name, e);
        }
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            throw new StoreException("cannot close database", e);
        }
    }
}
