package com.ikeda.support;

public final class Scripts {
    private static final char KANJI_START = '一';
    private static final char KANJI_END = '鿿';

    private Scripts() {
    }

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
