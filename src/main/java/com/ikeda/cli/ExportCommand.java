package com.ikeda.cli;

import com.ikeda.review.Candidate;
import com.ikeda.review.ReviewSheet;
import com.ikeda.store.CandidateStore;
import com.ikeda.store.Database;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.List;

@Command(name = "export", description = "Write every candidate carrying a verdict.")
public final class ExportCommand implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(ExportCommand.class);

    @Option(names = {"-o", "--out"}, description = "Destination sheet.")
    Path out = Path.of("review_decided.tsv");

    private final Workspace workspace;

    public ExportCommand() {
        this(new Workspace());
    }

    ExportCommand(Workspace workspace) {
        this.workspace = workspace;
    }

    @Override
    public void run() {
        try (Database database = workspace.openDatabase()) {
            List<Candidate> decided = new CandidateStore(database).decided();
            Workspace.write(out, ReviewSheet.write(decided));
            log.info("exported {} decided candidates to {}", decided.size(), out);
        }
    }
}
