package com.ikeda.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The DDL splitter. Guarded by tests because a semicolon inside a comment once
 * cut the comment in half and left its remainder to be executed as SQL.
 */
class SchemaScriptTest {

    @Test
    @DisplayName("splits statements on semicolons")
    void splitsOnSemicolons() {
        assertThat(CorpusStore.splitStatements("CREATE TABLE a (x INT); CREATE TABLE b (y INT);"))
                .containsExactly("CREATE TABLE a (x INT)", "CREATE TABLE b (y INT)");
    }

    @Test
    @DisplayName("ignores semicolons inside line comments")
    void ignoresSemicolonsInComments() {
        String script = """
                -- one statement follows; then nothing else
                CREATE TABLE a (x INT);
                """;

        assertThat(CorpusStore.splitStatements(script))
                .containsExactly("CREATE TABLE a (x INT)");
    }

    @Test
    @DisplayName("strips trailing comments without losing the statement")
    void stripsTrailingComments() {
        String script = "CREATE TABLE a (x INT);  -- why this table exists; at length\n";

        assertThat(CorpusStore.splitStatements(script))
                .containsExactly("CREATE TABLE a (x INT)");
    }

    @Test
    @DisplayName("drops blank and comment-only content")
    void dropsEmptyStatements() {
        assertThat(CorpusStore.splitStatements("-- just a comment\n\n   \n")).isEmpty();
        assertThat(CorpusStore.splitStatements("")).isEmpty();
    }

    @Test
    @DisplayName("the shipped schema parses into statements, all of them CREATE")
    void shippedSchemaIsWellFormed() throws Exception {
        String schema = new String(
                CorpusStore.class.getResourceAsStream("/schema.sql").readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);

        assertThat(CorpusStore.splitStatements(schema))
                .isNotEmpty()
                .allSatisfy(statement -> assertThat(statement).startsWith("CREATE"));
    }
}
