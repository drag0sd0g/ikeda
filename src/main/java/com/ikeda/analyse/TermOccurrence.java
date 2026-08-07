package com.ikeda.analyse;

/**
 * One content word at one position in a sentence.
 *
 * <p>Deliberately free of Sudachi types: it is what crosses the boundary into
 * the store, so persistence can be tested without loading a 70MB dictionary.
 *
 * @param key      Sudachi normalised form — the identity of the word across the corpus
 * @param surface  the form as it appears in this sentence
 * @param reading  katakana reading; conversion to hiragana is a card-rendering concern
 * @param pos      primary part of speech
 * @param position character offset within the sentence
 */
public record TermOccurrence(
        String key,
        String surface,
        String reading,
        String pos,
        int position) {
}
