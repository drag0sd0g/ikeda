package com.ikeda.card;

import java.util.LinkedHashMap;
import java.util.Map;

public record Card(
        String expression,
        String reading,
        String meaning,
        String example,
        String source,
        String docId,
        long baselineRank,
        long documentFrequency) {

    public static final String DECK = "金融::有報";
    public static final String NOTE_TYPE = "Ikeda Financial Japanese";

    public static final String FIELD_EXPRESSION = "Expression";
    public static final String FIELD_READING = "Reading";
    public static final String FIELD_MEANING = "Meaning";
    public static final String FIELD_EXAMPLE = "Example";
    public static final String FIELD_SOURCE = "ExampleSource";
    public static final String FIELD_DOC_ID = "DocID";
    public static final String FIELD_RANK = "BaselineRank";
    public static final String FIELD_DOCUMENT_FREQUENCY = "DocumentFrequency";

    public Map<String, String> fields() {
        var fields = new LinkedHashMap<String, String>();
        fields.put(FIELD_EXPRESSION, expression);
        fields.put(FIELD_READING, reading);
        fields.put(FIELD_MEANING, meaning);
        fields.put(FIELD_EXAMPLE, example);
        fields.put(FIELD_SOURCE, source);
        fields.put(FIELD_DOC_ID, docId);
        fields.put(FIELD_RANK, baselineRank > 0 ? String.valueOf(baselineRank) : "");
        fields.put(FIELD_DOCUMENT_FREQUENCY, String.valueOf(documentFrequency));
        return fields;
    }
}
