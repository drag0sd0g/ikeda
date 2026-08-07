-- Ikeda corpus store, phase 1.
--
-- Only the tables needed to hold the corpus. The ranking tables described in
-- TDD section 6 (term_stat, known_lemma, candidate) arrive with phase 2, when
-- there is a corpus to rank; creating them empty now would only invite doubt
-- about whether they are populated.
--
-- Statements are separated by semicolons and executed one at a time, so no
-- semicolon may appear inside a literal or a comment.

CREATE TABLE IF NOT EXISTS filing (
    doc_id           TEXT PRIMARY KEY,
    edinet_code      TEXT,
    filer_name       TEXT,
    doc_type_code    TEXT,
    ordinance_code   TEXT,
    form_code        TEXT,
    submit_date_time TEXT,
    ingested_at      TEXT NOT NULL
);

-- Raw narrative sections, retained so segmentation rules can be changed and
-- replayed without re-fetching from EDINET, which is rate limited to one
-- request every four seconds.
CREATE TABLE IF NOT EXISTS block (
    id         INTEGER PRIMARY KEY,
    doc_id     TEXT    NOT NULL REFERENCES filing(doc_id),
    seq        INTEGER NOT NULL,
    element_id TEXT,
    text       TEXT    NOT NULL,
    UNIQUE (doc_id, seq)
);

-- UNIQUE(doc_id, text) is a backstop. Segmenter already removes the 15-28% of
-- sentences duplicated by consolidated and non-consolidated context pairs, so
-- this fires only if that is bypassed. Identical sentences in *different*
-- filings are kept deliberately: document frequency must count each filing.
CREATE TABLE IF NOT EXISTS sentence (
    id          INTEGER PRIMARY KEY,
    doc_id      TEXT    NOT NULL REFERENCES filing(doc_id),
    block_id    INTEGER NOT NULL REFERENCES block(id),
    seq         INTEGER NOT NULL,
    text        TEXT    NOT NULL,
    char_len    INTEGER NOT NULL,
    token_count INTEGER NOT NULL,
    UNIQUE (doc_id, text)
);

-- One row per distinct word across the whole corpus, keyed on the Sudachi
-- normalised form. Surface and reading are representative rather than
-- authoritative: a key can occur with several readings, and phase 3 will need
-- to resolve that before a reading reaches a card.
CREATE TABLE IF NOT EXISTS term (
    id      INTEGER PRIMARY KEY,
    key     TEXT NOT NULL UNIQUE,
    surface TEXT NOT NULL,
    reading TEXT,
    pos     TEXT
);

-- doc_id is denormalised from sentence because document frequency is
-- COUNT(DISTINCT doc_id) grouped by term, and that runs on every ranking pass.
CREATE TABLE IF NOT EXISTS occurrence (
    id          INTEGER PRIMARY KEY,
    term_id     INTEGER NOT NULL REFERENCES term(id),
    sentence_id INTEGER NOT NULL REFERENCES sentence(id),
    doc_id      TEXT    NOT NULL REFERENCES filing(doc_id),
    position    INTEGER NOT NULL
);

-- Covers corpus frequency and document frequency in one index scan.
CREATE INDEX IF NOT EXISTS idx_occurrence_term_doc ON occurrence(term_id, doc_id);

-- Covers "which terms are in this sentence", used by the i+1 example filter.
CREATE INDEX IF NOT EXISTS idx_occurrence_sentence ON occurrence(sentence_id);

CREATE INDEX IF NOT EXISTS idx_sentence_doc ON sentence(doc_id);

CREATE INDEX IF NOT EXISTS idx_block_doc ON block(doc_id);
