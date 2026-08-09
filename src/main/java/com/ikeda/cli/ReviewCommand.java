package com.ikeda.cli;

import com.ikeda.store.CandidateStore;
import com.ikeda.store.CompoundStore;
import com.ikeda.store.Database;
import com.ikeda.store.SentenceStore;
import com.ikeda.store.VerdictRecorder;
import com.ikeda.ui.ReviewQueue;
import com.ikeda.ui.ReviewServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;

@Command(name = "review", description = "Open the review interface in a browser.")
public final class ReviewCommand implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(ReviewCommand.class);

    private static final int DEFAULT_DISPERSION = 9;

    @Option(names = {"-p", "--port"}, description = "Port to listen on, 0 to pick any free one.")
    int port = 8770;

    @Option(names = "--no-browser", description = "Do not open a browser automatically.")
    boolean noBrowser;

    private final Workspace workspace;

    public ReviewCommand() {
        this(new Workspace());
    }

    ReviewCommand(Workspace workspace) {
        this.workspace = workspace;
    }

    @Override
    public void run() {
        try (Database database = workspace.openDatabase()) {
            var candidates = new CandidateStore(database);
            candidates.populate(DEFAULT_DISPERSION, workspace.baseline());

            var queue = new ReviewQueue(candidates, new SentenceStore(database),
                    new CompoundStore(database), new VerdictRecorder(database),
                    workspace.glossSource());

            try (var server = new ReviewServer(queue, port)) {
                server.start();
                if (!noBrowser) {
                    openBrowser(server.address());
                }
                log.info("press Ctrl+C to stop");
                awaitShutdown();
            }
        }
    }

    private static void openBrowser(String address) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(address));
            }
        } catch (IOException | UnsupportedOperationException e) {
            log.info("open {} in a browser", address);
        }
    }

    private static void awaitShutdown() {
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
