package com.ikeda;

import com.worksap.nlp.sudachi.Config;
import com.worksap.nlp.sudachi.Dictionary;
import com.worksap.nlp.sudachi.DictionaryFactory;
import com.worksap.nlp.sudachi.Morpheme;
import com.worksap.nlp.sudachi.Tokenizer;

import java.nio.file.Path;
import java.util.stream.Collectors;

public class Main {

    private static final String SAMPLE = "将来の課税所得が生じる蓋然性を勘案して、繰延税金資産の回収可能性を判断しております。";

    static void main() throws Exception {
        Config config = Config.defaultConfig().systemDictionary(Path.of("dict/system_core.dic"));

        try(Dictionary dictionary = new DictionaryFactory().create(config)) {
            Tokenizer tokenizer = dictionary.create();

            for(Morpheme m : tokenizer.tokenize(Tokenizer.SplitMode.C, SAMPLE)){
                var parts = m.split(Tokenizer.SplitMode.A);
                var decomposition = parts.size() > 1 ? parts.stream().map(Morpheme::surface).collect(Collectors.joining(" + ")) : "";
                System.out.printf("%s\t%s\t%s\t%s\t%s%n",
                        m.surface(),
                        m.normalizedForm(),
                        m.readingForm(),
                        m.partOfSpeech().getFirst(),
                        decomposition);
            }
        }
    }
}