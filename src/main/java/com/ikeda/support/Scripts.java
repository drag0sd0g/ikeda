package com.ikeda.support;

/**
 * Japanese script tests.
 */
public final class Scripts {

    private static final char KANJI_START = '一';
    private static final char KANJI_END = '鿿';

    private Scripts() {
    }

    /**
     * True when the text contains at least one kanji.
     *
     * <p>Used to decide whether a term is worth proposing. Terms written entirely
     * in kana fail for two different reasons, both measured:
     *
     * <ul>
     *   <li><b>Katakana</b> is overwhelmingly English loanwords — ロボティクス,
     *       エンゲージメント, パンデミック. All eleven that reached review were
     *       already known, because an English speaker reads them for free. They
     *       are 13% of the candidate pool and, being recent borrowings, rank as
     *       very rare in a 2015 baseline, so they would otherwise dominate the
     *       top of the ranking.
     *   <li><b>Hiragana</b> content words here are grammatical scaffolding that
     *       the tokeniser labels as nouns or verbs — こと, よる, つく, うち. Only
     *       twelve exist in the whole pool, and their baseline ranks are wildly
     *       wrong because the reference corpus canonicalises them to kanji
     *       (こと to 事), making them look among the rarest words in Japanese.
     * </ul>
     *
     * <p>Mixed forms keep their kanji and so survive: 働き甲斐, 洗い替え,
     * サステナビリティ経営.
     */
    public static boolean containsKanji(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= KANJI_START && c <= KANJI_END) {
                return true;
            }
        }
        return false;
    }
}
