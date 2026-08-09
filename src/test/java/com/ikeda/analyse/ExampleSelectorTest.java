package com.ikeda.analyse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ExampleSelectorTest {

    private final ExampleSelector selector = ExampleSelector.forCards();

    private static ExampleSelector.SentenceContext sentence(long id, String text, String... terms) {
        return new ExampleSelector.SentenceContext(id, text, List.of(terms));
    }

    @Test
    @DisplayName("prefers the sentence with fewest other unknown words")
    void prefersFewestUnknowns() {
        var crowded = sentence(1, "余資運用の蓋然性と毀損の懸念があります。",
                "余資", "蓋然性", "毀損", "懸念");
        var clear = sentence(2, "余資の運用について定めております。", "余資", "運用");

        var chosen = selector.select("余資", List.of(crowded, clear), Set.of("運用"));

        assertThat(chosen).map(ExampleSelector.SentenceContext::sentenceId).contains(2L);
    }

    @Test
    @DisplayName("strips a section heading welded to the front")
    void stripsHeadingPrefix() {
        assertThat(ExampleSelector.stripHeading("３【事業の内容】　当社の企業グループは運輸事業です。"))
                .isEqualTo("当社の企業グループは運輸事業です。");
        assertThat(ExampleSelector.stripHeading("（６）【大株主の状況】氏名又は名称です。"))
                .isEqualTo("氏名又は名称です。");
    }

    @Test
    @DisplayName("leaves an ordinary sentence untouched")
    void leavesPlainSentenceAlone() {
        String plain = "当社は公共交通事業を営んでおります。";

        assertThat(ExampleSelector.stripHeading(plain)).isEqualTo(plain);
    }

    @Test
    @DisplayName("rejects a sentence opening with anaphora pointing outside it")
    void rejectsOpeningAnaphora() {
        var dangling = sentence(1, "当該余資については別途定めております。", "余資");

        assertThat(selector.select("余資", List.of(dangling), Set.of())).isEmpty();
    }

    @Test
    @DisplayName("falls back to a crowded sentence rather than leaving a card without an example")
    void prefersCrowdedOverNothing() {
        var crowded = sentence(1, "余資と蓋然性と毀損と懸念の関係を示します。",
                "余資", "蓋然性", "毀損", "懸念", "関係");

        assertThat(selector.select("余資", List.of(crowded), Set.of())).isPresent();
    }

    @Test
    @DisplayName("counts distinct unknown words, ignoring the target")
    void countsDistinctUnknowns() {
        var sentence = sentence(1, "余資の運用は余資運用規程に基づきます。", "余資", "運用", "規程");

        assertThat(ExampleSelector.unknownCount(sentence, "余資", Set.of("運用")))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("rejects sentences outside the length bounds")
    void rejectsOutOfBounds() {
        var tooShort = sentence(1, "余資です。", "余資");
        var tooLong = sentence(2, "余資" + "あ".repeat(100) + "。", "余資");

        assertThat(selector.select("余資", List.of(tooShort, tooLong), Set.of())).isEmpty();
    }

    @Test
    @DisplayName("counts only unknown words other than the target itself")
    void targetDoesNotCountAgainstItself() {
        var sentence = sentence(1, "余資の運用は余資運用規程に基づきます。", "余資", "運用", "規程");

        assertThat(selector.select("余資", List.of(sentence), Set.of("運用", "規程"))).isPresent();
    }

    @Test
    @DisplayName("finds the heading-stripped sentence usable when the raw one was too long")
    void strippingCanRescueASentence() {
        var withHeading = sentence(1,
                "２【沿革】　当社は昭和二十年に設立された会社であります。", "沿革", "設立", "会社");

        var chosen = selector.select("設立", List.of(withHeading), Set.of("会社"));

        assertThat(chosen).map(ExampleSelector.SentenceContext::text)
                .contains("当社は昭和二十年に設立された会社であります。");
    }

    @Test
    @DisplayName("accepts a sentence where the word appears inflected, not in dictionary form")
    void acceptsInflectedOccurrences() {
        var inflected = sentence(1, "信用リスクに晒されており、影響を受ける可能性があります。",
                "晒す", "信用", "影響");

        assertThat(selector.select("晒す", List.of(inflected), Set.of("信用", "影響")))
                .map(ExampleSelector.SentenceContext::sentenceId).contains(1L);
    }

    @Test
    @DisplayName("returns nothing when there are no sentences to choose from")
    void handlesNoCandidates() {
        assertThat(selector.select("余資", List.of(), Set.of())).isEmpty();
    }
}
