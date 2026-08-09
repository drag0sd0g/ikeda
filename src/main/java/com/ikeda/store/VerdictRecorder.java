package com.ikeda.store;

import com.ikeda.review.CandidateStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

public final class VerdictRecorder {

    private static final Logger log = LoggerFactory.getLogger(VerdictRecorder.class);

    private static final String SOURCE = "review";

    private final CandidateStore candidates;
    private final KnownLemmaStore known;

    public VerdictRecorder(Database database) {
        this(new CandidateStore(database), new KnownLemmaStore(database));
    }

    VerdictRecorder(CandidateStore candidates, KnownLemmaStore known) {
        this.candidates = candidates;
        this.known = known;
    }

    public int record(Map<String, CandidateStatus> verdicts) {
        int updated = candidates.recordVerdicts(verdicts);

        List<String> nowKnown = verdicts.entrySet().stream()
                .filter(entry -> entry.getValue() == CandidateStatus.KNOWN)
                .map(Map.Entry::getKey)
                .toList();
        if (!nowKnown.isEmpty()) {
            known.add(nowKnown, SOURCE);
        }
        return updated;
    }

    public int record(String term, CandidateStatus verdict) {
        return record(Map.of(term, verdict));
    }

    public int reset(String term) {
        int updated = candidates.resetVerdict(term);
        log.debug("reset verdict for {}", term);
        return updated;
    }
}
