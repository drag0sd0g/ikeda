# Ikeda

<img width="1254" height="1254" alt="ikeda_logo" src="https://github.com/user-attachments/assets/bf9e1e31-e7b3-4f07-a076-955577c721ab" />

Finds the financial-Japanese vocabulary you don't know yet.

Ikeda reads Japanese annual reports (有価証券報告書) from EDINET, works out which words in them you're missing, ranks them so the useful ones come first, and turns the result into Anki cards carrying real sentences from real filings.

It exists because the prioritisation problem is unsolved. Popup dictionaries handle lookup. Media-mining tools rank vocabulary by frequency in anime and novels. Nothing ranks Japanese *financial* vocabulary, and nothing prioritises against what you personally already know.

## What it produces

A review sheet, rarest words first:

```
verdict  term      reading        docs  total  example
         往査      オウサ           56     87  なお、社外監査役は海外５か国12拠点の往査を実施しております。
         戻入      レイニュウ       61    131  のれんに関連する減損損失は戻入れておりません。
         余資      ヨシ             72     93  余資運用については余資運用規程に基づき運用しております。
```

You mark each row `k` (already know it), `w` (worth a card) or `n` (not worth it), and import it back. Words you mark known are never proposed again.

## Requirements

- Java 25
- An [EDINET API key](https://api.edinet-fsa.go.jp/api/auth/index.aspx?mode=1) — free, email registration
- Anki running with the [AnkiConnect](https://ankiweb.net/shared/info/2055492159) add-on
- Two data files that can't be redistributed, downloaded separately (below)

## Setup

```bash
echo "EDINET_KEY=<your-key>" > .env

# Sudachi dictionary (~70MB)
mkdir -p dict
curl -L http://sudachi.s3-website-ap-northeast-1.amazonaws.com/sudachidict/sudachi-dictionary-latest-core.zip -o /tmp/dict.zip
unzip -j /tmp/dict.zip '*/system_core.dic' -d dict/

# BCCWJ frequency list — free for research and education, not redistributable
mkdir -p baseline
curl -L https://repository.ninjal.ac.jp/record/3234/files/BCCWJ_frequencylist_suw_ver1_0.zip -o /tmp/bccwj.zip
unzip -j /tmp/bccwj.zip -d baseline/
```

## Usage

```bash
set -a; source .env; set +a

# Build the corpus. Filings cluster in late June for March year-ends.
./gradlew run --args="ingest 2026-06-26"

# Load your Anki collection, so known words are never proposed
./gradlew run --args="anki"

# Write a batch to review
./gradlew run --args="sample -n 150 -o review_batch.tsv"

# ...fill in the verdict column, then
./gradlew run --args="verdicts review_batch.tsv"

./gradlew run --args="status"
```

Ingestion is rate-limited to one request every four seconds and takes about fifteen minutes for a full day of filings. It's safe to interrupt — filings already stored are skipped on the next run.

## Design

See [docs/technical-design-document.md](docs/technical-design-document.md) for how the ranking works, what was measured, and which approaches were tried and rejected.

## Licence

MIT. The Sudachi dictionary, the BCCWJ frequency list and EDINET filings are covered by their own terms and are not included in this repository.
