package com.ikeda.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Collection;

public final class KnownLemmaStore {
    private static final Logger log = LoggerFactory.getLogger(KnownLemmaStore.class);

    private static final String INSERT = """
            INSERT INTO known_lemma (lemma, source, first_seen)
            VALUES (?, ?, datetime('now'))
            ON CONFLICT(lemma) DO NOTHING
            """;

    private final Database database;

    public KnownLemmaStore(Database database) {
        this.database = database;
    }

    public int add(Collection<String> lemmas, String source) {
        long before = count();
        try (PreparedStatement statement = database.connection().prepareStatement(INSERT)) {
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
        int added = (int) (count() - before);
        log.info("known lemmas: +{} from {} ({} supplied, {} total)",
                added, source, lemmas.size(), count());
        return added;
    }

    public long count() {
        return database.count(Database.Table.KNOWN_LEMMA);
    }
}
