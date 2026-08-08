package com.ikeda.store;

import com.ikeda.analyse.AnalysedSentence;
import com.ikeda.analyse.TermOccurrence;
import com.ikeda.ingest.FilingRef;
import com.ikeda.ingest.NarrativeBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The corpus: filings, their narrative blocks, the prose sentences extracted from
 * them, and every content word occurrence.
 *
 * <p>Ingestion is atomic per filing. A filing is either fully present or entirely
 * absent, which together with {@link #hasFiling(String)} makes a run safe to
 * interrupt and resume — worth having when a full pass takes a quarter of an hour
 * of rate-limited downloading.
 */
public final class CorpusStore {

    private static final Logger log = LoggerFactory.getLogger(CorpusStore.class);

    private final Database database;

    /**
     * Maps term key to primary key, so a repeated word costs a hash lookup rather
     * than a database round trip. Cleared on rollback, because ids assigned inside
     * an aborted transaction no longer exist.
     */
    private final Map<String, Long> termIds = new HashMap<>();

    public CorpusStore(Database database) {
        this.database = database;
    }

    private Connection connection() {
        return database.connection();
    }

    public boolean hasFiling(String docId) {
        try (PreparedStatement statement =
                     connection().prepareStatement("SELECT 1 FROM filing WHERE doc_id = ?")) {
            statement.setString(1, docId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new StoreException("cannot query filing " + docId, e);
        }
    }

    /**
     * Writes one filing and everything derived from it, as a single transaction.
     *
     * @param sentences must reference blocks by index into {@code blocks}
     */
    public void ingestFiling(FilingRef filing,
                             List<NarrativeBlock> blocks,
                             List<AnalysedSentence> sentences) {
        try {
            insertFiling(filing);
            long[] blockIds = insertBlocks(filing.docId(), blocks);
            insertSentences(filing.docId(), blockIds, sentences);
            database.commit();
            log.debug("ingested {}: {} blocks, {} sentences",
                    filing.docId(), blocks.size(), sentences.size());
        } catch (SQLException e) {
            database.rollbackQuietly();
            termIds.clear();
            throw new StoreException("cannot ingest filing " + filing.docId(), e);
        }
    }

    private void insertFiling(FilingRef filing) throws SQLException {
        try (PreparedStatement statement = connection().prepareStatement("""
                INSERT INTO filing (doc_id, edinet_code, filer_name, doc_type_code,
                                    ordinance_code, form_code, submit_date_time, ingested_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, datetime('now'))
                """)) {
            statement.setString(1, filing.docId());
            statement.setString(2, filing.edinetCode());
            statement.setString(3, filing.filerName());
            statement.setString(4, filing.docTypeCode());
            statement.setString(5, filing.ordinanceCode());
            statement.setString(6, filing.formCode());
            statement.setString(7, filing.submitDateTime());
            statement.executeUpdate();
        }
    }

    private long[] insertBlocks(String docId, List<NarrativeBlock> blocks) throws SQLException {
        long[] ids = new long[blocks.size()];
        try (PreparedStatement statement = connection().prepareStatement(
                "INSERT INTO block (doc_id, seq, element_id, text) VALUES (?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {

            for (int i = 0; i < blocks.size(); i++) {
                NarrativeBlock block = blocks.get(i);
                statement.setString(1, docId);
                statement.setInt(2, i);
                statement.setString(3, block.elementId());
                statement.setString(4, block.text());
                statement.executeUpdate();
                ids[i] = generatedKey(statement);
            }
        }
        return ids;
    }

    private void insertSentences(String docId, long[] blockIds, List<AnalysedSentence> sentences)
            throws SQLException {

        try (PreparedStatement insertSentence = connection().prepareStatement("""
                     INSERT INTO sentence (doc_id, block_id, seq, text, char_len, token_count)
                     VALUES (?, ?, ?, ?, ?, ?)
                     """, Statement.RETURN_GENERATED_KEYS);
             PreparedStatement insertOccurrence = connection().prepareStatement("""
                     INSERT INTO occurrence (term_id, sentence_id, doc_id, position)
                     VALUES (?, ?, ?, ?)
                     """)) {

            for (AnalysedSentence sentence : sentences) {
                if (sentence.blockIndex() < 0 || sentence.blockIndex() >= blockIds.length) {
                    throw new SQLException("sentence references block index %d, only %d blocks"
                            .formatted(sentence.blockIndex(), blockIds.length));
                }
                insertSentence.setString(1, docId);
                insertSentence.setLong(2, blockIds[sentence.blockIndex()]);
                insertSentence.setInt(3, sentence.seq());
                insertSentence.setString(4, sentence.text());
                insertSentence.setInt(5, sentence.text().length());
                insertSentence.setInt(6, sentence.tokenCount());
                insertSentence.executeUpdate();
                long sentenceId = generatedKey(insertSentence);

                for (TermOccurrence term : sentence.terms()) {
                    insertOccurrence.setLong(1, termId(term));
                    insertOccurrence.setLong(2, sentenceId);
                    insertOccurrence.setString(3, docId);
                    insertOccurrence.setInt(4, term.position());
                    insertOccurrence.addBatch();
                }
            }
            // Occurrences outnumber sentences roughly fifteen to one and need no
            // generated keys, so they are the one insert worth batching.
            insertOccurrence.executeBatch();
        }
    }

    private long termId(TermOccurrence term) throws SQLException {
        Long cached = termIds.get(term.key());
        if (cached != null) {
            return cached;
        }
        try (PreparedStatement statement = connection().prepareStatement("""
                INSERT INTO term (key, surface, reading, pos) VALUES (?, ?, ?, ?)
                ON CONFLICT(key) DO UPDATE SET key = excluded.key
                RETURNING id
                """)) {
            statement.setString(1, term.key());
            statement.setString(2, term.surface());
            statement.setString(3, term.reading());
            statement.setString(4, term.pos());
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                long id = rs.getLong(1);
                termIds.put(term.key(), id);
                return id;
            }
        }
    }

    private static long generatedKey(PreparedStatement statement) throws SQLException {
        try (ResultSet keys = statement.getGeneratedKeys()) {
            if (!keys.next()) {
                throw new SQLException("insert returned no generated key");
            }
            return keys.getLong(1);
        }
    }

    public CorpusStats stats() {
        return new CorpusStats(
                database.count("filing"), database.count("block"), database.count("sentence"),
                database.count("term"), database.count("occurrence"));
    }

    /**
     * Terms ordered by how many filings they appear in.
     *
     * <p>The phase 1 exit criterion: this list should be dominated by 会社, 当社,
     * 事業 and similar at near-total document frequency. That is the correct and
     * uninteresting result.
     */
    public List<TermFrequency> topTermsByDocumentFrequency(int limit) {
        try (PreparedStatement statement = connection().prepareStatement("""
                SELECT t.key, t.pos, COUNT(*) AS corpus_freq,
                       COUNT(DISTINCT o.doc_id) AS doc_freq
                FROM occurrence o
                JOIN term t ON t.id = o.term_id
                GROUP BY o.term_id
                ORDER BY doc_freq DESC, corpus_freq DESC
                LIMIT ?
                """)) {
            statement.setInt(1, limit);
            try (ResultSet rs = statement.executeQuery()) {
                var results = new ArrayList<TermFrequency>();
                while (rs.next()) {
                    results.add(new TermFrequency(
                            rs.getString("key"), rs.getString("pos"),
                            rs.getLong("corpus_freq"), rs.getLong("doc_freq")));
                }
                return List.copyOf(results);
            }
        } catch (SQLException e) {
            throw new StoreException("cannot query term frequencies", e);
        }
    }
}
