package com.ikeda.cli;

import com.ikeda.review.CandidateStatus;
import com.ikeda.store.CandidateStore;
import com.ikeda.store.Database;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

final class Reporting {
    private static final Logger log = LoggerFactory.getLogger(Reporting.class);

    private Reporting() {
    }

    static void verdicts(Database database) {
        Map<CandidateStatus, Long> counts = new CandidateStore(database).verdictCounts();
        long known = counts.get(CandidateStatus.KNOWN);
        long worth = counts.get(CandidateStatus.WORTH_LEARNING);
        long notWorth = counts.get(CandidateStatus.NOT_WORTH_LEARNING);
        long decided = known + worth + notWorth;

        log.info("review: {} pending, {} decided", counts.get(CandidateStatus.PENDING), decided);
        if (decided == 0) {
            return;
        }
        log.info("  known {} | worth learning {} | not worth {}", known, worth, notWorth);
        log.info("  precision (worth / decided): {}%", 100 * worth / decided);
    }
}
