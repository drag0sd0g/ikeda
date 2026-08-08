package com.ikeda.testing;

import com.ikeda.analyse.AnalysedSentence;
import com.ikeda.analyse.TermOccurrence;
import com.ikeda.ingest.FilingRef;
import com.ikeda.ingest.NarrativeBlock;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class Fixtures {

    public static final String RISKS_ELEMENT = "jpcrp_cor:BusinessRisksTextBlock";
    public static final String POLICY_ELEMENT = "jpcrp_cor:BusinessPolicyTextBlock";

    private static final String CSV_HEADER =
            "要素ID\t項目名\tコンテキストID\t相対年度\t連結・個別\t期間・時点\tユニットID\t単位\t値";

    private Fixtures() {
    }

    public static FilingRef filing(String docId) {
        return filing(docId, "会社" + docId);
    }

    public static FilingRef filing(String docId, String filerName) {
        return new FilingRef(docId, "E00001", filerName, "120", "010", "030000",
                "2026-06-26 09:00", true);
    }

    public static NarrativeBlock block(String text) {
        return new NarrativeBlock(RISKS_ELEMENT, text);
    }

    public static NarrativeBlock block(String elementId, String text) {
        return new NarrativeBlock(elementId, text);
    }

    public static TermOccurrence term(String key, int position) {
        return new TermOccurrence(key, key, "カナ", "名詞", position);
    }

    public static AnalysedSentence sentence(String text, String... terms) {
        return sentence(0, 0, RISKS_ELEMENT, text, terms);
    }

    public static AnalysedSentence sentence(int blockIndex, int seq, String elementId,
                                            String text, String... terms) {
        List<TermOccurrence> occurrences = IntStream.range(0, terms.length)
                .mapToObj(i -> term(terms[i], i))
                .toList();
        return new AnalysedSentence(blockIndex, seq, elementId, text, text.length(), occurrences);
    }

    public static String csvRow(String elementId, String value) {
        return "%s\t項目\tctx\t当期\t連結\t期間\t\t\t\"%s\"".formatted(elementId, value);
    }

    public static String csv(String... rows) {
        return CSV_HEADER + "\n" + String.join("\n", rows) + "\n";
    }

    public static byte[] zipBundle(Map<String, String> entries) {
        var out = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(out)) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(new byte[]{(byte) 0xFF, (byte) 0xFE});
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_16LE));
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }
}
