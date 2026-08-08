# Ikeda — Technical Design Document

**Status:** Living document · **Last updated:** 2026-08-08

---

## 1. Problem

An advanced learner of Japanese working in finance has no tool that answers the question that matters: *given everything I already know, which words would most improve my comprehension of the documents I read at work?*

The existing ecosystem solves adjacent problems. Popup dictionaries serve reactive lookup. Media-mining tools rank vocabulary by frequency in entertainment. None operate over Japanese financial disclosure, and none prioritise against a particular learner's existing collection.

The gap is **prioritisation**, not lookup. A single 有価証券報告書 contains a few thousand lemmas a fluent non-native reader does not fully own. Which two hundred are worth the effort?

Ikeda mines Japanese regulatory filings and produces a ranked, deduplicated, context-attached vocabulary queue delivered into Anki.

---

## 2. Goals and non-goals

### Goals

| ID | Goal |
|----|------|
| G1 | Rank domain vocabulary by acquisition yield, not raw frequency |
| G2 | Never propose a term the learner has already confirmed as known |
| G3 | Every generated card carries an authentic example sentence and a traceable source |
| G4 | Report comprehension coverage over the corpus, and its change over time |
| G5 | Run as a single local process with no server dependencies |

### Non-goals

- A popup dictionary — that problem is well served elsewhere
- A spaced-repetition scheduler — Anki with FSRS does this well
- Translation, summarisation, or document question-answering
- General-purpose Japanese learning — this targets one register deliberately
- Multi-user or hosted operation — single-user, local-first

---

## 3. Success criteria

The original criterion was *≥70% of the top 50 candidates judged worth learning*. It was written before any data existed and has been **revised**, because measurement showed it asks the tool to do something structurally impossible: predict one person's vocabulary from population statistics. The residual information exists only in the learner's head, and the review loop is the mechanism for extracting it.

The criteria are now:

| Metric | Target | Measured |
|--------|--------|----------|
| Ranking lift over unranked baseline | ≥ 1.5× | **2.3×** (22% → 60% at top 30) |
| Reviews per accepted card | < 3 | **1.7** at top 30 |
| Duplicate rate | 0 candidates already confirmed known | 0 |
| Coverage lift | Rising known-token ratio on held-out filings | not yet measured |

Lift is the primary metric. If the ranked list is no better than random, no amount of downstream engineering rescues the project.

---

## 4. Architecture

A single Java process writing to an embedded database. No message broker, no service split, no vector store.

```
EDINET API v2
      │
      ▼
 ┌─────────────┐   ┌──────────────┐   ┌─────────────┐   ┌──────────────┐
 │   Ingest    │──▶│   Analyse    │──▶│    Rank     │──▶│    Review    │
 │  fetch,     │   │  segment,    │   │  baseline   │   │  batch out,  │
 │  extract    │   │  tokenise    │   │  rarity,    │   │  verdicts    │
 │  narrative  │   │  dedupe      │   │  known-set  │   │  back in     │
 └─────────────┘   └──────────────┘   └─────────────┘   └──────────────┘
        │                 │                  │                  │
        └─────────────────┴──────────────────┴──────────────────┘
                                 │
                          SQLite (local file)
                                 │
                    ┌────────────┴────────────┐
                    ▼                         ▼
             BCCWJ baseline            Anki (AnkiConnect)
                                       known-set in, cards out
```

**Why SQLite.** The workload is a few hundred filings and low-millions of token occurrences — comfortably within range, zero operational effort, and a fast feedback loop. Anki's own collection is a SQLite file, so both sides of the project use one driver. A server database becomes the right answer only if more than one process needs concurrent access.

**Package layout.** `com.ikeda.ingest` fetches and extracts, `analyse` segments and tokenises, `rank` holds the baseline, `anki` reads the collection, `review` is the sheet format, `store` is persistence, `support` is shared text utilities.

**Commands.** `ingest`, `anki`, `sample`, `export`, `verdicts`, `status`.

---

## 5. Pipeline

| Stage | Input | Output | Notes |
|-------|-------|--------|-------|
| 1. List | date | docIDs | `docTypeCode=120`, `ordinanceCode=010`, `formCode=030000` |
| 2. Fetch | docID | CSV bundle | `type=5`, one request every four seconds |
| 3. Extract | CSV bundle | narrative blocks | `要素ID` containing `TextBlock`, `jpcrp` files only |
| 4. Segment | block | sentences | Sudachi, then the §5.1 prose filter, then dedupe |
| 5. Tokenise | sentence | terms | Sudachi mode C; readings resolved per §5.2 |
| 6. Promote | terms | candidates | filters in §5.3 |
| 7. Rank | candidates | ordered batch | baseline rarity, §5.4 |
| 8. Review | batch | verdicts | tab-separated round trip |

### 5.1 Prose selection

A filing is roughly one quarter financial tables. EDINET's conversion flattens them into the same `TextBlock` values as narrative, with **all markup and cell boundaries already removed**, so structure cannot be recovered:

```
売上高（千円）3,054,7143,364,9353,293,3673,797,3743,571,516経常損失（△）（千円）△936,011…
```

Detection and discard is therefore the only option. The rule:

```
keep a sentence iff  text ends with 。  and  15 ≤ length ≤ 200
```

Tables have no sentence terminators because they are not sentences. This is a structural property of the data rather than a tuned heuristic, and it is a rule card generation needs anyway. Prose retention is stable at 65–69% of characters across filings; the remainder is headings, fragments and table residue.

**Block-level genre filtering was evaluated and rejected.** Element IDs describe where content lives, not what it is: `IssuedSharesTotalNumberOfSharesEtcTextBlock` reads like a share table, and in a company with preferred shares it holds pages of legal prose — 優先配当金, 償還請求日, 転換価額, 比例按分. A hiragana-ratio threshold separated cleanly at block level but not at sentence level, where legitimate prose runs as low as 6% hiragana.

**Deduplication is required.** Between 15% and 23% of the sentences in a single filing are exact duplicates, because consolidated (連結) and non-consolidated (個別) contexts repeat the same text. Removed in-process, with `UNIQUE(doc_id, text)` as a backstop. Identical sentences in *different* filings are kept deliberately: document frequency must count each filing.

### 5.2 Readings

`Morpheme.readingForm()` returns the reading of the **surface**, so an inflected word yields a reading that does not belong to the form stored as the term key: 晒される is keyed 晒す but reads サラサ. `ReadingResolver` re-tokenises the dictionary form and caches the result, because a few thousand lemmas repeat across 115,000 sentences.

### 5.3 Candidate filters

A term is promoted to candidate only if all hold:

| Filter | Rationale |
|---|---|
| Content part of speech | 名詞, 動詞, 形容詞, 副詞 |
| Length ≥ 2 characters | Single characters are fragments |
| **Contains at least one kanji** | See below |
| Document frequency ≥ 9 (5% of corpus) | Below this a term is one company's jargon — removes ~76% of vocabulary |
| Not in `known_lemma` | Never propose a confirmed known word |

**The kanji requirement** covers two separately measured problems:

- **Katakana** is overwhelmingly English loanwords — ロボティクス, エンゲージメント, パンデミック. All eleven that reached review were already known, because an English speaker reads them for free. They are 13% of the pool, and being recent borrowings they rank as very rare in a 2015 baseline, so they otherwise dominate the top of the ranking.
- **Kana-only content words** are grammatical scaffolding the tokeniser labels as nouns or verbs — こと, よる, つく, うち. Only twelve exist in the pool, and their baseline ranks are wildly wrong because the reference corpus canonicalises them to kanji (こと to 事), making them look among the rarest words in Japanese.

Stored as `term.has_kanji` at ingestion rather than tested at query time, because SQLite has no character-class matching for CJK.

### 5.4 Ranking

**Candidates are ordered by rarity in general written Japanese**, rarest first, using the BCCWJ short-unit frequency list. Ties break on corpus frequency.

This is the only feature that survived testing. Measured against 150 manually judged words:

| Feature | AUC | Outcome |
|---|---|---|
| **Baseline rarity** | **0.730** | **Adopted** |
| Corpus frequency | 0.615 | Too weak |
| Document frequency | 0.599 | Too weak |
| Formal/casual register ratio | 0.555 | Noise |
| Word length | 0.414 | Weak, and *inverted* |
| Anki membership | — | Not usable alone; see §7 |

The interpretation is that a learner who acquired Japanese by living in it has gaps precisely where a word rarely appears outside professional writing. Word length running backwards is the same effect: long compounds like 非正規雇用労働者 are transparent from their parts, while opaque two-character compounds like 改廃 and 業態 are not.

**Terms absent from the baseline are ranked last, not first.** 23% of candidates cannot be looked up, because they are compounds the baseline tokenises into shorter units — 公認会計士, 経常利益, 連結損益計算書. Those absent words proved **74% already known**, so treating absence as maximal rarity would put the worst candidates at the top.

**Matching on reading as well as written form was tested and rejected.** It fixes the orthographic-variant cases (こと from rank 131,323 to 20) but collapses homophones, falsely demoting genuinely rare words. AUC fell from 0.759 to 0.717.

### 5.5 Example sentences

Each candidate carries the shortest sentence between 20 and 80 characters containing it — short enough to read at a glance, long enough for context, and most likely to stand alone. This is a placeholder for proper i+1 selection, which should additionally require at most one *other* unknown term and reject sentences opening with anaphora (当該, 上記, 同).

---

## 6. Data model

```
filing(doc_id PK, edinet_code, filer_name, doc_type_code,
       ordinance_code, form_code, submit_date_time, ingested_at)

block(id PK, doc_id FK, seq, element_id, text)
      -- raw narrative retained so segmentation rules can change and be
      -- replayed without re-fetching at four seconds per filing

sentence(id PK, doc_id FK, block_id FK, seq, text, char_len, token_count,
         UNIQUE(doc_id, text))

term(id PK, key UNIQUE, surface, reading, pos, has_kanji)

occurrence(id PK, term_id FK, sentence_id FK, doc_id, position)
      -- doc_id denormalised: document frequency is COUNT(DISTINCT doc_id)
      -- grouped by term, and that runs on every ranking pass

known_lemma(lemma PK, source, first_seen)

candidate(term_id PK, corpus_frequency, document_frequency, bccwj_rank,
          example_sentence_id, status, decided_at)
```

`candidate.status` records the manual verdict — `PENDING`, `KNOWN`, `WORTH_LEARNING`, `NOT_WORTH_LEARNING`. Three outcomes rather than two because "I already know it" and "not worth a card" mean different things: the first says the known-set model is wrong, the second says the ranking is. Re-populating candidates deliberately leaves verdicts untouched.

**Schema changes** are handled by adding columns in place at startup. Anything more involved should be a delete-and-re-ingest, since the corpus is always rebuildable.

---

## 7. Anki integration

### Reading the known set

The whole collection is treated as known, on the owner's instruction: the review metadata spans several backups and accounts over years, so card maturity says nothing reliable about whether a word was learned.

Headwords come from `TargetKanji` where present, otherwise `Expression` — order matters, because some note types keep the headword in `TargetKanji` and a full example sentence in `Expression`. Comma-separated variants are split; anything containing spaces or longer than 12 characters is a sentence or a grammar pattern, not a word.

This yields ~5,700 lemmas and removes 16% of the candidate pool. **It is a reliable but narrow filter**: most of what an advanced learner knows was never carded, because it was too basic to need a card. Before the owner clarified that everything carded is known, the measurement suggested Anki membership was an *anti*-signal — words in the collection were more likely to be judged worth learning, since they were carded precisely because they were hard.

Verdicts of `KNOWN` are promoted into `known_lemma` with `source='review'`. This is what closes the gap no ranking can close: the known set grows every session and words never resurface.

### Writing cards

Not yet built. When it is:

**Deck:** a new tree, isolated from existing collections — `金融`, `金融::有報`, `金融::適時開示`. FSRS optimises parameters per preset, and opaque financial compounds have a different difficulty profile from exam vocabulary; mixing them fits neither. Isolation also allows the whole experiment to be deleted without touching years of history.

**Note type:** dedicated, not `Basic`. Fields: Expression, Reading, Meaning, Example, ExampleSource, DocID, plus BaselineRank and MinedAt. The metadata cost nothing to store and cannot be backfilled; they are what will eventually allow *does baseline rarity predict retention?* to be answered against the review log.

**Duplicate handling:** deduplication happens upstream via `known_lemma`; Anki's own check is a backstop. Configure `duplicateScopeOptions.checkAllModels` — the default checks only notes of the same type, so a new note type would not detect a word already present as `Basic`.

**JSON construction** must use a real serialiser. Filing prose contains 「」, quotes and backslashes that break string interpolation.

---

## 8. External dependencies

| Dependency | Version | Licence / constraint |
|------------|---------|---------------------|
| Java | 25 LTS | — |
| Gradle | 9.6 | 9.1+ required for JDK 25 |
| Sudachi | 0.7.5 | Apache 2.0 |
| SudachiDict | core | Apache 2.0; ~70MB, gitignored |
| Jackson | 2.19.0 | Apache 2.0 |
| Commons CSV | 1.14.1 | Apache 2.0 |
| SQLite JDBC | 3.49.1.0 | Apache 2.0 |
| EDINET API | v2 | Free key; **3–5s between requests** |
| BCCWJ frequency list | SUW v1.0 | NINJAL, free for research and education — **not redistributable**; fetched separately, gitignored |
| AnkiConnect | v6 | Local add-on, read-only usage |

Corpus data, the dictionary and the baseline are never committed. Test fixtures may contain small excerpts of EDINET filings, which are public disclosure.

---

## 9. Status

### Built

- EDINET ingestion, genre-filtered, resumable, rate-limited
- Narrative extraction and prose selection
- Sentence segmentation, tokenisation, per-filing deduplication, reading resolution
- Corpus persistence — 181 filings, 115,479 sentences, 20,769 terms, 2,468,430 occurrences
- Known set from Anki — ~5,700 lemmas
- Baseline ranking — 3,382 candidates, 2,158 scored
- Review round trip with persisted verdicts

### Not built

**Compound reconstruction.** Sudachi mode C merges only compounds attested in its dictionary: 課税所得 and 蓋然性 merge, 繰延税金資産 fragments into 繰延 / 税金 / 資産. The fragments are visible in the candidate pool — 貸し倒れ and 繰り延べ both appear as standalone terms.

The approach: take maximal runs of consecutive 名詞 tokens excluding 数詞, 代名詞 and 固有名詞; build the candidate from `surface()`, never `normalizedForm()`, because normalisation rewrites 繰延 to 繰り延べ and would produce the non-word 繰り延べ税金資産; accept a run when document frequency and adjacent-pair association both clear a floor; feed accepted compounds into a Sudachi user dictionary so later passes tokenise them atomically. Thresholds are corpus-dependent and cannot be set from a single document.

This should also improve ranking coverage, since many of the 23% of candidates absent from the baseline are exactly these compounds.

**Card generation.** i+1 example selection, glosses, and the AnkiConnect write path described in §7.

**Coverage measurement.** G4 is unimplemented: what fraction of tokens in a held-out filing does the learner know, and how does it move.

---

## 10. Observability and testing

**Stage counters are mandatory.** Every stage logs items in, items out, and the reason for the difference. This is not future-proofing: the first integration attempt returned zero narrative blocks with no indication of which assumption had failed, and the counters diagnosed it in a single run — after three plausible hypotheses had all proved wrong.

**Testing:**

- Parsing is exercised against zip bundles built in-test — UTF-16, quoted fields with embedded newlines, audit reports to exclude
- The prose filter is tested on real sentences and real flattened tables taken from filings
- Segmentation and reading resolution run against the real Sudachi tokeniser, skipping cleanly when the dictionary is absent
- Persistence runs against an in-memory database: atomicity, resumability, known-set exclusion, verdict preservation
- The Anki transport is tested against a real in-process HTTP server, not a mocked client
- Schema migration is tested by opening databases built to older shapes

---

## 11. Empirical findings

Recorded because they are design inputs, not incidental observations.

1. **`docTypeCode=120` mixes three document genres.** `jpcrp030000` is ordinary corporate 有価証券報告書; `jpsps070000` is 特定有価証券報告書 for investment trusts, structured differently; `jpaud` is the audit report bundled with every filing and near-identical across companies. Genre is filterable before download via `ordinanceCode` and `formCode`, saving a four-second fetch per unwanted document.
2. **TextBlock values contain no markup.** Tables arrive already flattened; structure cannot be recovered, only detected.
3. **Sentence terminators separate prose from tables almost perfectly** — 65–69% of prose characters retained, 89–97% of table characters discarded, with no tuned constant.
4. **Element IDs are not a reliable genre signal.** The same element holds a table in one filer and legal prose in another.
5. **Sudachi mode C merges only dictionary-attested compounds**, and `normalizedForm()` is unsafe for reconstructing them.
6. **Sentences repeat within a filing**, 15–23% of them, from 連結/個別 context pairs.
7. **Document frequency does not predict what the learner does not know.** Judged across five frequency bands, the worth-learning rate was 17%, 37%, 13%, 43%, 33% — flat within sampling noise. Unknown words are scattered evenly across the frequency range, including words appearing in 170+ of 181 filings.
8. **Nothing in the dispersion-filtered pool was junk.** Of 150 reviewed words, zero were judged "unknown but not worth learning". The `df ≥ 9` floor is doing its job, arguably conservatively.
9. **Baseline rarity is the only working predictor**, AUC 0.730. Register, length, corpus frequency and document frequency all failed.
10. **Absence from the baseline is a tokenisation artifact, not rarity** — those words are 74% already known.
11. **Katakana loanwords are free for an English speaker** — 11 of 11 already known.
12. **The learner's Anki collection is a narrow known-set**, covering 16% of the pool, because most of what they know was never carded.
13. **Observability paid for itself immediately** — see §10.

---

## 12. Open questions

| # | Item | Impact | Status |
|---|------|--------|--------|
| 1 | Compound reconstruction unimplemented | High | Open — the largest remaining lever on both ranking coverage and card quality |
| 2 | Some sentences carry a section heading welded to the front (`３【事業の内容】　当社の…`) | Medium | Open. The sentence is valuable; the fix is to strip the `【…】` prefix, not discard the row |
| 3 | A small number of candidates have no example sentence in the 20–80 character window | Low | Open |
| 4 | Coverage metric (G4) unimplemented | Medium | Open |
| 5 | Table-header fragments still reach the ranked list — 役分, 外数, 既発 | Low | Open; compound reconstruction may absorb some |
| 6 | A term key can occur with several readings; one is stored as representative | Medium | Must be resolved before readings reach cards |
| 7 | No continuous integration | Medium | Open |
