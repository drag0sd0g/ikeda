package com.ikeda.analyse;

import com.worksap.nlp.sudachi.Morpheme;
import com.worksap.nlp.sudachi.Tokenizer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves the reading of a term's dictionary form.
 *
 * <p>{@link Morpheme#readingForm()} gives the reading of the <em>surface</em>, so
 * an inflected word yields a reading that does not belong to the form stored as
 * the term key: 晒される is keyed 晒す but reads サラサ, 見て is keyed 見る but
 * reads ミ. Persisting that mismatch would put wrong readings on cards.
 *
 * <p>The fix is to re-tokenise the dictionary form and take its reading. Results
 * are cached, because a corpus of 115,000 sentences repeats the same few thousand
 * lemmas endlessly.
 */
public final class ReadingResolver {

    private final Tokenizer tokenizer;
    private final Map<String, String> cache = new HashMap<>();

    public ReadingResolver(Tokenizer tokenizer) {
        this.tokenizer = tokenizer;
    }

    /**
     * The reading of this morpheme's normalised form.
     *
     * <p>Uninflected words are the common case and are returned directly; only
     * words whose surface differs from their key need a second pass.
     */
    public String readingOf(Morpheme morpheme) {
        String key = morpheme.normalizedForm();
        if (key.equals(morpheme.surface())) {
            return morpheme.readingForm();
        }
        return cache.computeIfAbsent(key, this::readingOfForm);
    }

    /** The reading of a dictionary form given as text, for repairing stored rows. */
    public String readingOfForm(String form) {
        StringBuilder reading = new StringBuilder();
        for (List<Morpheme> sentence : tokenizer.tokenizeSentences(Tokenizer.SplitMode.C, form)) {
            for (Morpheme m : sentence) {
                reading.append(m.readingForm());
            }
        }
        // An empty result means the form did not tokenise; the surface reading is
        // wrong but present, which beats a card with no reading at all.
        return reading.isEmpty() ? form : reading.toString();
    }
}
