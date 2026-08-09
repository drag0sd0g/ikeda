package com.ikeda.store;

import com.ikeda.analyse.PartOfSpeech;
import com.ikeda.coverage.CoverageCalculator;

import java.util.List;
import java.util.Optional;

public final class CoverageStore {

    private static final String TERM_FREQUENCIES = """
            SELECT t.key                AS key,
                   COUNT(*)             AS occurrences,
                   t.key IN (SELECT lemma FROM known_lemma) AS known
            FROM occurrence o
            JOIN term t ON t.id = o.term_id
            WHERE t.pos IN %s AND t.has_kanji = 1 AND LENGTH(t.key) >= 2
            %s
            GROUP BY o.term_id
            """.formatted(PartOfSpeech.sqlInList(), "%s");

    private final Database database;

    public CoverageStore(Database database) {
        this.database = database;
    }

    public List<CoverageCalculator.TermFrequency> corpusFrequencies() {
        return database.query(TERM_FREQUENCIES.formatted(""), Sql.Binder.NONE,
                CoverageStore::toTermFrequency);
    }

    public List<CoverageCalculator.TermFrequency> filingFrequencies(String docId) {
        return database.query(TERM_FREQUENCIES.formatted("AND o.doc_id = ?"),
                statement -> statement.setString(1, docId),
                CoverageStore::toTermFrequency);
    }

    public Optional<String> anyFilingOtherThan(List<String> excluded) {
        String placeholders = excluded.isEmpty() ? "''"
                : String.join(",", java.util.Collections.nCopies(excluded.size(), "?"));
        return database.queryOne(
                "SELECT doc_id FROM filing WHERE doc_id NOT IN (%s) ORDER BY doc_id LIMIT 1"
                        .formatted(placeholders),
                statement -> {
                    for (int i = 0; i < excluded.size(); i++) {
                        statement.setString(i + 1, excluded.get(i));
                    }
                },
                row -> row.getString("doc_id"));
    }

    public String filerOf(String docId) {
        return database.queryOne("SELECT filer_name FROM filing WHERE doc_id = ?",
                statement -> statement.setString(1, docId),
                row -> row.getString("filer_name")).orElse(docId);
    }

    private static CoverageCalculator.TermFrequency toTermFrequency(java.sql.ResultSet row)
            throws java.sql.SQLException {
        return new CoverageCalculator.TermFrequency(
                row.getString("key"), row.getLong("occurrences"), row.getInt("known") == 1);
    }
}
