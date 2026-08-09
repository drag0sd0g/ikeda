package com.ikeda.store;

import com.ikeda.analyse.ExampleSelector;

import java.util.List;

public final class SentenceStore {

    private final Database database;

    public SentenceStore(Database database) {
        this.database = database;
    }

    public List<ExampleSelector.SentenceContext> forTerm(long termId, int limit) {
        return database.query("""
                        SELECT s.id, s.text
                        FROM occurrence o
                        JOIN sentence s ON s.id = o.sentence_id
                        WHERE o.term_id = ?
                        ORDER BY s.char_len
                        LIMIT ?
                        """,
                statement -> {
                    statement.setLong(1, termId);
                    statement.setInt(2, limit);
                },
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
                        SELECT f.filer_name, f.submit_date_time
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
}
