package com.ikeda.analyse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "CI", matches = "true")
class DictionaryPresenceTest {

    @Test
    @DisplayName("an automated build must not skip the tokeniser tests")
    void dictionaryIsAvailable() {
        assertThat(Files.exists(Path.of("dict/system_core.dic")))
                .withFailMessage("dict/system_core.dic is missing, so every test that "
                        + "exercises the tokeniser silently skipped")
                .isTrue();
    }
}
