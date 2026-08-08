package com.ikeda.cli;

import com.ikeda.anki.AnkiConnect;
import com.ikeda.store.Database;
import com.ikeda.store.KnownLemmaStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;

@Command(name = "anki", description = "Load the Anki collection into the known set.")
public final class AnkiCommand implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(AnkiCommand.class);

    private final Workspace workspace;
    private final AnkiConnect anki;

    public AnkiCommand() {
        this(new Workspace(), AnkiConnect.withDefaults());
    }

    AnkiCommand(Workspace workspace, AnkiConnect anki) {
        this.workspace = workspace;
        this.anki = anki;
    }

    @Override
    public void run() {
        if (!anki.isAvailable()) {
            throw new IllegalStateException(
                    "AnkiConnect is not responding — is Anki running with the add-on enabled?");
        }
        try (Database database = workspace.openDatabase()) {
            var known = new KnownLemmaStore(database);
            known.add(anki.headwords(), "anki");
            log.info("known set now holds {} lemmas", known.count());
        }
    }
}
