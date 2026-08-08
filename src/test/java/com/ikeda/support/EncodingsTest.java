package com.ikeda.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class EncodingsTest {

    private static final String JAPANESE = "要素ID\t値";
    private static final String ASCII = "docID\tvalue";

    enum Encoding {
        UTF_16LE_WITH_BOM(StandardCharsets.UTF_16LE, new byte[]{(byte) 0xFF, (byte) 0xFE}),
        UTF_16BE_WITH_BOM(StandardCharsets.UTF_16BE, new byte[]{(byte) 0xFE, (byte) 0xFF}),
        UTF_16LE_NO_BOM(StandardCharsets.UTF_16LE, new byte[0]),
        UTF_8(StandardCharsets.UTF_8, new byte[0]);

        private final Charset charset;
        private final byte[] bom;

        Encoding(Charset charset, byte[] bom) {
            this.charset = charset;
            this.bom = bom;
        }

        byte[] encode(String text) {
            var out = new ByteArrayOutputStream();
            try {
                out.write(bom);
                out.write(text.getBytes(charset));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return out.toByteArray();
        }
    }

    @ParameterizedTest(name = "{0} round trips Japanese")
    @EnumSource(Encoding.class)
    void decodesJapanese(Encoding encoding) {
        assertThat(Encodings.decode(encoding.encode(JAPANESE))).isEqualTo(JAPANESE);
    }

    @ParameterizedTest(name = "{0} round trips ASCII")
    @EnumSource(Encoding.class)
    void decodesAscii(Encoding encoding) {
        assertThat(Encodings.decode(encoding.encode(ASCII))).isEqualTo(ASCII);
    }

    @Test
    @DisplayName("detects UTF-16LE without a BOM rather than defaulting to big-endian")
    void detectsLittleEndianWithoutBom() {
        assertThat(Encodings.detect(JAPANESE.getBytes(StandardCharsets.UTF_16LE)))
                .isEqualTo(StandardCharsets.UTF_16LE);
        assertThat(Encodings.detect(ASCII.getBytes(StandardCharsets.UTF_16LE)))
                .isEqualTo(StandardCharsets.UTF_16LE);
    }

    @Test
    @DisplayName("tolerates empty and single-byte input")
    void toleratesShortInput() {
        assertThat(Encodings.decode(new byte[0])).isEmpty();
        assertThat(Encodings.decode(new byte[]{65})).isEqualTo("A");
    }
}
