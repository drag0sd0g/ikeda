package com.ikeda.cli;

import com.ikeda.compound.Association;
import com.ikeda.compound.CompoundCandidate;
import com.ikeda.compound.NounRunDetector;
import com.ikeda.store.CompoundStore;
import com.ikeda.store.Database;
import com.worksap.nlp.sudachi.Morpheme;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Command(name = "compounds",
        description = "Detect multi-word terms the tokeniser splits, and store them.")
public final class CompoundsCommand implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(CompoundsCommand.class);

    @Option(names = {"-d", "--min-documents"},
            description = "Filings a compound must appear in to be accepted.")
    int minDocuments = 9;

    @Option(names = {"-a", "--min-association"},
            description = "Weakest adjacent-pair association permitted, in bits.")
    double minAssociation = 3.0;

    private final Workspace workspace;

    public CompoundsCommand() {
        this(new Workspace());
    }

    CompoundsCommand(Workspace workspace) {
        this.workspace = workspace;
    }

    @Override
    public void run() {
        try (Database database = workspace.openDatabase();
             var segmenter = workspace.openSegmenter()) {

            var store = new CompoundStore(database);
            var detector = NounRunDetector.standard();

            List<CompoundStore.StoredSentence> sentences = store.sentences();
            var occurrences = new ArrayList<CompoundCandidate>();
            var sentencesBySurface = new HashMap<String, List<Long>>();
            var documentsBySurface = new HashMap<String, java.util.Set<String>>();
            var docIdBySentence = new HashMap<Long, String>();

            for (CompoundStore.StoredSentence sentence : sentences) {
                docIdBySentence.put(sentence.sentenceId(), sentence.docId());
                for (List<Morpheme> tokens : segmenter.tokenize(sentence.text())) {
                    for (CompoundCandidate candidate : detector.detect(tokens)) {
                        occurrences.add(candidate);
                        sentencesBySurface
                                .computeIfAbsent(candidate.surface(), key -> new ArrayList<>())
                                .add(sentence.sentenceId());
                        documentsBySurface
                                .computeIfAbsent(candidate.surface(), key -> new java.util.HashSet<>())
                                .add(sentence.docId());
                    }
                }
            }
            log.info("noun runs: {} occurrences, {} distinct",
                    occurrences.size(), sentencesBySurface.size());

            Association association = store.associationOver(occurrences);
            Map<String, CompoundCandidate> distinct = new HashMap<>();
            occurrences.forEach(candidate -> distinct.putIfAbsent(candidate.surface(), candidate));

            List<CompoundStore.AcceptedCompound> accepted = distinct.values().stream()
                    .map(candidate -> new CompoundStore.AcceptedCompound(candidate,
                            documentsBySurface.get(candidate.surface()).size(),
                            association.weakestLink(candidate.parts())))
                    .filter(compound -> compound.documentFrequency() >= minDocuments)
                    .filter(compound -> compound.association() >= minAssociation)
                    .toList();

            log.info("accepted {} compounds at documents >= {} and association >= {} bits",
                    accepted.size(), minDocuments, minAssociation);

            store.store(accepted, sentencesBySurface, docIdBySentence);

            accepted.stream()
                    .sorted((a, b) -> Long.compare(b.documentFrequency(), a.documentFrequency()))
                    .limit(15)
                    .forEach(compound -> log.info("  {} docs  {} ({} bits)",
                            "%4d".formatted(compound.documentFrequency()),
                            compound.candidate().surface(),
                            "%.1f".formatted(compound.association())));
        }
    }
}
