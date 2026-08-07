# Ikeda — Technical Design Document

**Status:** Draft · **Last updated:** 2026-08-02

---

## 1. Problem

Advanced learners of Japanese working in finance have no tool that answers the question that actually matters: *given everything I already know, which words would most improve my comprehension of the documents I read at work?*

The existing ecosystem solves adjacent problems. Yomitan and Jiten serve reactive lookup. jpdb ranks vocabulary by frequency in entertainment media. asbplayer and mokuro mine subtitles and manga. None of them operate over Japanese financial disclosure, and none of them prioritise against a learner's existing collection.

The gap is **prioritisation**, not lookup. A single 有価証券報告書 contains a few thousand lemmas a fluent non-native reader does not fully own. Which two hundred are worth the effort?

Ikeda mines Japanese regulatory filings and produces a ranked, deduplicated, context-attached vocabulary queue delivered into Anki.

---

## 2. Goals and non-goals

### Goals

| ID | Goal |
|----|------|
| G1 | Rank domain vocabulary by acquisition yield, not raw frequency |
| G2 | Never propose a term already present in the user's Anki collection |
| G3 | Every generated card carries an authentic example sentence and a traceable source |
| G4 | Report comprehension coverage over the corpus, and its change over time |
| G5 | Phase 1 runs as a single local process with no server dependencies |

### Non-goals

- A popup dictionary — Yomitan does this well
- A spaced-repetition scheduler — Anki with FSRS does this well
- Translation, summarisation, or document QA — that is FinDocDRAG's problem, not this one
- General-purpose Japanese learning — this targets one register deliberately
- Multi-user or hosted operation — single-user, local-first

---

## 3. Success criteria

The project is working if, and only if:

| Metric | Target | How measured |
|--------|--------|--------------|
| Candidate precision | ≥ 70% of the top 50 candidates judged worth learning | Manual review, recorded verdicts |
| Card quality | < 20% of generated cards need manual edit before study | Edit count during first review pass |
| Duplicate rate | 0 candidates already in the collection | Assertion against known-set |
| Coverage lift | Measurable rise in known-token ratio on held-out filings | Coverage metric, before/after |

Candidate precision is the primary metric. If the top 50 is mostly noise, no amount of downstream engineering rescues the project — the ranking is the product.

---

## 4. Architecture

Phase 1 is a single Java process writing to an embedded database. There is no Kafka, no service split, and no vector store until the ranking is demonstrably good.

```
EDINET API v2
      │
      ▼
 ┌─────────────┐   ┌──────────────┐   ┌─────────────┐   ┌──────────────┐
 │   Ingest    │──▶│   Analyse    │──▶│    Rank     │──▶│   Generate   │
 │  fetch,     │   │  segment,    │   │  keyness,   │   │  sentence,   │
 │  extract    │   │  tokenise,   │   │  known-set, │   │  gloss,      │
 │  narrative  │   │  compounds   │   │  scoring    │   │  push        │
 └─────────────┘   └──────────────┘   └─────────────┘   └──────────────┘
        │                 │                  │                  │
        └─────────────────┴──────────────────┴──────────────────┘
                                 │
                          SQLite (local store)
                                 │
                    ┌────────────┴────────────┐
                    ▼                         ▼
              BCCWJ baseline          Anki (AnkiConnect)
                                      known-set in, cards out
```

**Why SQLite in phase 1.** The workload is a few hundred filings and low-millions of token occurrences. That is comfortably within SQLite's range, costs zero operational effort, and keeps the feedback loop fast. The migration trigger to PostgreSQL is explicit: **when embeddings enter the pipeline** (phase 4, semantic deduplication), because that needs pgvector.

**Module layout.** Single Gradle module, packages by pipeline stage: `com.ikeda.ingest`, `com.ikeda.analyse`, `com.ikeda.rank`, `com.ikeda.generate`, `com.ikeda.store`. Split into modules only if and when services split.

---

## 5. Pipeline stages

| Stage | Input | Output | Notes |
|-------|-------|--------|-------|
| 1. List | date | docIDs | `docTypeCode=120`, `csvFlag=1` |
| 2. Fetch | docID | CSV bundle | `type=5`, rate-limited |
| 3. Extract | CSV bundle | narrative blocks | `要素ID` containing `TextBlock`; no genre filtering — see §5.0 |
| 4. Segment | block | sentences | `Tokenizer.tokenizeSentences`, then the §5.0 sentence filter |
| 5. Tokenise | sentence | morphemes | Sudachi mode C, mode A for parts |
| 6. Compound | morpheme run | compound terms | noun-run detection + association scoring |
| 7. Baseline | term | keyness | log-likelihood vs BCCWJ |
| 8. Known-set | Anki | known lemmas | AnkiConnect, normalised |
| 9. Rank | terms | candidates | filters + score |
| 10. Select | candidate | example sentence | i+1 constraint |
| 11. Gloss | candidate | meaning | JMdict, LLM fallback |
| 12. Push | card | Anki note | AnkiConnect |

The non-obvious stages are specified below.

### 5.0 Prose selection (stages 3–4)

A filing is roughly one quarter financial tables. EDINET's XBRL-to-CSV conversion flattens them into the same `TextBlock` values as narrative prose, with **all markup and cell boundaries already removed** — the tags are gone before the data reaches us, so structure cannot be recovered:

```
売上高（千円）3,054,7143,364,9353,293,3673,797,3743,571,516経常損失（△）（千円）△936,011…
```

Detection and discard is therefore the only option. The rule is:

```
keep a sentence iff  text ends with 。  and  15 ≤ length ≤ 200
```

Tables have no sentence terminators because they are not sentences. This is a structural property of the data rather than a tuned heuristic, and it is a rule stage 10 already requires for card examples — applied one stage earlier, it costs nothing new.

Measured over three filings, prose retention is stable at 65–69% of characters; the discarded remainder is headings, fragments and table residue. Roughly 1–2 sentences in 100 survive imperfectly, split inside a parenthetical `「…という。」` or carrying a table fragment. Excluding sentences containing `【` (structural markup that never occurs in running prose) would remove most of the remainder; it is deliberately **not** applied until the phase 2 candidate list shows whether it matters.

**Block-level genre filtering is explicitly rejected.** An element-ID allowlist or denylist, and a hiragana-ratio threshold, were both evaluated and discarded:

- **Element IDs describe where content lives, not what it is.** `IssuedSharesTotalNumberOfSharesEtcTextBlock` reads like a share table, and in a company with preferred shares it holds several pages of legal prose — 優先配当金, 償還請求日, 転換価額, 比例按分. `AnnexedDetailedScheduleOfPropertyPlantAndEquipment` carries footnotes containing 減損損失累計額. Denylisting either would discard some of the highest-value vocabulary in the corpus.
- **Hiragana ratio separates cleanly at block level but not at sentence level.** Legitimate prose sentences run as low as 6% hiragana, so any threshold that removes tables also removes real content. It was the right measurement at the wrong granularity, and the terminator rule makes it redundant.

**Deduplication is required, not optional.** Between 15% and 23% of the sentences in a single filing are exact duplicates, because consolidated (連結) and non-consolidated (個別) contexts repeat the same text. Enforced by `UNIQUE(doc_id, text_hash)`. Left unhandled, corpus frequency is inflated by roughly a fifth while document frequency is unaffected — which would silently distort keyness.

### 5.1 Compound reconstruction (stage 6)

**Why this exists.** Sudachi mode C merges only compounds attested in SudachiDict. Empirically, `課税所得`, `蓋然性` and `可能性` merge; `繰延税金資産` does not — it fragments into `繰延 / 税金 / 資産`. Financial Japanese is dense with long compounds absent from a general dictionary, so reconstruction is a required stage, not an optimisation.

**Algorithm.**

1. Over the mode-C token stream, take maximal runs of ≥2 consecutive tokens where `partOfSpeech()[0] == 名詞`, excluding 数詞, 代名詞 and 固有名詞. Proper nouns are excluded because they are company and product names, not vocabulary.
2. Candidate compound = concatenation of **`surface()`**, never `normalizedForm()`. Normalisation rewrites `繰延 → 繰り延べ`, which would produce the non-word `繰り延べ税金資産`. Normalised forms remain the lookup key for single tokens only.
3. Score association across the run. Accept if document frequency ≥ threshold and the minimum adjacent-pair PMI exceeds a floor. This separates real terms (`繰延税金資産`, recurring across thousands of filings) from incidental adjacency (`当社 事業 拡大`).
4. Emit accepted compounds as terms; also retain their parts as terms in their own right.
5. Feed accepted compounds into a **Sudachi user dictionary** (`Config.addUserDictionary`) so subsequent passes tokenise them atomically.

Thresholds are corpus-dependent and will be tuned empirically in phase 2. They cannot be set from a single document.

### 5.2 Keyness (stage 7)

Raw frequency surfaces 会社, 当社, 事業 — true and useless. Ikeda ranks by over-representation relative to general Japanese.

Dunning log-likelihood, for term with frequency `a` in the EDINET corpus (size `N₁`) and `b` in the baseline (size `N₂`):

```
E₁ = N₁(a+b)/(N₁+N₂)
E₂ = N₂(a+b)/(N₁+N₂)
G² = 2(a·ln(a/E₁) + b·ln(b/E₂))          signed by whether a/N₁ > b/N₂
```

**Baseline:** NINJAL's BCCWJ frequency lists. Use the **long-unit (LUW)** list against mode C and the short-unit (SUW) list against mode A — the unit definitions align, which avoids systematic mismatch.

**Dispersion** is applied as a filter, not a weight: require document frequency ratio ≥ 0.05. A term appearing 400 times in one filing is that company's jargon, not financial Japanese.

### 5.3 Compositionality (stage 9)

A compound whose meaning is predictable from parts the learner already knows is cheap. One that is opaque is expensive and deserves a card.

```
comp(t) = |{parts of t that are in the known set}| / |parts of t|
comp(t) = 0 for atomic terms
```

Parts come from `Morpheme.split(SplitMode.A)` for dictionary-attested compounds, and by construction for reconstructed compounds.

`有利子負債` scores 1.0 when 利子 and 負債 are known — transparent, deprioritised despite high keyness. `蓋然性` scores 0.0 because 蓋然 is not independently known — opaque, prioritised.

### 5.4 Candidate ranking (stage 9)

**Hard filters:** `G² ≥ 15.13` (p < 0.0001), document frequency ratio ≥ 0.05, not in known set, content POS only.

**Rank by:** `G²(t) × (1 − λ·comp(t))`, with `λ = 0.7`.

Dispersion deliberately stays a filter rather than entering the score — one tunable knob is easier to reason about than three.

### 5.5 Known-set extraction (stage 8)

Query AnkiConnect `findNotes` with `deck:*`, then `notesInfo`. Take Expression fields, **split on `,` and `、`** (existing notes contain comma-separated variants such as `妬む, 嫉む`), and normalise each through the same Sudachi pipeline that produced the candidates. Symmetry matters — a mismatch in normalisation silently reintroduces duplicates.

Seed the set with sub-N2 vocabulary so common words are never proposed.

### 5.6 Example sentence selection (stage 10)

**Hard filters:** 15 ≤ length ≤ 80 characters; at most one *other* unknown term; terminates in `。`; no leading anaphora (`当該`, `上記`, `同`, `なお`) that references outside the sentence.

**Sequencing.** When the only good sentence for term X also contains unknown term Y, Y is scheduled first and the sentence becomes valid for X one card later. The selector is a scheduler, not a filter.

---

## 6. Data model

```
filing(doc_id PK, edinet_code, filer_name, doc_type_code, submit_date, fetched_at)
block(id PK, doc_id FK, element_id, seq, text)
sentence(id PK, block_id FK, seq, text, char_len, token_count)
term(id PK, key UNIQUE, surface, reading, pos, is_compound, part_keys)
occurrence(term_id FK, sentence_id FK, position)
term_stat(term_id PK, corpus_freq, doc_freq, keyness, compositionality)
known_lemma(key PK, source, first_seen)
candidate(term_id PK, score, best_sentence_id, status, decided_at)
```

`candidate.status` records the manual verdict (`accepted` / `rejected` / `pending`). Those verdicts are the measurement for the candidate-precision metric and must not be discarded.

---

## 7. Anki integration

**Deck:** new tree, isolated from existing collections.

```
金融
├── 金融::有報
├── 金融::適時開示
└── 金融::prep::*      (throwaway filtered decks for document pre-reads)
```

**Rationale for isolation.** FSRS optimises parameters per preset. Opaque financial compounds have a different difficulty and stability profile from N1 exam vocabulary; mixing them produces parameters fitted to neither, and injecting hundreds of new cards perturbs an optimiser currently well-tuned on years of history. Isolation also allows the whole experiment to be suspended or deleted without touching existing decks.

**Note type:** dedicated, not `Basic`.

| Field | Purpose |
|-------|---------|
| Expression, Reading, Meaning | core |
| Example, ExampleSource | filing sentence and citation |
| DocID | traceability back to EDINET |
| Keyness, Compositionality | correlate ranking features against future retention |
| MinedAt | cohort analysis |

The metadata fields cost nothing now and cannot be backfilled later. They are what will eventually allow the question *does keyness predict retention?* to be answered against the revlog.

**Card templates:** recognition (JP→EN) primary; a reading-only template is planned, targeting terms understood conceptually in English but unreadable aloud — a specific failure mode at this level.

**Duplicate handling.** Deduplication happens upstream via the known-set; Anki's check is a backstop only. Configure `duplicateScopeOptions.checkAllModels: true` — the default checks only notes of the same note type, so a new note type would not detect a word already present as a `Basic` note.

**JSON construction.** Note payloads must be built with Jackson, never `String.format` into a JSON template. Filing prose contains `「」`, `"` and backslashes that break naive interpolation.

---

## 8. External dependencies

| Dependency | Version | Licence / constraint |
|------------|---------|---------------------|
| Java | 25 LTS (Temurin) | — |
| Gradle | 9.6 | 9.1+ required for JDK 25 daemon |
| Sudachi | 0.7.5 | Apache 2.0 |
| SudachiDict | core | Apache 2.0; ~70MB, gitignored |
| Jackson | 2.19.0 | Apache 2.0 |
| Commons CSV | 1.14.1 | Apache 2.0 |
| EDINET API | v2 | Free key; **3–5s between requests** |
| BCCWJ frequency lists | v1.1 | NINJAL, free for research/education — **must not be vendored**; fetch at setup, gitignore |
| JMdict | current | EDRDG, CC BY-SA — attribution required; do not redistribute derived dictionaries |

Corpus data is never committed. Test fixtures may contain small excerpts of EDINET filings, which are public disclosure.

---

## 9. Delivery phases

Each phase has an explicit exit criterion. A phase that fails its criterion is a signal to stop or rethink, not to proceed.

| Phase | Scope | Exit criterion |
|-------|-------|----------------|
| **0** ✅ | Toolchain, Sudachi, EDINET listing | 205 filings listed; mode C tokenising correctly |
| **1** | Corpus: fetch, extract, segment, tokenise, persist | ≥180 filings in local store; top terms by document frequency are unremarkable |
| **2** | Ranking: compounds, keyness, known-set, scoring | **≥70% of top 50 judged worth learning** |
| **3** | Cards: sentence selection, glosses, AnkiConnect push | 40 cards in 金融::有報 studied without edits |
| **4** | Productionise: daily ingestion, Postgres, embeddings, coverage dashboard | Only if 1–3 pass |

Phase 4 is where the FinDocDRAG architecture — Kafka, pgvector, Helm, Grafana — becomes justified. EDINET publishes daily and 適時開示 is a genuine continuous stream, so the streaming design is not decoration. But it earns its place at ten thousand documents, not fifty. **Every algorithmic decision that determines whether this project is useful lives in phases 2 and 3, and all of it runs in a single process.**

---

## 10. Observability and testing

**Stage counters are mandatory from phase 1.** Every stage logs documents in/out, blocks in/out, sentences, tokens, and terms. This is not future-proofing: the first real integration attempt returned zero narrative blocks with no indication of which assumption had failed, which cost a debugging cycle that a single count would have made obvious.

Every stage must be able to answer *how many items entered, how many left, and why the difference*.

**Testing:**

- Golden-file tests on a vendored sample EDINET CSV — covers UTF-16 decoding, quoting, and embedded newlines without network access
- Unit tests on keyness arithmetic against hand-computed values
- Unit tests on compound detection with fixed token sequences
- Property test: known-set extraction is idempotent and normalisation-symmetric with candidate generation

---

## 11. Open questions and risks

| # | Item | Impact | Status |
|---|------|--------|--------|
| 1 | Narrative extraction returns 0 blocks | ~~Blocker~~ | **Resolved 2026-08-02.** Cause was genre, not parsing — see finding 6. Encoding and header names were never wrong |
| 1b | Financial tables contaminate the corpus | ~~Medium~~ | **Resolved 2026-08-02.** Solved by the §5.0 sentence-terminator rule. An allowlist was evaluated and rejected as actively harmful — see findings 9–11 |
| 2 | JMdict coverage of financial compounds expected to be poor | Medium | Mitigation: LLM gloss grounded in the actual filing sentence, not a generic definition |
| 3 | Compound readings may be wrong (rendaku, irregular readings) | Medium | `readingForm()` concatenation is unreliable for reconstructed compounds; needs validation |
| 4 | Association thresholds unknown | Medium | Tunable only against a real corpus; deferred to phase 2 |
| 5 | Single-user data may be thin for coverage statistics | Low | Descriptive metrics only; no causal claims |
| 6 | Anki Expression fields are inconsistently formatted | Low | Known from existing collection; parser must handle comma variants and full-width punctuation |

---

## 12. Empirical findings to date

Recorded because they are design inputs, not incidental observations.

1. **2026-06-26 yields 205 有価証券報告書 with CSV available.** Peak filing season for March year-end companies is late June; most other dates return few or none.
2. **Sudachi mode C merges only dictionary-attested compounds.** `課税所得`, `蓋然性`, `可能性` merge. `繰延税金資産` does not. This is correct behaviour and is the direct justification for stage 6.
3. **`normalizedForm()` is unsafe for compound reconstruction.** `繰延 → 繰り延べ`. Reconstruct from `surface()`.
4. **`Tokenizer.lazyTokenizeSentences`** provides sentence segmentation and bounded-memory streaming over large documents, removing the need for regex sentence splitting.
5. **EDINET CSVs are UTF-16, tab-separated, quoted, with newlines inside quoted fields.** A proper CSV parser is mandatory; line-splitting silently shreds every narrative section.
6. **`docTypeCode=120` is too coarse — it mixes three document genres.** The XBRL taxonomy prefix identifies the genre: `jpcrp030000` is ordinary corporate 有価証券報告書 (wanted), `jpsps070000` is 特定有価証券報告書 for investment trusts and SPCs (different structure), `jpaud` is the 監査報告書 attached to every filing. Audit reports are near-identical boilerplate; ingesting them would give a corpus where the same 監査意見 legalese repeats once per filing and dominates document frequency.
7. **Genre is filterable before download.** `ordinanceCode=010` + `formCode=030000` in `documents.json` selects jpcrp corporate filings, avoiding a wasted 4-second fetch per unwanted document. A zip-entry filter on `/jpcrp` is still required, because audit reports are bundled inside corporate filings too.
8. **Observability paid for itself immediately.** The zero-block failure was diagnosed in a single run from the entry-name dump. The three hypotheses under investigation before instrumentation (encoding, BOM, header names) were all wrong.
9. **TextBlock values contain no markup.** EDINET's CSV conversion strips HTML and flattens tables before delivery — no `<td>`, no `&lt;td&gt;`, no tags of any kind. Table structure cannot be recovered, only detected and discarded.
10. **Sentence terminators separate prose from tables almost perfectly.** Requiring `。` plus a length bound retains 65–69% of prose characters and discards 89–97% of table characters, consistently across filings. No tuned constant is involved.
11. **Element IDs are not a reliable genre signal.** `IssuedSharesTotalNumberOfSharesEtcTextBlock` contains preferred-share terms and conditions — dense financial legal prose — in companies that issue them, while reading like a pure table in companies that do not. The same block varies in genre by filer. This is why §5.0 filters content rather than containers.
12. **Sentences repeat within a filing.** 15–23% of extracted sentences are exact duplicates arising from 連結/個別 context pairs on the same element.
