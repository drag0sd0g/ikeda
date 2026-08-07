package com.ikeda.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class EncodingsTest {

    private static final String SAMPLE = "要素ID\t値";

    @Test
    @DisplayName("decodes UTF-16LE with a BOM and strips the BOM")
    void decodesUtf16LeWithBom() throws IOException {
        byte[] bytes = withPrefix(new byte[]{(byte) 0xFF, (byte) 0xFE},
                SAMPLE.getBytes(StandardCharsets.UTF_16LE));

        assertThat(Encodings.decode(bytes)).isEqualTo(SAMPLE);
    }

    @Test
    @DisplayName("decodes UTF-16BE with a BOM and strips the BOM")
    void decodesUtf16BeWithBom() throws IOException {
        byte[] bytes = withPrefix(new byte[]{(byte) 0xFE, (byte) 0xFF},
                SAMPLE.getBytes(StandardCharsets.UTF_16BE));

        assertThat(Encodings.decode(bytes)).isEqualTo(SAMPLE);
    }

    @Test
    @DisplayName("decodes UTF-16LE without a BOM rather than defaulting to big-endian")
    void decodesUtf16LeWithoutBom() {
        byte[] bytes = SAMPLE.getBytes(StandardCharsets.UTF_16LE);

        assertThat(Encodings.detect(bytes)).isEqualTo(StandardCharsets.UTF_16LE);
        assertThat(Encodings.decode(bytes)).isEqualTo(SAMPLE);
    }

    @Test
    @DisplayName("decodes ASCII UTF-16LE without a BOM, which is also valid UTF-8")
    void decodesAsciiUtf16LeWithoutBom() {
        byte[] bytes = "docID\tvalue".getBytes(StandardCharsets.UTF_16LE);

        assertThat(Encodings.detect(bytes)).isEqualTo(StandardCharsets.UTF_16LE);
        assertThat(Encodings.decode(bytes)).isEqualTo("docID\tvalue");
    }

    @Test
    @DisplayName("falls back to UTF-8")
    void decodesUtf8() {
        byte[] bytes = SAMPLE.getBytes(StandardCharsets.UTF_8);

        assertThat(Encodings.decode(bytes)).isEqualTo(SAMPLE);
    }

    @Test
    @DisplayName("tolerates empty and single-byte input")
    void toleratesShortInput() {
        assertThat(Encodings.decode(new byte[0])).isEmpty();
        assertThat(Encodings.decode(new byte[]{65})).isEqualTo("A");
    }

    private static byte[] withPrefix(byte[] prefix, byte[] body) throws IOException {
        var out = new ByteArrayOutputStream();
        out.write(prefix);
        out.write(body);
        return out.toByteArray();
    }
}
