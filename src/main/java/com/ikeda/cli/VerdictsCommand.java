package com.ikeda.cli;

import com.ikeda.review.CandidateStatus;
import com.ikeda.review.ReviewSheet;
import com.ikeda.store.CandidateStore;
import com.ikeda.store.Database;
import com.ikeda.store.KnownLemmaStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@Command(name = "verdicts", description = "Read a reviewed sheet back.")
public final class VerdictsCommand implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(VerdictsCommand.class);

    @Parameters(index = "0", description = "Reviewed sheet to import.")
    Path sheet;

    private final Workspace workspace;

    public VerdictsCommand() {
        this(new Workspace());
    }

    VerdictsCommand(Workspace workspace) {
        this.workspace = workspace;
    }

    @Override
    public void run() {
        Map<String, CandidateStatus> verdicts =
                ReviewSheet.readVerdicts(Workspace.read(sheet));

        try (Database database = workspace.openDatabase()) {
            int updated = new CandidateStore(database).recordVerdicts(verdicts);

            List<String> nowKnown = verdicts.entrySet().stream()
                    .filter(entry -> entry.getValue() == CandidateStatus.KNOWN)
                    .map(Map.Entry::getKey)
                    .toList();
            if (!nowKnown.isEmpty()) {
                new KnownLemmaStore(database).add(nowKnown, "review");
            }

            log.info("read {} verdicts from {}, applied {}", verdicts.size(), sheet, updated);
            Reporting.verdicts(database);
        }
    }
}
