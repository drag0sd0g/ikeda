package com.ikeda.compound;

import com.ikeda.analyse.ProseFilter;
import com.ikeda.analyse.Segmenter;
import com.worksap.nlp.sudachi.Morpheme;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIf("dictionaryPresent")
class NounRunDetectorTest {

    private static final Path DICTIONARY = Path.of("dict/system_core.dic");

    static boolean dictionaryPresent() {
        return Files.exists(DICTIONARY);
    }

    private static Segmenter segmenter;
    private final NounRunDetector detector = NounRunDetector.standard();

    @BeforeAll
    static void setUp() {
        segmenter = new Segmenter(DICTIONARY, ProseFilter.CORPUS);
    }

    @AfterAll
    static void tearDown() {
        if (segmenter != null) {
            segmenter.close();
        }
    }

    private List<CompoundCandidate> detect(String text) {
        var found = new ArrayList<CompoundCandidate>();
        for (List<Morpheme> tokens : segmenter.tokenize(text)) {
            found.addAll(detector.detect(tokens));
        }
        return found;
    }

    @Test
    @DisplayName("rebuilds a compound the tokeniser splits into parts")
    void rebuildsSplitCompound() {
        assertThat(detect("繰延税金資産の回収可能性を判断しております。"))
                .extracting(CompoundCandidate::surface)
                .contains("繰延税金資産");
    }

    @Test
    @DisplayName("keeps the parts, so compositionality can be judged later")
    void keepsParts() {
        CompoundCandidate compound = detect("繰延税金資産の回収可能性を判断しております。").stream()
                .filter(c -> c.surface().equals("繰延税金資産"))
                .findFirst().orElseThrow();

        assertThat(compound.parts()).containsExactly("繰延", "税金", "資産");
        assertThat(compound.arity()).isEqualTo(3);
    }

    @Test
    @DisplayName("builds from surface forms, never normalised ones")
    void buildsFromSurfaces() {
        assertThat(detect("繰延税金資産の回収可能性を判断しております。"))
                .extracting(CompoundCandidate::surface)
                .doesNotContain("繰り延べ税金資産");
    }

    @Test
    @DisplayName("stops a run at a particle")
    void stopsAtParticles() {
        assertThat(detect("為替の変動があります。"))
                .extracting(CompoundCandidate::surface)
                .doesNotContain("為替変動");
    }

    @Test
    @DisplayName("ignores kana-only nouns")
    void ignoresKanaOnlyNouns() {
        assertThat(detect("リスクマネジメントを行っております。"))
                .extracting(CompoundCandidate::surface)
                .allSatisfy(surface -> assertThat(surface).matches(".*[一-鿿].*"));
    }

    @Test
    @DisplayName("respects the arity bounds")
    void respectsArityBounds() {
        var narrow = new NounRunDetector(4, 5);
        var found = new ArrayList<CompoundCandidate>();
        for (List<Morpheme> tokens : segmenter.tokenize("繰延税金資産の回収可能性。")) {
            found.addAll(narrow.detect(tokens));
        }
        assertThat(found).extracting(CompoundCandidate::surface).doesNotContain("繰延税金資産");
    }
}
