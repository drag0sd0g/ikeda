package com.ikeda.cli;

import com.ikeda.analyse.ProseFilter;
import com.ikeda.analyse.Segmenter;
import com.ikeda.rank.Baseline;
import com.ikeda.gloss.GlossSource;
import com.ikeda.gloss.JmdictGlossSource;
import com.ikeda.rank.BaselineRanking;
import com.ikeda.store.Database;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public final class Workspace {
    private static final Logger log = LoggerFactory.getLogger(Workspace.class);

    private static final Path SYSTEM_DICTIONARY = Path.of("dict/system_core.dic");
    private static final Path DATABASE = Path.of("ikeda.db");
    private static final Path BASELINE = Path.of("baseline/BCCWJ_frequencylist_suw_ver1_0.tsv");
    private static final Path DICTIONARY_DIRECTORY = Path.of("dictionary");

    private final Path databasePath;
    private final Path baselinePath;
    private final Path dictionaryPath;

    public Workspace() {
        this(DATABASE, BASELINE, SYSTEM_DICTIONARY);
    }

    public Workspace(Path databasePath, Path baselinePath, Path dictionaryPath) {
        this.databasePath = databasePath;
        this.baselinePath = baselinePath;
        this.dictionaryPath = dictionaryPath;
    }

    public Database openDatabase() {
        return Database.open(databasePath);
    }

    public Segmenter openSegmenter() {
        return new Segmenter(dictionaryPath, ProseFilter.CORPUS);
    }

    public GlossSource glossSource() {
        Optional<Path> dictionary = firstJsonIn(DICTIONARY_DIRECTORY);
        if (dictionary.isEmpty()) {
            log.warn("no dictionary in {} — cards will have no meaning", DICTIONARY_DIRECTORY);
            return GlossSource.NONE;
        }
        return JmdictGlossSource.load(dictionary.get());
    }

    private static Optional<Path> firstJsonIn(Path directory) {
        if (!Files.isDirectory(directory)) {
            return Optional.empty();
        }
        try (var entries = Files.list(directory)) {
            return entries.filter(path -> path.getFileName().toString().endsWith(".json")).findFirst();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list " + directory, e);
        }
    }

    public BaselineRanking baseline() {
        if (!Files.exists(baselinePath)) {
            log.warn("baseline not found at {} — candidates will be unranked", baselinePath);
            return BaselineRanking.NONE;
        }
        return Baseline.load(baselinePath);
    }

    public static void write(Path path, String content) {
        try {
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + path, e);
        }
    }

    public static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + path, e);
        }
    }
}
