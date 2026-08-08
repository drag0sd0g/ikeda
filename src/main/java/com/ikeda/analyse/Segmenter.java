package com.ikeda.analyse;

import com.ikeda.ingest.NarrativeBlock;
import com.worksap.nlp.sudachi.Config;
import com.worksap.nlp.sudachi.Dictionary;
import com.worksap.nlp.sudachi.DictionaryFactory;
import com.worksap.nlp.sudachi.Morpheme;
import com.worksap.nlp.sudachi.Tokenizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Segments narrative blocks into prose sentences, tokenised in Sudachi mode C.
 *
 * <p>Deduplicates within a filing: 15–23% of extracted sentences are exact
 * repeats, because consolidated (連結) and non-consolidated (個別) contexts carry
 * the same text under the same element. Left in place they inflate corpus
 * frequency without touching document frequency, which distorts keyness.
 */
public final class Segmenter implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Segmenter.class);

    private final Dictionary dictionary;
    private final Tokenizer tokenizer;
    private final ProseFilter proseFilter;
    private final ReadingResolver readings;

    public Segmenter(Path systemDictionary, ProseFilter proseFilter) {
        this.proseFilter = proseFilter;
        try {
            this.dictionary = new DictionaryFactory()
                    .create(Config.defaultConfig().systemDictionary(systemDictionary));
            this.tokenizer = dictionary.create();
            this.readings = new ReadingResolver(tokenizer);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot load Sudachi dictionary: " + systemDictionary, e);
        }
    }

    /**
     * One sentence of prose with its morphemes, already tokenised in mode C.
     *
     * <p>Morphemes are retained because phase 2 compound reconstruction needs the
     * token sequence. {@link #analysed()} projects to the Sudachi-free form the
     * store consumes.
     */
    public record Sentence(int blockIndex, int seq, String elementId, String text,
                           List<Morpheme> morphemes) {

        public AnalysedSentence analysed(ReadingResolver readings) {
            List<TermOccurrence> terms = morphemes.stream()
                    .filter(Segmenter::isContentWord)
                    .map(m -> new TermOccurrence(
                            m.normalizedForm(), m.surface(), readings.readingOf(m),
                            m.partOfSpeech().getFirst(), m.begin()))
                    .toList();
            return new AnalysedSentence(blockIndex, seq, elementId, text, morphemes.size(), terms);
        }
    }

    public record SegmentationStats(int blocks, int segments, int prose, int duplicates) {

        public int kept() {
            return prose - duplicates;
        }

        @Override
        public String toString() {
            return "blocks=%d segments=%d prose=%d duplicates=%d kept=%d"
                    .formatted(blocks, segments, prose, duplicates, kept());
        }
    }

    public record Segmentation(List<Sentence> sentences, SegmentationStats stats,
                               ReadingResolver readings) {

        public List<AnalysedSentence> analysed() {
            return sentences.stream().map(s -> s.analysed(readings)).toList();
        }
    }

    /** The resolver this segmenter uses, exposed for repairing stored readings. */
    public ReadingResolver readings() {
        return readings;
    }

    public Segmentation segment(List<NarrativeBlock> blocks) {
        var sentences = new ArrayList<Sentence>();
        var seen = new HashSet<String>();
        int segments = 0;
        int prose = 0;
        int duplicates = 0;

        for (int blockIndex = 0; blockIndex < blocks.size(); blockIndex++) {
            NarrativeBlock block = blocks.get(blockIndex);
            int seq = 0;

            for (var morphemes : tokenizer.tokenizeSentences(Tokenizer.SplitMode.C, block.text())) {
                segments++;
                int currentSeq = seq++;
                String text = morphemes.stream()
                        .map(Morpheme::surface)
                        .collect(Collectors.joining())
                        .strip();

                if (!proseFilter.isProse(text)) {
                    continue;
                }
                prose++;
                if (!seen.add(text)) {
                    duplicates++;
                    continue;
                }
                sentences.add(new Sentence(
                        blockIndex, currentSeq, block.elementId(), text, List.copyOf(morphemes)));
            }
        }

        var stats = new SegmentationStats(blocks.size(), segments, prose, duplicates);
        log.debug("segmentation complete: {}", stats);
        return new Segmentation(List.copyOf(sentences), stats, readings);
    }

    /** Content words only: nouns, verbs, adjectives and adverbs, excluding numerals and pronouns. */
    public static List<Morpheme> contentWords(List<Morpheme> morphemes) {
        return morphemes.stream().filter(Segmenter::isContentWord).toList();
    }

    static final Set<String> CONTENT_POS = Set.of("名詞", "動詞", "形容詞", "副詞");
    static final Set<String> EXCLUDED_SUBPOS = Set.of("数詞", "代名詞", "固有名詞");

    public static boolean isContentWord(Morpheme morpheme) {
        List<String> pos = morpheme.partOfSpeech();
        return CONTENT_POS.contains(pos.getFirst())
                && (pos.size() < 2 || !EXCLUDED_SUBPOS.contains(pos.get(1)));
    }

    @Override
    public void close() {
        try {
            dictionary.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
