package com.ikeda.ingest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class NarrativeExtractorTest {

    private static final String HEADER =
            "要素ID\t項目名\tコンテキストID\t相対年度\t連結・個別\t期間・時点\tユニットID\t単位\t値";

    private static final String RISKS = "jpcrp_cor:BusinessRisksTextBlock";
    private static final String POLICY = "jpcrp_cor:BusinessPolicyTextBlock";

    private final NarrativeExtractor extractor = new NarrativeExtractor("jpcrp");

    @Test
    @DisplayName("extracts TextBlock rows from jpcrp entries")
    void extractsTextBlocks() {
        byte[] zip = bundle(Map.of(
                "XBRL_TO_CSV/jpcrp030000-asr-001_E00001-000.csv",
                csv(row(RISKS, "為替変動のリスクがあります。"))));

        Extraction extraction = extractor.extract(zip);

        assertThat(extraction.blocks())
                .containsExactly(new NarrativeBlock(RISKS, "為替変動のリスクがあります。"));
    }

    @Test
    @DisplayName("skips audit report entries")
    void skipsAuditReports() {
        var entries = new LinkedHashMap<String, String>();
        entries.put("XBRL_TO_CSV/jpaud-aar-cn-001_E00001-000.csv",
                csv(row("jpaud_cor:AuditOpinionTextBlock", "独立監査人の監査報告書")));
        entries.put("XBRL_TO_CSV/jpcrp030000-asr-001_E00001-000.csv",
                csv(row(RISKS, "事業上のリスク")));

        Extraction extraction = extractor.extract(bundle(entries));

        assertThat(extraction.stats().zipEntries()).isEqualTo(2);
        assertThat(extraction.stats().csvFiles()).isEqualTo(1);
        assertThat(extraction.blocks())
                .extracting(NarrativeBlock::elementId)
                .containsExactly(RISKS);
    }

    @Test
    @DisplayName("ignores rows that are not TextBlock elements")
    void ignoresNonTextBlockRows() {
        byte[] zip = bundle(Map.of(
                "XBRL_TO_CSV/jpcrp030000-asr-001_E00001-000.csv",
                csv(row("jpcrp_cor:NetSales", "1000000"),
                    row(RISKS, "リスク情報"))));

        Extraction extraction = extractor.extract(zip);

        assertThat(extraction.stats().rows()).isEqualTo(2);
        assertThat(extraction.stats().textBlockRows()).isEqualTo(1);
        assertThat(extraction.blocks()).hasSize(1);
    }

    @Test
    @DisplayName("strips HTML markup and collapses whitespace")
    void stripsHtml() {
        byte[] zip = bundle(Map.of(
                "XBRL_TO_CSV/jpcrp030000-asr-001_E00001-000.csv",
                csv(row(RISKS, "<p>為替変動</p>&nbsp;<span>リスク</span>"))));

        Extraction extraction = extractor.extract(zip);

        assertThat(extraction.blocks().getFirst().text()).isEqualTo("為替変動 リスク");
    }

    @Test
    @DisplayName("handles quoted values containing newlines")
    void handlesEmbeddedNewlines() {
        byte[] zip = bundle(Map.of(
                "XBRL_TO_CSV/jpcrp030000-asr-001_E00001-000.csv",
                csv(row(RISKS, "第一段落\n第二段落"))));

        Extraction extraction = extractor.extract(zip);

        assertThat(extraction.blocks()).hasSize(1);
        assertThat(extraction.blocks().getFirst().text()).isEqualTo("第一段落 第二段落");
    }

    @Test
    @DisplayName("drops blocks that strip down to nothing")
    void dropsEmptyBlocks() {
        byte[] zip = bundle(Map.of(
                "XBRL_TO_CSV/jpcrp030000-asr-001_E00001-000.csv",
                csv(row(RISKS, "<p></p>"), row(POLICY, "経営方針"))));

        Extraction extraction = extractor.extract(zip);

        assertThat(extraction.stats().textBlockRows()).isEqualTo(2);
        assertThat(extraction.blocks()).hasSize(1);
    }

    @Test
    @DisplayName("keeps table blocks, because they contain prose the sentence filter will recover")
    void keepsTableBlocks() {
        // IssuedShares* reads like a table but holds preferred-share terms in some filers,
        // so extraction must not filter by element ID. See TDD §5.0.
        byte[] zip = bundle(Map.of(
                "XBRL_TO_CSV/jpcrp030000-asr-001_E00001-000.csv",
                csv(row("jpcrp_cor:IssuedSharesTotalNumberOfSharesEtcTextBlock",
                        "種類発行可能株式総数（株）普通株式24,000,000"
                                + "なお、Ａ種優先株式の一部を取得するときは、比例按分により決定する。"))));

        Extraction extraction = extractor.extract(zip);

        assertThat(extraction.blocks()).hasSize(1);
        assertThat(extraction.blocks().getFirst().text()).contains("比例按分");
    }

    // --- fixtures -------------------------------------------------------

    private static String row(String elementId, String value) {
        return "%s\t項目\tctx\t当期\t連結\t期間\t\t\t\"%s\"".formatted(elementId, value);
    }

    private static String csv(String... rows) {
        return HEADER + "\n" + String.join("\n", rows) + "\n";
    }

    /** Builds a zip whose CSV entries are UTF-16LE with a BOM, as EDINET emits them. */
    private static byte[] bundle(Map<String, String> entries) {
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
