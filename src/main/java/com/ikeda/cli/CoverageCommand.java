package com.ikeda.cli;

import com.ikeda.coverage.Coverage;
import com.ikeda.coverage.CoverageCalculator;
import com.ikeda.store.CoverageStore;
import com.ikeda.store.Database;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.List;
import java.util.Set;

@Command(name = "coverage",
        description = "Report how much of the corpus you can already read.")
public final class CoverageCommand implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(CoverageCommand.class);

    private static final int ASSUMED_GENERAL_VOCABULARY = 15_000;

    @Option(names = {"-d", "--doc"}, description = "Report on one filing instead of the corpus.")
    String docId;

    @Option(names = "--confirmed-only",
            description = "Count only words confirmed known, ignoring general Japanese.")
    boolean confirmedOnly;

    private final Workspace workspace;

    public CoverageCommand() {
        this(new Workspace());
    }

    CoverageCommand(Workspace workspace) {
        this.workspace = workspace;
    }

    @Override
    public void run() {
        try (Database database = workspace.openDatabase()) {
            var store = new CoverageStore(database);
            var calculator = CoverageCalculator.standard();

            List<CoverageCalculator.TermFrequency> confirmed = docId == null
                    ? store.corpusFrequencies()
                    : store.filingFrequencies(docId);
            String scope = docId == null ? "corpus" : store.filerOf(docId);

            report(scope + ", confirmed vocabulary only", calculator.of(confirmed));

            if (confirmedOnly) {
                return;
            }
            Set<String> general = workspace.baseline().commonest(ASSUMED_GENERAL_VOCABULARY);
            if (general.isEmpty()) {
                return;
            }
            List<CoverageCalculator.TermFrequency> estimated = confirmed.stream()
                    .map(term -> term.known() || general.contains(term.key())
                            ? new CoverageCalculator.TermFrequency(term.key(), term.occurrences(), true)
                            : term)
                    .toList();
            report(scope + ", assuming the commonest %,d words of general Japanese"
                    .formatted(ASSUMED_GENERAL_VOCABULARY), calculator.of(estimated));
        }
    }

    private static void report(String scope, Coverage coverage) {
        log.info("{}", scope);
        log.info("  token coverage {}  ({} of {} occurrences)",
                percent(coverage.tokenCoverage()),
                coverage.knownOccurrences(), coverage.totalOccurrences());
        log.info("  distinct words known {} of {}", coverage.knownTerms(), coverage.totalTerms());

        coverage.milestones().forEach(milestone -> {
            if (milestone.wordsNeeded() == 0) {
                log.info("    {} coverage already reached", percent(milestone.target()));
            } else if (milestone.reached()) {
                log.info("    {} more words to reach {}",
                        milestone.wordsNeeded(), percent(milestone.target()));
            } else {
                log.info("    {} is out of reach from this corpus", percent(milestone.target()));
            }
        });
    }

    private static String percent(double fraction) {
        return "%.1f%%".formatted(fraction * 100);
    }
}
