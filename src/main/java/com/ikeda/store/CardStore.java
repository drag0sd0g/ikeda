package com.ikeda.store;

import com.ikeda.analyse.ExampleSelector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public final class CardStore {

    private static final Logger log = LoggerFactory.getLogger(CardStore.class);

    public record Pending(long termId, String key, String reading, long bccwjRank,
                          long documentFrequency) { }

    private final Database database;

    public CardStore(Database database) {
        this.database = database;
    }

    public List<Pending> awaitingExport(int limit) {
        return database.query("""
                        SELECT c.term_id, t.key, t.reading, c.bccwj_rank, c.document_frequency
                        FROM candidate c
                        JOIN term t ON t.id = c.term_id
                        WHERE c.status = 'WORTH_LEARNING' AND c.exported_at IS NULL
                        ORDER BY c.document_frequency DESC
                        LIMIT ?
                        """,
                statement -> statement.setInt(1, limit),
                row -> new Pending(row.getLong("term_id"), row.getString("key"),
                        row.getString("reading"), row.getLong("bccwj_rank"),
                        row.getLong("document_frequency")));
    }

    public List<ExampleSelector.SentenceContext> sentencesFor(long termId) {
        return database.query("""
                        SELECT s.id, s.text
                        FROM occurrence o
                        JOIN sentence s ON s.id = o.sentence_id
                        WHERE o.term_id = ?
                        ORDER BY s.char_len
                        LIMIT 200
                        """,
                statement -> statement.setLong(1, termId),
                row -> new ExampleSelector.SentenceContext(
                        row.getLong("id"), row.getString("text"), List.of()));
    }

    public List<String> termsIn(long sentenceId) {
        return database.query("""
                        SELECT t.key FROM occurrence o
                        JOIN term t ON t.id = o.term_id
                        WHERE o.sentence_id = ?
                        """,
                statement -> statement.setLong(1, sentenceId),
                row -> row.getString("key"));
    }

    public String sourceOf(long sentenceId) {
        return database.queryOne("""
                        SELECT f.filer_name, f.submit_date_time, s.doc_id
                        FROM sentence s JOIN filing f ON f.doc_id = s.doc_id
                        WHERE s.id = ?
                        """,
                statement -> statement.setLong(1, sentenceId),
                row -> "%s %s".formatted(row.getString("filer_name"),
                        row.getString("submit_date_time"))).orElse("");
    }

    public String docIdOf(long sentenceId) {
        return database.queryOne("SELECT doc_id FROM sentence WHERE id = ?",
                statement -> statement.setLong(1, sentenceId),
                row -> row.getString("doc_id")).orElse("");
    }

    public Set<String> knownLemmas() {
        return database.query("SELECT lemma FROM known_lemma", Sql.Binder.NONE,
                        row -> row.getString("lemma"))
                .stream().collect(Collectors.toUnmodifiableSet());
    }

    public int markExported(Collection<Long> termIds) {
        try (PreparedStatement statement = database.connection().prepareStatement(
                "UPDATE candidate SET exported_at = datetime('now') WHERE term_id = ?")) {
            for (long termId : termIds) {
                statement.setLong(1, termId);
                statement.addBatch();
            }
            int[] results = statement.executeBatch();
            database.commit();
            log.info("marked {} candidates exported", results.length);
            return results.length;
        } catch (SQLException e) {
            database.rollbackQuietly();
            throw new StoreException("cannot mark candidates exported", e);
        }
    }
}
