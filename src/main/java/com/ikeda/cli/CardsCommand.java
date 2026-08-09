package com.ikeda.cli;

import com.ikeda.analyse.ExampleSelector;
import com.ikeda.anki.AnkiCardWriter;
import com.ikeda.anki.AnkiGateway;
import com.ikeda.card.Card;
import com.ikeda.gloss.Gloss;
import com.ikeda.gloss.GlossSource;
import com.ikeda.store.CardStore;
import com.ikeda.store.Database;
import com.ikeda.store.SentenceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Command(name = "cards", description = "Build cards for reviewed words and add them to Anki.")
public final class CardsCommand implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(CardsCommand.class);

    @Option(names = {"-n", "--limit"}, description = "Maximum cards to build.")
    int limit = 200;

    @Option(names = "--dry-run", description = "Build cards and report, without writing to Anki.")
    boolean dryRun;

    private static final int COMMON_JAPANESE_WORDS = 15_000;

    private final Workspace workspace;
    private final AnkiGateway gateway;

    public CardsCommand() {
        this(new Workspace(), AnkiGateway.withDefaults());
    }

    CardsCommand(Workspace workspace, AnkiGateway gateway) {
        this.workspace = workspace;
        this.gateway = gateway;
    }

    @Override
    public void run() {
        GlossSource glosses = workspace.glossSource();
        var selector = ExampleSelector.forCards();

        try (Database database = workspace.openDatabase()) {
            var store = new CardStore(database);
            var sentences = new SentenceStore(database);
            Set<String> known = new java.util.HashSet<>(store.knownLemmas());
            known.addAll(workspace.baseline().commonest(COMMON_JAPANESE_WORDS));
            List<CardStore.Pending> pending = store.awaitingExport(limit);

            if (pending.isEmpty()) {
                log.info("no reviewed words awaiting export");
                return;
            }

            var cards = new ArrayList<Card>();
            var exported = new ArrayList<Long>();
            int withoutGloss = 0;
            int withoutExample = 0;

            for (CardStore.Pending word : pending) {
                Optional<Gloss> gloss = glosses.lookup(word.key());
                if (gloss.isEmpty()) {
                    withoutGloss++;
                }
                Optional<ExampleSelector.SentenceContext> example =
                        selectExample(sentences, selector, word, known);
                if (example.isEmpty()) {
                    withoutExample++;
                    continue;
                }
                long sentenceId = example.get().sentenceId();
                cards.add(new Card(
                        word.key(),
                        word.reading(),
                        gloss.map(Gloss::meaningLine).orElse(""),
                        example.get().text(),
                        sentences.sourceOf(sentenceId),
                        sentences.docIdOf(sentenceId),
                        word.bccwjRank(),
                        word.documentFrequency()));
                exported.add(word.termId());
            }

            log.info("built {} cards from {} words ({} without a gloss, {} without an example)",
                    cards.size(), pending.size(), withoutGloss, withoutExample);

            if (dryRun) {
                cards.stream().limit(10).forEach(card ->
                        log.info("  {} [{}] {} — {}", card.expression(), card.reading(),
                                card.meaning(), card.example()));
                return;
            }
            if (!gateway.isAvailable()) {
                throw new IllegalStateException(
                        "AnkiConnect is not responding — is Anki running with the add-on enabled?");
            }

            var writer = new AnkiCardWriter(gateway);
            writer.ensureDeck();
            writer.ensureNoteType();
            writer.add(cards);
            store.markExported(exported);
        }
    }

    private Optional<ExampleSelector.SentenceContext> selectExample(
            SentenceStore sentences, ExampleSelector selector,
            CardStore.Pending word, Set<String> known) {

        List<ExampleSelector.SentenceContext> contexts = sentences.forTerm(word.termId(), 200)
                .stream()
                .map(sentence -> new ExampleSelector.SentenceContext(
                        sentence.sentenceId(), sentence.text(),
                        sentences.termsIn(sentence.sentenceId())))
                .toList();
        return selector.select(word.key(), contexts, known);
    }
}
