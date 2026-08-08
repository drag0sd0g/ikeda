package com.ikeda.store;

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
    }

    private void addColumnIfMissing(String table, String column, String type)
            throws SQLException {
        if (!tableExists(table) || columnExists(table, column)) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "ALTER TABLE %s ADD COLUMN %s %s".formatted(table, column, type));
        }
        connection.commit();
        log.info("migrated: added {}.{}", table, column);
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

    long count(String table) {
        try (Statement statement = connection.createStatement();
             var rs = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new StoreException("cannot count " + table, e);
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
