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

@Command(name = "sample",
        description = "Write the next review batch, rarest in general Japanese first.")
public final class SampleCommand implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(SampleCommand.class);

    static final double DISPERSION_FRACTION = 0.05;

    @Option(names = {"-n", "--size"}, description = "Candidates to write.")
    int size = 150;

    @Option(names = {"-o", "--out"}, description = "Destination sheet.")
    Path out = Path.of("review_batch.tsv");

    private final Workspace workspace;

    public SampleCommand() {
        this(new Workspace());
    }

    SampleCommand(Workspace workspace) {
        this.workspace = workspace;
    }

    @Override
    public void run() {
        try (Database database = workspace.openDatabase()) {
            var candidates = new CandidateStore(database);
            candidates.populate(DISPERSION_FRACTION, workspace.baseline());

            List<Candidate> batch = candidates.nextBatch(size);
            Workspace.write(out, ReviewSheet.write(batch));

            log.info("wrote {} candidates to {}", batch.size(), out);
            ReviewSheet.instructions().forEach(log::info);
        }
    }
}
