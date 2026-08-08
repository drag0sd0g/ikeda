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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

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

    public static Database inMemory() {
        return new Database("jdbc:sqlite::memory:");
    }

    Connection connection() {
        return connection;
    }

    private void configure() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");

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

    private void migrate() throws SQLException {
        addColumnIfMissing("candidate", "bccwj_rank", "INTEGER");
        if (addColumnIfMissing("term", "has_kanji", "INTEGER NOT NULL DEFAULT 1")) {
            backfillHasKanji();
        }
    }

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

    <T> List<T> query(String sql, Sql.Binder binder, Sql.RowMapper<T> mapper) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet rows = statement.executeQuery()) {
                var results = new java.util.ArrayList<T>();
                while (rows.next()) {
                    results.add(mapper.map(rows));
                }
                return List.copyOf(results);
            }
        } catch (SQLException e) {
            throw new StoreException("query failed: " + summarise(sql), e);
        }
    }

    <T> java.util.Optional<T> queryOne(String sql, Sql.Binder binder, Sql.RowMapper<T> mapper) {
        return query(sql, binder, mapper).stream().findFirst();
    }

    boolean exists(String sql, Sql.Binder binder) {
        return !query(sql, binder, row -> Boolean.TRUE).isEmpty();
    }

    int update(String sql, Sql.Binder binder) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new StoreException("update failed: " + summarise(sql), e);
        }
    }

    private static String summarise(String sql) {
        return sql.strip().lines().findFirst().orElse(sql);
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
        }
    }

    public enum Table {
        FILING("filing"), BLOCK("block"), SENTENCE("sentence"), TERM("term"),
        OCCURRENCE("occurrence"), CANDIDATE("candidate"), KNOWN_LEMMA("known_lemma");

        private final String name;

        Table(String name) {
            this.name = name;
        }
    }

    long count(Table table) {
        return queryOne("SELECT COUNT(*) FROM " + table.name, Sql.Binder.NONE,
                row -> row.getLong(1)).orElse(0L);
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
