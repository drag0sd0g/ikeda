package com.ikeda.support;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

public final class Encodings {
    private static final char BOM = '\uFEFF';
    private static final int SAMPLE_BYTES = 512;

    private Encodings() {
    }

    public static String decode(byte[] bytes) {
        String decoded = new String(bytes, detect(bytes));
        return decoded.isEmpty() || decoded.charAt(0) != BOM ? decoded : decoded.substring(1);
    }

    public static Charset detect(byte[] bytes) {
        if (bytes.length >= 2) {
            int first = bytes[0] & 0xFF;
            int second = bytes[1] & 0xFF;

            if (first == 0xFF && second == 0xFE) {
                return StandardCharsets.UTF_16LE;
            }
            if (first == 0xFE && second == 0xFF) {
                return StandardCharsets.UTF_16BE;
            }
        }
        if (isNulPadded(bytes) || !isValidUtf8(bytes)) {
            return StandardCharsets.UTF_16LE;
        }
        return StandardCharsets.UTF_8;
    }

    private static boolean isNulPadded(byte[] bytes) {
        int sampled = 0;
        int nuls = 0;
        for (int i = 1; i < Math.min(bytes.length, SAMPLE_BYTES); i += 2) {
            sampled++;
            if (bytes[i] == 0) {
                nuls++;
            }
        }
        return sampled > 0 && nuls * 2 >= sampled;
    }

    private static boolean isValidUtf8(byte[] bytes) {
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            decoder.decode(ByteBuffer.wrap(bytes));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }
}
