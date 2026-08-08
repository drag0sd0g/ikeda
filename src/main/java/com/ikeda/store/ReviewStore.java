package com.ikeda.store;

import com.ikeda.review.Candidate;
import com.ikeda.review.CandidateStatus;
import com.ikeda.support.Scripts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Candidates, the reviewer's verdicts, and the words already known.
 *
 * <p>The corpus holds every word seen. A candidate is one worth putting in front
 * of the reviewer, which is a far smaller set: the dispersion floor removes about
 * three quarters of the vocabulary as company-specific noise, and the known
 * lemmas remove another sixth.
 */
public final class ReviewStore {

    private static final Logger log = LoggerFactory.getLogger(ReviewStore.class);

    /** Parts of speech that can carry a learnable meaning. */
    private static final String CONTENT_POS = "('名詞','動詞','形容詞','副詞')";

    /** Single characters are almost always fragments rather than words. */
    private static final int MIN_TERM_LENGTH = 2;

    /** Example sentences short enough to read at a glance, long enough for context. */
    private static final int MIN_EXAMPLE_CHARS = 20;
    private static final int MAX_EXAMPLE_CHARS = 80;

    private static final String CANDIDATE_COLUMNS = """
            SELECT c.term_id, t.key, t.reading, t.pos, c.corpus_frequency,
                   c.document_frequency, s.text AS example, c.status
            FROM candidate c
            JOIN term t ON t.id = c.term_id
            LEFT JOIN sentence s ON s.id = c.example_sentence_id
            """;

    private final Database database;

    public ReviewStore(Database database) {
        this.database = database;
    }

    // --- known lemmas ---------------------------------------------------

    /**
     * Adds words to the known set.
     *
     * <p>Idempotent, and the original source is preserved on re-import so that
     * a word first seen in Anki is not later reattributed to a review pass.
     *
     * @return how many were newly added
     */
    public int addKnown(Collection<String> lemmas, String source) {
        long before = database.count("known_lemma");
        try (PreparedStatement statement = database.connection().prepareStatement("""
                INSERT INTO known_lemma (lemma, source, first_seen)
                VALUES (?, ?, datetime('now'))
                ON CONFLICT(lemma) DO NOTHING
                """)) {
            for (String lemma : lemmas) {
                statement.setString(1, lemma);
                statement.setString(2, source);
                statement.addBatch();
            }
            statement.executeBatch();
            database.commit();
        } catch (SQLException e) {
            database.rollbackQuietly();
            throw new StoreException("cannot add known lemmas", e);
        }
        long added = database.count("known_lemma") - before;
        log.info("known lemmas: +{} from {} ({} supplied, {} total)",
                added, source, lemmas.size(), database.count("known_lemma"));
        return (int) added;
    }

    public long knownCount() {
        return database.count("known_lemma");
    }

    // --- candidates -----------------------------------------------------

    /**
     * Rebuilds the candidate set from the corpus.
     *
     * <p>Existing verdicts are preserved; only counts, rank and example refresh.
     * Words in {@code known_lemma} are excluded outright — they are not
     * candidates at all, so they never consume a review slot.
     *
     * @param minDocumentFrequency dispersion floor. A term appearing many times in
     *                             one filing is that company's jargon.
     * @param baselineRank         general-Japanese rank per lemma; empty result
     *                             means absent from the baseline, which is left
     *                             NULL rather than treated as maximally rare.
     */
    public int populate(int minDocumentFrequency, Function<String, Integer> baselineRank) {
        try {
            try (PreparedStatement statement = database.connection().prepareStatement("""
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
                          AND LENGTH(t.key) >= ?
                          AND t.key NOT IN (SELECT lemma FROM known_lemma)
                        GROUP BY o.term_id
                        HAVING COUNT(DISTINCT o.doc_id) >= ?
                    ) stats
                    LEFT JOIN (
                        -- Shortest sentence in range: easiest to read, most likely
                        -- to stand alone. SQLite takes bare columns from the MIN row.
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
                    """.formatted(CONTENT_POS))) {

                statement.setInt(1, MIN_TERM_LENGTH);
                statement.setInt(2, minDocumentFrequency);
                statement.setInt(3, MIN_EXAMPLE_CHARS);
                statement.setInt(4, MAX_EXAMPLE_CHARS);
                statement.executeUpdate();
            }

            // A word can become known after it was first promoted, so drop any
            // undecided candidate the known set has since caught up with.
            try (PreparedStatement statement = database.connection().prepareStatement("""
                    DELETE FROM candidate
                    WHERE status = 'PENDING'
                      AND term_id IN (SELECT id FROM term
                                      WHERE key IN (SELECT lemma FROM known_lemma))
                    """)) {
                statement.executeUpdate();
            }

            removeKanaOnly();
            assignRanks(baselineRank);
            database.commit();

        } catch (SQLException e) {
            database.rollbackQuietly();
            throw new StoreException("cannot populate candidates", e);
        }

        int total = (int) database.count("candidate");
        log.info("candidates: {} at document frequency >= {}", total, minDocumentFrequency);
        return total;
    }

    /**
     * Drops undecided candidates written entirely in kana.
     *
     * <p>Done in Java rather than SQL because SQLite has no character-class
     * matching for CJK. See {@link Scripts#containsKanji} for the evidence.
     */
    private void removeKanaOnly() throws SQLException {
        var doomed = new ArrayList<Long>();
        try (PreparedStatement select = database.connection().prepareStatement("""
                SELECT c.term_id, t.key FROM candidate c
                JOIN term t ON t.id = c.term_id
                WHERE c.status = 'PENDING'
                """);
             ResultSet rs = select.executeQuery()) {
            while (rs.next()) {
                if (!Scripts.containsKanji(rs.getString(2))) {
                    doomed.add(rs.getLong(1));
                }
            }
        }
        if (doomed.isEmpty()) {
            return;
        }
        try (PreparedStatement delete = database.connection().prepareStatement(
                "DELETE FROM candidate WHERE term_id = ?")) {
            for (long termId : doomed) {
                delete.setLong(1, termId);
                delete.addBatch();
            }
            delete.executeBatch();
        }
        log.info("dropped {} kana-only candidates", doomed.size());
    }

    private void assignRanks(Function<String, Integer> baselineRank) throws SQLException {
        List<long[]> ids = new ArrayList<>();
        var keys = new ArrayList<String>();

        try (PreparedStatement select = database.connection().prepareStatement(
                "SELECT c.term_id, t.key FROM candidate c JOIN term t ON t.id = c.term_id");
             ResultSet rs = select.executeQuery()) {
            while (rs.next()) {
                ids.add(new long[]{rs.getLong(1)});
                keys.add(rs.getString(2));
            }
        }

        int scored = 0;
        try (PreparedStatement update = database.connection().prepareStatement(
                "UPDATE candidate SET bccwj_rank = ? WHERE term_id = ?")) {
            for (int i = 0; i < ids.size(); i++) {
                Integer rank = baselineRank.apply(keys.get(i));
                if (rank == null) {
                    update.setNull(1, Types.INTEGER);
                } else {
                    update.setInt(1, rank);
                    scored++;
                }
                update.setLong(2, ids.get(i)[0]);
                update.addBatch();
            }
            update.executeBatch();
        }
        log.info("baseline ranks: {} of {} candidates scored", scored, ids.size());
    }

    /**
     * The next batch to review, rarest in general Japanese first.
     *
     * <p>Baseline rarity is the only feature that predicted unknown-ness (AUC
     * 0.73), lifting precision from 22% to about 46%. Candidates absent from the
     * baseline are placed last rather than first: absence is usually a compound
     * the baseline tokenises differently, and such words proved 74% already known.
     */
    public List<Candidate> nextBatch(int limit) {
        try (PreparedStatement statement = database.connection().prepareStatement(
                CANDIDATE_COLUMNS + """
                        WHERE c.status = 'PENDING'
                        ORDER BY c.bccwj_rank IS NULL, c.bccwj_rank DESC,
                                 c.corpus_frequency DESC
                        LIMIT ?
                        """)) {
            statement.setInt(1, limit);
            return read(statement);
        } catch (SQLException e) {
            throw new StoreException("cannot query next batch", e);
        }
    }

    /**
     * Every candidate carrying a verdict.
     *
     * <p>These are the labelled data: the most expensive artifact in the project,
     * and the only one that cannot be regenerated from EDINET.
     */
    public List<Candidate> decided() {
        try (PreparedStatement statement = database.connection().prepareStatement(
                CANDIDATE_COLUMNS + """
                        WHERE c.status <> 'PENDING'
                        ORDER BY c.document_frequency DESC, c.corpus_frequency DESC
                        """)) {
            return read(statement);
        } catch (SQLException e) {
            throw new StoreException("cannot query decided candidates", e);
        }
    }

    /**
     * Applies verdicts keyed by term, and promotes every "known" verdict into the
     * known set so the word is never proposed again.
     *
     * @return how many candidates were updated; terms not present are ignored
     */
    public int recordVerdicts(Map<String, CandidateStatus> verdicts) {
        int updated;
        try (PreparedStatement statement = database.connection().prepareStatement("""
                UPDATE candidate
                SET status = ?, decided_at = datetime('now')
                WHERE term_id = (SELECT id FROM term WHERE key = ?)
                """)) {
            updated = 0;
            for (Map.Entry<String, CandidateStatus> verdict : verdicts.entrySet()) {
                statement.setString(1, verdict.getValue().name());
                statement.setString(2, verdict.getKey());
                updated += statement.executeUpdate();
            }
            database.commit();
        } catch (SQLException e) {
            database.rollbackQuietly();
            throw new StoreException("cannot record verdicts", e);
        }

        List<String> nowKnown = verdicts.entrySet().stream()
                .filter(e -> e.getValue() == CandidateStatus.KNOWN)
                .map(Map.Entry::getKey)
                .toList();
        if (!nowKnown.isEmpty()) {
            addKnown(nowKnown, "review");
        }

        log.info("recorded {} verdicts ({} supplied)", updated, verdicts.size());
        return updated;
    }

    /** How many candidates carry each verdict. */
    public Map<CandidateStatus, Long> verdictCounts() {
        var counts = new EnumMap<CandidateStatus, Long>(CandidateStatus.class);
        for (CandidateStatus status : CandidateStatus.values()) {
            counts.put(status, 0L);
        }
        try (PreparedStatement statement = database.connection().prepareStatement(
                "SELECT status, COUNT(*) FROM candidate GROUP BY status");
             ResultSet rs = statement.executeQuery()) {

            while (rs.next()) {
                counts.put(CandidateStatus.valueOf(rs.getString(1)), rs.getLong(2));
            }
            return counts;
        } catch (SQLException e) {
            throw new StoreException("cannot count verdicts", e);
        }
    }

    private static List<Candidate> read(PreparedStatement statement) throws SQLException {
        try (ResultSet rs = statement.executeQuery()) {
            var results = new ArrayList<Candidate>();
            while (rs.next()) {
                results.add(new Candidate(
                        rs.getLong("term_id"),
                        rs.getString("key"),
                        rs.getString("reading"),
                        rs.getString("pos"),
                        rs.getLong("corpus_frequency"),
                        rs.getLong("document_frequency"),
                        rs.getString("example"),
                        CandidateStatus.valueOf(rs.getString("status"))));
            }
            return List.copyOf(results);
        }
    }
}
