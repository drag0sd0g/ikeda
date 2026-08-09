package com.ikeda.compound;

import com.ikeda.analyse.PartOfSpeech;
import com.ikeda.support.Scripts;
import com.worksap.nlp.sudachi.Morpheme;
import com.worksap.nlp.sudachi.Tokenizer;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class NounRunDetector {

    private static final Set<String> EXCLUDED_SUBPOS =
            Set.of("数詞", "代名詞", "固有名詞", "非自立可能");

    private final int minArity;
    private final int maxArity;

    public NounRunDetector(int minArity, int maxArity) {
        this.minArity = minArity;
        this.maxArity = maxArity;
    }

    public static NounRunDetector standard() {
        return new NounRunDetector(2, 5);
    }

    public List<CompoundCandidate> detect(List<Morpheme> morphemes) {
        var found = new ArrayList<CompoundCandidate>();
        var run = new ArrayList<Morpheme>();

        for (Morpheme morpheme : morphemes) {
            if (isRunnable(morpheme)) {
                run.add(morpheme);
            } else {
                emit(run, found);
                run.clear();
            }
        }
        emit(run, found);
        return List.copyOf(found);
    }

    private void emit(List<Morpheme> run, List<CompoundCandidate> found) {
        if (run.size() < minArity || run.size() > maxArity) {
            return;
        }
        List<String> parts = run.stream().map(Morpheme::surface).toList();
        List<String> shortUnits = run.stream()
                .flatMap(morpheme -> shortUnitsOf(morpheme).stream())
                .toList();
        found.add(new CompoundCandidate(String.join("", parts), parts, shortUnits));
    }

    private static List<String> shortUnitsOf(Morpheme morpheme) {
        List<Morpheme> split = morpheme.split(Tokenizer.SplitMode.A);
        return split.size() <= 1
                ? List.of(morpheme.normalizedForm())
                : split.stream().map(Morpheme::normalizedForm).toList();
    }

    private static boolean isRunnable(Morpheme morpheme) {
        List<String> pos = morpheme.partOfSpeech();
        if (!PartOfSpeech.NOUN.label().equals(pos.getFirst())) {
            return false;
        }
        if (pos.size() > 1 && EXCLUDED_SUBPOS.contains(pos.get(1))) {
            return false;
        }
        return Scripts.containsKanji(morpheme.surface());
    }
}
