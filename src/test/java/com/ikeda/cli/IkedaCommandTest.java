package com.ikeda.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;

class IkedaCommandTest {

    private final CommandLine cli = new CommandLine(new IkedaCommand());

    private String runCapturingErr(String... args) {
        var err = new StringWriter();
        cli.setErr(new PrintWriter(err));
        cli.setOut(new PrintWriter(new StringWriter()));
        cli.execute(args);
        return err.toString();
    }

    @Test
    @DisplayName("exposes every command as a subcommand")
    void exposesSubcommands() {
        assertThat(cli.getSubcommands()).containsOnlyKeys(
                "ingest", "anki", "sample", "export", "verdicts", "status",
                "compounds", "cards");
    }

    @Test
    @DisplayName("rejects an unknown command instead of doing something surprising")
    void rejectsUnknownCommand() {
        assertThat(runCapturingErr("nonsense")).contains("Unmatched argument");
        assertThat(cli.execute("nonsense")).isNotZero();
    }

    @Test
    @DisplayName("rejects ingest without a date")
    void requiresIngestDate() {
        assertThat(runCapturingErr("ingest")).contains("Missing required parameter");
    }

    @Test
    @DisplayName("rejects a malformed date rather than defaulting")
    void rejectsMalformedDate() {
        assertThat(runCapturingErr("ingest", "not-a-date")).contains("Invalid value");
    }

    @Test
    @DisplayName("parses ingest options")
    void parsesIngestOptions() {
        cli.parseArgs("ingest", "2026-06-26", "--limit", "5");
        IngestCommand ingest = cli.getSubcommands().get("ingest").getCommand();

        assertThat(ingest.date).hasToString("2026-06-26");
        assertThat(ingest.limit).isEqualTo(5);
    }

    @Test
    @DisplayName("defaults the ingest limit to unbounded")
    void defaultsIngestLimit() {
        cli.parseArgs("ingest", "2026-06-26");
        IngestCommand ingest = cli.getSubcommands().get("ingest").getCommand();

        assertThat(ingest.limit).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    @DisplayName("parses sample size and destination")
    void parsesSampleOptions() {
        cli.parseArgs("sample", "-n", "40", "-o", "batch.tsv");
        SampleCommand sample = cli.getSubcommands().get("sample").getCommand();

        assertThat(sample.size).isEqualTo(40);
        assertThat(sample.out).hasToString("batch.tsv");
    }

    @Test
    @DisplayName("requires a sheet for verdicts")
    void requiresVerdictsSheet() {
        assertThat(runCapturingErr("verdicts")).contains("Missing required parameter");
    }

    @Test
    @DisplayName("parses card options")
    void parsesCardOptions() {
        cli.parseArgs("cards", "-n", "25", "--dry-run");
        CardsCommand cards = cli.getSubcommands().get("cards").getCommand();

        assertThat(cards.limit).isEqualTo(25);
        assertThat(cards.dryRun).isTrue();
    }

    @Test
    @DisplayName("parses compound thresholds")
    void parsesCompoundOptions() {
        cli.parseArgs("compounds", "-d", "12", "-a", "4.5");
        CompoundsCommand compounds = cli.getSubcommands().get("compounds").getCommand();

        assertThat(compounds.minDocuments).isEqualTo(12);
        assertThat(compounds.minAssociation).isEqualTo(4.5);
    }

    @Test
    @DisplayName("offers help")
    void offersHelp() {
        assertThat(cli.execute("--help")).isZero();
    }
}
