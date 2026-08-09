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
import java.util.Set;

public final class Baseline implements BaselineRanking {
    private static final Logger log = LoggerFactory.getLogger(Baseline.class);

    private static final String LEMMA_COLUMN = "lemma";
    private static final String FREQUENCY_COLUMN = "frequency";

    private final Map<String, Integer> ranks;

    private Baseline(Map<String, Integer> ranks) {
        this.ranks = ranks;
    }

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

    @Override
    public Optional<Integer> rankOf(String lemma) {
        return Optional.ofNullable(ranks.get(lemma));
    }

    @Override
    public Set<String> commonest(int limit) {
        return ranks.entrySet().stream()
                .filter(entry -> entry.getValue() <= limit)
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
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
