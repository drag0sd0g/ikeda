package com.ikeda.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(
        name = "ikeda",
        description = "Mine Japanese financial filings for vocabulary worth learning.",
        mixinStandardHelpOptions = true,
        subcommands = {
                IngestCommand.class,
                AnkiCommand.class,
                SampleCommand.class,
                ExportCommand.class,
                VerdictsCommand.class,
                CompoundsCommand.class,
                CardsCommand.class,
                StatusCommand.class,
        })
public final class IkedaCommand implements Runnable {
    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(System.out);
    }
}
