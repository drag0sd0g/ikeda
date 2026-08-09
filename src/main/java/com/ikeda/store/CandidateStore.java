package com.ikeda.store;

import com.ikeda.analyse.PartOfSpeech;
import com.ikeda.rank.BaselineRanking;
import com.ikeda.rank.PartwiseRank;
import com.ikeda.review.Candidate;
import com.ikeda.review.CandidateStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.EnumMap;
import java.util.Optional;
import java.util.List;
import java.util.Map;

public final class CandidateStore {
    private static final Logger log = LoggerFactory.getLogger(CandidateStore.class);

    private static final int MIN_TERM_LENGTH = 2;
    private static final int MIN_EXAMPLE_CHARS = 20;
    private static final int MAX_EXAMPLE_CHARS = 80;

    private static final String SELECT_CANDIDATES = """
            SELECT c.term_id, t.key, t.reading, t.pos, c.corpus_frequency,
                   c.document_frequency, s.text AS example, c.status
            FROM candidate c
            JOIN term t ON t.id = c.term_id
            LEFT JOIN sentence s ON s.id = c.example_sentence_id
            """;

    private static final String PROMOTE = """
            INSERT INTO candidate (term_id, corpus_frequency, document_frequency,
                                   example_sentence_id)
            SELECT stats.term_id, stats.cf, stats.df, example.sentence_id
            FROM (
                SELECT o.term_id                AS term_id,
                       COUNT(*)                 AS cf,
                       COUNT(DISTINCT o.doc_id) AS df
                FROM occurrence o
                JOIN term t ON t.id = o.term_id
                WHERE t.pos IN %s
                  AND t.has_kanji = 1
                  AND LENGTH(t.key) >= ?
                  AND t.key NOT IN (SELECT lemma FROM known_lemma)
                GROUP BY o.term_id
                HAVING COUNT(DISTINCT o.doc_id) >= ?
            ) stats
            LEFT JOIN (
                SELECT o.term_id AS term_id, s.id AS sentence_id, MIN(s.char_len)
                FROM occurrence o
                JOIN sentence s ON s.id = o.sentence_id
                WHERE s.char_len BETWEEN ? AND ?
                GROUP BY o.term_id
            ) example ON example.term_id = stats.term_id
            ON CONFLICT(term_id) DO UPDATE SET
                corpus_frequency    = excluded.corpus_frequency,
                document_frequency  = excluded.document_frequency,
                example_sentence_id = excluded.example_sentence_id
            """.formatted(PartOfSpeech.sqlInList());

    private static final String RETIRE_KNOWN = """
            DELETE FROM candidate
            WHERE status = 'PENDING'
              AND term_id IN (SELECT id FROM term
                              WHERE key IN (SELECT lemma FROM known_lemma))
            """;

    private record RankedTerm(long termId, String lemma, boolean compound,
                              List<String> parts, List<String> shortUnits) { }

    private final Database database;

    public CandidateStore(Database database) {
        this.database = database;
    }

    public int populate(double dispersionFraction, BaselineRanking baseline) {
        long filings = database.count(Database.Table.FILING);
        int minDocumentFrequency = Math.max(2, (int) Math.ceil(dispersionFraction * filings));
        return populate(minDocumentFrequency, baseline);
    }

    public int populate(int minDocumentFrequency, BaselineRanking baseline) {
        try {
            try (PreparedStatement statement =
                         database.connection().prepareStatement(PROMOTE)) {
                statement.setInt(1, MIN_TERM_LENGTH);
                statement.setInt(2, minDocumentFrequency);
                statement.setInt(3, MIN_EXAMPLE_CHARS);
                statement.setInt(4, MAX_EXAMPLE_CHARS);
                statement.executeUpdate();
            }
            try (PreparedStatement statement =
                         database.connection().prepareStatement(RETIRE_KNOWN)) {
                statement.executeUpdate();
            }
            assignRanks(baseline);
            database.commit();
        } catch (SQLException e) {
            database.rollbackQuietly();
            throw new StoreException("cannot populate candidates", e);
        }

        int total = (int) count();
        log.info("candidates: {} at document frequency >= {}", total, minDocumentFrequency);
        return total;
    }

    private void assignRanks(BaselineRanking baseline) throws SQLException {
        List<RankedTerm> terms = database.query("""
                        SELECT c.term_id, t.key, t.is_compound, t.part_keys, t.part_units
                        FROM candidate c JOIN term t ON t.id = c.term_id
                        """,
                Sql.Binder.NONE,
                row -> new RankedTerm(row.getLong("term_id"), row.getString("key"),
                        row.getInt("is_compound") == 1,
                        CompoundStore.partsOf(row.getString("part_keys")),
                        CompoundStore.partsOf(row.getString("part_units"))));

        var partwise = new PartwiseRank(baseline);
        int measured = 0;
        int estimated = 0;

        try (PreparedStatement update = database.connection().prepareStatement(
                "UPDATE candidate SET bccwj_rank = ?, effective_rank = ? WHERE term_id = ?")) {
            for (RankedTerm term : terms) {
                Optional<Integer> direct = baseline.rankOf(term.lemma());
                Optional<Integer> effective = direct.isPresent() ? direct
                        : term.compound() ? partwise.estimate(term.shortUnits())
                        : Optional.empty();

                setNullable(update, 1, direct);
                setNullable(update, 2, effective);
                update.setLong(3, term.termId());
                update.addBatch();

                if (direct.isPresent()) {
                    measured++;
                } else if (effective.isPresent()) {
                    estimated++;
                }
            }
            update.executeBatch();
        }
        log.info("ranks: {} measured, {} estimated from parts, {} unscored",
                measured, estimated, terms.size() - measured - estimated);
    }

    public List<Candidate> nextBatch(int limit) {
        return database.query(SELECT_CANDIDATES + """
                        WHERE c.status = 'PENDING'
                        ORDER BY c.effective_rank IS NULL, c.effective_rank DESC,
                                 c.corpus_frequency DESC
                        LIMIT ?
                        """,
                statement -> statement.setInt(1, limit),
                CandidateStore::toCandidate);
    }

    public List<Candidate> decided() {
        return database.query(SELECT_CANDIDATES + """
                        WHERE c.status <> 'PENDING'
                        ORDER BY c.document_frequency DESC, c.corpus_frequency DESC
                        """,
                Sql.Binder.NONE, CandidateStore::toCandidate);
    }

    public int recordVerdicts(Map<String, CandidateStatus> verdicts) {
        int updated = 0;
        try (PreparedStatement statement = database.connection().prepareStatement("""
                UPDATE candidate
                SET status = ?, decided_at = datetime('now')
                WHERE term_id = (SELECT id FROM term WHERE key = ?)
                """)) {
            for (var verdict : verdicts.entrySet()) {
                statement.setString(1, verdict.getValue().name());
                statement.setString(2, verdict.getKey());
                updated += statement.executeUpdate();
            }
            database.commit();
        } catch (SQLException e) {
            database.rollbackQuietly();
            throw new StoreException("cannot record verdicts", e);
        }
        log.info("recorded {} verdicts ({} supplied)", updated, verdicts.size());
        return updated;
    }

    public Optional<Integer> baselineRankOf(long termId) {
        return database.queryOne("""
                        SELECT effective_rank FROM candidate
                        WHERE term_id = ? AND effective_rank IS NOT NULL
                        """,
                statement -> statement.setLong(1, termId),
                row -> row.getInt("effective_rank"));
    }

    public int resetVerdict(String term) {
        return database.update("""
                        UPDATE candidate
                        SET status = 'PENDING', decided_at = NULL
                        WHERE term_id = (SELECT id FROM term WHERE key = ?)
                        """,
                statement -> statement.setString(1, term));
    }

    public Map<CandidateStatus, Long> verdictCounts() {
        var counts = new EnumMap<CandidateStatus, Long>(CandidateStatus.class);
        for (CandidateStatus status : CandidateStatus.values()) {
            counts.put(status, 0L);
        }
        database.query("SELECT status, COUNT(*) AS total FROM candidate GROUP BY status",
                        Sql.Binder.NONE,
                        row -> Map.entry(CandidateStatus.valueOf(row.getString("status")),
                                row.getLong("total")))
                .forEach(entry -> counts.put(entry.getKey(), entry.getValue()));
        return counts;
    }

    private static void setNullable(PreparedStatement statement, int index, Optional<Integer> value)
            throws SQLException {
        if (value.isPresent()) {
            statement.setInt(index, value.get());
        } else {
            statement.setNull(index, Types.INTEGER);
        }
    }

    public long count() {
        return database.count(Database.Table.CANDIDATE);
    }

    private static Candidate toCandidate(ResultSet row) throws SQLException {
        return new Candidate(
                row.getLong("term_id"),
                row.getString("key"),
                row.getString("reading"),
                row.getString("pos"),
                row.getLong("corpus_frequency"),
                row.getLong("document_frequency"),
                row.getString("example"),
                CandidateStatus.valueOf(row.getString("status")));
    }
}
