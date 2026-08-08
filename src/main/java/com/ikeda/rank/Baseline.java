package com.ikeda.rank;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Frequency of words in general written Japanese, from NINJAL's BCCWJ short-unit
 * vocabulary list.
 *
 * <p>This is the only feature that survived testing as a predictor of what an
 * advanced learner does not know (AUC 0.73). Corpus frequency, document
 * frequency, word length and formal-versus-casual register were all measured and
 * all failed; Anki membership turned out to be an anti-signal until its owner
 * confirmed the whole collection should count as known.
 *
 * <p>The list is not redistributable — free for research and education, but not
 * to be vendored — so it is downloaded separately and gitignored.
 */
public final class Baseline {

    private static final Logger log = LoggerFactory.getLogger(Baseline.class);

    private static final String LEMMA_COLUMN = "lemma";
    private static final String FREQUENCY_COLUMN = "frequency";

    /** lemma to rank, 1 being the commonest word in the corpus. */
    private final Map<String, Integer> ranks;

    private Baseline(Map<String, Integer> ranks) {
        this.ranks = ranks;
    }

    /**
     * Reads the BCCWJ short-unit list.
     *
     * <p>A lemma can appear several times under different parts of speech; the
     * frequencies are summed, because the ranking asks how often a written form
     * occurs at all, not how often it occurs as a particular part of speech.
     */
    public static Baseline load(Path tsv) {
        var totals = new HashMap<String, Long>();

        try (BufferedReader reader = Files.newBufferedReader(tsv, StandardCharsets.UTF_8)) {
            String[] header = reader.readLine().split("\t", -1);
            int lemmaColumn = indexOf(header, LEMMA_COLUMN);
            int frequencyColumn = indexOf(header, FREQUENCY_COLUMN);

            String line;
            while ((line = reader.readLine()) != null) {
                String[] cells = line.split("\t", -1);
                if (cells.length <= Math.max(lemmaColumn, frequencyColumn)) {
                    continue;
                }
                try {
                    totals.merge(cells[lemmaColumn],
                            Long.parseLong(cells[frequencyColumn].strip()), Long::sum);
                } catch (NumberFormatException e) {
                    // Malformed row in a 170k-line third-party file; skip it.
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read baseline " + tsv, e);
        }

        var ranks = new HashMap<String, Integer>(totals.size());
        int[] position = {0};
        totals.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .forEach(entry -> ranks.put(entry.getKey(), ++position[0]));

        log.info("baseline loaded: {} lemmas from {}", ranks.size(), tsv.getFileName());
        return new Baseline(ranks);
    }

    /**
     * Rank of a lemma in general Japanese, 1 being commonest.
     *
     * <p>Empty means absent from the baseline. That is <em>not</em> evidence of
     * rarity: 23% of candidates are absent, and they are 74% already known,
     * because they are compounds the baseline splits into shorter units. Callers
     * must not substitute a large rank for a missing one.
     */
    public Optional<Integer> rankOf(String lemma) {
        return Optional.ofNullable(ranks.get(lemma));
    }

    public int size() {
        return ranks.size();
    }

    private static int indexOf(String[] header, String column) {
        for (int i = 0; i < header.length; i++) {
            if (header[i].strip().equals(column)) {
                return i;
            }
        }
        throw new IllegalArgumentException(
                "baseline is missing the '%s' column".formatted(column));
    }
}
