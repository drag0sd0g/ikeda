package com.ikeda.cli;

import com.ikeda.store.CorpusStore;
import com.ikeda.store.Database;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "status", description = "Show corpus and review counts.")
public final class StatusCommand implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(StatusCommand.class);

    @Option(names = {"-n", "--top"}, description = "Terms to list by document frequency.")
    int top = 20;

    private final Workspace workspace;

    public StatusCommand() {
        this(new Workspace());
    }

    StatusCommand(Workspace workspace) {
        this.workspace = workspace;
    }

    @Override
    public void run() {
        try (Database database = workspace.openDatabase()) {
            var corpus = new CorpusStore(database);
            log.info("corpus: {}", corpus.stats());
            log.info("top {} terms by document frequency:", top);
            corpus.topTermsByDocumentFrequency(top).forEach(term ->
                    log.info("  {} docs {} total  {} ({})",
                            "%5d".formatted(term.documentFrequency()),
                            "%7d".formatted(term.corpusFrequency()),
                            term.key(), term.pos()));
            Reporting.verdicts(database);
        }
    }
}
