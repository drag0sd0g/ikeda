# Ikeda — Technical Design Document

---

## 1. Problem

A fluent but non-native reader of Japanese working in finance has no tool that answers the question that matters: *given everything I already know, which words would most improve my comprehension of the documents I read at work?*

The existing ecosystem solves adjacent problems. Popup dictionaries serve reactive lookup. Media-mining tools rank vocabulary by frequency in entertainment. Neither operates over Japanese financial disclosure, and neither prioritises against a particular reader's existing knowledge.

The gap is **prioritisation**, not lookup. A single 有価証券報告書 contains a few thousand distinct words that a competent non-native reader does not fully own. Which two hundred are worth deliberate study?

Ikeda mines Japanese regulatory filings and produces a ranked, deduplicated, context-attached vocabulary queue delivered into Anki as flashcards.

---

## 2. Domain background

This section exists because the pipeline's design is driven by properties of Japanese and of Japanese corporate disclosure that are not obvious from a backend engineering standpoint.

### 2.1 Japanese has no spaces

Japanese is written without word boundaries. `当社は繰延税金資産を計上しております` is one unbroken string. Extracting words therefore requires **morphological analysis**: a dictionary-driven algorithm that segments the string and labels each segment with a part of speech, a dictionary form, and a reading.

Ikeda uses Sudachi for this. Three properties of its output shape the design:

**Split modes.** Sudachi segments at three granularities. Mode A produces the shortest units; mode C produces the longest, merging sequences that its dictionary records as single words. `課税所得` (taxable income) emerges from mode C as one token because SudachiDict contains it. `繰延税金資産` (deferred tax assets) does not, and emerges as three tokens: `繰延` / `税金` / `資産`. Domain vocabulary is systematically under-represented in a general dictionary, which is why compound reconstruction (§5.6) exists.

**Surface form versus normalised form.** A token's *surface* is the text as written; its *normalised form* is a canonical spelling used as the identity of the word. Normalisation rewrites `繰延` to `繰り延べ`. The normalised form is the correct key for counting occurrences of a word, and the wrong string for reassembling a compound: concatenating normalised parts yields `繰り延べ税金資産`, which is not a word. Compounds are therefore built from surfaces and counted by normalised forms.

**Readings belong to surfaces, not to dictionary forms.** Japanese verbs and adjectives inflect. The reading a morphological analyser reports is the reading of the *inflected* text it saw. The token `晒される` has dictionary form `晒す` but reading `サラサ`, which is the reading of the stem that appeared, not of the dictionary form. Storing the pair unchanged produces a word labelled with a reading that does not belong to it. Ikeda re-analyses each dictionary form to obtain its own reading (§5.5).

### 2.2 Scripts carry information

Japanese uses three scripts concurrently, and which one a word is written in is itself a signal.

- **Kanji** are logographic characters. Most content vocabulary contains at least one.
- **Hiragana** is a syllabary used for grammatical material and for words whose kanji are rare or informal.
- **Katakana** is a syllabary used predominantly for foreign loanwords.

A word written entirely in katakana is, in this domain, almost always an English borrowing — `ロボティクス`, `エンゲージメント`, `パンデミック`. For a reader who speaks English these carry no learning cost. A word written entirely in hiragana in this corpus is almost always grammatical scaffolding that the analyser has labelled as a noun or verb.

Ikeda therefore requires a candidate to contain at least one kanji (§5.7).

### 2.3 Filings are not prose

An 有価証券報告書 is a statutory annual report. Roughly a quarter of its content is financial tables. EDINET's conversion to tabular form flattens those tables into the same text fields as narrative sections, with all markup and cell boundaries already removed:

```
売上高（千円）3,054,7143,364,9353,293,3673,797,3743,571,516経常損失（△）（千円）△936,011…
```

Table structure cannot be recovered from this. It can only be detected and discarded (§5.3).

Filings also bundle documents of different genres under one identifier, and repeat identical explanatory text under consolidated (連結) and non-consolidated (個別) accounting contexts (§5.1, §5.4).

### 2.4 What makes a word worth learning

Frequency alone is a poor guide. The most frequent words in any corpus are the ones a competent reader already knows. The useful signal is the intersection of two conditions: the word is **common in the target domain** and **absent from the reader's vocabulary**.

The first is measurable directly from the corpus. The second cannot be observed and must be predicted. Ikeda's ranking is a model of the second condition; its accuracy is what determines whether the tool is useful (§5.8).

---

## 3. Goals and non-goals

### Goals

| ID | Goal |
|----|------|
| G1 | Rank domain vocabulary by acquisition yield, not raw frequency |
| G2 | Never propose a word the reader has confirmed as known |
| G3 | Every generated card carries an authentic example sentence and a traceable source |
| G4 | Report comprehension coverage over the corpus, and its change over time |
| G5 | Run as a single local process with no server dependencies |

### Non-goals

- A popup dictionary
- A spaced-repetition scheduler — Anki with FSRS does this
- Translation, summarisation, or document question-answering
- General-purpose Japanese learning — this targets one register deliberately
- Multi-user or hosted operation

---

## 4. Architecture

A single Java process writing to an embedded database. No message broker, no service split, no vector store.

```
EDINET API v2
      │
      ▼
 ┌───────────┐  ┌────────────┐  ┌───────────┐  ┌──────────┐  ┌──────────┐
 │  Ingest   │─▶│  Analyse   │─▶│   Rank    │─▶│  Review  │─▶│  Cards   │
 │  fetch,   │  │  segment,  │  │ baseline  │  │  batch   │  │  gloss,  │
 │  extract  │  │  tokenise  │  │ rarity,   │  │  out,    │  │  example │
 │           │  │  compound  │  │ known-set │  │ verdicts │  │  push    │
 └───────────┘  └────────────┘  └───────────┘  └──────────┘  └──────────┘
        │              │              │              │             │
        └──────────────┴──────────────┴──────────────┴─────────────┘
                                 │
                          SQLite (local file)
                    ┌────────────┼────────────┐
                    ▼            ▼            ▼
              BCCWJ baseline  JMdict     Anki (AnkiConnect)
```

**Why SQLite.** The workload is a few hundred filings and low-millions of token occurrences — comfortably within range, zero operational effort, fast feedback. Anki's own collection is a SQLite file, so both sides of the project use one driver. A server database becomes correct only if more than one process needs concurrent access.

**Package layout.** `ingest` fetches and extracts; `analyse` segments, tokenises and selects examples; `compound` reconstructs multi-word terms; `rank` holds the frequency baseline; `gloss` resolves meanings; `anki` reads the collection and writes cards; `review` is the sheet format; `card` is the note model; `store` is persistence; `cli` is the command layer; `support` is shared text utilities.

**Commands.** `ingest`, `anki`, `compounds`, `sample`, `export`, `verdicts`, `cards`, `status`.

---

## 5. Pipeline

| Stage | Input | Output |
|-------|-------|--------|
| 1. List | date | document identifiers |
| 2. Fetch | identifier | tabular bundle |
| 3. Extract | bundle | narrative blocks |
| 4. Segment | block | prose sentences |
| 5. Tokenise | sentence | terms with readings |
| 6. Compound | corpus | multi-word terms |
| 7. Promote | terms | candidates |
| 8. Rank | candidates | ordered batch |
| 9. Review | batch | verdicts |
| 10. Card | verdict | Anki note |

### 5.1 Document selection

EDINET identifies annual reports by `docTypeCode=120`, but that code spans three genres distinguished by their XBRL taxonomy prefix:

| Prefix | Genre | Wanted |
|---|---|---|
| `jpcrp030000` | Ordinary corporate annual report | Yes |
| `jpsps070000` | Annual report for investment trusts and special-purpose companies | No — different document structure |
| `jpaud` | Auditor's report, bundled with every filing | No — near-identical boilerplate across companies |

Genre is determined before download by `ordinanceCode=010` and `formCode=030000`, avoiding a fetch for unwanted documents. A second filter on the taxonomy prefix excludes the auditor's report bundled inside wanted filings.

Requests are paced at one every four seconds, which the API requires.

### 5.2 Narrative extraction

The bundle is a set of tab-separated files encoded in UTF-16. Narrative sections are the rows whose element identifier contains `TextBlock`; their values are HTML-bearing text. Tags are stripped and whitespace collapsed.

Encoding is detected from the byte prefix rather than assumed. A byte-order mark settles it outright; without one, the choice between UTF-8 and UTF-16LE is made by testing for NUL padding and for UTF-8 validity, because ASCII in UTF-16LE is also valid UTF-8, and Japanese in UTF-16LE is not.

### 5.3 Prose selection

Flattened tables are discarded by a single rule:

```
keep a sentence iff  it ends with 。  and  15 ≤ length ≤ 200 characters
```

Tables have no sentence terminators because they are not sentences. This is a structural property of the data rather than a tuned heuristic, and it is a rule card generation needs anyway.

**Filtering by section is deliberately not done.** Element identifiers describe where content sits, not what it is: a section named for a share table holds several pages of legal prose in a company that has issued preferred shares. Content is filtered; containers are not.

### 5.4 Deduplication

Filings repeat identical explanatory sentences under consolidated and non-consolidated accounting contexts. Duplicates are removed per filing, with a uniqueness constraint on `(document, text)` as a backstop.

Identical sentences occurring in *different* filings are kept deliberately: document frequency must count each filing once, and cross-filing repetition is signal rather than noise.

### 5.5 Readings

Each term's stored reading is obtained by re-analysing its dictionary form, not by taking the reading of the inflected text in which it was found. See §2.1.

### 5.6 Compound reconstruction

Domain vocabulary is systematically absent from a general-purpose dictionary, so the analyser splits multi-word terms into parts. Reconstruction proceeds in three steps.

**Detect.** Over the mode-C token stream, take maximal runs of two to five consecutive nouns, excluding numerals, pronouns, proper nouns, and tokens written without kanji. Proper nouns are excluded because they are company and product names rather than vocabulary. The candidate is the concatenation of the run's *surface* forms.

**Score.** A run of adjacent nouns is not necessarily a term; `当社 事業 拡大` is three nouns that happened to occur in sequence. Two measures separate terms from incidental adjacency:

- *Document frequency* — the number of distinct filings the run appears in. A genuine term recurs across companies that wrote their reports independently.
- *Pointwise mutual information* — for an adjacent pair, the logarithm of how much more often the two parts occur together than independent occurrence would predict:

  ```
  PMI(a, b) = log₂ ( P(a, b) / ( P(a) · P(b) ) )
  ```

  A run is scored by its **weakest link**: the minimum PMI across its adjacent pairs. A chain is only as bonded as its loosest join, so a run containing one incidental adjacency is rejected regardless of how tightly its other parts cohere.

**Accept and store.** Runs clearing both thresholds are stored as terms, and occurrences are attributed to the sentences they were found in. Both thresholds are corpus-dependent and configurable.

Two forms of each part are recorded: the surface, which is what the compound is spelled from, and the *short-unit* decomposition, which is what a frequency baseline can be queried with. The second is what allows a compound with no baseline entry of its own to be ranked from its constituents (§5.8) — a compound whose meaning follows from parts the reader already knows is cheap to acquire, while an opaque one is not.

### 5.7 Candidate promotion

A term becomes a candidate only if all hold:

| Filter | Reason |
|---|---|
| Content part of speech | Nouns, verbs, adjectives, adverbs |
| Length ≥ 2 characters | Single characters are fragments |
| Contains at least one kanji | See §2.2 |
| Document frequency ≥ 5% of the corpus | Below this a term is one company's jargon rather than the domain's vocabulary |
| Not in the known set | Never propose a confirmed known word |

The dispersion floor is the single most effective filter, removing roughly three quarters of the vocabulary.

### 5.8 Ranking

**Candidates are ordered by rarity in general written Japanese**, rarest first, using the short-unit frequency list of a balanced reference corpus (BCCWJ). Ties break on corpus frequency.

The reasoning is stated in §2.4. A reader who acquired Japanese through immersion and general study has gaps precisely where a word is common in professional writing but rare in everyday writing. Rarity in a balanced corpus is therefore a proxy for *unknown-ness*, and combining it with the corpus-internal filters of §5.7 approximates "common here, unfamiliar to you".

Three properties of the implementation are load-bearing.

**Absence from the baseline is never evidence of rarity.** A reference corpus and a morphological analyser disagree about word boundaries and about orthography: one segments 不動産 into two units while the other treats it as one, and one spells a stem 繰延 while the other spells it 繰り延べ. A lookup miss therefore usually means the two disagree, not that the word is rare. This rule is applied consistently at every level — a whole word that cannot be found is ranked last rather than first, and a compound part that cannot be found is ignored rather than counted as maximally rare.

**Compounds are ranked from their parts.** A reconstructed compound has no entry in the baseline by construction, so ranking it by measured rarity would bury every domain term behind every single word. Instead its rank is estimated as the rarity of its **rarest constituent**, matching the intuition that a compound is only as hard as its least familiar part. A compound of everyday parts is transparent and sinks; one containing a specialist morpheme rises.

For this to work the constituents must be compared at the granularity the baseline uses. Reference frequency lists are built on *short units*, while compound detection operates on *long units*, so each part is decomposed to short units before lookup. Without this step a part that is itself a merged unit — 会計年度, 引当金, 予約権 — fails to match, even though its own constituents are common.

**Lookup is by written form only.** Matching on reading as a fallback repairs the case where the reference corpus canonicalises a word to a different spelling, but it collapses homophones, and Japanese has many. The cure is worse than the disease.

### 5.9 Example selection

Every card carries a sentence taken from a filing. Candidate sentences must contain the target, fall within a length window, and not open with an expression that refers outside the sentence (当該, 上記, 同社, なお, その). A leading section heading welded to the sentence — `３【事業の内容】　当社は…` — is stripped rather than causing rejection.

Among surviving sentences the choice minimises the number of *other* words the reader does not know, then prefers the shorter sentence. This approximates comprehensible input: a sentence understandable except for the one word being taught.

The count is a **preference, not a filter**. Requiring at most one unknown word is unachievable in this register — financial prose is dense with domain vocabulary — and enforcing it leaves most words without any example at all. Ranking by the count while accepting the best available sentence keeps the intent without the failure mode.

For the count to be meaningful, the known set used here is broader than the confirmed one: it also treats the commonest tens of thousands of words in general Japanese as known, since the reader is a competent non-native reader rather than a beginner.

### 5.10 Glosses

Meanings come from JMdict, a free Japanese-English dictionary. Coverage of general vocabulary is good; coverage of financial compounds is partial, and some entries carry a literal sense rather than the business one.

Where JMdict has no entry the meaning field is left **empty rather than guessed**. A card with a word, a reading and an authentic example is still useful; a card with an invented meaning is worse than none.

---

## 6. Data model

```
filing(doc_id PK, edinet_code, filer_name, doc_type_code,
       ordinance_code, form_code, submit_date_time, ingested_at)

block(id PK, doc_id FK, seq, element_id, text)

sentence(id PK, doc_id FK, block_id FK, seq, text, char_len, token_count,
         UNIQUE(doc_id, text))

term(id PK, key UNIQUE, surface, reading, pos, has_kanji,
     is_compound, part_keys, part_units)

occurrence(id PK, term_id FK, sentence_id FK, doc_id, position)

known_lemma(lemma PK, source, first_seen)

candidate(term_id PK, corpus_frequency, document_frequency, bccwj_rank, effective_rank,
          example_sentence_id, status, decided_at, exported_at)
```

**Raw narrative blocks are retained** so segmentation rules can change and be replayed without re-fetching, which is expensive under the API's rate limit.

**`doc_id` is denormalised onto `occurrence`** because document frequency is a distinct count grouped by term, and that query runs on every ranking pass.

**`bccwj_rank` holds only measured rarity and `effective_rank` holds what the queue orders by** — the measured value where one exists, the estimate from constituents otherwise. Keeping them apart preserves the distinction between what was looked up and what was inferred.

**`has_kanji` is stored rather than computed** because SQLite has no character-class matching for CJK.

**`candidate.status`** records the reviewer's verdict — `PENDING`, `KNOWN`, `WORTH_LEARNING`, `NOT_WORTH_LEARNING`. Three outcomes rather than two, because "I already know it" and "not worth a card" carry different diagnoses: the first says the known-set model is wrong, the second says the ranking is. Re-populating candidates leaves verdicts untouched.

**Schema changes** add columns in place at startup. Anything more involved should be a rebuild, since the corpus is always reproducible from the source API.

---

## 7. Review loop

The ranking predicts what the reader does not know. It cannot be right about a specific person from population statistics alone, and the residual is only obtainable by asking.

A batch is written as a tab-separated sheet — word, reading, part of speech, document frequency, corpus frequency, example sentence, and an empty verdict column. The reader marks each row and the sheet is read back. A file round trip rather than an interface, because a spreadsheet already sorts, filters and hides columns better than anything worth building.

Verdicts are the project's most expensive artifact: they cost human attention and cannot be regenerated. They are preserved across corpus rebuilds, and a `KNOWN` verdict is promoted into the known set so the word is never proposed again. That mechanism is what closes the gap no ranking can close — the known set grows with every session.

---

## 8. Anki integration

### Reading

The entire collection is treated as known. Headwords are taken from the field holding the word, in a preference order that matters: some note types keep the headword in a dedicated field and a full example sentence in the field that would otherwise hold it. Comma-separated variants are split; anything containing spaces or longer than twelve characters is a sentence or a grammar pattern rather than a word.

This is a reliable but **narrow** filter. Most of what a competent reader knows was never carded, because it was too basic to need a card.

### Writing

Cards are written into a dedicated deck, `金融::有報`, with a dedicated note type. Nothing else in the collection is read for writing purposes, modified, or deleted; the write path exposes exactly three operations — create the deck, create the note type, add notes — and the deck name is fixed rather than parameterised.

Deck isolation is not only hygiene. Anki's scheduler optimises its parameters per configuration group, and opaque domain compounds have a different difficulty profile from general vocabulary; mixing them fits neither. Isolation also allows the whole experiment to be removed without touching existing material.

**Fields:** Expression, Reading, Meaning, Example, ExampleSource, DocID, BaselineRank, DocumentFrequency. The last two cost nothing to store and cannot be backfilled; they are what allows the question *does baseline rarity predict retention?* to be answered later against the review log.

**Two card templates.** Recognition shows the word and asks for reading and meaning. Reading shows the word with its meaning and asks only for the reading — a distinct failure mode at this level, where a reader recognises a compound instantly on the page but cannot pronounce it.

**Duplicate handling.** Deduplication happens upstream via the known set; Anki's own check is a backstop scoped to the deck. Note payloads are built with a JSON serialiser, never string interpolation: filing prose contains quotation marks and backslashes.

---

## 9. External dependencies

| Dependency | Constraint |
|------------|-----------|
| Java 25, Gradle 9.6+ | — |
| Sudachi + SudachiDict core | Apache 2.0; dictionary downloaded separately |
| Jackson, Commons CSV, SQLite JDBC, picocli, SLF4J | Apache 2.0 / MIT |
| EDINET API v2 | Free key; four seconds between requests |
| BCCWJ short-unit frequency list | Free for research and education; **not redistributable** |
| JMdict | Creative Commons Attribution-ShareAlike; attribution required |
| AnkiConnect | Local add-on |

Corpus data, the dictionary, the baseline and the glossary are never committed. Test fixtures may contain small excerpts of filings, which are public disclosure.

---

## 10. Observability and testing

**Stage counters are mandatory.** Every stage logs items in, items out, and the reason for the difference. A silent zero at any stage is indistinguishable from correct operation without them.

**Testing:**

- Parsing is exercised against bundles built in-test — UTF-16, quoted fields with embedded newlines, auditor's reports to exclude
- The prose filter is tested on real sentences and real flattened tables
- Segmentation, reading resolution and compound detection run against the real morphological analyser, skipping cleanly when the dictionary is absent
- Persistence runs against an in-memory database: atomicity, resumability, known-set exclusion, verdict preservation
- The Anki transport is tested against a real in-process HTTP server rather than a mocked client
- Schema migration is tested by opening databases built to older shapes
- One end-to-end test carries a word from a source bundle through to a review sheet and its retirement from the candidate pool
