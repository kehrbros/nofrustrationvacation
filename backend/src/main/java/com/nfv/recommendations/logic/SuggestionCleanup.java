package com.nfv.recommendations.logic;

import com.nfv.recommendations.config.RecommendationProperties;
import com.nfv.recommendations.data.SuggestionSetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;

/** Deletes sets expired longer than the retention, options go with them (database cascade). */
@Slf4j
@Component
@RequiredArgsConstructor
class SuggestionCleanup {
    private final SuggestionSetRepository setRepository;
    private final RecommendationProperties properties;
    private final Clock clock;

    @Scheduled(cron = "${nfv.recommendations.cleanup-cron:0 30 3 * * *}")
    @Transactional
    public void deleteExpired() {
        int deleted = setRepository.deleteExpiredBefore(OffsetDateTime.now(clock).minus(properties.retention()));
        if (deleted > 0) {
            log.info("deleted {} expired suggestion sets", deleted);
        }
    }
}