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

CREATE TABLE IF NOT EXISTS block (
    id         INTEGER PRIMARY KEY,
    doc_id     TEXT    NOT NULL REFERENCES filing(doc_id),
    seq        INTEGER NOT NULL,
    element_id TEXT,
    text       TEXT    NOT NULL,
    UNIQUE (doc_id, seq)
);

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

CREATE TABLE IF NOT EXISTS term (
    id        INTEGER PRIMARY KEY,
    key       TEXT NOT NULL UNIQUE,
    surface   TEXT NOT NULL,
    reading   TEXT,
    pos       TEXT,
    has_kanji INTEGER NOT NULL DEFAULT 1
);

CREATE TABLE IF NOT EXISTS occurrence (
    id          INTEGER PRIMARY KEY,
    term_id     INTEGER NOT NULL REFERENCES term(id),
    sentence_id INTEGER NOT NULL REFERENCES sentence(id),
    doc_id      TEXT    NOT NULL REFERENCES filing(doc_id),
    position    INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS known_lemma (
    lemma      TEXT PRIMARY KEY,
    source     TEXT NOT NULL,
    first_seen TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_known_lemma_source ON known_lemma(source);

CREATE TABLE IF NOT EXISTS candidate (
    term_id             INTEGER PRIMARY KEY REFERENCES term(id),
    corpus_frequency    INTEGER NOT NULL,
    document_frequency  INTEGER NOT NULL,
    bccwj_rank          INTEGER,
    example_sentence_id INTEGER REFERENCES sentence(id),
    status              TEXT    NOT NULL DEFAULT 'PENDING',
    decided_at          TEXT
);

CREATE INDEX IF NOT EXISTS idx_candidate_status ON candidate(status);
CREATE INDEX IF NOT EXISTS idx_candidate_rank ON candidate(bccwj_rank);

CREATE INDEX IF NOT EXISTS idx_occurrence_term_doc ON occurrence(term_id, doc_id);

CREATE INDEX IF NOT EXISTS idx_occurrence_sentence ON occurrence(sentence_id);

CREATE INDEX IF NOT EXISTS idx_sentence_doc ON sentence(doc_id);

CREATE INDEX IF NOT EXISTS idx_block_doc ON block(doc_id);
