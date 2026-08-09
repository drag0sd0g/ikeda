package com.ikeda.ui;

import com.ikeda.gloss.Gloss;
import com.ikeda.gloss.GlossSource;
import com.ikeda.review.Candidate;
import com.ikeda.review.CandidateStatus;
import com.ikeda.store.CandidateStore;
import com.ikeda.store.CompoundStore;
import com.ikeda.store.SentenceStore;
import com.ikeda.store.VerdictRecorder;

import java.util.List;
import java.util.Map;

public final class ReviewQueue {

    private static final int EXAMPLES_PER_TERM = 4;
    private static final int EXAMPLE_MIN_CHARS = 15;
    private static final int EXAMPLE_MAX_CHARS = 120;

    private final CandidateStore candidates;
    private final SentenceStore sentences;
    private final CompoundStore compounds;
    private final VerdictRecorder verdicts;
    private final GlossSource glosses;

    public ReviewQueue(CandidateStore candidates, SentenceStore sentences,
                       CompoundStore compounds, VerdictRecorder verdicts, GlossSource glosses) {
        this.candidates = candidates;
        this.sentences = sentences;
        this.compounds = compounds;
        this.verdicts = verdicts;
        this.glosses = glosses;
    }

    public List<ReviewItem> next(int size) {
        return candidates.nextBatch(size).stream().map(this::toItem).toList();
    }

    public Map<CandidateStatus, Long> progress() {
        return candidates.verdictCounts();
    }

    public void record(String term, CandidateStatus verdict) {
        verdicts.record(term, verdict);
    }

    public void undo(String term) {
        verdicts.reset(term);
    }

    private ReviewItem toItem(Candidate candidate) {
        var seen = new java.util.HashSet<String>();
        List<ReviewItem.Example> examples = sentences.forTerm(candidate.termId(), 60).stream()
                .filter(sentence -> sentence.text().length() >= EXAMPLE_MIN_CHARS)
                .filter(sentence -> sentence.text().length() <= EXAMPLE_MAX_CHARS)
                .filter(sentence -> seen.add(sentence.text()))
                .limit(EXAMPLES_PER_TERM)
                .map(sentence -> new ReviewItem.Example(
                        sentence.text(), sentences.sourceOf(sentence.sentenceId())))
                .toList();

        return new ReviewItem(
                candidate.key(),
                candidate.reading(),
                glosses.lookup(candidate.key()).map(Gloss::meaningLine).orElse(""),
                candidate.pos(),
                compounds.partsFor(candidate.termId()),
                candidate.documentFrequency(),
                candidate.corpusFrequency(),
                candidates.baselineRankOf(candidate.termId()).orElse(null),
                examples);
    }
}
