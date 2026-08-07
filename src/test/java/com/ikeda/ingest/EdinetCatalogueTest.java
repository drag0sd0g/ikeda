package com.ikeda.ingest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EdinetCatalogueTest {

    private static final String LISTING = """
            {
              "results": [
                {
                  "docID": "S100AAAA", "edinetCode": "E00001",
                  "filerName": "株式会社アルファ", "docTypeCode": "120",
                  "ordinanceCode": "010", "formCode": "030000",
                  "submitDateTime": "2026-06-26 09:00", "csvFlag": "1"
                },
                {
                  "docID": "S100BBBB", "edinetCode": "E00002",
                  "filerName": "ベータ投資信託", "docTypeCode": "120",
                  "ordinanceCode": "010", "formCode": "070000",
                  "submitDateTime": "2026-06-26 10:00", "csvFlag": "1"
                },
                {
                  "docID": "S100CCCC", "edinetCode": "E00003",
                  "filerName": "株式会社ガンマ", "docTypeCode": "120",
                  "ordinanceCode": "010", "formCode": "030000",
                  "submitDateTime": "2026-06-26 11:00", "csvFlag": "0"
                },
                {
                  "docID": "S100DDDD", "edinetCode": "E00004",
                  "filerName": "株式会社デルタ", "docTypeCode": "140",
                  "ordinanceCode": "010", "formCode": "030000",
                  "submitDateTime": "2026-06-26 12:00", "csvFlag": "1"
                }
              ]
            }
            """;

    private static final DocumentFilter FILTER = DocumentFilter.CORPORATE_ANNUAL_REPORT;

    @Test
    @DisplayName("parses every listed filing")
    void parsesAllFilings() {
        List<FilingRef> refs = EdinetCatalogue.parse(LISTING.getBytes(StandardCharsets.UTF_8));

        assertThat(refs).hasSize(4);
        assertThat(refs.getFirst().filerName()).isEqualTo("株式会社アルファ");
        assertThat(refs.getFirst().csvAvailable()).isTrue();
    }

    @Test
    @DisplayName("selects only corporate annual reports with CSV available")
    void selectsOnlyCorporateAnnualReports() {
        List<FilingRef> selected =
                EdinetCatalogue.parse(LISTING.getBytes(StandardCharsets.UTF_8)).stream()
                        .filter(FILTER::matches)
                        .toList();

        assertThat(selected)
                .extracting(FilingRef::docId)
                .containsExactly("S100AAAA");
    }

    @Test
    @DisplayName("rejects investment trusts, missing CSV, and other document types")
    void rejectsNonMatchingGenres() {
        List<FilingRef> refs = EdinetCatalogue.parse(LISTING.getBytes(StandardCharsets.UTF_8));

        assertThat(FILTER.matches(refs.get(1))).isFalse();   // formCode 070000
        assertThat(FILTER.matches(refs.get(2))).isFalse();   // csvFlag 0
        assertThat(FILTER.matches(refs.get(3))).isFalse();   // docTypeCode 140
    }
}
