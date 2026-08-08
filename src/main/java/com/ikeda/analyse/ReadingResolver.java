package com.ikeda.analyse;

import com.worksap.nlp.sudachi.Morpheme;
import com.worksap.nlp.sudachi.Tokenizer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ReadingResolver {
    private final Tokenizer tokenizer;
    private final Map<String, String> cache = new HashMap<>();

    public ReadingResolver(Tokenizer tokenizer) {
        this.tokenizer = tokenizer;
    }

    public String readingOf(Morpheme morpheme) {
        String key = morpheme.normalizedForm();
        if (key.equals(morpheme.surface())) {
            return morpheme.readingForm();
        }
        return cache.computeIfAbsent(key, this::readingOfForm);
    }

    public String readingOfForm(String form) {
        StringBuilder reading = new StringBuilder();
        for (List<Morpheme> sentence : tokenizer.tokenizeSentences(Tokenizer.SplitMode.C, form)) {
            for (Morpheme m : sentence) {
                reading.append(m.readingForm());
            }
        }

        return reading.isEmpty() ? form : reading.toString();
    }
}
