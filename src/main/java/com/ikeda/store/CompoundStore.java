package com.ikeda.store;

import com.ikeda.compound.Association;
import com.ikeda.compound.CompoundCandidate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class CompoundStore {

    private static final Logger log = LoggerFactory.getLogger(CompoundStore.class);

    private static final String PART_SEPARATOR = "+";

    public record StoredSentence(long sentenceId, String docId, String text) { }

    public record AcceptedCompound(CompoundCandidate candidate, long documentFrequency,
                                   double association) { }

    private final Database database;

    public CompoundStore(Database database) {
        this.database = database;
    }

    public List<StoredSentence> sentences() {
        return database.query("SELECT id, doc_id, text FROM sentence ORDER BY id",
                Sql.Binder.NONE,
                row -> new StoredSentence(row.getLong("id"), row.getString("doc_id"),
                        row.getString("text")));
    }

    public Association associationOver(List<CompoundCandidate> occurrences) {
        var partCounts = new HashMap<String, Long>();
        var pairCounts = new HashMap<String, Long>();

        for (CompoundCandidate candidate : occurrences) {
            List<String> parts = candidate.parts();
            for (String part : parts) {
                partCounts.merge(part, 1L, Long::sum);
            }
            for (int i = 0; i + 1 < parts.size(); i++) {
                pairCounts.merge(Association.pairKey(parts.get(i), parts.get(i + 1)), 1L, Long::sum);
            }
        }
        return new Association(partCounts, pairCounts);
    }

    public int store(List<AcceptedCompound> compounds, Map<String, List<Long>> sentencesBySurface,
                     Map<Long, String> docIdBySentence) {
        try {
            int stored = 0;
            try (PreparedStatement insertTerm = database.connection().prepareStatement("""
                         INSERT INTO term (key, surface, reading, pos, has_kanji,
                                           is_compound, part_keys)
                         VALUES (?, ?, '', ?, 1, 1, ?)
                         ON CONFLICT(key) DO UPDATE SET is_compound = 1,
                                                        part_keys = excluded.part_keys
                         RETURNING id
                         """);
                 PreparedStatement insertOccurrence = database.connection().prepareStatement("""
                         INSERT OR IGNORE INTO occurrence (term_id, sentence_id, doc_id, position)
                         VALUES (?, ?, ?, 0)
                         """)) {

                for (AcceptedCompound compound : compounds) {
                    String surface = compound.candidate().surface();
                    insertTerm.setString(1, surface);
                    insertTerm.setString(2, surface);
                    insertTerm.setString(3, com.ikeda.analyse.PartOfSpeech.NOUN.label());
                    insertTerm.setString(4, String.join(PART_SEPARATOR, compound.candidate().parts()));

                    long termId;
                    try (ResultSet rs = insertTerm.executeQuery()) {
                        rs.next();
                        termId = rs.getLong(1);
                    }
                    for (long sentenceId : sentencesBySurface.getOrDefault(surface, List.of())) {
                        insertOccurrence.setLong(1, termId);
                        insertOccurrence.setLong(2, sentenceId);
                        insertOccurrence.setString(3, docIdBySentence.get(sentenceId));
                        insertOccurrence.addBatch();
                    }
                    stored++;
                }
                insertOccurrence.executeBatch();
            }
            database.commit();
            log.info("stored {} compounds", stored);
            return stored;

        } catch (SQLException e) {
            database.rollbackQuietly();
            throw new StoreException("cannot store compounds", e);
        }
    }

    public List<String> compoundKeys() {
        return database.query("SELECT key FROM term WHERE is_compound = 1",
                Sql.Binder.NONE, row -> row.getString("key"));
    }

    public static List<String> partsOf(String partKeys) {
        return partKeys == null || partKeys.isBlank()
                ? List.of()
                : new ArrayList<>(List.of(partKeys.split("\\" + PART_SEPARATOR)));
    }
}
